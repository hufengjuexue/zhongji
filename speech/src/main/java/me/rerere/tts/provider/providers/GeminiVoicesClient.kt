package me.rerere.tts.provider.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.provider.TTSProviderException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

private val SPLIT_TTS_KEY_REGEX = "[\\s,;]+".toRegex()
private val RETRY_DELAY_TTS_REGEX = "\"retryDelay\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)s\"".toRegex()
private val PACIFIC_ZONE = ZoneId.of("America/Los_Angeles")
private val VOICE_JSON = Json { ignoreUnknownKeys = true }
private val VOICE_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

val GEMINI_TTS_MODEL_LADDER = listOf(
    "gemini-3.8-flash-tts",
    "gemini-3.8-flash-lite-tts",
    "gemini-3.1-flash-tts-preview",
    "gemini-2.5-flash-preview-tts",
)

data class GeminiFeaturedVoice(
    val id: String,
    val traitZh: String,
    val gender: String,
)

/**
 * Gemini TTS 30 个官方精选 Studio 预设音色（4 代模型通用）。
 */
val GEMINI_FEATURED_VOICES: List<GeminiFeaturedVoice> = listOf(
    GeminiFeaturedVoice("Kore", "坚定沉稳 · 清晰中性女声", "female"),
    GeminiFeaturedVoice("Aoede", "轻快清透 · 自然松弛", "female"),
    GeminiFeaturedVoice("Leda", "年轻元气 · 少女感", "female"),
    GeminiFeaturedVoice("Achernar", "柔和轻缓 · 温柔亲切", "female"),
    GeminiFeaturedVoice("Vindemiatrix", "温柔细腻 · 柔和抒情", "female"),
    GeminiFeaturedVoice("Sulafat", "温暖亲切 · 治愈陪伴", "female"),
    GeminiFeaturedVoice("Despina", "顺滑柔和 · 优雅声线", "female"),
    GeminiFeaturedVoice("Callirrhoe", "随和自然 · 日常对话", "female"),
    GeminiFeaturedVoice("Autonoe", "明亮清脆 · 活泼朝气", "female"),
    GeminiFeaturedVoice("Laomedeia", "明朗向上 · 轻快节奏", "female"),
    GeminiFeaturedVoice("Erinome", "清澈干净 · 字正腔圆", "female"),
    GeminiFeaturedVoice("Pulcherrima", "利落直率 · 清晰有力", "female"),
    GeminiFeaturedVoice("Zephyr", "明亮轻盈 · 清新声线", "female"),
    GeminiFeaturedVoice("Gacrux", "成熟稳重 · 叙事质感", "female"),
    GeminiFeaturedVoice("Puck", "活泼俏皮 · 灵动少年感", "male"),
    GeminiFeaturedVoice("Charon", "沉稳叙事 · 低沉磁性", "male"),
    GeminiFeaturedVoice("Fenrir", "饱满张力 · 富有情绪", "male"),
    GeminiFeaturedVoice("Orus", "笃定厚实 · 沉着声线", "male"),
    GeminiFeaturedVoice("Enceladus", "带气声感 · 贴耳低语", "male"),
    GeminiFeaturedVoice("Iapetus", "清晰干净 · 稳重男声", "male"),
    GeminiFeaturedVoice("Umbriel", "松弛自然 · 闲适声线", "male"),
    GeminiFeaturedVoice("Algieba", "顺滑磁性 · 温柔男声", "male"),
    GeminiFeaturedVoice("Algenib", "低沉微沙 · 质感颗粒", "male"),
    GeminiFeaturedVoice("Rasalgethi", "条理清晰 · 旁白解说", "male"),
    GeminiFeaturedVoice("Alnilam", "坚定有力 · 稳健声线", "male"),
    GeminiFeaturedVoice("Schedar", "平稳均匀 · 耐听叙事", "male"),
    GeminiFeaturedVoice("Achird", "友好随和 · 邻家质感", "male"),
    GeminiFeaturedVoice("Zubenelgenubi", "随性自然 · 口语松弛", "male"),
    GeminiFeaturedVoice("Sadachbia", "鲜活生动 · 富于表现", "male"),
    GeminiFeaturedVoice("Sadaltager", "知性沉静 · 学者感", "male"),
)

