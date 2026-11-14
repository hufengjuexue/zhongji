package me.rerere.ai.provider.providers.google

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.KeyRotationMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.GOOGLE_MODEL_3_5_LITE
import me.rerere.ai.util.GOOGLE_MODEL_3_6_FLASH
import me.rerere.ai.util.GOOGLE_MODEL_3_8_FLASH
import me.rerere.ai.util.GOOGLE_MODEL_HYDROGEN
import me.rerere.ai.util.GoogleDispatchCandidate
import me.rerere.ai.util.GoogleQuotaBucket
import me.rerere.ai.util.KeyCooldownReason
import me.rerere.ai.util.KeyCooldownTracker
import me.rerere.ai.util.KeyPoolMonitor
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.classifyGoogleCooldown
import me.rerere.ai.util.classifyGoogleModelBucket
import me.rerere.ai.util.json
import me.rerere.ai.util.maskApiKey
import me.rerere.ai.util.msUntilNextPacificMidnight
import me.rerere.ai.util.parseApiKeys
import okio.Buffer
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
    private val hydrogenModel = Model(
        modelId = "gemini-4-hydrogen",
        displayName = "Gemini 4 Hydrogen",
        type = ModelType.CHAT,
    )
    private val flash36Model = Model(
        modelId = "gemini-3.6-flash",
        displayName = "Gemini 3.6 Flash",
        type = ModelType.CHAT,
    )
    private val chatModel = Model(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        type = ModelType.CHAT,
    )
    private val nonRpChatModel = Model(
        modelId = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash",
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
    fun `KeyRoulette honors SEQUENTIAL vs STICKY vs ROUND_ROBIN rotationMode`() {
        val roulette = KeyRoulette.default()
        val keys = "key-a\nkey-b\nkey-c"

        val seqBeforeExhaust = listOf("conv-1", "conv-2", "conv-3").map { sid ->
            roulette.next(
                keys,
                providerId = "p1",
                sessionId = sid,
                rotationMode = KeyRotationMode.SEQUENTIAL,
                modelId = "gemini-3.8-flash",
            )
        }
        assertEquals(listOf("key-a", "key-a", "key-a"), seqBeforeExhaust)

        roulette.reportGoogleError(
            key = "key-a",
            statusCode = 429,
            responseBody = """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""",
            modelId = "gemini-3.8-flash",
        )
        assertEquals(
            "key-b",
            roulette.next(keys, providerId = "p1", sessionId = "conv-1", rotationMode = KeyRotationMode.SEQUENTIAL, modelId = "gemini-3.8-flash"),
        )
        // Other model on key-a stays unaffected in SEQUENTIAL mode
        assertEquals(
            "key-a",
            roulette.next(keys, providerId = "p1", sessionId = "conv-1", rotationMode = KeyRotationMode.SEQUENTIAL, modelId = "gemini-3.6-flash"),
        )

        KeyPoolMonitor.resetAll()

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
    fun `classifyGoogleModelBucket classifies RP and auxiliary models accurately`() {
        assertEquals(GoogleQuotaBucket.GEMINI_3_5_LITE, classifyGoogleModelBucket("gemini-3.5-flash-lite"))
        assertEquals(GoogleQuotaBucket.GEMINI_3_5_LITE, classifyGoogleModelBucket("models/gemini-3.5-flash-lite-preview"))
        assertEquals(null, classifyGoogleModelBucket("gemini-3.5-transcribe"))
        assertEquals(null, classifyGoogleModelBucket("gemini-3-flash"))
        assertEquals(GoogleQuotaBucket.GEMINI_3_6, classifyGoogleModelBucket("gemini-3.6-flash"))
        assertEquals(GoogleQuotaBucket.GEMINI_3_8, classifyGoogleModelBucket("models/gemini-3.8-flash-preview"))
        assertEquals(GoogleQuotaBucket.GEMINI_4_HYDROGEN, classifyGoogleModelBucket("gemini-4-hydrogen"))
        assertEquals(GoogleQuotaBucket.EMBEDDING, classifyGoogleModelBucket("gemini-embedding-2"))
        assertEquals(GoogleQuotaBucket.DECISION, classifyGoogleModelBucket("gemini-decision-1"))
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
            params = TextGenerationParams(model = nonRpChatModel, sessionId = "conv-rpd-test"),
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

    @Test
    fun `per-model 429 RPD isolation does not block other chat models on the same key`() = runTest {
        val attemptedPairs = mutableListOf<Pair<String, String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val key = req.header("x-goog-api-key") ?: ""
                val path = req.url.encodedPath
                attemptedPairs.add(key to path)
                if (key == "AIzaKeyOne00001" && "hydrogen" in path) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(
                            """{"error":{"code":429,"details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
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
                            """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            }
            .build()

        val provider = GoogleProvider(client)
        val keysRaw = "AIzaKeyOne00001\nAIzaKeyTwo00002"
        val setting = ProviderSetting.Google(apiKey = keysRaw, keyRotationMode = KeyRotationMode.SEQUENTIAL)

        // Exhaust Hydrogen on Key 1 -> degrades within Key 1 to 3.8 Flash (no cross-key jump!)
        val firstResult = provider.generateText(
            setting,
            listOf(UIMessage.user("hi")),
            TextGenerationParams(model = hydrogenModel, sessionId = "s-h4"),
        )
        assertEquals("AIzaKeyOne00001", attemptedPairs[0].first)
        assertTrue("hydrogen" in attemptedPairs[0].second)
        assertEquals("AIzaKeyOne00001", attemptedPairs[1].first)
        assertTrue("3.8-flash" in attemptedPairs[1].second)
        assertEquals(GOOGLE_MODEL_3_8_FLASH, firstResult.model)

        // Switching to 3.8 Flash should still use Key 1 cleanly!
        provider.generateText(
            setting,
            listOf(UIMessage.user("hi again")),
            TextGenerationParams(model = chatModel, sessionId = "s-38"),
        )
        assertEquals("AIzaKeyOne00001", attemptedPairs[2].first)

        val snapshot1 = KeyPoolMonitor.inspectPool(keysRaw).first { it.key == "AIzaKeyOne00001" }
        assertEquals(KeyCooldownReason.RPD_EXHAUSTED, snapshot1.bucketUsage[GoogleQuotaBucket.GEMINI_4_HYDROGEN]?.reason)
        assertEquals(KeyCooldownReason.READY, snapshot1.bucketUsage[GoogleQuotaBucket.GEMINI_3_8]?.reason)
        assertEquals(2, snapshot1.bucketUsage[GoogleQuotaBucket.GEMINI_3_8]?.used)
        assertEquals(0, snapshot1.bucketUsage[GoogleQuotaBucket.GEMINI_4_HYDROGEN]?.used)
    }

    @Test
    fun `auxiliaryApiKey isolates embedding calls from chat key pool`() = runTest {
        val usedKeys = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                usedKeys.add(req.header("x-goog-api-key") ?: "")
                val path = req.url.encodedPath
                val body = if (path.endsWith(":embedContent")) {
                    """{"embedding":{"values":[0.5,0.5]}}"""
                } else {
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"chat ok"}]},"finishReason":"STOP"}]}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(
            apiKey = "AIzaChatKeyOne001\nAIzaChatKeyTwo002",
            auxiliaryApiKey = "AIzaAuxDedicate01",
            keyRotationMode = KeyRotationMode.SEQUENTIAL,
        )

        provider.generateEmbedding(
            setting,
            EmbeddingGenerationParams(model = embeddingModel, input = listOf("memory node")),
        )
        provider.generateText(
            setting,
            listOf(UIMessage.user("hello")),
            TextGenerationParams(model = chatModel, sessionId = "s-aux"),
        )

        assertEquals(listOf("AIzaAuxDedicate01", "AIzaChatKeyOne001"), usedKeys)
    }

    @Test
    fun `KeyPoolMonitor counts streamText once per stream across multiple SSE chunks and supports resetDailyUsage`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val sse = """
                    data: {"candidates":[{"content":{"role":"model","parts":[{"text":"part1 "}]}}]}

                    data: {"candidates":[{"content":{"role":"model","parts":[{"text":"part2"}]},"finishReason":"STOP"}]}

                    data: [DONE]

                """.trimIndent() + "\n"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sse.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()

        val provider = GoogleProvider(client)
        val keysRaw = "AIzaSingleKey0001"
        val setting = ProviderSetting.Google(apiKey = keysRaw, keyRotationMode = KeyRotationMode.SEQUENTIAL)

        val chunks = provider.streamText(
            setting,
            listOf(UIMessage.user("stream twice")),
            TextGenerationParams(model = flash36Model, sessionId = "s-count"),
        ).toList()
        val text = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertEquals("part1 part2", text)

        val beforeReset = KeyPoolMonitor.inspectPool(keysRaw).single()
        assertEquals(1, beforeReset.bucketUsage[GoogleQuotaBucket.GEMINI_3_6]?.used)

        KeyPoolMonitor.resetDailyUsage(keysRaw)
        val afterReset = KeyPoolMonitor.inspectPool(keysRaw).single()
        assertEquals(0, afterReset.bucketUsage[GoogleQuotaBucket.GEMINI_3_6]?.used)
    }

    @Test
    fun `same key degrades hydrogen to 3_8 then 3_6 on 60s RPM cooldown without jumping to key 2`() {
        val keysRaw = "key-1\nkey-2\nkey-3"
        val t0 = 1_760_000_000_000L
        val rpmBody = """{"error":{"code":429,"details":[{"retryDelay":"60s"}]}}"""

        assertEquals(
            GoogleDispatchCandidate("key-1", GOOGLE_MODEL_HYDROGEN),
            KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0).first(),
        )

        KeyCooldownTracker.reportGoogleError("key-1", 429, null, rpmBody, GOOGLE_MODEL_HYDROGEN, now = t0)
        assertEquals(
            GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_8_FLASH),
            KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 1_000L).first(),
        )

        KeyCooldownTracker.reportGoogleError("key-1", 429, null, rpmBody, GOOGLE_MODEL_3_8_FLASH, now = t0)
        assertEquals(
            GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_6_FLASH),
            KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 2_000L).first(),
        )
    }

    @Test
    fun `all 3 primary models short-cooled on key 1 falls back to key 1 3_5 lite and revives hydrogen after 60s`() {
        val keysRaw = "key-1\nkey-2\nkey-3"
        val t0 = 1_760_000_000_000L
        val rpmBody = """{"error":{"code":429,"details":[{"retryDelay":"60s"}]}}"""

        listOf(GOOGLE_MODEL_HYDROGEN, GOOGLE_MODEL_3_8_FLASH, GOOGLE_MODEL_3_6_FLASH).forEach { model ->
            KeyCooldownTracker.reportGoogleError("key-1", 429, null, rpmBody, model, now = t0)
        }

        // 1 分钟内全部主力短冷 -> 切 key-1 的 3.5fl 兜底，不跳 key-2
        val duringCooldown = KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 5_000L)
        assertEquals(GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_5_LITE), duringCooldown.first())
        assertTrue(duringCooldown.none { it.apiKey == "key-2" && it.modelId == GOOGLE_MODEL_HYDROGEN })

        // 即使使用 key-1 的 3.5fl 成功，也不解除 key-1 主力的 60s 冷却
        KeyCooldownTracker.reportSuccess("key-1", GOOGLE_MODEL_3_5_LITE, now = t0 + 6_000L)
        assertEquals(
            GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_5_LITE),
            KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 10_000L).first(),
        )

        // 60s 冷却过期后自动复活 key-1 的 Hydrogen
        assertEquals(
            GoogleDispatchCandidate("key-1", GOOGLE_MODEL_HYDROGEN),
            KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 61_000L).first(),
        )
    }

    @Test
    fun `when active primary key is #3 and all 3 primaries are short-cooled, falls back to #1 3_5 lite without jumping to #4`() {
        val keysRaw = "key-1\nkey-2\nkey-3\nkey-4"
        val t0 = 1_760_000_000_000L
        val rpdBody = """{"error":{"code":429,"message":"PerDay quota exceeded"}}"""
        val rpmBody = """{"error":{"code":429,"details":[{"retryDelay":"60s"}]}}"""

        // #1 和 #2 主力 3 档全部 RPD 耗尽
        listOf("key-1", "key-2").forEach { key ->
            listOf(GOOGLE_MODEL_HYDROGEN, GOOGLE_MODEL_3_8_FLASH, GOOGLE_MODEL_3_6_FLASH).forEach { model ->
                KeyCooldownTracker.reportGoogleError(key, 429, null, rpdBody, model, now = t0)
            }
        }
        // #3 处于主力位且 3 档全短冷却（未日满）
        listOf(GOOGLE_MODEL_HYDROGEN, GOOGLE_MODEL_3_8_FLASH, GOOGLE_MODEL_3_6_FLASH).forEach { model ->
            KeyCooldownTracker.reportGoogleError("key-3", 429, null, rpmBody, model, now = t0)
        }

        val plan = KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 1_000L)
        assertEquals(GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_5_LITE), plan.first())
        assertTrue(plan.none { it.apiKey == "key-4" })
        assertTrue(plan.none { it.apiKey == "key-3" && it.modelId == GOOGLE_MODEL_3_5_LITE })
    }

    @Test
    fun `when key 1 all 3 primaries hit RPD_EXHAUSTED, skips key 1 3_5 lite and switches to key 2 hydrogen`() {
        val keysRaw = "key-1\nkey-2\nkey-3"
        val t0 = 1_760_000_000_000L
        val rpdBody = """{"error":{"code":429,"message":"PerDay quota exceeded"}}"""

        listOf(GOOGLE_MODEL_HYDROGEN, GOOGLE_MODEL_3_8_FLASH, GOOGLE_MODEL_3_6_FLASH).forEach { model ->
            KeyCooldownTracker.reportGoogleError("key-1", 429, null, rpdBody, model, now = t0)
        }

        val plan = KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 1_000L)
        assertEquals(GoogleDispatchCandidate("key-2", GOOGLE_MODEL_HYDROGEN), plan.first())
        assertTrue(plan.none { it.apiKey == "key-1" && it.modelId == GOOGLE_MODEL_3_5_LITE })
    }

    @Test
    fun `only falls back to first 2 keys 3_5 lite after all keys exhaust their primary 3 models`() {
        val keysRaw = "key-1\nkey-2\nkey-3"
        val t0 = 1_760_000_000_000L
        val rpdBody = """{"error":{"code":429,"message":"PerDay quota exceeded"}}"""

        // 测试通过每日计数达到 20 次也视为 RPD 满额
        repeat(20) { KeyCooldownTracker.reportSuccess("key-1", GOOGLE_MODEL_HYDROGEN, now = t0) }
        repeat(20) { KeyCooldownTracker.reportSuccess("key-1", GOOGLE_MODEL_3_8_FLASH, now = t0) }
        repeat(20) { KeyCooldownTracker.reportSuccess("key-1", GOOGLE_MODEL_3_6_FLASH, now = t0) }

        listOf("key-2", "key-3").forEach { key ->
            listOf(GOOGLE_MODEL_HYDROGEN, GOOGLE_MODEL_3_8_FLASH, GOOGLE_MODEL_3_6_FLASH).forEach { model ->
                KeyCooldownTracker.reportGoogleError(key, 429, null, rpdBody, model, now = t0)
            }
        }

        val plan = KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_HYDROGEN, now = t0 + 1_000L)
        assertEquals(
            listOf(
                GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_5_LITE),
                GoogleDispatchCandidate("key-2", GOOGLE_MODEL_3_5_LITE),
            ),
            plan,
        )
        assertTrue(plan.none { it.apiKey == "key-3" })

        // 若从 3.8 Flash 起步，绝不反向升级到 Hydrogen
        val planFrom38 = KeyCooldownTracker.resolveGoogleFallbackChain(GOOGLE_MODEL_3_8_FLASH)
        assertEquals(listOf(GOOGLE_MODEL_3_8_FLASH, GOOGLE_MODEL_3_6_FLASH), planFrom38)
    }

    @Test
    fun `effectiveAuxKeys returns first 2 keys when auxiliaryApiKey is blank and summarizePool caps 3_5L to min(N, 2) x 500`() {
        val provider = GoogleProvider(OkHttpClient())
        val fourKeys = "key-1\nkey-2\nkey-3\nkey-4"

        val settingDefaultAux = ProviderSetting.Google(apiKey = fourKeys, auxiliaryApiKey = "")
        val resolvedDefault = with(provider) { settingDefaultAux.effectiveAuxKeys() }
        assertEquals("key-1\nkey-2", resolvedDefault)

        val settingCustomAux = ProviderSetting.Google(apiKey = fourKeys, auxiliaryApiKey = "aux-only-1")
        val resolvedCustom = with(provider) { settingCustomAux.effectiveAuxKeys() }
        assertEquals("aux-only-1", resolvedCustom)

        val t0 = 1_760_000_000_000L
        KeyCooldownTracker.reportSuccess("key-1", GOOGLE_MODEL_3_5_LITE, now = t0)
        KeyCooldownTracker.reportSuccess("key-2", GOOGLE_MODEL_3_5_LITE, now = t0)
        KeyCooldownTracker.reportSuccess("key-3", GOOGLE_MODEL_3_5_LITE, now = t0)
        KeyCooldownTracker.reportSuccess("key-4", GOOGLE_MODEL_HYDROGEN, now = t0)

        val summary4 = KeyPoolMonitor.summarizePool(fourKeys, now = t0)
        assertEquals(80, summary4.bucketSummaries[GoogleQuotaBucket.GEMINI_4_HYDROGEN]?.totalCapacity)
        assertEquals(1, summary4.bucketSummaries[GoogleQuotaBucket.GEMINI_4_HYDROGEN]?.used)
        assertEquals(1000, summary4.bucketSummaries[GoogleQuotaBucket.GEMINI_3_5_LITE]?.totalCapacity)
        assertEquals(2, summary4.bucketSummaries[GoogleQuotaBucket.GEMINI_3_5_LITE]?.used)

        val summary1 = KeyPoolMonitor.summarizePool("key-1", now = t0)
        assertEquals(500, summary1.bucketSummaries[GoogleQuotaBucket.GEMINI_3_5_LITE]?.totalCapacity)
    }

    @Test
    fun `GoogleProvider generateText and streamText rewrite effectiveModel in HTTP URL and report counts`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                val key = req.header("x-goog-api-key") ?: ""
                val isStream = url.contains(":streamGenerateContent")

                if (key == "key-1" && (url.contains("hydrogen") || url.contains("3.8-flash") || url.contains("3.6-flash"))) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("""{"error":{"code":429,"details":[{"retryDelay":"60s"}]}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (isStream) {
                    val sse = """
                        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"lite stream"}]},"finishReason":"STOP"}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sse.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"candidates":[{"content":{"role":"model","parts":[{"text":"lite ok"}]},"finishReason":"STOP"}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val provider = GoogleProvider(client)
        val keysRaw = "key-1\nkey-2"
        val setting = ProviderSetting.Google(apiKey = keysRaw, keyRotationMode = KeyRotationMode.SEQUENTIAL)
        val reasoningHydrogen = hydrogenModel.copy(abilities = listOf(ModelAbility.REASONING))

        val genResult = provider.generateText(
            setting,
            listOf(UIMessage.user("hello")),
            TextGenerationParams(model = reasoningHydrogen, reasoningLevel = ReasoningLevel.MEDIUM, sessionId = "s-gp-1"),
        )
        assertEquals(GOOGLE_MODEL_3_5_LITE, genResult.model)
        assertEquals(
            listOf(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            ),
            requestedUrls,
        )

        val streamChunks = provider.streamText(
            setting,
            listOf(UIMessage.user("hello stream")),
            TextGenerationParams(model = reasoningHydrogen, reasoningLevel = ReasoningLevel.MEDIUM, sessionId = "s-gp-2"),
        ).toList()
        assertTrue(requestedUrls.last().contains("/models/gemini-3.5-flash-lite:streamGenerateContent"))
        val finishChunk = streamChunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals(GOOGLE_MODEL_3_5_LITE, finishChunk.model)

        val snap1 = KeyPoolMonitor.inspectPool(keysRaw).first { it.key == "key-1" }
        assertEquals(2, snap1.bucketUsage[GoogleQuotaBucket.GEMINI_3_5_LITE]?.used)
        assertEquals(0, snap1.bucketUsage[GoogleQuotaBucket.GEMINI_4_HYDROGEN]?.used)
    }

    @Test
    fun `InteractionsAPI generateText and streamText rewrite top-level model in JSON body and report counts`() = runTest {
        val sentModels = mutableListOf<Pair<String, String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val key = req.header("x-goog-api-key") ?: ""
                val buf = Buffer()
                req.body?.writeTo(buf)
                val reqJson = json.parseToJsonElement(buf.readUtf8()).jsonObject
                val modelInBody = reqJson["model"]!!.jsonPrimitive.content
                val isStream = reqJson["stream"]?.jsonPrimitive?.content == "true"
                sentModels.add(key to modelInBody)

                if (key == "key-1" && modelInBody == GOOGLE_MODEL_HYDROGEN) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("""{"error":{"code":429,"details":[{"retryDelay":"60s"}]}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (isStream) {
                    val sse = """
                        data: {"event_type":"interaction.created","interaction":{"id":"int_s1","status":"in_progress"}}

                        data: {"index":0,"step":{"type":"model_output"},"event_type":"step.start"}

                        data: {"index":0,"delta":{"type":"text","text":"stream 3.8"},"event_type":"step.delta"}

                        data: {"index":0,"event_type":"step.stop"}

                        data: {"event_type":"interaction.completed","interaction":{"id":"int_s1","status":"completed"}}

                        data: [DONE]

                    """.trimIndent() + "\n"
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sse.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"id":"int_1","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"ok 3.8"}]}]}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val provider = GoogleProvider(client)
        val keysRaw = "key-1\nkey-2"
        val setting = ProviderSetting.Google(
            apiKey = keysRaw,
            useInteractionsApi = true,
            keyRotationMode = KeyRotationMode.SEQUENTIAL,
        )
        val reasoningHydrogen = hydrogenModel.copy(abilities = listOf(ModelAbility.REASONING))

        val result = provider.generateText(
            setting,
            listOf(UIMessage.user("hello interactions")),
            TextGenerationParams(model = reasoningHydrogen, reasoningLevel = ReasoningLevel.HIGH, sessionId = "s-int-1"),
        )
        assertEquals(GOOGLE_MODEL_3_8_FLASH, result.model)
        assertEquals(
            listOf(
                "key-1" to GOOGLE_MODEL_HYDROGEN,
                "key-1" to GOOGLE_MODEL_3_8_FLASH,
            ),
            sentModels,
        )

        val streamChunks = provider.streamText(
            setting,
            listOf(UIMessage.user("hello interactions stream")),
            TextGenerationParams(model = reasoningHydrogen, reasoningLevel = ReasoningLevel.HIGH, sessionId = "s-int-2"),
        ).toList()
        assertEquals("key-1" to GOOGLE_MODEL_3_8_FLASH, sentModels.last())
        val finishChunk = streamChunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals(GOOGLE_MODEL_3_8_FLASH, finishChunk.model)

        val snap1 = KeyPoolMonitor.inspectPool(keysRaw).first { it.key == "key-1" }
        assertEquals(2, snap1.bucketUsage[GoogleQuotaBucket.GEMINI_3_8]?.used)
        assertEquals(0, snap1.bucketUsage[GoogleQuotaBucket.GEMINI_4_HYDROGEN]?.used)
    }

    @Test
    fun `non-RP model does not switch models and direct 3_5 lite only uses first 2 keys`() {
        val keysRaw = "key-1\nkey-2\nkey-3"
        val t0 = 1_760_000_000_000L
        val rpdBody = """{"error":{"code":429,"message":"PerDay quota exceeded"}}"""

        KeyCooldownTracker.reportGoogleError("key-1", 429, null, rpdBody, nonRpChatModel.modelId, now = t0)
        val nonRpPlan = KeyCooldownTracker.planGoogleCandidates(keysRaw, nonRpChatModel.modelId, now = t0 + 1_000L)
        assertEquals(
            listOf(
                GoogleDispatchCandidate("key-2", nonRpChatModel.modelId),
                GoogleDispatchCandidate("key-3", nonRpChatModel.modelId),
            ),
            nonRpPlan,
        )

        val litePlan = KeyCooldownTracker.planGoogleCandidates(keysRaw, GOOGLE_MODEL_3_5_LITE, now = t0 + 1_000L)
        assertEquals(
            listOf(
                GoogleDispatchCandidate("key-1", GOOGLE_MODEL_3_5_LITE),
                GoogleDispatchCandidate("key-2", GOOGLE_MODEL_3_5_LITE),
            ),
            litePlan,
        )
        assertTrue(litePlan.none { it.apiKey == "key-3" })
    }
}
