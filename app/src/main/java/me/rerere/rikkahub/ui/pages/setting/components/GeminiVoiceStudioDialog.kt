package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.tts.controller.AudioPlayer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.GEMINI_FEATURED_VOICES
import me.rerere.tts.provider.providers.GeminiCreatedVoice
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.GeminiVoiceEntry
import me.rerere.tts.provider.providers.GeminiVoicesClient
import me.rerere.tts.provider.providers.maskGeminiTtsKey
import me.rerere.tts.provider.providers.parseGeminiTtsKeys
import java.io.ByteArrayOutputStream

private data class StudioVoiceItem(
    val id: String,
    val title: String,
    val gender: String,
    val trait: String,
    val languageCode: String = "",
    val isFeatured: Boolean = false,
)

private data class VoicePromptPreset(
    val label: String,
    val name: String,
    val gender: String,
    val lang: String,
    val prompt: String,
)

private val VOICE_DESIGN_PRESETS = listOf(
    VoicePromptPreset(
        label = "温柔知性姐姐",
        name = "Kairos-姐姐",
        gender = "female",
        lang = "zh-CN",
        prompt = "Warm, gentle, mature Chinese female voice in her late 20s, intimate late-night radio host tone, soft breathiness, calm pacing, natural conversational cadence."
    ),
    VoicePromptPreset(
        label = "元气清脆少女",
        name = "Kairos-少女",
        gender = "female",
        lang = "zh-CN",
        prompt = "Bright, lively young Chinese female voice, clear timbre, slight cheerful smile in tone, agile rhythm, expressive yet never shrill."
    ),
    VoicePromptPreset(
        label = "清冷电波系",
        name = "Kairos-电波",
        gender = "female",
        lang = "zh-CN",
        prompt = "Cool, composed, slightly detached young female voice with crystalline articulation, quiet emotional depth, measured steady tempo."
    ),
    VoicePromptPreset(
        label = "低沉磁性男声",
        name = "Kairos-低沉男",
        gender = "male",
        lang = "zh-CN",
        prompt = "Deep resonant Chinese male voice in his early 30s, composed storytelling warmth, rich chest resonance, unhurried steady cadence."
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GeminiVoiceStudioDialog(
    setting: TTSProviderSetting.Gemini,
    onDismiss: () -> Unit,
    onUpdateSetting: (TTSProviderSetting.Gemini) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voicesClient = remember { GeminiVoicesClient() }
    val ttsProvider = remember { GeminiTTSProvider() }
    val audioPlayer = remember { AudioPlayer(context.applicationContext) }

    var playJob by remember { mutableStateOf<Job?>(null) }
    var playingVoiceId by remember { mutableStateOf<String?>(null) }
    var loadingVoiceId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            playJob?.cancel()
            audioPlayer.release()
        }
    }

    val keys = remember(setting.apiKey) { parseGeminiTtsKeys(setting.apiKey) }
    var selectedKeyIndex by remember(keys) { mutableIntStateOf(0) }
    val activeKey = keys.getOrElse(selectedKeyIndex.coerceIn(0, (keys.size - 1).coerceAtLeast(0))) { "" }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 捏声线, 1: 官方音色库
    var statusError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // 捏声线状态
    var designName by remember { mutableStateOf("Kairos-专属") }
    var designGender by remember { mutableStateOf("female") }
    var designLanguage by remember { mutableStateOf("zh-CN") }
    var designPrompt by remember { mutableStateOf(VOICE_DESIGN_PRESETS.first().prompt) }
    var isCreating by remember { mutableStateOf(false) }
    var createdVoice by remember { mutableStateOf<GeminiCreatedVoice?>(null) }

    // 项目已存声线列表
    var storedVoices by remember { mutableStateOf<List<GeminiVoiceEntry>>(emptyList()) }
    var isLoadingStored by remember { mutableStateOf(false) }
    var deletingVoiceId by remember { mutableStateOf<String?>(null) }

    // 官方音色库状态
    var cloudPrebuiltVoices by remember { mutableStateOf<List<GeminiVoiceEntry>>(emptyList()) }
    var isLoadingPrebuilt by remember { mutableStateOf(false) }
    var genderFilter by remember { mutableStateOf("all") } // all / female / male
    var searchQuery by remember { mutableStateOf("") }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        audioPlayer.stop()
        playingVoiceId = null
        loadingVoiceId = null
    }

    fun playBytes(voiceId: String, bytes: ByteArray, format: AudioFormat) {
        stopPlayback()
        playingVoiceId = voiceId
        playJob = scope.launch {
            try {
                audioPlayer.play(
                    TTSResponse(
                        audioData = bytes,
                        format = format,
                        sampleRate = 24000,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusError = "播放失败: ${e.message ?: e::class.simpleName}"
            } finally {
                if (playingVoiceId == voiceId) {
                    playingVoiceId = null
                }
            }
        }
    }

    fun auditionWithTts(voiceId: String, force38OwnerKey: String? = null) {
        if (playingVoiceId == voiceId || loadingVoiceId == voiceId) {
            stopPlayback()
            return
        }
        if (keys.isEmpty()) {
            statusError = "请先填写至少 1 把 Gemini API Key 再试听"
            return
        }
        stopPlayback()
        statusError = null
        statusMessage = null
        loadingVoiceId = voiceId
        playJob = scope.launch {
            try {
                val sampleSetting = if (force38OwnerKey != null) {
                    setting.copy(
                        apiKey = force38OwnerKey,
                        model = "gemini-3.8-flash-tts",
                        voiceName = voiceId,
                        fallbackVoiceName = voiceId,
                        autoFallbackModels = false,
                        voiceIdsByKeyHint = emptyMap(),
                    )
                } else {
                    setting.copy(
                        model = "gemini-3.8-flash-tts",
                        voiceName = voiceId,
                        fallbackVoiceName = voiceId,
                        voiceIdsByKeyHint = emptyMap(),
                    )
                }
                val bytes = withContext(Dispatchers.IO) {
                    val out = ByteArrayOutputStream()
                    ttsProvider.generateSpeech(
                        context = context.applicationContext,
                        providerSetting = sampleSetting,
                        request = TTSRequest("你好，这是一段实时语音试听，很高兴在这里遇见你。")
                    ).collect { chunk ->
                        out.write(chunk.data)
                    }
                    out.toByteArray()
                }
                loadingVoiceId = null
                if (bytes.isEmpty()) {
                    statusError = "未收到音频数据"
                    return@launch
                }
                playingVoiceId = voiceId
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
                statusError = "试听失败: ${e.message ?: e::class.simpleName}"
            } finally {
                if (loadingVoiceId == voiceId) loadingVoiceId = null
                if (playingVoiceId == voiceId) playingVoiceId = null
            }
        }
    }

    val mergedLibraryVoices = remember(cloudPrebuiltVoices, genderFilter, searchQuery) {
        val featuredMap = GEMINI_FEATURED_VOICES.associateBy { it.id.lowercase() }
        val baseList = GEMINI_FEATURED_VOICES.map { f ->
            StudioVoiceItem(
                id = f.id,
                title = f.id,
                gender = f.gender,
                trait = f.traitZh,
                isFeatured = true,
            )
        }.toMutableList()
        val existingIds = baseList.map { it.id.lowercase() }.toMutableSet()

        cloudPrebuiltVoices.forEach { cloud ->
            val key = cloud.id.lowercase()
            if (key.isBlank()) return@forEach
            val desc = listOfNotNull(
                cloud.description?.takeIf { it.isNotBlank() },
                cloud.persona?.takeIf { it.isNotBlank() },
                cloud.accent?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (existingIds.add(key)) {
                baseList.add(
                    StudioVoiceItem(
                        id = cloud.id,
                        title = cloud.displayName.ifBlank { cloud.id },
                        gender = cloud.gender?.lowercase() ?: "neutral",
                        trait = desc.ifBlank { "Google Cloud 预置音色" },
                        languageCode = cloud.languageCode.orEmpty(),
                        isFeatured = featuredMap.containsKey(key),
                    )
                )
            }
        }

        val q = searchQuery.trim().lowercase()
        baseList.filter { item ->
            val genderOk = genderFilter == "all" || item.gender == genderFilter
            val queryOk = q.isEmpty() ||
                item.id.lowercase().contains(q) ||
                item.title.lowercase().contains(q) ||
                item.trait.lowercase().contains(q) ||
                item.languageCode.lowercase().contains(q)
            genderOk && queryOk
        }
    }

    Dialog(
        onDismissRequest = {
            stopPlayback()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gemini 3.8 声线工坊",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val syncBadge = if (setting.voiceIdsByKeyHint.size > 1) {
                            " (已同步 ${setting.voiceIdsByKeyHint.size} Key)"
                        } else {
                            ""
                        }
                        Text(
                            text = "主: ${setting.voiceName.ifBlank { "未设" }}$syncBadge · 备选: ${setting.fallbackVoiceName.ifBlank { "Kore" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            stopPlayback()
                            onDismiss()
                        }
                    ) {
                        Text("关闭")
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("捏声线 (3.8)")
                    }
                    SegmentedButton(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("官方音色库 (3.8)")
                    }
                }

                if (keys.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "归属 Key:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        keys.forEachIndexed { idx, key ->
                            FilterChip(
                                selected = selectedKeyIndex == idx,
                                onClick = { selectedKeyIndex = idx },
                                label = { Text("#${idx + 1} ${maskGeminiTtsKey(key)}") },
                            )
                        }
                    }
                }

                statusError?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                statusMessage?.let { msg ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                if (selectedTab == 0) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "用自然语言描述声线并保存到项目（仅 Gemini 3.8 TTS 可用）。多 Key 模式下建议点击「同步捏到全部 Key」，换 Key 依然使用同一定制声线；未同步 Key 将使用「相似备选官方音色」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            VOICE_DESIGN_PRESETS.forEach { preset ->
                                FilterChip(
                                    selected = designPrompt == preset.prompt,
                                    onClick = {
                                        designName = preset.name
                                        designGender = preset.gender
                                        designLanguage = preset.lang
                                        designPrompt = preset.prompt
                                    },
                                    label = { Text(preset.label) },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = designName,
                            onValueChange = { designName = it },
                            label = { Text("声线名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf("female" to "女声", "male" to "男声", "neutral" to "中性").forEach { (g, label) ->
                                FilterChip(
                                    selected = designGender == g,
                                    onClick = { designGender = g },
                                    label = { Text(label) },
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            listOf("zh-CN", "en-US", "ja-JP").forEach { lang ->
                                FilterChip(
                                    selected = designLanguage == lang,
                                    onClick = { designLanguage = lang },
                                    label = { Text(lang) },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = designPrompt,
                            onValueChange = { designPrompt = it },
                            label = { Text("声线 Prompt 描述（建议英文，越具体越稳）") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (keys.size > 1) {
                                Button(
                                    enabled = !isCreating && keys.isNotEmpty() && designPrompt.isNotBlank(),
                                    onClick = {
                                        statusError = null
                                        statusMessage = null
                                        isCreating = true
                                        val cleanName = designName.trim().ifBlank { "Kairos-Voice" }
                                        val cleanPrompt = designPrompt.trim()
                                        scope.launch {
                                            try {
                                                val (succeeded, failures) = withContext(Dispatchers.IO) {
                                                    val okList = mutableListOf<GeminiCreatedVoice>()
                                                    val errList = mutableListOf<String>()
                                                    for (key in keys) {
                                                        try {
                                                            okList += voicesClient.createPromptedVoice(
                                                                baseUrl = setting.baseUrl,
                                                                apiKey = key,
                                                                displayName = cleanName,
                                                                gender = designGender,
                                                                languageCode = designLanguage,
                                                                prompt = cleanPrompt,
                                                                model = "gemini-3.8-flash-tts",
                                                            )
                                                        } catch (e: CancellationException) {
                                                            throw e
                                                        } catch (e: Exception) {
                                                            errList += "${maskGeminiTtsKey(key)}: ${e.message ?: e::class.simpleName}"
                                                        }
                                                    }
                                                    okList to errList
                                                }
                                                if (succeeded.isEmpty()) {
                                                    statusError = "同步捏声线失败: ${failures.firstOrNull().orEmpty()}"
                                                } else {
                                                    val syncedMap = succeeded.associate { it.ownerKeyHint to it.id }
                                                    val primary = succeeded.first()
                                                    createdVoice = primary
                                                    onUpdateSetting(
                                                        setting.copy(
                                                            voiceName = primary.id,
                                                            voiceOwnerKeyHint = primary.ownerKeyHint,
                                                            voiceIdsByKeyHint = syncedMap,
                                                        )
                                                    )
                                                    statusMessage = if (failures.isEmpty()) {
                                                        "已同步捏声线到全部 ${succeeded.size} 把 Key 并设为主音色: ${primary.id}"
                                                    } else {
                                                        "已同步 ${succeeded.size}/${keys.size} 把 Key（主: ${primary.id}）；部分失败: ${failures.joinToString("; ")}"
                                                    }
                                                    if (primary.sampleWavBytes.isNotEmpty()) {
                                                        playBytes(primary.id, primary.sampleWavBytes, AudioFormat.WAV)
                                                    }
                                                }
                                            } catch (e: CancellationException) {
                                                throw e
                                            } finally {
                                                isCreating = false
                                            }
                                        }
                                    },
                                ) {
                                    if (isCreating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("同步生成中...")
                                    } else {
                                        Text("同步捏到全部 Key (${keys.size})")
                                    }
                                }
                            }

                            val singleCreateAction = {
                                statusError = null
                                statusMessage = null
                                isCreating = true
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            voicesClient.createPromptedVoice(
                                                baseUrl = setting.baseUrl,
                                                apiKey = activeKey,
                                                displayName = designName.trim().ifBlank { "Kairos-Voice" },
                                                gender = designGender,
                                                languageCode = designLanguage,
                                                prompt = designPrompt.trim(),
                                                model = "gemini-3.8-flash-tts",
                                            )
                                        }
                                        createdVoice = result
                                        statusMessage = "已生成并保存到项目 (${result.ownerKeyHint}): ${result.id}"
                                        if (result.sampleWavBytes.isNotEmpty()) {
                                            playBytes(result.id, result.sampleWavBytes, AudioFormat.WAV)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        statusError = "生成失败: ${e.message ?: e::class.simpleName}"
                                    } finally {
                                        isCreating = false
                                    }
                                }
                            }

                            if (keys.size > 1) {
                                OutlinedButton(
                                    enabled = !isCreating && activeKey.isNotBlank() && designPrompt.isNotBlank(),
                                    onClick = { singleCreateAction() },
                                ) {
                                    Text("仅当前 Key 试捏")
                                }
                            } else {
                                Button(
                                    enabled = !isCreating && activeKey.isNotBlank() && designPrompt.isNotBlank(),
                                    onClick = { singleCreateAction() },
                                ) {
                                    if (isCreating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("生成中...")
                                    } else {
                                        Text("生成并试听")
                                    }
                                }
                            }

                            if (playingVoiceId != null) {
                                OutlinedButton(onClick = { stopPlayback() }) {
                                    Text("停止播放")
                                }
                            }
                        }

                        createdVoice?.let { cv ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "${cv.displayName} (${cv.id})",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "归属 Key: ${cv.ownerKeyHint} · 点下面可直接应用为主音色，再去右栏挑一个最像的官方音色当相似备选。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (cv.sampleWavBytes.isNotEmpty()) {
                                            OutlinedButton(
                                                onClick = {
                                                    if (playingVoiceId == cv.id) stopPlayback()
                                                    else playBytes(cv.id, cv.sampleWavBytes, AudioFormat.WAV)
                                                }
                                            ) {
                                                Text(if (playingVoiceId == cv.id) "停止" else "重播样音")
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                onUpdateSetting(
                                                    setting.copy(
                                                        voiceName = cv.id,
                                                        voiceOwnerKeyHint = cv.ownerKeyHint,
                                                        voiceIdsByKeyHint = setting.voiceIdsByKeyHint + (cv.ownerKeyHint to cv.id),
                                                    )
                                                )
                                                statusMessage = "已设为专属主音色: ${cv.id}"
                                            }
                                        ) {
                                            Text("设为专属主音色")
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "当前 Key 已存声线",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            OutlinedButton(
                                enabled = !isLoadingStored && activeKey.isNotBlank(),
                                onClick = {
                                    statusError = null
                                    isLoadingStored = true
                                    scope.launch {
                                        try {
                                            val res = withContext(Dispatchers.IO) {
                                                voicesClient.listVoices(
                                                    baseUrl = setting.baseUrl,
                                                    apiKey = activeKey,
                                                    type = "prompted",
                                                    pageSize = 100,
                                                )
                                            }
                                            storedVoices = res.voices
                                            if (res.voices.isEmpty()) {
                                                statusMessage = "当前 Key 下还没有保存的捏声线"
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            statusError = "拉取列表失败: ${e.message ?: e::class.simpleName}"
                                        } finally {
                                            isLoadingStored = false
                                        }
                                    }
                                }
                            ) {
                                Text(if (isLoadingStored) "加载中..." else "刷新列表")
                            }
                        }

                        storedVoices.forEach { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "${entry.displayName.ifBlank { entry.id }} · ${entry.gender.orEmpty()} ${entry.languageCode.orEmpty()}".trim(),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = entry.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    entry.prompted?.input?.takeIf { it.isNotBlank() }?.let { promptText ->
                                        Text(
                                            text = promptText,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { auditionWithTts(entry.id, force38OwnerKey = activeKey) },
                                        ) {
                                            Text(
                                                when {
                                                    loadingVoiceId == entry.id -> "合成中..."
                                                    playingVoiceId == entry.id -> "停止"
                                                    else -> "试听"
                                                }
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val ownerHint = maskGeminiTtsKey(activeKey)
                                                onUpdateSetting(
                                                    setting.copy(
                                                        voiceName = entry.id,
                                                        voiceOwnerKeyHint = ownerHint,
                                                        voiceIdsByKeyHint = setting.voiceIdsByKeyHint + (ownerHint to entry.id),
                                                    )
                                                )
                                                statusMessage = "已设为主音色: ${entry.id}"
                                            }
                                        ) {
                                            Text("设为主音色")
                                        }
                                        TextButton(
                                            enabled = deletingVoiceId != entry.id,
                                            onClick = {
                                                deletingVoiceId = entry.id
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            voicesClient.deleteVoice(setting.baseUrl, activeKey, entry.id)
                                                        }
                                                        storedVoices = storedVoices.filterNot { it.id == entry.id }
                                                        statusMessage = "已删除 ${entry.id}"
                                                    } catch (e: Exception) {
                                                        statusError = "删除失败: ${e.message ?: e::class.simpleName}"
                                                    } finally {
                                                        deletingVoiceId = null
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(if (deletingVoiceId == entry.id) "删除中..." else "删除")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Gemini 3.8 双子星与多 Key 通用。可试听后一键设为主音色，或设为捏声线的「相似备选」用于未同步 Key 接力。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("搜索音色名或风格（如 温柔 / 低沉 / Kore）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf("all" to "全部", "female" to "女声", "male" to "男声").forEach { (key, label) ->
                                FilterChip(
                                    selected = genderFilter == key,
                                    onClick = { genderFilter = key },
                                    label = { Text(label) },
                                )
                            }
                            OutlinedButton(
                                enabled = !isLoadingPrebuilt && activeKey.isNotBlank(),
                                onClick = {
                                    statusError = null
                                    isLoadingPrebuilt = true
                                    scope.launch {
                                        try {
                                            val allCloud = withContext(Dispatchers.IO) {
                                                val acc = mutableListOf<GeminiVoiceEntry>()
                                                var token: String? = null
                                                var guard = 0
                                                do {
                                                    val page = voicesClient.listVoices(
                                                        baseUrl = setting.baseUrl,
                                                        apiKey = activeKey,
                                                        type = "prebuilt",
                                                        pageSize = 100,
                                                        pageToken = token,
                                                    )
                                                    acc.addAll(page.voices)
                                                    token = page.nextPageToken
                                                    guard++
                                                } while (!token.isNullOrBlank() && guard < 10)
                                                acc
                                            }
                                            cloudPrebuiltVoices = allCloud
                                            statusMessage = "已同步云端官方音色，共获取 ${allCloud.size} 条"
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            statusError = "同步云端音色失败: ${e.message ?: e::class.simpleName}"
                                        } finally {
                                            isLoadingPrebuilt = false
                                        }
                                    }
                                }
                            ) {
                                Text(if (isLoadingPrebuilt) "同步中..." else "拉取云端全量库")
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(mergedLibraryVoices, key = { it.id }) { item ->
                                val isPrimary = setting.voiceName.equals(item.id, ignoreCase = true)
                                val isFallback = setting.fallbackVoiceName.equals(item.id, ignoreCase = true)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isPrimary || isFallback) {
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                    ),
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
                                                text = item.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                text = buildString {
                                                    append(
                                                        when (item.gender) {
                                                            "female" -> "女声"
                                                            "male" -> "男声"
                                                            else -> item.gender
                                                        }
                                                    )
                                                    if (isPrimary) append(" · 当前主音色")
                                                    if (isFallback) append(" · 当前备选")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            text = item.trait,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(onClick = { auditionWithTts(item.id) }) {
                                                Text(
                                                    when {
                                                        loadingVoiceId == item.id -> "合成中..."
                                                        playingVoiceId == item.id -> "停止"
                                                        else -> "试听"
                                                    }
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onUpdateSetting(
                                                        setting.copy(
                                                            voiceName = item.id,
                                                            voiceOwnerKeyHint = "",
                                                            voiceIdsByKeyHint = emptyMap(),
                                                        )
                                                    )
                                                    statusMessage = "已设为主音色: ${item.id}"
                                                }
                                            ) {
                                                Text("设为主音色")
                                            }
                                            Button(
                                                onClick = {
                                                    onUpdateSetting(
                                                        setting.copy(fallbackVoiceName = item.id)
                                                    )
                                                    statusMessage = "已设为相似备选音色: ${item.id}"
                                                }
                                            ) {
                                                Text("设为相似备选")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