fun parseGeminiTtsKeys(raw: String): List<String> = raw
    .split(SPLIT_TTS_KEY_REGEX)
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()

fun maskGeminiTtsKey(key: String): String = when {
    key.length > 14 -> "${key.take(8)}...${key.takeLast(4)}"
    key.length > 6 -> "${key.take(4)}...${key.takeLast(2)}"
    key.isNotBlank() -> "***"
    else -> ""
}

fun normalizeGeminiTtsBaseUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return if (trimmed.isBlank()) "https://generativelanguage.googleapis.com/v1beta" else trimmed
}

internal fun msUntilNextPacificMidnightForTts(nowMs: Long = System.currentTimeMillis()): Long {
    val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), PACIFIC_ZONE)
    val nextMidnight = nowZdt.toLocalDate().plusDays(1).atStartOfDay(PACIFIC_ZONE)
    return (nextMidnight.toInstant().toEpochMilli() - nowMs).coerceAtLeast(60_000L)
}

private fun currentPacificDateKeyForTts(nowMs: Long = System.currentTimeMillis()): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), PACIFIC_ZONE).toLocalDate().toString()

internal fun isDailyQuotaExhaustedForTts(statusCode: Int, body: String?): Boolean {
    if (statusCode != 429 || body.isNullOrBlank()) return false
    val lower = body.lowercase()
    return "perday" in lower ||
        "per day" in lower ||
        "requests per day" in lower ||
        "daily" in lower ||
        "rpd" in lower ||
        "limit: 0" in lower
}

internal fun extractRetryDelayMsForTts(body: String?): Long? {
    if (body.isNullOrBlank()) return null
    val match = RETRY_DELAY_TTS_REGEX.find(body) ?: return null
    val seconds = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
    return (seconds * 1000).toLong().coerceIn(5_000L, 10 * 60_000L)
}

/**
 * Gemini TTS 轻量级 `(key, model)` 冷却与每日使用计数器。
 * - 默认严格锁定靠前的 Key，仅在 429 / 401 / 5xx 时切换到下一 Key 或下一代模型；
 * - 每日 0 点（太平洋时间）自动清零计数。
 */
object GeminiTtsPoolTracker {
    private data class CooldownEntry(
        val untilMs: Long,
        val isRpd: Boolean,
        val consecutiveErrors: Int,
    )

    private val modelCooldowns = ConcurrentHashMap<String, CooldownEntry>()
    private val authCooldowns = ConcurrentHashMap<String, Long>()
    private val dailyCounts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var activeDayKey: String = currentPacificDateKeyForTts()

    private fun ensureDayFresh(nowMs: Long = System.currentTimeMillis()) {
        val today = currentPacificDateKeyForTts(nowMs)
        if (today != activeDayKey) {
            synchronized(this) {
                if (today != activeDayKey) {
                    dailyCounts.clear()
                    modelCooldowns.entries.removeIf { it.value.isRpd }
                    activeDayKey = today
                }
            }
        }
    }

    private fun pairKey(apiKey: String, modelId: String): String =
        "${apiKey.trim()}|${modelId.trim().removePrefix("models/").lowercase()}"

    fun isAvailable(apiKey: String, modelId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        ensureDayFresh(nowMs)
        val authUntil = authCooldowns[apiKey.trim()]
        if (authUntil != null && authUntil > nowMs) return false
        val entry = modelCooldowns[pairKey(apiKey, modelId)] ?: return true
        return entry.untilMs <= nowMs
    }

    fun recordSuccess(apiKey: String, modelId: String, nowMs: Long = System.currentTimeMillis()) {
        ensureDayFresh(nowMs)
        val pk = pairKey(apiKey, modelId)
        modelCooldowns.remove(pk)
        authCooldowns.remove(apiKey.trim())
        dailyCounts.merge(pk, 1, Int::plus)
    }

