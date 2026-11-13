package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.ScanText
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.resolveDecisionModel
import me.rerere.rikkahub.data.ai.resolveEmbeddingModel
import me.rerere.rikkahub.data.datastore.AUTO_COMPRESS_THRESHOLD_RANGE
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ModelListState
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.onboarding.OnboardingGuideOverlay
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

private enum class ModelSettingTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    MODEL(HugeIcons.AiBrain01, R.string.setting_model_page_tab_model),
    PROMPT(HugeIcons.AiEditing, R.string.setting_model_page_tab_prompt);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { ModelSettingTab.entries.size }
    val navController = LocalNavController.current
    var showGuideOverlay by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = CustomColors.topBarColors.containerColor,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.setting_model_page_title)) },
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = { showGuideOverlay = true }) {
                            Icon(
                                imageVector = HugeIcons.Idea01,
                                contentDescription = "3 模型配置指引",
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors,
                )
            },
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                PagerTabs(
                    pagerState = pagerState,
                    tabs = ModelSettingTab.entries,
                    modifier = Modifier.padding(bottom = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                )

                // 导航条的位置留在列表的内边距里，内容可以滚到它下面
                val contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    when (ModelSettingTab.entries[page]) {
                        ModelSettingTab.MODEL -> ModelSettingsPage(
                            settings = settings,
                            vm = vm,
                            contentPadding = contentPadding,
                            onReplayGuide = { showGuideOverlay = true },
                        )

                        ModelSettingTab.PROMPT -> PromptSettingsPage(
                            settings = settings,
                            vm = vm,
                            contentPadding = contentPadding,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showGuideOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            OnboardingGuideOverlay(
                onFinish = { showGuideOverlay = false },
                onGoToProviders = {
                    showGuideOverlay = false
                    navController.navigate(Screen.SettingProvider)
                },
            )
        }
    }
}

