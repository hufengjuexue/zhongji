package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

// MiMo 流式音频按文档示例使用 24kHz PCM16LE
private const val MIMO_SAMPLE_RATE = 24000
private const val DEFAULT_MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"
private const val DEFAULT_MIMO_MODEL = "mimo-v2.5-tts"
private const val DEFAULT_MIMO_VOICE_DESIGN_PROMPT =
    "温柔自然的年轻女声，吐字清晰，语速适中，略带亲切感与细腻情绪。"
private val SPLIT_MIMO_KEY_REGEX = "[\\s,;]+".toRegex()
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
// 只关心 delta.audio.data 其余字段忽略
private val mimoJson = Json { ignoreUnknownKeys = true }

val MIMO_OFFICIAL_VOICES: List<String> = listOf(
    "mimo_default",
    "冰糖",
    "茉莉",
    "苏打",
    "白桦",
    "Mia",
    "Chloe",
    "Milo",
    "Dean",
)

fun parseMiMoTtsKeys(raw: String): List<String> = raw
    .split(SPLIT_MIMO_KEY_REGEX)
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()

/**
 * MiMo TTS 轻量级 Key 冷却追踪器：支持 401/403/429/5xx 自动冷却与顺位 Failover。
 */
object MiMoTtsKeyTracker {
    private data class CooldownEntry(
        val untilMs: Long,
        val consecutiveErrors: Int,
    )

    private val cooldowns = ConcurrentHashMap<String, CooldownEntry>()

    fun isAvailable(apiKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entry = cooldowns[apiKey.trim()] ?: return true
        return entry.untilMs <= nowMs
    }

    fun recordSuccess(apiKey: String) {
        cooldowns.remove(apiKey.trim())
    }

    fun recordFailure(
        apiKey: String,
        statusCode: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return
        val prevErrors = (cooldowns[trimmed]?.consecutiveErrors ?: 0) + 1
        val durationMs = when {
            statusCode == 401 || statusCode == 403 -> 24L * 60 * 60_000L
            statusCode == 429 -> min(60_000L * (1 shl (prevErrors - 1).coerceAtMost(3)), 10 * 60_000L)
            statusCode >= 500 || statusCode == 408 -> min(30_000L * (1 shl (prevErrors - 1).coerceAtMost(3)), 5 * 60_000L)
            else -> return
        }
        cooldowns[trimmed] = CooldownEntry(
            untilMs = nowMs + durationMs,
            consecutiveErrors = prevErrors,
        )
    }

    fun resetCooldowns() {
        cooldowns.clear()
    }

    fun clearAllForTesting() {
        cooldowns.clear()
    }
}

@Serializable
private data class MiMoChunk(
    val choices: List<MiMoChoice> = emptyList()
)

@Serializable
private data class MiMoChoice(
    val delta: MiMoDelta? = null
)

@Serializable
private data class MiMoDelta(
    val audio: MiMoAudio? = null
)

@Serializable
private data class MiMoAudio(
    val data: String? = null
)

internal fun decodeMiMoAudioData(data: String): ByteArray? {
    val payload = data.trim()
    // [DONE] 表示流结束 不输出音频
    if (payload == "[DONE]") return null
    // 非 [DONE] 的 data 视为 JSON 片段 解析失败直接上抛
    val chunk = mimoJson.decodeFromString<MiMoChunk>(payload)
    val encoded = chunk.choices.firstOrNull()?.delta?.audio?.data ?: return null
    // 空字符串视为无音频片段
    if (encoded.isBlank()) return null
    return Base64.getDecoder().decode(encoded)
}

internal class MiMoSseProcessor(
    private val model: String,
    private val voice: String
) {
    private var hasAudio = false
    // metadata 只构造一次 贯穿整个流
    private val metadata = mapOf(
        "provider" to "mimo",
        "model" to model,
        "voice" to voice
    )

    fun process(event: SseEvent): AudioChunk? {
        return when (event) {
            is SseEvent.Open -> null
            is SseEvent.Event -> {
                // 只处理包含 audio.data 的增量事件 其他事件忽略
                val pcmData = decodeMiMoAudioData(event.data) ?: return null
                hasAudio = true
                AudioChunk(
                    data = pcmData,
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    metadata = metadata
                )
            }

            is SseEvent.Closed -> {
                // 如果整段流没有任何音频片段 直接报错
                if (!hasAudio) {
                    throw IllegalStateException("MiMo TTS returned no audio chunks")
                }
                // 流关闭时补一个终结 chunk 便于播放器收尾
                AudioChunk(
                    data = byteArrayOf(),
                    format = AudioFormat.PCM,
                    sampleRate = MIMO_SAMPLE_RATE,
                    isLast = true,
                    metadata = metadata
                )
            }

            is SseEvent.Failure -> {
                val statusCode = event.response?.code
                if (statusCode != null) {
                    throw TTSProviderException(
                        message = "MiMo TTS streaming failed: HTTP $statusCode",
                        statusCode = statusCode,
                        cause = event.throwable
                    )
                }
                throw event.throwable ?: Exception("MiMo TTS streaming failed")
            }
        }
    }
}

