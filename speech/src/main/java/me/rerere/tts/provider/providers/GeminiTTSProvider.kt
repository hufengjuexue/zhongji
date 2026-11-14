package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.concurrent.TimeUnit

private const val GEMINI_SAMPLE_RATE = 24000
private val GEMINI_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val geminiTtsJson = Json { ignoreUnknownKeys = true }

private val LEADING_STYLE_TAG_REGEX = Regex("^\\s*\\[\\s*style\\s*:\\s*([^\\]]+)\\]\\s*", RegexOption.IGNORE_CASE)

private val KNOWN_VOCAL_TAGS = setOf(
    "argh", "breath", "heavy breath", "exhales", "cackle", "cheer",
    "chuckle", "chuckles", "cough", "cry", "gasp", "giggle", "groan",
    "growl", "grunt", "grr", "hiss", "laugh", "laughter", "moan",
    "pant", "pff", "phew", "scream", "shout", "shriek", "sigh", "sighs",
    "sneeze", "snicker", "snort", "sob", "throat-clearing", "tsk",
    "whimper", "whispers", "whispering", "yawn", "short pause", "long pause",
)

private val SQUARE_VOCAL_TAG_REGEX = Regex("\\[([a-zA-Z][a-zA-Z\\s-]{1,24})\\]")
private val ANGLE_VOCAL_TAG_REGEX = Regex("<([a-zA-Z][a-zA-Z\\s-]{1,24})>")

internal data class PreparedGeminiTtsInput(
    val text: String,
    val style: String,
)

internal class GeminiTtsHttpException(
    val statusCode: Int,
    val statusMessage: String,
    val errorBody: String,
) : Exception("Gemini TTS HTTP $statusCode $statusMessage: ${errorBody.take(240)}")

internal fun normalizeGeminiTtsModelId(model: String): String =
    model.trim().removePrefix("models/").lowercase()

internal fun isInteractionsTtsModel(model: String): Boolean {
    val m = normalizeGeminiTtsModelId(model)
    return "2.5" !in m && "2-5" !in m
}

internal fun supportsVoiceDesignAndStyle(model: String): Boolean {
    val m = normalizeGeminiTtsModelId(model)
    if ("2.5" in m || "2-5" in m) return false
    if ("3.1" in m || "3-1" in m) return false
    return true
}

internal fun buildGeminiTtsCandidateModels(
    primaryModel: String,
    autoFallback: Boolean,
): List<String> {
    val primary = primaryModel.trim().removePrefix("models/").ifBlank { "gemini-3.8-flash-tts" }
    if (!autoFallback) return listOf(primary)
    val normPrimary = primary.lowercase()
    val idx = GEMINI_TTS_MODEL_LADDER.indexOfFirst { it.lowercase() == normPrimary }
    return if (idx >= 0) {
        GEMINI_TTS_MODEL_LADDER.subList(idx, GEMINI_TTS_MODEL_LADDER.size)
    } else {
        (listOf(primary) + GEMINI_TTS_MODEL_LADDER).distinctBy { it.lowercase() }
    }
}

internal fun resolveGeminiVoiceForAttempt(
    setting: TTSProviderSetting.Gemini,
    apiKey: String,
    targetModel: String,
    forceFallback: Boolean = false,
): String {
    val primary = setting.voiceName.trim().ifBlank { "Kore" }
    val fallback = setting.fallbackVoiceName.trim().ifBlank { "Kore" }
    if (forceFallback) return fallback

    val isCustom = primary.startsWith("voice_") || primary.startsWith("voicekey_")
    if (!isCustom) return primary
    if (!supportsVoiceDesignAndStyle(targetModel)) return fallback

    if (primary.startsWith("voice_") &&
        setting.voiceOwnerKeyHint.isNotBlank() &&
        maskGeminiTtsKey(apiKey) != setting.voiceOwnerKeyHint
    ) {
        return fallback
    }
    return primary
}

