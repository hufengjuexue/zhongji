package me.rerere.tts.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.SseEvent
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class MiMoTTSProviderTest {
    @Before
    fun resetKeys() {
        MiMoTtsKeyTracker.clearAllForTesting()
    }

    @Test
    fun decode_audio_data_from_sse_chunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_sse_chunk_without_audio_data() {
        val data = """{"choices":[{"delta":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }

    @Test
    fun emits_single_terminal_chunk_on_done_and_closed() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val audioData = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val first = processor.process(SseEvent.Event(id = null, type = null, data = audioData))
        val done = processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
        val terminal = processor.process(SseEvent.Closed)

        assertNotNull(first)
        assertEquals(AudioFormat.PCM, first?.format)
        assertFalse(first?.isLast ?: true)
        assertNull(done)
        assertNotNull(terminal)
        assertTrue(terminal?.isLast ?: false)
    }

    @Test
    fun throws_when_stream_closed_without_audio() {
        val processor = MiMoSseProcessor(model = "mimo-v2-tts", voice = "mimo_default")

        var thrown: Throwable? = null
        try {
            processor.process(SseEvent.Event(id = null, type = null, data = "[DONE]"))
            processor.process(SseEvent.Closed)
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun voicedesign_omits_audio_voice_and_supports_optimize_text_preview() {
        val settingWithOpt = TTSProviderSetting.MiMo(
            model = "mimo-v2.5-tts-voicedesign",
            voice = "mimo_default",
            voiceDesignPrompt = "清冷电波系少女音，语速平稳",
            optimizeTextPreview = true,
        )
        val bodyWithOpt = buildMiMoTtsRequestBody(settingWithOpt, "你好，世界")
        assertEquals("mimo-v2.5-tts-voicedesign", bodyWithOpt["model"]!!.jsonPrimitive.content)
        val msgsWithOpt = bodyWithOpt["messages"]!!.jsonArray
        assertEquals(2, msgsWithOpt.size)
        assertEquals("user", msgsWithOpt[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("清冷电波系少女音，语速平稳", msgsWithOpt[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgsWithOpt[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你好，世界", msgsWithOpt[1].jsonObject["content"]!!.jsonPrimitive.content)
        val audioWithOpt = bodyWithOpt["audio"]!!.jsonObject
        assertEquals("pcm16", audioWithOpt["format"]!!.jsonPrimitive.content)
        assertFalse(audioWithOpt.containsKey("voice"))
        assertTrue(audioWithOpt["optimize_text_preview"]!!.jsonPrimitive.boolean)

        val settingFallbackPrompt = TTSProviderSetting.MiMo(
            model = "mimo-v2.5-tts-voicedesign",
            voiceDesignPrompt = "",
            optimizeTextPreview = false,
        )
        val bodyFallback = buildMiMoTtsRequestBody(settingFallbackPrompt, "测试文本")
        val msgsFallback = bodyFallback["messages"]!!.jsonArray
        assertEquals(2, msgsFallback.size)
        assertEquals("user", msgsFallback[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(msgsFallback[0].jsonObject["content"]!!.jsonPrimitive.content.isNotBlank())
        val audioFallback = bodyFallback["audio"]!!.jsonObject
        assertFalse(audioFallback.containsKey("voice"))
        assertFalse(audioFallback.containsKey("optimize_text_preview"))
    }

    @Test
    fun voiceclone_sends_clone_audio_base64() {
        val sampleB64 = Base64.getEncoder().encodeToString(byteArrayOf(11, 22, 33, 44))
        val setting = TTSProviderSetting.MiMo(
            model = "mimo-v2.5-tts-voiceclone",
            cloneAudioBase64 = sampleB64,
            voiceDesignPrompt = "",
        )
        val body = buildMiMoTtsRequestBody(setting, "克隆测试")
        assertEquals("mimo-v2.5-tts-voiceclone", body["model"]!!.jsonPrimitive.content)
        val msgs = body["messages"]!!.jsonArray
        assertEquals(1, msgs.size)
        assertEquals("assistant", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(sampleB64, body["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun preset_model_carries_director_prompt_and_official_voice() {
        assertEquals(
            listOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean"),
            MIMO_OFFICIAL_VOICES,
        )
        val setting = TTSProviderSetting.MiMo(
            model = "mimo-v2.5-tts",
            voice = "冰糖",
            voiceDesignPrompt = "轻声细语，带一点笑意",
        )
        val body = buildMiMoTtsRequestBody(setting, "(温柔)早安呀[轻笑]")
        val msgs = body["messages"]!!.jsonArray
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("轻声细语，带一点笑意", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("冰糖", body["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun multi_key_failover_switches_to_backup_key_on_429() = runBlocking {
        val pcm = byteArrayOf(5, 6, 7, 8)
        val pcmB64 = Base64.getEncoder().encodeToString(pcm)
        val seenKeys = mutableListOf<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val key = req.header("api-key").orEmpty()
                seenKeys += key
                if (key == "mimo-key-1") {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body("""{"error":"rate_limited"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val sse = """data: {"choices":[{"delta":{"audio":{"data":"$pcmB64"}}}]}""" +
                        "\n\ndata: [DONE]\n\n"
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sse.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
            })
            .build()

        val provider = MiMoTTSProvider(httpClient = client)
        val setting = TTSProviderSetting.MiMo(
            apiKey = "mimo-key-1\nmimo-key-2",
            model = "mimo-v2.5-tts-voicedesign",
            voiceDesignPrompt = "温柔知性女声",
        )

        val chunks = provider.synthesizeSpeechFlow(setting, TTSRequest("测试多Key切换")).toList()
        assertEquals(listOf("mimo-key-1", "mimo-key-2"), seenKeys)
        assertFalse(MiMoTtsKeyTracker.isAvailable("mimo-key-1"))
        assertTrue(MiMoTtsKeyTracker.isAvailable("mimo-key-2"))
        assertEquals(2, chunks.size)
        assertArrayEquals(pcm, chunks[0].data)
        assertTrue(chunks[1].isLast)
    }
}
