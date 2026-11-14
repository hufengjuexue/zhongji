package me.rerere.tts.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.resolveVoiceDesignRelayCandidates
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GeminiTTSProviderTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun resetPool() {
        GeminiTtsPoolTracker.clearAllForTesting()
        MiMoTtsKeyTracker.clearAllForTesting()
    }

    private fun buildMinimalWav(pcmPayload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmPayload.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // mono
        header.putInt(24000)
        header.putInt(48000)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmPayload.size)
        return header.array() + pcmPayload
    }

    private fun readRequestBody(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun clientWithHandler(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()

    private fun buildJsonResponse(
        request: Request,
        code: Int,
        body: String,
        contentType: String = "application/json",
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(body.toResponseBody(contentType.toMediaType()))
        .build()

    @Test
    fun prepareGeminiTtsText_translatesTagsAndStyleFor38() {
        val raw = "[style: warm and gentle]你回来啦[chuckle][short pause]我想你了。"

        val p38 = prepareGeminiTtsText(raw, "soft laugh", "gemini-3.8-flash-tts")
        assertEquals("soft laugh; warm and gentle", p38.style)
        assertEquals("你回来啦<chuckle><short pause>我想你了。", p38.text)
    }

    @Test
    fun buildGeminiTtsCandidateModels_onlyUses38LadderAndConvergesLegacyModels() {
        assertEquals(
            listOf(
                "gemini-3.8-flash-tts",
                "gemini-3.8-flash-lite-tts",
            ),
            GEMINI_TTS_MODEL_LADDER,
        )
        assertEquals(
            listOf(
                "gemini-3.8-flash-tts",
                "gemini-3.8-flash-lite-tts",
            ),
            buildGeminiTtsCandidateModels("gemini-3.8-flash-tts", autoFallback = true),
        )
        assertEquals(
            listOf("gemini-3.8-flash-lite-tts"),
            buildGeminiTtsCandidateModels("gemini-3.8-flash-lite-tts", autoFallback = true),
        )
        // Legacy 3.1 / 2.5 converge back to 3.8
        assertEquals(
            listOf(
                "gemini-3.8-flash-tts",
                "gemini-3.8-flash-lite-tts",
            ),
            buildGeminiTtsCandidateModels("models/gemini-3.1-flash-tts-preview", autoFallback = true),
        )
        assertEquals(
            listOf("gemini-3.8-flash-tts"),
            buildGeminiTtsCandidateModels("gemini-2.5-flash-preview-tts", autoFallback = false),
        )
    }

    @Test
    fun resolveGeminiVoiceForAttempt_matchesSyncedVoicePerKeyAndFallsBackForUnsyncedKey() {
        val key1 = "AIzaSyKeyOne1234567890"
        val key2 = "AIzaSyKeyTwo0987654321"
        val key3 = "AIzaSyKeyThree55555555"
        val setting = TTSProviderSetting.Gemini(
            apiKey = "$key1\n$key2\n$key3",
            voiceName = "voice_custom_key1",
            fallbackVoiceName = "Aoede",
            voiceOwnerKeyHint = maskGeminiTtsKey(key1),
            voiceIdsByKeyHint = mapOf(
                maskGeminiTtsKey(key1) to "voice_custom_key1",
                maskGeminiTtsKey(key2) to "voice_custom_key2",
            ),
        )

        assertEquals("voice_custom_key1", resolveGeminiVoiceForAttempt(setting, key1, "gemini-3.8-flash-tts"))
        assertEquals("voice_custom_key2", resolveGeminiVoiceForAttempt(setting, key2, "gemini-3.8-flash-lite-tts"))
        // Unsynced key3 falls back to Aoede
        assertEquals("Aoede", resolveGeminiVoiceForAttempt(setting, key3, "gemini-3.8-flash-tts"))
    }

    @Test
    fun stripWavHeaderAndExtractInteractionsEvents() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wav = buildMinimalWav(pcm)
        assertArrayEquals(pcm, stripWavHeaderIfPresent(wav))
        assertArrayEquals(pcm, stripWavHeaderIfPresent(pcm))

        val b64Wav = Base64.getEncoder().encodeToString(wav)
        val deltaEvent = """{"event_type":"step.delta","delta":{"type":"audio","data":"$b64Wav"}}"""
        val extractedDelta = extractAudioBytesFromInteractionsEvent(deltaEvent)
        assertEquals(1, extractedDelta.size)
        assertArrayEquals(pcm, extractedDelta.first())

        val snapshotEvent = """{"steps":[{"type":"model_output","content":[{"type":"audio","data":"$b64Wav"}]}]}"""
        val extractedSnap = extractAudioBytesFromInteractionsEvent(snapshotEvent)
        assertEquals(1, extractedSnap.size)
        assertArrayEquals(pcm, extractedSnap.first())
    }

    @Test
    fun synthesizeSpeechFlow_38StreamsSseWithStyleAndStrippedWavHeaders() = runBlocking {
        val pcm1 = byteArrayOf(10, 20, 30, 40)
        val pcm2 = byteArrayOf(50, 60, 70, 80)
        val wav1B64 = Base64.getEncoder().encodeToString(buildMinimalWav(pcm1))
        val pcm2B64 = Base64.getEncoder().encodeToString(pcm2)

        var capturedPath = ""
        var capturedKeyHeader = ""
        var capturedBody = ""

        val client = clientWithHandler { req ->
            capturedPath = req.url.encodedPath + "?" + (req.url.encodedQuery ?: "")
            capturedKeyHeader = req.header("x-goog-api-key") ?: ""
            capturedBody = readRequestBody(req)
            val sse = buildString {
                appendLine("""data: {"event_type":"step.delta","delta":{"type":"audio","data":"$wav1B64"}}""")
                appendLine()
                appendLine("""data: {"event_type":"step.delta","delta":{"type":"audio","data":"$pcm2B64"}}""")
                appendLine()
                appendLine("data: [DONE]")
            }
            buildJsonResponse(req, 200, sse, "text/event-stream")
        }

        val provider = GeminiTTSProvider(httpClient = client)
        val setting = TTSProviderSetting.Gemini(
            apiKey = "AIzaSyTestPrimaryKey1234",
            model = "gemini-3.8-flash-tts",
            voiceName = "voice_my_lain",
            fallbackVoiceName = "Achernar",
            stylePrompt = "gentle whisper",
        )

        val chunks = provider.synthesizeSpeechFlow(
            setting,
            TTSRequest("[style: late night]早安[sigh]"),
        ).toList()

        assertEquals("/v1beta/interactions?alt=sse", capturedPath)
        assertEquals("AIzaSyTestPrimaryKey1234", capturedKeyHeader)

        val reqObj = json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("gemini-3.8-flash-tts", reqObj["model"]!!.jsonPrimitive.content)
        val contentItem = reqObj["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("早安<sigh>", contentItem["text"]!!.jsonPrimitive.content)
        val styleAttr = contentItem["annotations"]!!.jsonArray[0].jsonObject["style"]!!.jsonPrimitive.content
        assertEquals("gentle whisper; late night", styleAttr)

        assertEquals(2, chunks.size)
        assertEquals(AudioFormat.PCM, chunks[0].format)
        assertArrayEquals(pcm1, chunks[0].data)
        assertArrayEquals(pcm2, chunks[1].data)
    }

    @Test
    fun synthesizeSpeechFlow_exhaustsFlashThenLitePerKeyBeforeSwitchingKeyAndUsesSyncedVoices() = runBlocking {
        val attempts = mutableListOf<Triple<String, String, String>>()
        val pcmFinal = byteArrayOf(11, 22, 33, 44)
        val pcmFinalB64 = Base64.getEncoder().encodeToString(pcmFinal)

        val key1 = "AIzaSyPrimaryPoolKey1111"
        val key2 = "AIzaSySecondaryPoolKey2222"

        val client = clientWithHandler { req ->
            val key = req.header("x-goog-api-key").orEmpty()
            val obj = json.parseToJsonElement(readRequestBody(req)).jsonObject
            val model = obj["model"]?.jsonPrimitive?.content.orEmpty()
            val voice = obj["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0]
                .jsonObject["voice"]!!.jsonPrimitive.content
            attempts += Triple(key, model, voice)

            if (key == key1 || model == "gemini-3.8-flash-tts") {
                buildJsonResponse(
                    req,
                    429,
                    """{"error":{"message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDay"}]}]}}""",
                )
            } else {
                val sse = """data: {"event_type":"step.delta","delta":{"type":"audio","data":"$pcmFinalB64"}}""" +
                    "\n\ndata: [DONE]\n"
                buildJsonResponse(req, 200, sse, "text/event-stream")
            }
        }

        val provider = GeminiTTSProvider(httpClient = client)
        val setting = TTSProviderSetting.Gemini(
            apiKey = "$key1\n$key2",
            model = "gemini-3.8-flash-tts",
            voiceName = "voice_synced_k1",
            fallbackVoiceName = "Leda",
            voiceOwnerKeyHint = maskGeminiTtsKey(key1),
            voiceIdsByKeyHint = mapOf(
                maskGeminiTtsKey(key1) to "voice_synced_k1",
                maskGeminiTtsKey(key2) to "voice_synced_k2",
            ),
            stylePrompt = "soft",
            autoFallbackModels = true,
        )

        val chunks = provider.synthesizeSpeechFlow(setting, TTSRequest("测试同Key双档耗尽再换Key")).toList()

        assertEquals(
            listOf(
                Triple(key1, "gemini-3.8-flash-tts", "voice_synced_k1"),
                Triple(key1, "gemini-3.8-flash-lite-tts", "voice_synced_k1"),
                Triple(key2, "gemini-3.8-flash-tts", "voice_synced_k2"),
                Triple(key2, "gemini-3.8-flash-lite-tts", "voice_synced_k2"),
            ),
            attempts,
        )
        assertEquals(1, chunks.size)
        assertArrayEquals(pcmFinal, chunks[0].data)

        val summary = GeminiTtsPoolTracker.summarizePool("$key1\n$key2")
        assertEquals(1 to 20, summary["gemini-3.8-flash-lite-tts"])
        assertEquals(0 to 20, summary["gemini-3.8-flash-tts"])
    }

    @Test
    fun synthesizeSpeechFlow_retriesImmediatelyWithFallbackOnVoiceNotFound404() = runBlocking {
        val requestedVoices = mutableListOf<String>()
        val pcmOk = byteArrayOf(7, 8, 9, 10)
        val b64Ok = Base64.getEncoder().encodeToString(pcmOk)

        val client = clientWithHandler { req ->
            val obj = json.parseToJsonElement(readRequestBody(req)).jsonObject
            val voice = obj["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0]
                .jsonObject["voice"]!!.jsonPrimitive.content
            requestedVoices += voice
            if (voice == "voice_other_project") {
                buildJsonResponse(req, 404, """{"error":{"message":"Voice voice_other_project not found in project"}}""")
            } else {
                val sse = """data: {"event_type":"step.delta","delta":{"type":"audio","data":"$b64Ok"}}""" + "\n\ndata: [DONE]\n"
                buildJsonResponse(req, 200, sse, "text/event-stream")
            }
        }

        val provider = GeminiTTSProvider(httpClient = client)
        val setting = TTSProviderSetting.Gemini(
            apiKey = "AIzaSyDifferentProjectKey888",
            model = "gemini-3.8-flash-tts",
            voiceName = "voice_other_project",
            fallbackVoiceName = "Kore",
            voiceOwnerKeyHint = "", // 未标记归属 Key，先试主音色，遇 404 立即原位用 Kore 重试
        )

        val chunks = provider.synthesizeSpeechFlow(setting, TTSRequest("你好")).toList()
        assertEquals(listOf("voice_other_project", "Kore"), requestedVoices)
        assertEquals(1, chunks.size)
        assertArrayEquals(pcmOk, chunks[0].data)
    }

    @Test
    fun ttsManager_relaysFromExhaustedGemini38ToMiMoVoiceDesign() = runBlocking {
        val mimoPcm = byteArrayOf(21, 22, 23, 24)
        val mimoB64 = Base64.getEncoder().encodeToString(mimoPcm)
        val callTrace = mutableListOf<String>()

        val geminiClient = clientWithHandler { req ->
            val obj = json.parseToJsonElement(readRequestBody(req)).jsonObject
            callTrace += "gemini:${obj["model"]?.jsonPrimitive?.content}"
            buildJsonResponse(
                req,
                429,
                """{"error":{"message":"Quota exceeded"}}""",
            )
        }

        val mimoClient = clientWithHandler { req ->
            val obj = json.parseToJsonElement(readRequestBody(req)).jsonObject
            callTrace += "mimo:${obj["model"]?.jsonPrimitive?.content}"
            val sse = """data: {"choices":[{"delta":{"audio":{"data":"$mimoB64"}}}]}""" +
                "\n\ndata: [DONE]\n\n"
            buildJsonResponse(req, 200, sse, "text/event-stream")
        }

        val geminiSetting = TTSProviderSetting.Gemini(
            apiKey = "AIzaSyExhaustedKey1111",
            model = "gemini-3.8-flash-tts",
            autoFallbackModels = true,
        )
        val emptyOpenAi = TTSProviderSetting.OpenAI(apiKey = "")
        val systemTts = TTSProviderSetting.SystemTTS()
        val mimoSetting = TTSProviderSetting.MiMo(
            apiKey = "mimo-free-key",
            model = "mimo-v2.5-tts-voicedesign",
            voiceDesignPrompt = "温柔知性女声",
        )
        val otherCloud = TTSProviderSetting.OpenAI(apiKey = "sk-backup")

        val relayOrder = resolveVoiceDesignRelayCandidates(
            primary = geminiSetting,
            fallbackProviders = listOf(geminiSetting, emptyOpenAi, systemTts, otherCloud, mimoSetting),
        )
        // MiMo VoiceDesign must be prioritized over non-VoiceDesign cloud backups, and empty/system filtered out
        assertEquals(listOf(mimoSetting, otherCloud), relayOrder)

        val manager = TTSManager(
            context = null,
            geminiProvider = GeminiTTSProvider(httpClient = geminiClient),
            miMoProvider = MiMoTTSProvider(httpClient = mimoClient),
        )

        val chunks = manager.generateSpeech(
            providerSetting = geminiSetting,
            fallbackProviders = listOf(geminiSetting, emptyOpenAi, systemTts, mimoSetting),
            request = TTSRequest("跨引擎接力测试"),
        ).toList()

        assertEquals(
            listOf(
                "gemini:gemini-3.8-flash-tts",
                "gemini:gemini-3.8-flash-lite-tts",
                "mimo:mimo-v2.5-tts-voicedesign",
            ),
            callTrace,
        )
        assertTrue(chunks.isNotEmpty())
        assertArrayEquals(mimoPcm, chunks[0].data)
    }

    @Test
    fun voicesClient_createListAndDeleteWorkAsSpecified() {
        val samplePcm = byteArrayOf(1, 2, 3, 4)
        val sampleWav = buildMinimalWav(samplePcm)
        val sampleB64 = Base64.getEncoder().encodeToString(sampleWav)

        val requests = mutableListOf<Pair<String, String>>()
        val client = clientWithHandler { req ->
            requests += req.method to (req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: ""))
            when {
                req.method == "POST" && req.url.encodedPath.endsWith("/voices") -> {
                    val body = json.parseToJsonElement(readRequestBody(req)).jsonObject
                    assertTrue(body["store"]!!.jsonPrimitive.content.toBoolean())
                    assertEquals("prompted", body["voice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                    buildJsonResponse(
                        req,
                        200,
                        """{"id":"voice_generated_777","display_name":"Kairos-姐姐","sample_audio":{"mime_type":"audio/wav","data":"$sampleB64"}}""",
                    )
                }
                req.method == "GET" && req.url.encodedPath.endsWith("/voices") -> {
                    buildJsonResponse(
                        req,
                        200,
                        """{"voices":[{"id":"voice_generated_777","display_name":"Kairos-姐姐","type":"prompted","gender":"female","language_code":"zh-CN"}]}""",
                    )
                }
                req.method == "DELETE" && req.url.encodedPath.endsWith("/voices/voice_generated_777") -> {
                    buildJsonResponse(req, 200, "{}")
                }
                else -> buildJsonResponse(req, 404, "{}")
            }
        }

        val voicesClient = GeminiVoicesClient(httpClient = client)
        val apiKey = "AIzaSyVoiceStudioKey0001"
        val created = voicesClient.createPromptedVoice(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = apiKey,
            displayName = "Kairos-姐姐",
            gender = "female",
            languageCode = "zh-CN",
            prompt = "Warm gentle Chinese female voice",
        )
        assertEquals("voice_generated_777", created.id)
        assertArrayEquals(sampleWav, created.sampleWavBytes)
        assertEquals(maskGeminiTtsKey(apiKey), created.ownerKeyHint)

        val listed = voicesClient.listVoices(
            baseUrl = "",
            apiKey = apiKey,
            type = "prompted",
        )
        assertEquals(1, listed.voices.size)
        assertEquals("voice_generated_777", listed.voices[0].id)

        voicesClient.deleteVoice("", apiKey, "voices/voice_generated_777")
        assertEquals(
            listOf(
                "POST" to "/v1beta/voices",
                "GET" to "/v1beta/voices?page_size=100&type=prompted",
                "DELETE" to "/v1beta/voices/voice_generated_777",
            ),
            requests,
        )
    }
}