internal fun prepareGeminiTtsText(
    rawText: String,
    settingStyle: String,
    targetModel: String,
): PreparedGeminiTtsInput {
    var working = rawText.trim()
    var inlineStyle = ""
    val styleMatch = LEADING_STYLE_TAG_REGEX.find(working)
    if (styleMatch != null) {
        inlineStyle = styleMatch.groupValues[1].trim()
        working = working.removeRange(styleMatch.range).trimStart()
    }

    val mergedStyle = listOf(settingStyle.trim(), inlineStyle)
        .filter { it.isNotBlank() }
        .joinToString(", ")

    return if (isInteractionsTtsModel(targetModel)) {
        val converted = SQUARE_VOCAL_TAG_REGEX.replace(working) { match ->
            val tag = match.groupValues[1].trim().lowercase()
            if (tag in KNOWN_VOCAL_TAGS) "<$tag>" else match.value
        }
        PreparedGeminiTtsInput(text = converted, style = mergedStyle)
    } else {
        var stripped = SQUARE_VOCAL_TAG_REGEX.replace(working) { match ->
            val tag = match.groupValues[1].trim().lowercase()
            if (tag in KNOWN_VOCAL_TAGS) " " else match.value
        }
        stripped = ANGLE_VOCAL_TAG_REGEX.replace(stripped) { match ->
            val tag = match.groupValues[1].trim().lowercase()
            if (tag in KNOWN_VOCAL_TAGS) " " else match.value
        }
        PreparedGeminiTtsInput(
            text = stripped.replace(Regex("\\s{2,}"), " ").trim(),
            style = "",
        )
    }
}

internal fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
    if (bytes.size <= 44) return bytes
    val isRiff = bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte()
    val isWave = bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'A'.code.toByte() &&
        bytes[10] == 'V'.code.toByte() &&
        bytes[11] == 'E'.code.toByte()
    if (!isRiff || !isWave) return bytes

    var offset = 12
    while (offset + 8 <= bytes.size) {
        val isData = bytes[offset] == 'd'.code.toByte() &&
            bytes[offset + 1] == 'a'.code.toByte() &&
            bytes[offset + 2] == 't'.code.toByte() &&
            bytes[offset + 3] == 'a'.code.toByte()
        val chunkSize = ((bytes[offset + 4].toInt() and 0xFF)) or
            ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 7].toInt() and 0xFF) shl 24)
        offset += 8
        if (isData) {
            val end = if (chunkSize > 0 && offset + chunkSize <= bytes.size) offset + chunkSize else bytes.size
            return bytes.copyOfRange(offset, end)
        }
        if (chunkSize < 0) break
        offset += chunkSize + (chunkSize and 1)
    }
    return bytes.copyOfRange(44, bytes.size)
}

internal fun buildInteractionsTtsRequestBody(
    model: String,
    prepared: PreparedGeminiTtsInput,
    voice: String,
): JsonObject = buildJsonObject {
    put("model", model)
    put(
        "input",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "user_input")
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", prepared.text)
                                    if (supportsVoiceDesignAndStyle(model) && prepared.style.isNotBlank()) {
                                        put(
                                            "annotations",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "speech_metadata")
                                                        put("style", prepared.style)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        },
    )
    put(
        "response_format",
        buildJsonObject {
            put("type", "audio")
            put("mime_type", "audio/l16")
        },
    )
    put(
        "generation_config",
        buildJsonObject {
            put(
                "speech_config",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("voice", voice)
                        },
                    )
                },
            )
        },
    )
    put("stream", true)
}

internal fun buildLegacyGenerateContentTtsRequestBody(
    model: String,
    text: String,
    voice: String,
): JsonObject = buildJsonObject {
    put(
        "contents",
        buildJsonArray {
            add(
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("text", text)
                                },
                            )
                        },
                    )
                },
            )
        },
    )
    put(
        "generationConfig",
        buildJsonArrayOrObject = buildJsonObject {
            put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
            put(
                "speechConfig",
                buildJsonObject {
                    put(
                        "voiceConfig",
                        buildJsonObject {
                            put(
                                "prebuiltVoiceConfig",
                                buildJsonObject {
                                    put("voiceName", voice)
                                },
                            )
                        },
                    )
                },
            )
        },
    )
    put("model", model)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(
    key: String,
    buildJsonArrayOrObject: JsonObject,
) {
    put(key, buildJsonArrayOrObject as kotlinx.serialization.json.JsonElement)
}