    fun recordFailure(
        apiKey: String,
        modelId: String,
        statusCode: Int,
        body: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        ensureDayFresh(nowMs)
        val trimmedKey = apiKey.trim()
        if (statusCode == 401 || statusCode == 403) {
            authCooldowns[trimmedKey] = nowMs + 24L * 60 * 60_000L
            return
        }
        val pk = pairKey(trimmedKey, modelId)
        val prevErrors = (modelCooldowns[pk]?.consecutiveErrors ?: 0) + 1
        val isRpd = isDailyQuotaExhaustedForTts(statusCode, body)
        val durationMs = when {
            isRpd -> msUntilNextPacificMidnightForTts(nowMs)
            statusCode == 429 -> extractRetryDelayMsForTts(body) ?: min(60_000L * (1 shl (prevErrors - 1).coerceAtMost(3)), 10 * 60_000L)
            statusCode >= 500 || statusCode == 408 -> min(30_000L * (1 shl (prevErrors - 1).coerceAtMost(3)), 5 * 60_000L)
            else -> return
        }
        modelCooldowns[pk] = CooldownEntry(
            untilMs = nowMs + durationMs,
            isRpd = isRpd,
            consecutiveErrors = prevErrors,
        )
    }

    fun getDailyUsed(apiKey: String, modelId: String, nowMs: Long = System.currentTimeMillis()): Int {
        ensureDayFresh(nowMs)
        return dailyCounts[pairKey(apiKey, modelId)] ?: 0
    }

    fun summarizePool(keysRaw: String, nowMs: Long = System.currentTimeMillis()): Map<String, Pair<Int, Int>> {
        ensureDayFresh(nowMs)
        val keys = parseGeminiTtsKeys(keysRaw)
        return GEMINI_TTS_MODEL_LADDER.associateWith { modelId ->
            val used = keys.sumOf { k -> dailyCounts[pairKey(k, modelId)] ?: 0 }
            val cap = keys.size * 10
            used to cap
        }
    }

    fun resetCooldowns() {
        modelCooldowns.clear()
        authCooldowns.clear()
    }

    fun clearAllForTesting() {
        modelCooldowns.clear()
        authCooldowns.clear()
        dailyCounts.clear()
        activeDayKey = currentPacificDateKeyForTts()
    }
}

@Serializable
data class GeminiVoiceEntry(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val type: String = "",
    val gender: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    val accent: String? = null,
    val pitch: String? = null,
    val persona: String? = null,
    val description: String? = null,
    val model: String? = null,
    @SerialName("expire_time") val expireTime: String? = null,
    val prompted: GeminiPromptedPayload? = null,
)

@Serializable
data class GeminiPromptedPayload(
    val input: String? = null,
)

@Serializable
private data class GeminiSampleAudioPayload(
    val data: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
)

@Serializable
private data class GeminiCreateVoiceResponse(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val type: String = "",
    @SerialName("sample_audio") val sampleAudio: GeminiSampleAudioPayload? = null,
)

