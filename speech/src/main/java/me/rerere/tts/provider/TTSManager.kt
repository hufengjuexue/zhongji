package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.providers.ElevenLabsTTSProvider
import me.rerere.tts.provider.providers.FishAudioTTSProvider
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.GroqTTSProvider
import me.rerere.tts.provider.providers.MiMoTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.QwenTTSProvider
import me.rerere.tts.provider.providers.StepTTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider
import me.rerere.tts.provider.providers.VolcengineTTSProvider
import me.rerere.tts.provider.providers.XAITTSProvider
import me.rerere.tts.provider.providers.parseGeminiTtsKeys
import me.rerere.tts.provider.providers.parseMiMoTtsKeys

internal fun isAvailableRelayProvider(setting: TTSProviderSetting): Boolean = when (setting) {
    is TTSProviderSetting.Gemini -> parseGeminiTtsKeys(setting.apiKey).isNotEmpty()
    is TTSProviderSetting.MiMo -> parseMiMoTtsKeys(setting.apiKey).isNotEmpty()
    is TTSProviderSetting.OpenAI -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.MiniMax -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.Qwen -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.Groq -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.XAI -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.ElevenLabs -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.Step -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.FishAudio -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.Volcengine -> setting.apiKey.isNotBlank()
    is TTSProviderSetting.SystemTTS -> false
}

internal fun isModernVoiceDesignProvider(setting: TTSProviderSetting): Boolean =
    setting is TTSProviderSetting.MiMo || setting is TTSProviderSetting.Gemini

internal fun resolveVoiceDesignRelayCandidates(
    primary: TTSProviderSetting,
    fallbackProviders: List<TTSProviderSetting>,
): List<TTSProviderSetting> {
    val uniqueCandidates = fallbackProviders
        .filter { it.id != primary.id }
        .distinctBy { it.id }
        .filter { isAvailableRelayProvider(it) }

    val modernVoiceDesign = uniqueCandidates.filter { isModernVoiceDesignProvider(it) }
    val otherCloud = uniqueCandidates.filterNot { isModernVoiceDesignProvider(it) }
    return modernVoiceDesign + otherCloud
}

class TTSManager internal constructor(
    private val context: Context?,
    private val geminiProvider: GeminiTTSProvider = GeminiTTSProvider(),
    private val miMoProvider: MiMoTTSProvider = MiMoTTSProvider(),
) {
    constructor(context: Context) : this(
        context = context,
        geminiProvider = GeminiTTSProvider(),
        miMoProvider = MiMoTTSProvider(),
    )

    private val openAIProvider = OpenAITTSProvider()
    private val systemProvider = SystemTTSProvider()
    private val miniMaxProvider = MiniMaxTTSProvider()
    private val qwenProvider = QwenTTSProvider()
    private val groqProvider = GroqTTSProvider()
    private val xaiProvider = XAITTSProvider()
    private val stepProvider = StepTTSProvider()
    private val elevenLabsProvider = ElevenLabsTTSProvider()
    private val fishAudioProvider = FishAudioTTSProvider()
    private val volcengineProvider = VolcengineTTSProvider()

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        request: TTSRequest
    ): Flow<AudioChunk> = invokeSingleProvider(providerSetting, request)

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        fallbackProviders: List<TTSProviderSetting> = emptyList(),
        request: TTSRequest,
    ): Flow<AudioChunk> {
        val relayCandidates = resolveVoiceDesignRelayCandidates(providerSetting, fallbackProviders)
        if (relayCandidates.isEmpty()) {
            return invokeSingleProvider(providerSetting, request)
        }
        val attempts = listOf(providerSetting) + relayCandidates
        return flow {
            var lastError: Throwable? = null
            for (candidate in attempts) {
                var emittedAudio = false
                try {
                    invokeSingleProvider(candidate, request).collect { chunk ->
                        if (chunk.data.isNotEmpty()) {
                            emittedAudio = true
                        }
                        emit(chunk)
                    }
                    return@flow
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (emittedAudio) {
                        throw e
                    }
                }
            }
            if (lastError != null) {
                throw lastError
            }
        }
    }

    private fun invokeSingleProvider(
        providerSetting: TTSProviderSetting,
        request: TTSRequest,
    ): Flow<AudioChunk> {
        return when (providerSetting) {
            is TTSProviderSetting.Gemini -> geminiProvider.synthesizeSpeechFlow(providerSetting, request)
            is TTSProviderSetting.MiMo -> miMoProvider.synthesizeSpeechFlow(providerSetting, request)
            is TTSProviderSetting.OpenAI -> openAIProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.SystemTTS -> systemProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.MiniMax -> miniMaxProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.Qwen -> qwenProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.Groq -> groqProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.XAI -> xaiProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.FishAudio -> fishAudioProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.Step -> stepProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
            is TTSProviderSetting.Volcengine -> volcengineProvider.generateSpeech(requireContext(providerSetting), providerSetting, request)
        }
    }

    private fun requireContext(providerSetting: TTSProviderSetting): Context =
        requireNotNull(context) {
            "Android Context is required for ${providerSetting::class.simpleName}"
        }

    /**
     * 返回该 provider 硬编码的语气标记引导提示词（默认空）。
     * 供 text_to_speech 工具注入 system prompt 使用。
     */
    fun getPromptGuidance(providerSetting: TTSProviderSetting): String {
        return when (providerSetting) {
            is TTSProviderSetting.OpenAI -> openAIProvider.promptGuidance
            is TTSProviderSetting.Gemini -> geminiProvider.promptGuidance
            is TTSProviderSetting.SystemTTS -> systemProvider.promptGuidance
            is TTSProviderSetting.MiniMax -> miniMaxProvider.promptGuidance
            is TTSProviderSetting.Qwen -> qwenProvider.promptGuidance
            is TTSProviderSetting.Groq -> groqProvider.promptGuidance
            is TTSProviderSetting.XAI -> xaiProvider.promptGuidance
            is TTSProviderSetting.MiMo -> miMoProvider.promptGuidance
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.promptGuidance
            is TTSProviderSetting.FishAudio -> fishAudioProvider.promptGuidance
            is TTSProviderSetting.Step -> stepProvider.promptGuidance
            is TTSProviderSetting.Volcengine -> volcengineProvider.promptGuidance
        }
    }
}