internal fun extractAudioBytesFromInteractionsEvent(rawJson: String): List<ByteArray> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank() || trimmed == "[DONE]") return emptyList()
    val root = geminiTtsJson.parseToJsonElement(trimmed).jsonObject
    val results = mutableListOf<ByteArray>()

    // 1. Streaming event: step.delta with delta.type == "audio"
    val eventType = root["event_type"]?.jsonPrimitive?.contentOrNull
        ?: root["type"]?.jsonPrimitive?.contentOrNull
    val delta = root["delta"] as? JsonObject
    if (delta != null && (eventType == null || eventType == "step.delta")) {
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
        val deltaData = delta["data"]?.jsonPrimitive?.contentOrNull
        if (deltaType == "audio" && !deltaData.isNullOrBlank()) {
            results += stripWavHeaderIfPresent(Base64.getDecoder().decode(deltaData))
        }
    }

    // 2. Non-streaming / completed interaction snapshot: steps[].content[].data
    val steps = (root["steps"] as? JsonArray)
        ?: ((root["interaction"] as? JsonObject)?.get("steps") as? JsonArray)
    if (results.isEmpty() && steps != null) {
        for (stepEl in steps) {
            val stepObj = stepEl as? JsonObject ?: continue
            val stepType = stepObj["type"]?.jsonPrimitive?.contentOrNull
            if (stepType != null && stepType != "model_output") continue
            val contentArr = stepObj["content"] as? JsonArray ?: continue
            for (cEl in contentArr) {
                val cObj = cEl as? JsonObject ?: continue
                if (cObj["type"]?.jsonPrimitive?.contentOrNull == "audio") {
                    val dataStr = cObj["data"]?.jsonPrimitive?.contentOrNull
                    if (!dataStr.isNullOrBlank()) {
                        results += stripWavHeaderIfPresent(Base64.getDecoder().decode(dataStr))
                    }
                }
            }
        }
    }

    return results
}

private fun isVoiceNotFoundError(statusCode: Int, errorBody: String): Boolean {
    if (statusCode != 400 && statusCode != 404) return false
    val lower = errorBody.lowercase()
    return "voice" in lower && ("not found" in lower || "invalid" in lower || "permission" in lower || "does not exist" in lower)
}

