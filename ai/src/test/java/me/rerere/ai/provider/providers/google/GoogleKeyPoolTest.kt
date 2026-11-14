package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.KeyRotationMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyCooldownReason
import me.rerere.ai.util.KeyPoolMonitor
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.classifyGoogleCooldown
import me.rerere.ai.util.maskApiKey
import me.rerere.ai.util.msUntilNextPacificMidnight
import me.rerere.ai.util.parseApiKeys
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleKeyPoolTest {
    private val chatModel = Model(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        type = ModelType.CHAT,
    )
    private val embeddingModel = Model(
        modelId = "gemini-embedding-2",
        displayName = "Gemini Embedding 2",
        type = ModelType.EMBEDDING,
    )

    @Before
    fun setUp() {
        KeyPoolMonitor.resetAll()
    }

    @After
    fun tearDown() {
        KeyPoolMonitor.resetAll()
    }

    @Test
    fun `parseApiKeys splits mixed separators and deduplicates`() {
        val raw = "AIzaKey1, AIzaKey2\nAIzaKey3;AIzaKey1  AIzaKey4"
        assertEquals(
            listOf("AIzaKey1", "AIzaKey2", "AIzaKey3", "AIzaKey4"),
            parseApiKeys(raw),
        )
        assertEquals("AIzaSy12...90AB", maskApiKey("AIzaSy1234567890AB"))
    }

    @Test
    fun `KeyRoulette honors STICKY vs ROUND_ROBIN rotationMode`() {
        val roulette = KeyRoulette.default()
        val keys = "key-a\nkey-b\nkey-c"

        val stickyResults = (1..5).map {
            roulette.next(keys, providerId = "p1", sessionId = "conv-1", rotationMode = KeyRotationMode.STICKY)
        }.distinct()
        assertEquals(1, stickyResults.size)

        val rrResults = (1..3).map {
            roulette.next(keys, providerId = "p1", sessionId = "conv-1", rotationMode = KeyRotationMode.ROUND_ROBIN)
        }
        assertEquals(listOf("key-a", "key-b", "key-c"), rrResults)
    }

    @Test
    fun `classifyGoogleCooldown distinguishes RPD exhausted vs RPM retryDelay vs auth error`() {
        val now = 1_760_000_000_000L
        val rpdBody = """
            {
              "error": {
                "code": 429,
                "message": "Quota exceeded for quota metric 'Generate requests per day'.",
                "details": [{
                  "@type": "type.googleapis.com/google.rpc.QuotaFailure",
                  "violations": [{ "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier" }]
                }]
              }
            }
        """.trimIndent()
        val (rpdReason, rpdDuration) = classifyGoogleCooldown(429, 1, null, rpdBody, now)
        assertEquals(KeyCooldownReason.RPD_EXHAUSTED, rpdReason)
        assertEquals(msUntilNextPacificMidnight(now), rpdDuration)
        assertTrue(rpdDuration in 60_000L..(24 * 60 * 60 * 1000L + 60_000L))

        val rpmBody = """
            {
              "error": {
                "code": 429,
                "details": [{ "quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier" }, { "retryDelay": "23s" }]
              }
            }
        """.trimIndent()
        val (rpmReason, rpmDuration) = classifyGoogleCooldown(429, 1, null, rpmBody, now)
        assertEquals(KeyCooldownReason.RPM_LIMIT, rpmReason)
        assertEquals(23_000L, rpmDuration)

        val (authReason, authDuration) = classifyGoogleCooldown(403, 1, null, "forbidden", now)
        assertEquals(KeyCooldownReason.AUTH_ERROR, authReason)
        assertEquals(300_000L, authDuration)
    }

    @Test
    fun `generateText fails over cleanly on 429 RPD exhaustion and cools exhausted key`() = runTest {
        val attemptedKeys = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val key = req.header("x-goog-api-key") ?: ""
                attemptedKeys.add(key)
                if (attemptedKeys.size == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(
                            """{"error":{"code":429,"message":"PerDay quota exceeded","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"candidates":[{"content":{"role":"model","parts":[{"text":"pong"}]},"finishReason":"STOP"}]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            }
            .build()

        val provider = GoogleProvider(client)
        val keysRaw = "AIzaFirstKey00001\nAIzaSecondKey00002"
        val setting = ProviderSetting.Google(apiKey = keysRaw)

        val result = provider.generateText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("ping")),
            params = TextGenerationParams(model = chatModel, sessionId = "conv-rpd-test"),
        )

        assertEquals(2, attemptedKeys.size)
        assertTrue(attemptedKeys[0] != attemptedKeys[1])
        val text = result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertEquals("pong", text)

        val statuses = KeyPoolMonitor.inspect(keysRaw).associateBy { it.key }
        assertEquals(KeyCooldownReason.RPD_EXHAUSTED, statuses[attemptedKeys[0]]?.reason)
        assertEquals(KeyCooldownReason.READY, statuses[attemptedKeys[1]]?.reason)
    }

    @Test
    fun `streamText fails over seamlessly before first SSE event when primary key hits 429`() = runTest {
        val attemptedKeys = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val key = req.header("x-goog-api-key") ?: ""
                attemptedKeys.add(key)
                if (attemptedKeys.size == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(
                            """{"error":{"code":429,"message":"quota GenerateRequestsPerDay exhausted"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                } else {
                    val sse = """
                        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hello stream"}]},"finishReason":"STOP"}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sse.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
            }
            .build()

        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(apiKey = "AIzaStreamA00001\nAIzaStreamB00002")

        val chunks = provider.streamText(
            providerSetting = setting,
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(model = chatModel, sessionId = "conv-stream-failover"),
        ).toList()

        assertEquals(2, attemptedKeys.size)
        val texts = chunks.filterIsInstance<StreamChunk.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("hello stream", texts)
    }

    @Test
    fun `generateEmbedding supports native embedContent, batchEmbedContents, and 429 failover`() = runTest {
        var callCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                callCount++
                val path = req.url.encodedPath
                if (callCount == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("""{"error":{"code":429,"message":"RPM limit"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (path.endsWith(":embedContent")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"embedding":{"values":[0.1,0.2,0.3]}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"embeddings":[{"values":[1.0,2.0]},{"values":[3.0,4.0]}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(apiKey = "AIzaEmbedA00001, AIzaEmbedB00002")

        val single = provider.generateEmbedding(
            setting,
            EmbeddingGenerationParams(model = embeddingModel, input = listOf("hello")),
        )
        assertEquals(listOf(listOf(0.1f, 0.2f, 0.3f)), single.embeddings)

        val batch = provider.generateEmbedding(
            setting,
            EmbeddingGenerationParams(model = embeddingModel, input = listOf("a", "b")),
        )
        assertEquals(listOf(listOf(1.0f, 2.0f), listOf(3.0f, 4.0f)), batch.embeddings)

        val singleBody = provider.buildEmbedContentRequestBody("gemini-embedding-2", "hello")
        assertEquals("models/gemini-embedding-2", singleBody["model"]?.jsonPrimitive?.content)

        val batchBody = provider.buildBatchEmbedContentsRequestBody("gemini-embedding-2", listOf("a", "b"))
        assertEquals(2, batchBody["requests"]?.jsonArray?.size)
        assertEquals(
            "models/gemini-embedding-2",
            batchBody["requests"]?.jsonArray?.get(0)?.jsonObject?.get("model")?.jsonPrimitive?.content,
        )
    }
}