@Serializable
private data class GeminiListVoicesResponse(
    val voices: List<GeminiVoiceEntry> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

data class GeminiCreatedVoice(
    val id: String,
    val displayName: String,
    val sampleWavBytes: ByteArray,
    val ownerKeyHint: String,
)

data class GeminiListVoicesResult(
    val voices: List<GeminiVoiceEntry>,
    val nextPageToken: String?,
)

class GeminiVoicesClient(
    private val httpClient: OkHttpClient = defaultVoiceHttpClient,
) {
    fun createPromptedVoice(
        baseUrl: String,
        apiKey: String,
        displayName: String,
        gender: String? = null,
        languageCode: String? = null,
        prompt: String,
        model: String = "gemini-3.8-flash-tts",
    ): GeminiCreatedVoice {
        val normalizedBase = normalizeGeminiTtsBaseUrl(baseUrl)
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "请先填写 Gemini API Key" }
        require(prompt.isNotBlank()) { "音色描述不能为空" }

        val voiceObj = buildJsonObject {
            put("model", model.ifBlank { "gemini-3.8-flash-tts" })
            put("type", "prompted")
            if (displayName.isNotBlank()) put("display_name", displayName.trim())
            if (!gender.isNullOrBlank()) put("gender", gender.trim().lowercase())
            if (!languageCode.isNullOrBlank()) put("language_code", languageCode.trim())
            put(
                "prompted",
                buildJsonObject {
                    put("input", prompt.trim())
                },
            )
        }
        val bodyJson = buildJsonObject {
            put("store", true)
            put("voice", voiceObj)
        }.toString()

        val request = Request.Builder()
            .url("$normalizedBase/voices")
            .addHeader("x-goog-api-key", cleanKey)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(VOICE_JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                throw TTSProviderException(
                    message = "Voice Design 失败 (${response.code}): ${raw.take(280)}",
                    statusCode = response.code,
                )
            }
            val parsed = VOICE_JSON.decodeFromString<GeminiCreateVoiceResponse>(raw)
            val audioB64 = parsed.sampleAudio?.data.orEmpty()
            val wavBytes = if (audioB64.isNotBlank()) Base64.getDecoder().decode(audioB64) else byteArrayOf()
            if (parsed.id.isBlank()) {
                throw IllegalStateException("CreateVoice 未返回有效的 voice_id")
            }
            return GeminiCreatedVoice(
                id = parsed.id,
                displayName = parsed.displayName.ifBlank { displayName.trim().ifBlank { parsed.id } },
                sampleWavBytes = wavBytes,
                ownerKeyHint = maskGeminiTtsKey(cleanKey),
            )
        }
    }

    fun listVoices(
        baseUrl: String,
        apiKey: String,
        type: String? = null,
        gender: String? = null,
        languageCode: String? = null,
        search: String? = null,
        pageSize: Int = 100,
        pageToken: String? = null,
    ): GeminiListVoicesResult {
        val normalizedBase = normalizeGeminiTtsBaseUrl(baseUrl)
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "请先填写 Gemini API Key" }

        val params = buildList {
            if (!type.isNullOrBlank()) add("type=${enc(type)}")
            if (!gender.isNullOrBlank()) add("gender=${enc(gender)}")
            if (!languageCode.isNullOrBlank()) add("language_code=${enc(languageCode)}")
            if (!search.isNullOrBlank()) add("search=${enc(search)}")
            add("page_size=${pageSize.coerceIn(1, 500)}")
            if (!pageToken.isNullOrBlank()) add("page_token=${enc(pageToken)}")
        }.joinToString("&")

        val request = Request.Builder()
            .url("$normalizedBase/voices?$params")
            .addHeader("x-goog-api-key", cleanKey)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                throw TTSProviderException(
                    message = "拉取音色库失败 (${response.code}): ${raw.take(280)}",
                    statusCode = response.code,
                )
            }
            val parsed = VOICE_JSON.decodeFromString<GeminiListVoicesResponse>(raw)
            return GeminiListVoicesResult(
                voices = parsed.voices,
                nextPageToken = parsed.nextPageToken?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun deleteVoice(
        baseUrl: String,
        apiKey: String,
        voiceId: String,
    ) {
        val normalizedBase = normalizeGeminiTtsBaseUrl(baseUrl)
        val cleanKey = apiKey.trim()
        val cleanId = voiceId.trim().removePrefix("voices/")
        require(cleanKey.isNotBlank()) { "请先填写 Gemini API Key" }
        require(cleanId.isNotBlank()) { "voiceId 不能为空" }

        val request = Request.Builder()
            .url("$normalizedBase/voices/$cleanId")
            .addHeader("x-goog-api-key", cleanKey)
            .delete()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body.string()
                throw TTSProviderException(
                    message = "删除音色失败 (${response.code}): ${raw.take(280)}",
                    statusCode = response.code,
                )
            }
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.toString())

    companion object {
        private val defaultVoiceHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