internal fun buildMiMoTtsRequestBody(
    setting: TTSProviderSetting.MiMo,
    text: String,
): JsonObject {
    val model = setting.model.trim().ifBlank { DEFAULT_MIMO_MODEL }
    val isVoiceDesign = model.equals("mimo-v2.5-tts-voicedesign", ignoreCase = true)
    val isVoiceClone = model.equals("mimo-v2.5-tts-voiceclone", ignoreCase = true)
    val userPrompt = setting.voiceDesignPrompt.trim().let {
        if (isVoiceDesign && it.isBlank()) DEFAULT_MIMO_VOICE_DESIGN_PROMPT else it
    }

    return buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            if (userPrompt.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }
            add(buildJsonObject {
                put("role", "assistant")
                put("content", text)
            })
        })
        put("audio", buildJsonObject {
            put("format", "pcm16")
            if (!isVoiceDesign) {
                val voiceValue = if (isVoiceClone) {
                    setting.cloneAudioBase64.trim().ifBlank { setting.voice.trim() }
                } else {
                    setting.voice.trim().ifBlank { "mimo_default" }
                }
                if (voiceValue.isNotBlank()) {
                    put("voice", voiceValue)
                }
            }
            if (isVoiceDesign && setting.optimizeTextPreview) {
                put("optimize_text_preview", true)
            }
        })
        put("stream", true)
    }
}

class MiMoTTSProvider(
    private val httpClient: OkHttpClient = defaultMiMoHttpClient,
) : TTSProvider<TTSProviderSetting.MiMo> {

    // MiMo 支持在朗读文本中嵌入风格/音频标签控制语气与情感
    // 官方文档: https://xiaomimimo.com (音频标签控制)
    override val promptGuidance: String = """
        The active text-to-speech engine (MiMo-V2.5-TTS) supports emotion, singing, and style control via embedded tags.
        When you call the text_to_speech tool, you MAY enrich the "text" argument with these tags to make the speech more expressive.
        Put tags ONLY inside the tool's text argument — never in your visible reply to the user.

        Two kinds of tags:
        1. Overall style tag — place ONE at the very beginning of the text: (style) . Combine multiple styles with spaces inside the same brackets, e.g. (开心 磁性) or (温柔) . Brackets may be () , （） or [] .
           Common styles: 开心/悲伤/愤怒/恐惧/惊讶/兴奋/委屈/平静/冷漠/怅然/欣慰/无奈/释然/温柔/高冷/活泼/严肃/慵懒/俏皮/深沉/磁性/醇厚/清亮/空灵/甜美/沙哑/御姐音/正太音/大叔音/台湾腔/东北话/四川话/河南话/粤语 . Custom styles are also allowed.
           For singing, start with (唱歌) or [唱歌] followed by lyrics (Chinese lyrics work best).
        2. Inline audio tags — insert [tag] anywhere to fine-tune delivery, e.g. [吸气] [深呼吸] [叹气] [笑] [轻笑] [大笑] [冷笑] [抽泣] [哽咽] [颤抖] [气声] [撒娇] [疲惫] [震惊] [唱歌] .

        IMPORTANT constraints (required by this app's text pipeline):
        - Use (风格) and [tag] square brackets — do NOT use <tag> angle brackets!
        - Do NOT put any punctuation (，。！？、：；…) INSIDE a tag's brackets. Separate multiple styles with spaces only, e.g. write (紧张 深呼吸) NOT (紧张，深呼吸).
        - Keep inline audio tags standalone like [叹气] or [轻笑]; do not immediately follow a [tag] with a (…) group.
        - Do not use markdown emphasis (*, _) — it will be stripped.
        - Use tags naturally and sparingly; don't over-annotate.

        Example text argument: (温柔 磁性)夜已经深了[叹气]城市还在呼吸[轻笑]我是今晚陪你的人。
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = synthesizeSpeechFlow(providerSetting, request)

    internal fun synthesizeSpeechFlow(
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val keys = parseMiMoTtsKeys(providerSetting.apiKey)
        if (keys.isEmpty()) {
            throw TTSProviderException("请先配置 MiMo TTS API Key", statusCode = 401)
        }
        val baseUrl = providerSetting.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_MIMO_BASE_URL }
        val model = providerSetting.model.trim().ifBlank { DEFAULT_MIMO_MODEL }
        val metadataVoice = when {
            model.equals("mimo-v2.5-tts-voicedesign", ignoreCase = true) -> "voicedesign"
            model.equals("mimo-v2.5-tts-voiceclone", ignoreCase = true) -> "voiceclone"
            else -> providerSetting.voice.trim().ifBlank { "mimo_default" }
        }
        val requestBodyJson = buildMiMoTtsRequestBody(providerSetting, request.text).toString()

        val candidates = keys.filter { MiMoTtsKeyTracker.isAvailable(it) }.ifEmpty {
            listOf(keys.first())
        }

        var lastError: Throwable? = null
        var emittedAudio = false

        for (apiKey in candidates) {
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val processor = MiMoSseProcessor(
                model = model,
                voice = metadataVoice,
            )

            try {
                httpClient.sseFlow(httpRequest).collect { event ->
                    processor.process(event)?.let { chunk ->
                        if (chunk.data.isNotEmpty()) {
                            emittedAudio = true
                        }
                        emit(chunk)
                    }
                }
                MiMoTtsKeyTracker.recordSuccess(apiKey)
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: TTSProviderException) {
                lastError = e
                e.statusCode?.let { code ->
                    MiMoTtsKeyTracker.recordFailure(apiKey, code)
                }
                if (emittedAudio) throw e
                val code = e.statusCode
                if (code != null && code !in setOf(401, 403, 404, 408, 429) && code < 500) {
                    throw e
                }
            } catch (e: Exception) {
                lastError = e
                if (emittedAudio) throw e
            }
        }

        when (val err = lastError) {
            is TTSProviderException -> throw err
            else -> throw TTSProviderException(
                message = err?.message ?: "MiMo TTS 全部 Key 均已失败",
                cause = err,
            )
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val defaultMiMoHttpClient = OkHttpClient.Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
