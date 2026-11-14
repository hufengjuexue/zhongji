package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.tts.controller.AudioPlayer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.GEMINI_TTS_MODEL_LADDER
import me.rerere.tts.provider.providers.GeminiTtsPoolTracker
import me.rerere.tts.provider.providers.MIMO_OFFICIAL_VOICES
import me.rerere.tts.provider.providers.MiMoTTSProvider
import me.rerere.tts.provider.providers.parseGeminiTtsKeys
import me.rerere.tts.provider.providers.parseMiMoTtsKeys
import me.rerere.ui.components.OutlinedNumberInput
import me.rerere.ui.components.SelectTextField
import java.io.ByteArrayOutputStream
import java.util.Base64

@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "现代 VoiceDesign 自动接力：当列表同时配置 Gemini 3.8 与 MiMo V2.5 TTS 时，3.8 额度用完或限流将自动无感接力另一现代引擎。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_tts_page_provider_type)) },
                supportingContent = { Text(stringResource(R.string.setting_tts_page_provider_type_description)) },
            ) {
                SelectTextField(
                    value = when (setting) {
                        is TTSProviderSetting.OpenAI -> "OpenAI"
                        is TTSProviderSetting.Gemini -> "Gemini"
                        is TTSProviderSetting.SystemTTS -> "System TTS"
                        is TTSProviderSetting.MiniMax -> "MiniMax"
                        is TTSProviderSetting.Qwen -> "Qwen"
                        is TTSProviderSetting.Groq -> "Groq"
                        is TTSProviderSetting.XAI -> "xAI"
                        is TTSProviderSetting.MiMo -> "MiMo"
                        is TTSProviderSetting.Step -> "Step"
                        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                        is TTSProviderSetting.FishAudio -> "Fish Audio"
                        is TTSProviderSetting.Volcengine -> "火山引擎"
                    },
                    options = TTSProviderSetting.Types,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { providerClass ->
                        when (providerClass) {
                            TTSProviderSetting.OpenAI::class -> "OpenAI"
                            TTSProviderSetting.Gemini::class -> "Gemini"
                            TTSProviderSetting.SystemTTS::class -> "System TTS"
                            TTSProviderSetting.MiniMax::class -> "MiniMax"
                            TTSProviderSetting.Qwen::class -> "Qwen"
                            TTSProviderSetting.Groq::class -> "Groq"
                            TTSProviderSetting.XAI::class -> "xAI"
                            TTSProviderSetting.MiMo::class -> "MiMo"
                            TTSProviderSetting.ElevenLabs::class -> "ElevenLabs"
                            TTSProviderSetting.FishAudio::class -> "Fish Audio"
                            TTSProviderSetting.Volcengine::class -> "火山引擎"
                            TTSProviderSetting.Step::class -> "Step"
                            else -> providerClass.simpleName ?: "Unknown"
                        }
                    },
                    onOptionSelected = { providerClass ->
                        val newSetting = when (providerClass) {
                            TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(
                                id = setting.id,
                                name = "OpenAI TTS"
                            )

                            TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(
                                id = setting.id,
                                name = "Gemini TTS"
                            )

                            TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(
                                id = setting.id,
                                name = "System TTS"
                            )

                            TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(
                                id = setting.id,
                                name = "MiniMax TTS"
                            )

                            TTSProviderSetting.Qwen::class -> TTSProviderSetting.Qwen(
                                id = setting.id,
                                name = "Qwen TTS"
                            )

                            TTSProviderSetting.Groq::class -> TTSProviderSetting.Groq(
                                id = setting.id,
                                name = "Groq TTS"
                            )

                            TTSProviderSetting.XAI::class -> TTSProviderSetting.XAI(
                                id = setting.id,
                                name = "xAI TTS"
                            )

                            TTSProviderSetting.MiMo::class -> TTSProviderSetting.MiMo(
                                id = setting.id,
                                name = "MiMo TTS"
                            )

                            TTSProviderSetting.ElevenLabs::class -> TTSProviderSetting.ElevenLabs(
                                id = setting.id,
                                name = "ElevenLabs TTS"
                            )

                            TTSProviderSetting.FishAudio::class -> TTSProviderSetting.FishAudio(
                                id = setting.id,
                                name = "Fish Audio TTS"
                            )

                            TTSProviderSetting.Step::class -> TTSProviderSetting.Step(
                                id = setting.id,
                                name = "Step TTS"
                            )

                            TTSProviderSetting.Volcengine::class -> TTSProviderSetting.Volcengine(id = setting.id)

                            else -> setting
                        }
                        onValueChange(newSetting)
                    }
                )
            }

            // Name
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_tts_page_name)) },
                supportingContent = { Text(stringResource(R.string.setting_tts_page_name_description)) }
            ) {
                OutlinedTextField(
                    value = setting.name,
                    onValueChange = { newName ->
                        onValueChange(setting.copyProvider(name = newName))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.setting_tts_page_name_placeholder)) }
                )
            }
        }

        CardGroup {
            when (setting) {
                is TTSProviderSetting.OpenAI -> openAITTSItems(setting, onValueChange)
                is TTSProviderSetting.Gemini -> geminiTTSItems(setting, onValueChange)
                is TTSProviderSetting.MiniMax -> miniMaxTTSItems(setting, onValueChange)
                is TTSProviderSetting.SystemTTS -> systemTTSItems(setting, onValueChange)
                is TTSProviderSetting.Qwen -> qwenTTSItems(setting, onValueChange)
                is TTSProviderSetting.Groq -> groqTTSItems(setting, onValueChange)
                is TTSProviderSetting.XAI -> xAITTSItems(setting, onValueChange)
                is TTSProviderSetting.MiMo -> miMoTTSItems(setting, onValueChange)
                is TTSProviderSetting.ElevenLabs -> elevenLabsTTSItems(setting, onValueChange)
                is TTSProviderSetting.FishAudio -> fishAudioTTSItems(setting, onValueChange)
                is TTSProviderSetting.Step -> stepTTSItems(setting, onValueChange)
                is TTSProviderSetting.Volcengine -> volcengineTTSItems(setting, onValueChange)
            }
        }
    }
}

