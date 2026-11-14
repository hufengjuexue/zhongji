package me.rerere.tts.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
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
import org.junit.Assert.assertFalse
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
    fun prepareGeminiTtsText_translatesTagsAndStyleAcrossGenerations() {
        val raw = "[style: warm and gentle]你回来啦[chuckle][short pause]我想你了。"

        val p38 = prepareGeminiTtsText(raw, "soft laugh", "gemini-3.8-flash-tts")
        assertEquals("soft laugh; warm and gentle", p38.style)
        assertEquals("你回来啦<chuckle><short pause>我想你了。", p38.text)

        val p31 = prepareGeminiTtsText(raw, "ignored", "gemini-3.1-flash-tts-preview")
        assertEquals("", p31.style)
        assertEquals("你回来啦<chuckle><short pause>我想你了。", p31.text)

        val p25 = prepareGeminiTtsText(raw, "ignored", "gemini-2.5-flash-preview-tts")
        assertEquals("", p25.style)
        assertEquals("你回来啦 我想你了。", p25.text)
    }

    @Test
    fun buildGeminiTtsCandidateModels_followsLadderOrSingleModel() {
        assertEquals(
            listOf(
                "gemini-3.8-flash-tts",
                "gemini-3.8-flash-lite-tts",
                "gemini-3.1-flash-tts-preview",
                "gemini-2.5-flash-preview-tts",
            ),
            buildGeminiTtsCandidateModels("gemini-3.8-flash-tts", autoFallback = true),
        )
        assertEquals(
            listOf(
                "gemini-3.1-flash-tts-preview",
                "gemini-2.5-flash-preview-tts",
            ),
            buildGeminiTtsCandidateModels("models/gemini-3.1-flash-tts-preview", autoFallback = true),
        )
        assertEquals(
            listOf("gemini-3.8-flash-tts"),
            buildGeminiTtsCandidateModels("gemini-3.8-flash-tts", autoFallback = false),
        )
    }

    @Test
    fun resolveGeminiVoiceForAttempt_respectsProjectOwnerAndModelGeneration() {
        val key1 = "AIzaSyKeyOne1234567890"
        val key2 = "AIzaSyKeyTwo0987654321"
        val setting = TTSProviderSetting.Gemini(
            apiKey = "$key1\n$key2",
            voiceName = "voice_custom_123",
            fallbackVoiceName = "Aoede",
            voiceOwnerKeyHint = maskGeminiTtsKey(key1),
        )

        // 归属 Key + 3.8 -> 保留 voice_custom_123
        assertEquals("voice_custom_123", resolveGeminiVoiceForAttempt(setting, key1, "gemini-3.8-flash-tts"))
        assertEquals("voice_custom_123", resolveGeminiVoiceForAttempt(setting, key1, "gemini-3.8-flash-lite-tts"))

        // 同 Key 降级 3.1 / 2.5 -> 自动切换相似备选 Aoede
        assertEquals("Aoede", resolveGeminiVoiceForAttempt(setting, key1, "gemini-3.1-flash-tts-preview"))
        assertEquals("Aoede", resolveGeminiVoiceForAttempt(setting, key1, "gemini-2.5-flash-preview-tts"))

        // 切到另一项目的 Key2（即使仍在 3.8）-> 自动切换 Aoede
        assertEquals("Aoede", resolveGeminiVoiceForAttempt(setting, key2, "gemini-3.8-flash-tts"))
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
    fun synthesizeSpeechFlow_fallsBackAcrossAllFourGenerationsAndTracksCounts() = runBlocking {
        val seenModels = mutableListOf<String>()
        val seenVoices = mutableListOf<String>()
        var model31HadAnnotations = true

        val pcmFinal = byteArrayOf(11, 22, 33, 44)
        val pcmFinalB64 = Base64.getEncoder().encodeToString(pcmFinal)

        val client = clientWithHandler { req ->
            val rawBody = readRequestBody(req)
            val obj = json.parseToJsonElement(rawBody).jsonObject
            val model = obj["model"]?.jsonPrimitive?.content.orEmpty()
            seenModels += model

            if (req.url.encodedPath.endsWith("/interactions")) {
                val contentObj = obj["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
                if (model.contains("3.1")) {
                    model31HadAnnotations = contentObj.containsKey("annotations")
                }
                val voice = obj["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0]
                    .jsonObject["voice"]!!.jsonPrimitive.content
                seenVoices += voice
                buildJsonResponse(
                    req,
                    429,
                    """{"error":{"message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDay"}]}]}}""",
                )
            } else {
                val voice = obj["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!
                    .jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content
                seenVoices += voice
                val legacyBody = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"$pcmFinalB64"}}]}}]}"""
                buildJsonResponse(req, 200, legacyBody)
            }
        }

        val key = "AIzaSySingleKeyForLadder999"
        val provider = GeminiTTSProvider(httpClient = client)
        val setting = TTSProviderSetting.Gemini(
            apiKey = key,
            model = "gemini-3.8-flash-tts",
            voiceName = "voice_owner_custom",
            fallbackVoiceName = "Leda",
            voiceOwnerKeyHint = maskGeminiTtsKey(key),
            stylePrompt = "soft",
            autoFallbackModels = true,
        )

        val chunks = provider.synthesizeSpeechFlow(setting, TTSRequest("测试降级[laugh]")).toList()

        assertEquals(GEMINI_TTS_MODEL_LADDER, seenModels)
        assertEquals(
            listOf("voice_owner_custom", "voice_owner_custom", "Leda", "Leda"),
            seenVoices,
        )
        assertFalse("Gemini 3.1 must not receive speech_metadata annotations", model31HadAnnotations)
        assertEquals(1, chunks.size)
        assertArrayEquals(pcmFinal, chunks[0].data)

        val summary = GeminiTtsPoolTracker.summarizePool(key)
        assertEquals(1 to 10, summary["gemini-2.5-flash-preview-tts"])
        assertEquals(0 to 10, summary["gemini-3.8-flash-tts"])
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