class GeminiTTSProvider(
    private val httpClient: OkHttpClient = defaultTtsHttpClient,
) : TTSProvider<TTSProviderSetting.Gemini> {

    @Serializable
    private data class LegacyResponse(
        val candidates: List<LegacyCandidate> = emptyList(),
    )

    @Serializable
    private data class LegacyCandidate(
        val content: LegacyContent? = null,
    )

    @Serializable
    private data class LegacyContent(
        val parts: List<LegacyPart> = emptyList(),
    )

    @Serializable
    private data class LegacyPart(
        val inlineData: LegacyInlineData? = null,
    )

    @Serializable
    private data class LegacyInlineData(
        val data: String = "",
        val mimeType: String = "",
    )

    override val promptGuidance: String = """
        The active text-to-speech engine (Gemini 3.8 TTS) supports acting style and non-speech vocal cues.
        When calling the text_to_speech tool, you MAY enrich the "text" argument using safe square-bracket tags (never put them in your visible reply):
        1. Optional leading style cue at the very start: [style: whispered urgently] or [style: warm and gentle]. Keep the style phrase in short English without punctuation.
        2. Inline vocal bursts (use English keywords inside square brackets): [sigh], [chuckle], [laugh], [gasp], [giggle], [whispers], [heavy breath], [breath], [short pause], [long pause].
        Constraints required by the app text pipeline:
        - Use [tag] square brackets (NOT <tag> angle brackets and NOT * / _ markdown).
        - Do not put Chinese/English punctuation inside brackets.
        - Use sparingly and naturally.
        Example: [style: soft and teasing]你终于回来啦[chuckle][short pause]我还以为你忘了呢。
    """.trimIndent()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Gemini,
        request: TTSRequest,
    ): Flow<AudioChunk> = synthesizeSpeechFlow(providerSetting, request)

    internal fun synthesizeSpeechFlow(
        providerSetting: TTSProviderSetting.Gemini,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val keys = parseGeminiTtsKeys(providerSetting.apiKey)
        if (keys.isEmpty()) {
            throw TTSProviderException("请先配置 Gemini TTS API Key", statusCode = 401)
        }
        val baseUrl = normalizeGeminiTtsBaseUrl(providerSetting.baseUrl)
        val models = buildGeminiTtsCandidateModels(providerSetting.model, providerSetting.autoFallbackModels)

        val candidates = buildList {
            for (m in models) {
                for (k in keys) {
                    if (GeminiTtsPoolTracker.isAvailable(k, m)) {
                        add(k to m)
                    }
                }
            }
        }.ifEmpty {
            // 全部在冷却期时仍给主模型 + 首把 Key 一次最后尝试机会
            listOf(keys.first() to models.first())
        }

        var lastError: Throwable? = null
        var emittedAudio = false

        for ((apiKey, modelId) in candidates) {
            val resolvedVoice = resolveGeminiVoiceForAttempt(providerSetting, apiKey, modelId)
            try {
                synthesizeSingleAttempt(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelId = modelId,
                    voice = resolvedVoice,
                    rawText = request.text,
                    settingStyle = providerSetting.stylePrompt,
                    onChunk = { chunk ->
                        emittedAudio = true
                        emit(chunk)
                    },
                )
                GeminiTtsPoolTracker.recordSuccess(apiKey, modelId)
                return@flow
            } catch (e: GeminiTtsHttpException) {
                lastError = e
                // 若遇到跨项目/无效 voice_... 错误且还没吐音频，立即用相似备选音色原地重试一次
                if (!emittedAudio &&
                    resolvedVoice.startsWith("voice_") &&
                    isVoiceNotFoundError(e.statusCode, e.errorBody)
                ) {
                    val fallbackVoice = resolveGeminiVoiceForAttempt(
                        setting = providerSetting,
                        apiKey = apiKey,
                        targetModel = modelId,
                        forceFallback = true,
                    )
                    if (fallbackVoice != resolvedVoice) {
                        try {
                            synthesizeSingleAttempt(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                modelId = modelId,
                                voice = fallbackVoice,
                                rawText = request.text,
                                settingStyle = providerSetting.stylePrompt,
                                onChunk = { chunk ->
                                    emittedAudio = true
                                    emit(chunk)
                                },
                            )
                            GeminiTtsPoolTracker.recordSuccess(apiKey, modelId)
                            return@flow
                        } catch (retryEx: GeminiTtsHttpException) {
                            lastError = retryEx
                            GeminiTtsPoolTracker.recordFailure(apiKey, modelId, retryEx.statusCode, retryEx.errorBody)
                            if (emittedAudio) throw toTtsException(retryEx)
                            continue
                        }
                    }
                }

                GeminiTtsPoolTracker.recordFailure(apiKey, modelId, e.statusCode, e.errorBody)
                if (emittedAudio) throw toTtsException(e)
                if (e.statusCode !in setOf(401, 403, 404, 408, 429) && e.statusCode < 500) {
                    throw toTtsException(e)
                }
            } catch (e: Exception) {
                lastError = e
                if (emittedAudio) throw e
            }
        }

        when (val err = lastError) {
            is GeminiTtsHttpException -> throw toTtsException(err)
            is TTSProviderException -> throw err
            else -> throw TTSProviderException(
                message = err?.message ?: "Gemini TTS 全部 Key 与模型均已耗尽或失败",
                cause = err,
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun synthesizeSingleAttempt(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        voice: String,
        rawText: String,
        settingStyle: String,
        onChunk: suspend (AudioChunk) -> Unit,
    ) {
        val prepared = prepareGeminiTtsText(rawText, settingStyle, modelId)
        val metadata = mapOf(
            "provider" to "gemini",
            "model" to modelId,
            "voice" to voice,
            "sampleRate" to GEMINI_SAMPLE_RATE.toString(),
            "channels" to "1",
            "bitDepth" to "16",
        )

        if (isInteractionsTtsModel(modelId)) {
            val bodyJson = buildInteractionsTtsRequestBody(modelId, prepared, voice).toString()
            val httpRequest = Request.Builder()
                .url("$baseUrl/interactions")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(bodyJson.toRequestBody(GEMINI_JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errText = response.body.string()
                    throw GeminiTtsHttpException(response.code, response.message, errText)
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                var hasAudio = false

                if ("text/event-stream" in contentType) {
                    val dataBuilder = StringBuilder()
                    response.body.charStream().buffered().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith("data:") -> {
                                    if (dataBuilder.isNotEmpty()) dataBuilder.append('\n')
                                    dataBuilder.append(line.removePrefix("data:").trimStart())
                                }
                                line.isBlank() && dataBuilder.isNotEmpty() -> {
                                    val payload = dataBuilder.toString()
                                    dataBuilder.setLength(0)
                                    val chunks = extractAudioBytesFromInteractionsEvent(payload)
                                    for (pcm in chunks) {
                                        if (pcm.isNotEmpty()) {
                                            hasAudio = true
                                            onChunk(
                                                AudioChunk(
                                                    data = pcm,
                                                    format = AudioFormat.PCM,
                                                    sampleRate = GEMINI_SAMPLE_RATE,
                                                    isLast = false,
                                                    metadata = metadata,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (dataBuilder.isNotEmpty()) {
                        val chunks = extractAudioBytesFromInteractionsEvent(dataBuilder.toString())
                        for (pcm in chunks) {
                            if (pcm.isNotEmpty()) {
                                hasAudio = true
                                onChunk(
                                    AudioChunk(
                                        data = pcm,
                                        format = AudioFormat.PCM,
                                        sampleRate = GEMINI_SAMPLE_RATE,
                                        isLast = false,
                                        metadata = metadata,
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    val rawJson = response.body.string()
                    val chunks = extractAudioBytesFromInteractionsEvent(rawJson)
                    for (pcm in chunks) {
                        if (pcm.isNotEmpty()) {
                            hasAudio = true
                            onChunk(
                                AudioChunk(
                                    data = pcm,
                                    format = AudioFormat.PCM,
                                    sampleRate = GEMINI_SAMPLE_RATE,
                                    isLast = false,
                                    metadata = metadata,
                                ),
                            )
                        }
                    }
                }

                if (!hasAudio) {
                    throw IllegalStateException("Gemini Interactions TTS 未返回有效音频数据")
                }
                onChunk(
                    AudioChunk(
                        data = byteArrayOf(),
                        format = AudioFormat.PCM,
                        sampleRate = GEMINI_SAMPLE_RATE,
                        isLast = true,
                        metadata = metadata,
                    ),
                )
            }
        } else {
            val bodyJson = buildLegacyGenerateContentTtsRequestBody(modelId, prepared.text, voice).toString()
            val httpRequest = Request.Builder()
                .url("$baseUrl/models/$modelId:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(GEMINI_JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val rawJson = response.body.string()
                if (!response.isSuccessful) {
                    throw GeminiTtsHttpException(response.code, response.message, rawJson)
                }
                val parsed = geminiTtsJson.decodeFromString<LegacyResponse>(rawJson)
                val b64 = parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData?.data.orEmpty()
                if (b64.isBlank()) {
                    throw IllegalStateException("Gemini 2.5 TTS 未返回音频数据")
                }
                val audioBytes = stripWavHeaderIfPresent(Base64.getDecoder().decode(b64))
                onChunk(
                    AudioChunk(
                        data = audioBytes,
                        format = AudioFormat.PCM,
                        sampleRate = GEMINI_SAMPLE_RATE,
                        isLast = true,
                        metadata = metadata,
                    ),
                )
            }
        }
    }

    private fun toTtsException(e: GeminiTtsHttpException): TTSProviderException = TTSProviderException(
        message = "Gemini TTS 请求失败 (${e.statusCode}): ${e.errorBody.ifBlank { e.statusMessage }.take(240)}",
        statusCode = e.statusCode,
        cause = e,
    )

    companion object {
        private val defaultTtsHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