private fun CardGroupScope.openAITTSItems(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    // Voice
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private val MIMO_VOICE_DESIGN_INSPIRATIONS = listOf(
    "温柔御姐" to "温柔知性的年轻御姐女声，声线温润带一点磁性，语速从容自然，略带亲昵贴耳感。",
    "清冷电波" to "清冷克制的少女声线，咬字清晰干净，略带空灵与电波感，语调平稳疏离却有细腻情绪。",
    "元气少女" to "元气清脆的年轻少女音，音色明亮甜美，语速轻快活泼，带着自然的笑意与灵动感。",
    "磁性男声" to "低沉磁性的年轻男声，胸腔共鸣饱满，语速沉稳克制，温柔而有安全感。",
)

private const val MAX_MIMO_CLONE_AUDIO_BYTES = 5 * 1024 * 1024

@OptIn(ExperimentalLayoutApi::class)
private fun CardGroupScope.miMoTTSItems(
    setting: TTSProviderSetting.MiMo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val mimoKeys = parseMiMoTtsKeys(setting.apiKey)
    val modelTrimmed = setting.model.trim()
    val isVoiceDesign = modelTrimmed.equals("mimo-v2.5-tts-voicedesign", ignoreCase = true)
    val isVoiceClone = modelTrimmed.equals("mimo-v2.5-tts-voiceclone", ignoreCase = true)

    // API Key 号池
    formItem(
        headlineContent = { Text("API Key 号池") },
        supportingContent = {
            Text(
                if (mimoKeys.isEmpty()) {
                    "支持填写多把 MiMo API Key（逗号、空格或换行分隔），遇 429/5xx 自动冷却轮换"
                } else {
                    "已识别 ${mimoKeys.size} 把 MiMo Key（限时免费 100 RPM / 无日封顶，低延迟 pcm16 流式）"
                }
            )
        }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            placeholder = { Text("mimo-xxx（可粘贴多把，换行或逗号分隔）") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    // Model + 3 档快捷切换胶囊
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text("MiMo-V2.5-TTS 全系支持流式 pcm16：支持无限免费捏声线、9 个预设音色与唱歌、以及音频克隆") }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("mimo-v2.5-tts-voicedesign") }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    "mimo-v2.5-tts-voicedesign" to "VoiceDesign 捏声线",
                    "mimo-v2.5-tts" to "内置音色+唱歌",
                    "mimo-v2.5-tts-voiceclone" to "VoiceClone 克隆",
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = modelTrimmed.equals(id, ignoreCase = true),
                        onClick = { onValueChange(setting.copy(model = id)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    // VoiceDesign / 导演指令 + 4 个灵感胶囊
    formItem(
        headlineContent = {
            Text(
                if (isVoiceDesign) "声线描述 (VoiceDesign Prompt)"
                else "导演 / 情感控制指令（可选）"
            )
        },
        supportingContent = {
            Text(
                if (isVoiceDesign) {
                    "通过自然语言描述音色年龄、质感与情绪（以 user 角色发送；留空则兜底温柔女声）"
                } else {
                    "非空时以 user 角色发送导演/情感控制指令（如“低声耳语，语速偏慢”）"
                }
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = setting.voiceDesignPrompt,
                onValueChange = { newPrompt ->
                    onValueChange(setting.copy(voiceDesignPrompt = newPrompt))
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = {
                    Text(
                        if (isVoiceDesign) "例如：温柔知性的年轻女声，吐字清晰，略带气声与亲昵感"
                        else "可选：填写情感、语气或角色导演指令"
                    )
                }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MIMO_VOICE_DESIGN_INSPIRATIONS.forEach { (label, prompt) ->
                    FilterChip(
                        selected = setting.voiceDesignPrompt.trim() == prompt,
                        onClick = { onValueChange(setting.copy(voiceDesignPrompt = prompt)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    if (isVoiceDesign) {
        formItem(
            headlineContent = { Text("智能文案润色 (optimize_text_preview)") },
            supportingContent = { Text("开启后由模型根据声线描述润色试听台词（仅 VoiceDesign 模式生效）") }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (setting.optimizeTextPreview) "已开启" else "已关闭（严格按原文本朗读）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = setting.optimizeTextPreview,
                    onCheckedChange = { enabled ->
                        onValueChange(setting.copy(optimizeTextPreview = enabled))
                    }
                )
            }
        }
    } else if (isVoiceClone) {
        formItem(
            headlineContent = { Text("克隆参考音频样本 (Base64)") },
            supportingContent = { Text("支持直接粘贴音频 Base64，或导入本地 mp3 / wav 样本（建议 5~15 秒，≤ 5MB）") }
        ) {
            val context = LocalContext.current
            var importStatus by remember(setting.id) { mutableStateOf<String?>(null) }
            val audioPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            total += read
                            if (total > MAX_MIMO_CLONE_AUDIO_BYTES) {
                                importStatus = "样本文件超过 5MB 限制，请裁剪后重试"
                                return@use null
                            }
                            out.write(buf, 0, read)
                        }
                        out.toByteArray()
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val b64 = Base64.getEncoder().encodeToString(bytes)
                        onValueChange(setting.copy(cloneAudioBase64 = b64))
                        importStatus = "已导入音频样本 (${(bytes.size / 1024).coerceAtLeast(1)} KB)"
                    } else if (bytes != null) {
                        importStatus = "所选音频文件为空"
                    }
                } catch (e: Exception) {
                    importStatus = "读取失败: ${e.message ?: e::class.simpleName}"
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = setting.cloneAudioBase64,
                    onValueChange = { newB64 ->
                        onValueChange(setting.copy(cloneAudioBase64 = newB64))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text("粘贴参考音频 Base64（不带 data:audio/... 前缀或纯 Base64）") }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { audioPicker.launch(arrayOf("audio/*")) }
                    ) {
                        Text("导入本地 mp3 / wav")
                    }
                    if (setting.cloneAudioBase64.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onValueChange(setting.copy(cloneAudioBase64 = ""))
                                importStatus = "已清空克隆样本"
                            }
                        ) {
                            Text("清空")
                        }
                    }
                }
                importStatus?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        // 9 个官方预设音色
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
            supportingContent = { Text("mimo-v2.5-tts 全部 9 个官方预设音色，支持正文使用 (风格) 与 [叹气]/[轻笑]/[抽泣]/[气声]/[唱歌]") }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectTextField(
                    value = setting.voice,
                    options = MIMO_OFFICIAL_VOICES,
                    onValueChange = { newVoice ->
                        onValueChange(setting.copy(voice = newVoice))
                    },
                    onOptionSelected = { voice ->
                        onValueChange(setting.copy(voice = voice))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("mimo_default") }
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MIMO_OFFICIAL_VOICES.forEach { presetVoice ->
                        FilterChip(
                            selected = setting.voice.trim() == presetVoice,
                            onClick = { onValueChange(setting.copy(voice = presetVoice)) },
                            label = { Text(presetVoice) },
                        )
                    }
                }
            }
        }
    }

    // 就地一键试听当前效果
    formItem(
        headlineContent = { Text("即时试听") },
        supportingContent = { Text("直接使用当前 MiMo 模型、声线与提示词合成一段 24kHz PCM16 试听语音") }
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val mimoProvider = remember { MiMoTTSProvider() }
        val audioPlayer = remember(setting.id) { AudioPlayer(context.applicationContext) }
        var previewJob by remember(setting.id) { mutableStateOf<Job?>(null) }
        var isLoading by remember(setting.id) { mutableStateOf(false) }
        var isPlaying by remember(setting.id) { mutableStateOf(false) }
        var previewError by remember(setting.id) { mutableStateOf<String?>(null) }

        DisposableEffect(setting.id) {
            onDispose {
                previewJob?.cancel()
                audioPlayer.release()
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = mimoKeys.isNotEmpty(),
                    onClick = {
                        if (isLoading || isPlaying) {
                            previewJob?.cancel()
                            previewJob = null
                            audioPlayer.stop()
                            isLoading = false
                            isPlaying = false
                            return@Button
                        }
                        previewError = null
                        isLoading = true
                        previewJob = scope.launch {
                            try {
                                val bytes = withContext(Dispatchers.IO) {
                                    val out = ByteArrayOutputStream()
                                    mimoProvider.generateSpeech(
                                        context = context.applicationContext,
                                        providerSetting = setting,
                                        request = TTSRequest("你好，我是小米 MiMo 语音助手，很高兴为你服务。"),
                                    ).collect { chunk ->
                                        out.write(chunk.data)
                                    }
                                    out.toByteArray()
                                }
                                isLoading = false
                                if (bytes.isEmpty()) {
                                    previewError = "未收到音频数据"
                                    return@launch
                                }
                                isPlaying = true
                                audioPlayer.play(
                                    TTSResponse(
                                        audioData = bytes,
                                        format = AudioFormat.PCM,
                                        sampleRate = 24000,
                                    )
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                previewError = "试听失败: ${e.message ?: e::class.simpleName}"
                            } finally {
                                isLoading = false
                                isPlaying = false
                            }
                        }
                    },
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("合成中...")
                    } else if (isPlaying) {
                        Text("停止试听")
                    } else {
                        Text("一键试听当前效果")
                    }
                }
            }
            previewError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun CardGroupScope.miniMaxTTSItems(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.8-hd") }
        )
    }

    // Voice ID
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voiceIds,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { voiceId ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun CardGroupScope.geminiTTSItems(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    val keys = parseGeminiTtsKeys(setting.apiKey)

    // API Key 号池
    formItem(
        headlineContent = { Text("API Key 号池") },
        supportingContent = {
            Text(
                if (keys.isEmpty()) {
                    "支持填写多把 Google API Key（换行或逗号分隔），每把 Key 3.8 双子星合计约 20 次/天"
                } else {
                    "已识别 ${keys.size} 把 Key（单 Key 先吃空 3.8 Flash → 3.8 Lite 再换下把 Key，合计最高 ${keys.size * 20} 次/天）"
                }
            )
        }
    ) {
        var poolRefreshTick by remember { mutableIntStateOf(0) }
        val poolSummary = remember(setting.apiKey, poolRefreshTick) {
            GeminiTtsPoolTracker.summarizePool(setting.apiKey)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = setting.apiKey,
                onValueChange = { newApiKey ->
                    onValueChange(setting.copy(apiKey = newApiKey))
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                placeholder = { Text("AIzaSy...（可粘贴多把，每行一把）") },
            )

            if (keys.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "今日 3.8 号池配额估算（太平洋零点清零）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    GeminiTtsPoolTracker.resetCooldowns()
                                    poolRefreshTick++
                                }
                            ) {
                                Text("清空冷却")
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val labels = mapOf(
                                "gemini-3.8-flash-tts" to "3.8 Flash",
                                "gemini-3.8-flash-lite-tts" to "3.8 Lite",
                            )
                            GEMINI_TTS_MODEL_LADDER.forEach { modelId ->
                                val (used, cap) = poolSummary[modelId] ?: (0 to keys.size * 10)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = "${labels[modelId] ?: modelId}: $used/$cap",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Model + 3.8 双子星快选胶囊
    formItem(
        headlineContent = { Text("首选语音模型 (Gemini 3.8)") },
        supportingContent = { Text("仅使用现代 Gemini 3.8 双子星（支持捏声线 VoiceDesign、style 指令与音色表演标签）") }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = setting.model,
                onValueChange = { newModel ->
                    onValueChange(setting.copy(model = newModel))
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("gemini-3.8-flash-tts") }
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    "gemini-3.8-flash-tts" to "3.8 Flash",
                    "gemini-3.8-flash-lite-tts" to "3.8 Lite",
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = setting.model.trim().equals(id, ignoreCase = true),
                        onClick = { onValueChange(setting.copy(model = id)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    // 自动降级接力开关
    formItem(
        headlineContent = { Text("3.8 双子星自动接力降级") },
        supportingContent = { Text("单 Key 内先尝试 3.8 Flash (10 次/天) → 3.8 Lite (10 次/天)，吃空后自动切号池下一把 Key") }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (setting.autoFallbackModels) "已开启（单 Key 最高吃满 20 次/天）" else "已关闭（仅使用所选 3.8 模型）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = setting.autoFallbackModels,
                onCheckedChange = { enabled ->
                    onValueChange(setting.copy(autoFallbackModels = enabled))
                }
            )
        }
    }

    // 主音色 + 声线工坊弹窗入口
    formItem(
        headlineContent = { Text("主音色 (Voice Name)") },
        supportingContent = {
            val isCustom = setting.voiceName.trim().startsWith("voice_") || setting.voiceName.trim().startsWith("voicekey_")
            Text(
                if (isCustom) {
                    val syncedCount = setting.voiceIdsByKeyHint.size
                    if (syncedCount > 1) {
                        "当前使用 3.8 定制声线（已同步 $syncedCount 把 Key，换 Key 不变声线）；未同步 Key 将使用下方相似备选"
                    } else {
                        val owner = setting.voiceOwnerKeyHint.ifBlank { "创建时的项目 Key" }
                        "当前使用 3.8 定制声线（归属 $owner），可在声线工坊一键「同步捏到全部 Key」"
                    }
                } else {
                    "可填写官方预置名（如 Kore / Aoede / Leda）或打开声线工坊捏专属声线并试听"
                }
            )
        }
    ) {
        var showVoiceStudio by remember(setting.id) { mutableStateOf(false) }

        if (showVoiceStudio) {
            GeminiVoiceStudioDialog(
                setting = setting,
                onDismiss = { showVoiceStudio = false },
                onUpdateSetting = { updated -> onValueChange(updated) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = setting.voiceName,
                onValueChange = { newVoiceName ->
                    val trimmed = newVoiceName.trim()
                    val isCustom = trimmed.startsWith("voice_") || trimmed.startsWith("voicekey_")
                    onValueChange(
                        if (isCustom) {
                            setting.copy(voiceName = newVoiceName)
                        } else {
                            setting.copy(
                                voiceName = newVoiceName,
                                voiceOwnerKeyHint = "",
                                voiceIdsByKeyHint = emptyMap(),
                            )
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Kore 或 voice_xxx") }
            )
            Button(
                onClick = { showVoiceStudio = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 3.8 声线工坊（同步多 Key 捏声线 · 官方音色库）")
            }
        }
    }

    // 相似备选音色
    formItem(
        headlineContent = { Text("相似备选官方音色") },
        supportingContent = { Text("当换到未同步定制声线的 Key 时自动接力使用，保证声音风格连贯（也可在声线工坊一键设定）") }
    ) {
        OutlinedTextField(
            value = setting.fallbackVoiceName,
            onValueChange = { newFallback ->
                onValueChange(setting.copy(fallbackVoiceName = newFallback))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Kore") }
        )
    }

    // 语气 / 风格 Prompt（3.8 专用）
    formItem(
        headlineContent = { Text("默认语气 / 表演风格 (3.8)") },
        supportingContent = { Text("Gemini 3.8 生效。正文还支持 [sigh] [laugh] [whispers] 等标签，客户端会自动转为 3.8 表演指令") }
    ) {
        OutlinedTextField(
            value = setting.stylePrompt,
            onValueChange = { newStyle ->
                onValueChange(setting.copy(stylePrompt = newStyle))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
            placeholder = { Text("例如: Speak warmly and gently with a subtle smile") }
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://generativelanguage.googleapis.com/v1beta") }
        )
    }
}

private fun CardGroupScope.systemTTSItems(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // Speech Rate
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..3.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    // Pitch
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..2.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

private fun CardGroupScope.qwenTTSItems(
    setting: TTSProviderSetting.Qwen,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    val models = listOf(
        "qwen-audio-3.0-tts-flash",
        "qwen-audio-3.0-tts-plus",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { model ->
                val defaultVoice = when (model) {
                    "qwen-audio-3.0-tts-plus" -> "longanlingxin"
                    "qwen-audio-3.0-tts-flash" -> "longanhuan_v3.6"
                    else -> setting.voice
                }
                onValueChange(setting.copy(model = model, voice = defaultVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen-audio-3.0-tts-flash") }
        )
    }

    // Voice
    val voices = when (setting.model) {
        "qwen-audio-3.0-tts-plus" -> listOf("longanlingxin", "longanlufeng")
        "qwen-audio-3.0-tts-flash" -> listOf(
            "longanfengyue", "longanyuanfei", "longanlingxi", "longanxiaoxin",
            "longanhuan_v3.6", "longjielidou_v3.6", "longpaopao_v3.6",
            "longhuohuo_v3.6", "longchuanshu_v3.6", "loongmary",
            "loongeva_v3.6", "loongjohn",
        )

        else -> emptyList()
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Audio Format
    val formats = listOf("wav", "mp3", "pcm", "opus")

    formItem(
        headlineContent = { Text("Audio Format") },
        supportingContent = { Text("Audio encoding returned by Qwen TTS") }
    ) {
        SelectTextField(
            value = setting.format,
            options = formats,
            readOnly = true,
            onOptionSelected = { format ->
                onValueChange(setting.copy(format = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000, 44100, 48000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text("Audio sample rate in Hz") }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { sampleRate ->
                onValueChange(setting.copy(sampleRate = sampleRate))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.groqTTSItems(
    setting: TTSProviderSetting.Groq,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gsk_xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
    }

    // Voice
    val voices = listOf("austin", "natalie", "kailin")

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.xAITTSItems(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("xai-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") }
        )
    }

    // Voice ID
    val voices = listOf(
        "eve" to "Eve",
        "ara" to "Ara",
        "rex" to "Rex",
        "sal" to "Sal",
        "leo" to "Leo"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voices,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Language
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es-ES" to "Spanish (Spain)",
        "es-MX" to "Spanish (Mexico)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "it" to "Italian",
        "ru" to "Russian",
        "ar-EG" to "Arabic (Egypt)",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "bn" to "Bengali"
    )

    formItem(
        headlineContent = { Text("Language") },
    ) {
        SelectTextField(
            value = setting.language,
            options = languages,
            onValueChange = { newLanguage ->
                onValueChange(setting.copy(language = newLanguage))
            },
            onOptionSelected = { (code, _) ->
                onValueChange(setting.copy(language = code))
            },
            optionToString = { (code, displayName) -> "$displayName ($code)" },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.elevenLabsTTSItems(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk_...") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") }
        )
    }

    // Model
    val models = listOf(
        "eleven_multilingual_v2" to "Eleven Multilingual v2",
        "eleven_v3" to "Eleven v3",
        "eleven_flash_v2_5" to "Eleven Flash v2.5"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("JBFqnCBsd6RMkjVDRZzb") }
        )
    }

    // Stability
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_stability)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_stability_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                onValueChange(setting.copy(stability = newStability.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.5",
        )
    }

    // Similarity Boost
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_similarity_boost)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_similarity_boost_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                onValueChange(setting.copy(similarityBoost = newSimilarityBoost.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.75",
        )
    }
}

private fun CardGroupScope.fishAudioTTSItems(
    setting: TTSProviderSetting.FishAudio,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://fish.audio/app/api-keys") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.fish.audio") }
        )
    }

    // Model (下拉选择框 + 文本输入框，完全同 ElevenLabs 格式)
    val models = listOf(
        "s2.1-pro" to "S2.1-Pro (推荐)",
        "s2.1-pro-free" to "S2.1-Pro Free (免费)",
        "s2-pro" to "S2-Pro",
        "s1" to "S1"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID (reference_id)
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.referenceId,
            onValueChange = { newReferenceId ->
                onValueChange(setting.copy(referenceId = newReferenceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("802e3bc2b27e49c2995d23ef70e6ac89") }
        )
    }

    // Temperature
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_temperature)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_temperature_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.temperature,
            onValueChange = { newTemperature ->
                onValueChange(setting.copy(temperature = newTemperature.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.7",
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_fish_audio_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                onValueChange(setting.copy(speed = newSpeed.coerceIn(0.5f, 2f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "1.0",
        )
    }
}

private fun CardGroupScope.stepTTSItems(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text("从阶跃星辰官网获取密钥: platform.stepfun.com/interface-key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("从阶跃星辰官网获取密钥") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    // Model
    val models = listOf(
        "step-tts-mini" to "step-tts-mini (轻量, 便宜)",
        "step-tts-vivid" to "step-tts-vivid (情感丰富)",
        "stepaudio-2.5-tts" to "stepaudio-2.5-tts (语境感知, 支持 instruction)",
        "step-tts-2" to "step-tts-2 (上一代)",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice
    // 部分常用 voice-id, 完整列表见官方开发指南
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices = listOf(
        "elegantgentle-female" to "气质温婉 (elegantgentle-female)",
        "livelybreezy-female" to "活力轻快 (livelybreezy-female)",
        "energeticconfident-female" to "活力自信 (energeticconfident-female)",
        "jingdiannvsheng" to "经典女声 (jingdiannvsheng)",
        "wenroushunv" to "温柔熟女 (wenroushunv)",
        "tianmeinvsheng" to "甜美女声 (tianmeinvsheng)",
        "qingchunshaonv" to "清纯少女 (qingchunshaonv)",
        "wenrounvsheng" to "温柔女声 (wenrounvsheng)",
        "ruanmengnvsheng" to "软萌女生 (ruanmengnvsheng)",
        "youyanvsheng" to "优雅女生 (youyanvsheng)",
        "lengyanyujie" to "冷艳御姐 (lengyanyujie)",
        "shuangkuaijiejie" to "爽快姐姐 (shuangkuaijiejie)",
        "wenjingxuejie" to "文静学姐 (wenjingxuejie)",
        "linjiajiejie" to "邻家姐姐 (linjiajiejie)",
        "linjiameimei" to "邻家妹妹 (linjiameimei)",
        "zhixingjiejie" to "知性姐姐 (zhixingjiejie)",
        "cixingnansheng" to "磁性男声 (cixingnansheng)",
        "wenrounansheng" to "温柔男声 (wenrounansheng)",
        "yuanqinansheng" to "元气男声 (yuanqinansheng)",
        "zhengpaiqingnian" to "正派青年 (zhengpaiqingnian)",
        "ruyananshi" to "儒雅男士 (ruyananshi)",
        "boyinnansheng" to "播音男声 (boyinnansheng)",
        "shenchennanyin" to "深沉男音 (shenchennanyin)",
        "shuangkuainansheng" to "爽快男声 (shuangkuainansheng)",
        "ganliannvsheng" to "干练女声 (ganliannvsheng)",
        "qinhenvsheng" to "亲切女声 (qinhenvsheng)",
        "huolinvsheng" to "活力女声 (huolinvsheng)",
        "jilingshaonv" to "机灵少女 (jilingshaonv)",
        "yuanqishaonv" to "元气少女 (yuanqishaonv)",
        "wenrougongzi" to "温柔公子 (wenrougongzi)",
        "qingniandaxuesheng" to "青年大学生 (qingniandaxuesheng)",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voice = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Response Format
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    formItem(
        headlineContent = { Text("Response Format") },
        supportingContent = { Text("音频编码格式 (注意 StepFun API 字段名为 camelCase)") }
    ) {
        SelectTextField(
            value = setting.responseFormat,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(responseFormat = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(responseFormat = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text("语速 (0.5 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Volume
    formItem(
        headlineContent = { Text("Volume") },
        supportingContent = { Text("音量 (0.1 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Volume"
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text("采样率 (Hz)") }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { rate ->
                onValueChange(setting.copy(sampleRate = rate))
            },
            optionToString = { rate -> "$rate Hz" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    formItem(
        headlineContent = { Text("Instruction") },
        supportingContent = { Text("全局语境指令, 仅 stepaudio-2.5-tts 生效 (≤200 字符, 留空不下发)") }
    ) {
        OutlinedTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如: 语气温柔, 语速偏慢") },
            minLines = 2,
            maxLines = 4,
        )
    }
}

private fun CardGroupScope.volcengineTTSItems(
    setting: TTSProviderSetting.Volcengine,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text("API Key") },
        supportingContent = { Text("请填写豆包语音控制台的 API Key，不是火山方舟控制台的 API Key。") }
    ) {
        var keyVisible by remember(setting.id) { mutableStateOf(false) }
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key",
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) }) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            placeholder = { Text("https://openspeech.bytedance.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(
        headlineContent = { Text("资源 ID") },
        supportingContent = { Text("需与已开通的服务和音色匹配，默认 seed-tts-2.0。") }
    ) {
        OutlinedTextField(
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(
        headlineContent = { Text("音色 ID") },
        supportingContent = { Text("填写控制台中的音色 ID，默认使用 VV 音色。") }
    ) {
        OutlinedTextField(
            value = setting.speaker,
            onValueChange = { onValueChange(setting.copy(speaker = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text("范围 -50～100，0 为正常语速，-50 为半速，100 为两倍速。") }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { onValueChange(setting.copy(speechRate = it.coerceIn(-50, 100))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