@Composable
private fun ModelSettingsPage(
    settings: Settings,
    vm: SettingVM,
    contentPadding: PaddingValues,
    onReplayGuide: () -> Unit,
) {
    val chatModel = rememberModelListState(
        modelId = settings.chatModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val embeddingModel = rememberModelListState(
        modelId = settings.vectorStorageModelId,
        providers = settings.providers,
        type = ModelType.EMBEDDING,
    )
    val decisionModel = rememberModelListState(
        modelId = settings.vectorDecisionModelId,
        providers = settings.providers,
        type = ModelType.DECISION,
        allowedTypes = setOf(ModelType.DECISION, ModelType.CHAT),
    )
    val fastModel = rememberModelListState(
        modelId = settings.fastModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val memoryModel = rememberModelListState(
        modelId = settings.memoryModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val translateModel = rememberModelListState(
        modelId = settings.translateModeId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    val ocrModel = rememberModelListState(
        modelId = settings.ocrModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    var showUtilityModels by rememberSaveable { mutableStateOf(false) }

    val resolvedEmbedding = remember(settings) { settings.resolveEmbeddingModel()?.second }
    val resolvedDecision = remember(settings) { settings.resolveDecisionModel()?.second }

    val effectiveEmbeddingName = embeddingModel.currentModel?.displayName
        ?: resolvedEmbedding?.displayName?.let { "自动: $it" }
        ?: if (settings.vectorStorageEnabled) "词法兜底" else null

    val effectiveDecisionName = decisionModel.currentModel?.displayName
        ?: resolvedDecision?.displayName?.let { "自动: $it" }

    val coreReadyCount = listOf(
        chatModel.currentModel != null,
        effectiveEmbeddingName != null,
        effectiveDecisionName != null,
    ).count { it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 顶部 3 模型协同总览仪表盘
        item(key = "core_overview") {
            CoreEngineOverviewCard(
                readyCount = coreReadyCount,
                chatModelName = chatModel.currentModel?.displayName,
                embeddingModelName = effectiveEmbeddingName,
                decisionModelName = effectiveDecisionName,
                onOpenGuide = onReplayGuide,
            )
        }

        // 01 主对话模型：灵魂与逻辑核心
        item(key = "core_chat") {
            val containerColor = MaterialTheme.colorScheme.primaryContainer
            val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            val chatModelTitle = stringResource(R.string.setting_model_page_chat_model)
            CardGroup(
                title = {
                    CoreSectionHeader(
                        badge = "01 · 叙事与推理",
                        title = "主对话模型",
                        badgeContainerColor = containerColor,
                        badgeContentColor = contentColor,
                    )
                },
            ) {
                modelItem(
                    icon = HugeIcons.BubbleChat,
                    title = chatModelTitle,
                    description = "唯一主力大脑，负责沉浸角色扮演、文风输出与轻量 Agent 工具调度",
                    state = chatModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                )
            }
        }

        // 02 向量嵌入模型：一阶段广域海选
        item(key = "core_embedding") {
            val containerColor = MaterialTheme.colorScheme.secondaryContainer
            val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            CardGroup(
                title = {
                    CoreSectionHeader(
                        badge = "02 · Stage 1 海选",
                        title = "向量嵌入模型",
                        badgeContainerColor = containerColor,
                        badgeContentColor = contentColor,
                    )
                },
            ) {
                switchItem(
                    checked = settings.vectorStorageEnabled,
                    onCheckedChange = { v -> vm.updateSettings { it.copy(vectorStorageEnabled = v) } },
                    leadingContent = {
                        CardGroupIcon(
                            HugeIcons.Database02,
                            containerColor = containerColor,
                            contentColor = contentColor,
                        )
                    },
                    headlineContent = { Text("启用尾部记忆向量检索") },
                    supportingContent = {
                        Text("把 topics 档案与溢出早期对话切片编码；仅在最后一条 User 前尾部注入，100% 死保头部前缀缓存")
                    },
                )
                if (settings.vectorStorageEnabled) {
                    modelItem(
                        icon = HugeIcons.Database02,
                        title = "嵌入模型 (Embedding)",
                        description = "负责一阶段语义粗召回并持久化本地 VEC1 缓存；留空自动匹配可用嵌入模型或走本地词法兜底",
                        state = embeddingModel,
                        iconContainerColor = containerColor,
                        iconContentColor = contentColor,
                        placeholder = resolvedEmbedding?.displayName?.let { "自动匹配：$it" }
                            ?: "自动匹配（无嵌入模型时走词法兜底）",
                        onClear = { vm.updateSettings { latest -> latest.copy(vectorStorageModelId = null) } },
                    )
                }
            }
        }

        // 03 决策重排模型：二阶段裁决过滤
        item(key = "core_decision") {
            val containerColor = MaterialTheme.colorScheme.tertiaryContainer
            val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            CardGroup(
                title = {
                    CoreSectionHeader(
                        badge = "03 · Stage 2 裁决",
                        title = "决策重排模型",
                        badgeContainerColor = containerColor,
                        badgeContentColor = contentColor,
                    )
                },
            ) {
                modelItem(
                    icon = HugeIcons.Sparkles,
                    title = "决策重排模型 (Decision / Rerank)",
                    description = "对一阶段捞出的候选记忆结合语境精准裁决过滤误召回（推荐 gemini-decision-1 或轻量快速模型）",
                    state = decisionModel,
                    iconContainerColor = containerColor,
                    iconContentColor = contentColor,
                    placeholder = resolvedDecision?.displayName?.let { "自动启用：$it" }
                        ?: "自动匹配 Decision 模型（无则跳过重排）",
                    onClear = { vm.updateSettings { latest -> latest.copy(vectorDecisionModelId = null) } },
                )
                if (settings.vectorStorageEnabled) {
                    formItem(
                        headlineContent = { Text("语义相似度阈值") },
                        trailingContent = { CardGroupValue("${(settings.vectorStorageThreshold * 100).toInt()}%") },
                    ) {
                        Slider(
                            value = settings.vectorStorageThreshold,
                            onValueChange = { v -> vm.updateSettings { s -> s.copy(vectorStorageThreshold = v) } },
                            valueRange = 0.1f..0.8f,
                            steps = 13,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    formItem(
                        headlineContent = { Text("检索扫描深度") },
                        supportingContent = { Text("取最近几条非系统消息拼接为本轮检索 Query") },
                        trailingContent = { CardGroupValue("最近 ${settings.vectorStorageScanDepth} 条") },
                    ) {
                        Slider(
                            value = settings.vectorStorageScanDepth.toFloat().coerceIn(1f, 8f),
                            onValueChange = { v ->
                                vm.updateSettings { s -> s.copy(vectorStorageScanDepth = v.roundToInt().coerceIn(1, 8)) }
                            },
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 长对话上下文自动压缩（从通用设置迁入，配合尾召回防止上下文溢出）
        item(key = "context_compress") {
            CardGroup(
                title = { Text("长对话上下文保护") },
            ) {
                switchItem(
                    checked = settings.enableAutoCompress,
                    onCheckedChange = {
                        vm.updateSettings { latest -> latest.copy(enableAutoCompress = it) }
                    },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_auto_compress_desc)) },
                    headlineContent = { Text(stringResource(R.string.setting_display_page_auto_compress_title)) },
                )
                if (settings.enableAutoCompress) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_compress_threshold_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_compress_threshold_desc)) },
                        trailingContent = {
                            CardGroupValue("${(settings.autoCompressThreshold * 100).roundToInt()}%")
                        },
                    ) {
                        Slider(
                            value = settings.autoCompressThreshold,
                            onValueChange = {
                                val value = (it * 20).roundToInt() / 20f
                                vm.updateSettings { latest -> latest.copy(autoCompressThreshold = value) }
                            },
                            valueRange = AUTO_COMPRESS_THRESHOLD_RANGE,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 默认折叠的辅助功能模型（快速助手 / 独立做梦记忆 / 翻译 / OCR）
        item(key = "utility_toggle") {
            Surface(
                onClick = { showUtilityModels = !showUtilityModels },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CardGroupIcon(
                        icon = HugeIcons.Settings03,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "辅助功能模型与开关（可选）",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                        )
                        Text(
                            text = "快速辅助（自动标题 / 建议）· 记忆整理 · 翻译 · OCR",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (showUtilityModels) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "utility_panel") {
            AnimatedVisibility(
                visible = showUtilityModels,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val fastBg = MaterialTheme.colorScheme.tertiaryContainer
                    val fastFg = MaterialTheme.colorScheme.onTertiaryContainer
                    val fastModelTitle = stringResource(R.string.setting_model_page_fast_model)
                    val fastModelDesc = stringResource(R.string.setting_model_page_fast_model_desc)
                    val memoryModelTitle = stringResource(R.string.setting_model_page_memory_model)
                    val memoryModelDesc = stringResource(R.string.setting_model_page_memory_model_desc)
                    val memoryModelPlaceholder = stringResource(R.string.setting_model_page_memory_model_follow_fast)
                    CardGroup(
                        title = { Text("后台辅助与做梦整理") },
                    ) {
                        modelItem(
                            icon = HugeIcons.Zap,
                            title = fastModelTitle,
                            description = fastModelDesc,
                            state = fastModel,
                            iconContainerColor = fastBg,
                            iconContentColor = fastFg,
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                            trailingContent = {
                                ReasoningButton(
                                    reasoningLevel = settings.fastModelReasoningLevel,
                                    onUpdateReasoningLevel = {
                                        vm.updateSettings { latest -> latest.copy(fastModelReasoningLevel = it) }
                                    },
                                )
                            },
                        )
                        switchItem(
                            checked = settings.enableAutoTitle,
                            onCheckedChange = {
                                vm.updateSettings { latest -> latest.copy(enableAutoTitle = it) }
                            },
                            headlineContent = { Text(stringResource(R.string.setting_model_page_enable_auto_title)) },
                        )
                        switchItem(
                            checked = settings.enableSuggestion,
                            onCheckedChange = {
                                vm.updateSettings { latest -> latest.copy(enableSuggestion = it) }
                            },
                            headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
                        )
                        modelItem(
                            icon = HugeIcons.Brain02,
                            title = memoryModelTitle,
                            description = memoryModelDesc,
                            state = memoryModel,
                            iconContainerColor = fastBg,
                            iconContentColor = fastFg,
                            placeholder = memoryModelPlaceholder,
                            onClear = { vm.updateSettings { latest -> latest.copy(memoryModelId = null) } },
                        )
                        if (memoryModel.currentModel != null) {
                            item(
                                headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                                trailingContent = {
                                    ReasoningButton(
                                        reasoningLevel = settings.memoryModelReasoningLevel,
                                        onUpdateReasoningLevel = {
                                            vm.updateSettings { latest -> latest.copy(memoryModelReasoningLevel = it) }
                                        },
                                    )
                                },
                            )
                        }
                    }

                    val toolBg = MaterialTheme.colorScheme.secondaryContainer
                    val toolFg = MaterialTheme.colorScheme.onSecondaryContainer
                    val translateModelTitle = stringResource(R.string.setting_model_page_translate_model)
                    val translateModelDesc = stringResource(R.string.setting_model_page_translate_model_desc)
                    val ocrModelTitle = stringResource(R.string.setting_model_page_ocr_model)
                    val ocrModelDesc = stringResource(R.string.setting_model_page_ocr_model_desc)
                    CardGroup(
                        title = { Text("翻译与识图专项") },
                    ) {
                        modelItem(
                            icon = HugeIcons.Translate,
                            title = translateModelTitle,
                            description = translateModelDesc,
                            state = translateModel,
                            iconContainerColor = toolBg,
                            iconContentColor = toolFg,
                        )
                        modelItem(
                            icon = HugeIcons.ScanText,
                            title = ocrModelTitle,
                            description = ocrModelDesc,
                            state = ocrModel,
                            iconContainerColor = toolBg,
                            iconContentColor = toolFg,
                        )
                    }
                }
            }
        }
    }

    ModelListSheet(state = chatModel) {
        vm.updateSettings { latest -> latest.copy(chatModelId = it.id) }
    }
    ModelListSheet(state = embeddingModel) {
        vm.updateSettings { latest -> latest.copy(vectorStorageModelId = it.id) }
    }
    ModelListSheet(state = decisionModel) {
        vm.updateSettings { latest -> latest.copy(vectorDecisionModelId = it.id) }
    }
    ModelListSheet(state = fastModel) {
        vm.updateSettings { latest -> latest.copy(fastModelId = it.id) }
    }
    ModelListSheet(state = memoryModel) {
        vm.updateSettings { latest -> latest.copy(memoryModelId = it.id) }
    }
    ModelListSheet(state = translateModel) {
        vm.updateSettings { latest -> latest.copy(translateModeId = it.id) }
    }
    ModelListSheet(state = ocrModel) {
        vm.updateSettings { latest -> latest.copy(ocrModelId = it.id) }
    }
}

@Composable
private fun CoreEngineOverviewCard(
    readyCount: Int,
    chatModelName: String?,
    embeddingModelName: String?,
    decisionModelName: String?,
    onOpenGuide: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "三模型协作管线 · 只需配好这 3 个",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = "主对话演绎 × 嵌入粗召回 × 决策精排保缓存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    onClick = onOpenGuide,
                    shape = CircleShape,
                    color = if (readyCount == 3) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Text(
                        text = "$readyCount / 3 就绪",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CoreSlotTile(
                    index = "01 对话",
                    value = chatModelName ?: "待选择",
                    ready = chatModelName != null,
                    modifier = Modifier.weight(1f),
                )
                CoreSlotTile(
                    index = "02 嵌入",
                    value = embeddingModelName ?: "未开启",
                    ready = embeddingModelName != null,
                    modifier = Modifier.weight(1f),
                )
                CoreSlotTile(
                    index = "03 决策",
                    value = decisionModelName ?: "未激活",
                    ready = decisionModelName != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CoreSlotTile(
    index: String,
    value: String,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (ready) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = index,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                if (ready) {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (ready) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun CoreSectionHeader(
    badge: String,
    title: String,
    badgeContainerColor: Color,
    badgeContentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = badgeContainerColor,
            contentColor = badgeContentColor,
        ) {
            Text(
                text = badge,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(title)
    }
}

// 一项模型设置：图标、用途说明，下面是显示当前模型的选择按钮
private fun CardGroupScope.modelItem(
    icon: ImageVector,
    title: String,
    description: String,
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
    placeholder: String? = null,
    onClear: (() -> Unit)? = null,
) = formItem(
    headlineContent = { Text(title) },
    supportingContent = { Text(description) },
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
        placeholder = placeholder ?: stringResource(R.string.model_list_select_model),
        onClear = onClear,
    )
}
