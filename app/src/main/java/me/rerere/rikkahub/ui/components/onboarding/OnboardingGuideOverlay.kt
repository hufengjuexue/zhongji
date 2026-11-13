package me.rerere.rikkahub.ui.components.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.BubbleChat
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R

private val ObsidianBackdrop = Color(0xFF0B0C12)
private val SingularityGold = Color(0xFFF3CE84)

/**
 * 冷启动高定矢量开屏过渡遮罩：与系统 SplashScreen 底色一致，
 * 用纯矢量光环 + 渐显字标平滑承接至主界面或新手引导。
 */
@Composable
fun KairosSplashOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        revealed = true
    }

    val logoScale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.88f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "splash_logo_scale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.25f,
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
        label = "splash_glow_alpha",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(360)) + scaleOut(targetScale = 1.03f, animationSpec = tween(360)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBackdrop),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(280.dp)
                    .graphicsLayer { alpha = glowAlpha }
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SingularityGold.copy(alpha = 0.20f),
                            SingularityGold.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * 0.5f,
                    )
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_splash_kairos),
                    contentDescription = "Kairos",
                    modifier = Modifier
                        .size(164.dp)
                        .graphicsLayer {
                            scaleX = logoScale
                            scaleY = logoScale
                        },
                )

                AnimatedVisibility(
                    visible = revealed,
                    enter = fadeIn(tween(420, delayMillis = 120)) +
                        slideInVertically(tween(420, delayMillis = 120)) { it / 3 },
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "K A I R O S",
                            color = Color(0xFFF6F1E6),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 6.sp,
                        )
                        Text(
                            text = "恰逢其时的记忆 · 只需 3 个模型",
                            color = Color(0xFFA7ACB9),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private data class OnboardingSlide(
    val tag: String,
    val title: String,
    val subtitle: String,
    val description: String,
)

private val OnboardingSlides = listOf(
    OnboardingSlide(
        tag = "01 / 05 · 现代化 RP 架构",
        title = "只需 3 个模型\n剩下的交给我",
        subtitle = "Kairos —— 让对的记忆在恰逢其时的这一轮浮现",
        description = "告别繁琐插件、采样器玄学与上万字八股文。你只需要选好「主对话 · 向量嵌入 · 决策重排」这 3 个分工模型，前缀缓存保护、尾部记忆检索与关系图谱全由 Kairos 自动驱动。",
    ),
    OnboardingSlide(
        tag = "02 / 05 · 核心模型 01",
        title = "主对话模型\n撑起逻辑与文风上限",
        subtitle = "Chat Model · 唯一主力大脑",
        description = "体验 = 逻辑底座 × 语言颗粒度。把最聪明的旗舰模型只留给主对话轮次，专心演绎人物口吻、情绪张力与轻量 Agent 工具调用，不被检索杂活分散注意力。",
    ),
    OnboardingSlide(
        tag = "03 / 05 · 核心模型 02",
        title = "向量嵌入模型\n负责一阶段广域海选",
        subtitle = "Embedding Model · 长期档案 + 早期历史切片",
        description = "在云端把设定集（topics）与溢出窗口的早年对话编码并持久化本地 `VEC1` 缓存。每一轮先毫秒级算出语义余弦相似度，捞出一阶段候选记忆池（未配时也自动降级词法召回）。",
    ),
    OnboardingSlide(
        tag = "04 / 05 · 核心模型 03",
        title = "决策重排模型\n二阶段精准裁决官",
        subtitle = "Decision / Rerank · 保准度更死保前缀缓存",
        description = "推荐 `gemini-decision-1`（单前向打分）或轻量快速模型：结合当前语境把一阶段候选池过滤精排，只将最切题的几段从尾部注入，100% 死保头部 Prompt Caching 不被击穿。",
    ),
    OnboardingSlide(
        tag = "05 / 05 · 一切就绪",
        title = "两步配齐\n剩下的交给 Kairos",
        subtitle = "其余标题、翻译、OCR 均归入可选辅助",
        description = "先进「提供商」填入 API Key，再去「模型设置」配好这 3 个核心角色即可开聊。专注沉浸 RP 的同时，内置的 MCP、Skills 与工作区工具也能随时胜任轻量 Agent 任务。",
    ),
)

/**
 * 首次启动「PPT 式点击切换动画」新手指引浮层。
 * 点击中间演示卡片或底部按钮可逐页推进动画，支持左右滑动与随时跳过。
 */
@Composable
fun OnboardingGuideOverlay(
    onFinish: () -> Unit,
    onGoToProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { OnboardingSlides.size })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage == OnboardingSlides.lastIndex

    // 每切换一页，驱动该页内部的阶梯式 PPT 元素入场动画
    var activeBeat by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentPage) {
        activeBeat = 0
        delay(120)
        activeBeat = 1
        delay(180)
        activeBeat = 2
        delay(180)
        activeBeat = 3
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 顶部品牌标 + 步进胶囊指示器 + 跳过按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_splash_kairos),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                    )
                    Text(
                        text = "Kairos 指南",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(OnboardingSlides.size) { index ->
                        val selected = index == currentPage
                        val width by animateDpAsState(
                            targetValue = if (selected) 24.dp else 7.dp,
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            label = "dot_width_$index",
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            label = "dot_color_$index",
                        )
                        Box(
                            modifier = Modifier
                                .height(7.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(dotColor)
                                .clickable {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                        )
                    }
                }

                TextButton(onClick = onFinish) {
                    Text(if (isLastPage) "关闭" else "跳过")
                }
            }

            // 中央 PPT 幻灯片舞台（支持左右滑动 + 点击卡片直接切换下一页）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                val slide = OnboardingSlides[page]
                val isCurrent = page == currentPage
                val stageBeat = if (isCurrent) activeBeat else 3

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    // 动态可点击演示舞台卡片（点击直接推进到下一张 PPT）
                    Surface(
                        onClick = {
                            if (!isLastPage) {
                                scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                            } else {
                                onGoToProviders()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text(
                                        text = slide.tag,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (!isLastPage) {
                                    Text(
                                        text = "轻触画面切换 →",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            AnimatedContent(
                                targetState = page,
                                transitionSpec = {
                                    (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 8 } + scaleIn(
                                        initialScale = 0.96f,
                                        animationSpec = tween(260),
                                    )) togetherWith (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 8 })
                                },
                                label = "ppt_stage_content",
                            ) { animPage ->
                                PptDiagramStage(
                                    page = animPage,
                                    beat = stageBeat,
                                )
                            }
                        }
                    }

                    // 标题与精炼说明区
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = slide.title,
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = slide.subtitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = slide.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp,
                        )
                    }
                }
            }

            // 底部控制栏
            if (isLastPage) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onGoToProviders,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Sparkles,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("去添加提供商并配置 3 个模型")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
                            },
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                        ) {
                            Icon(HugeIcons.ArrowLeft01, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("上一页")
                        }
                        TextButton(
                            onClick = onFinish,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("先逛逛聊天页")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage((currentPage - 1).coerceAtLeast(0)) }
                        },
                        enabled = currentPage > 0,
                        shape = CircleShape,
                    ) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("上一页")
                    }

                    Button(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                        },
                        shape = CircleShape,
                    ) {
                        Text("下一步")
                        Spacer(Modifier.width(6.dp))
                        Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PptDiagramStage(
    page: Int,
    beat: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (page) {
            0 -> {
                PptRoleRow(
                    index = "01",
                    icon = HugeIcons.BubbleChat,
                    title = "主对话模型 (Chat)",
                    detail = "逻辑底座与语言颗粒度上限 · 兼轻量 Agent",
                    highlighted = beat >= 1,
                    accent = MaterialTheme.colorScheme.primary,
                )
                PptRoleRow(
                    index = "02",
                    icon = HugeIcons.Database02,
                    title = "向量嵌入模型 (Embedding)",
                    detail = "一阶段广域海选 · 档案与历史语义检索",
                    highlighted = beat >= 2,
                    accent = MaterialTheme.colorScheme.secondary,
                )
                PptRoleRow(
                    index = "03",
                    icon = HugeIcons.Sparkles,
                    title = "决策重排模型 (Decision)",
                    detail = "二阶段语境精排 · 滤除噪音并死保前缀缓存",
                    highlighted = beat >= 3,
                    accent = MaterialTheme.colorScheme.tertiary,
                )
            }

            1 -> {
                PptRoleRow(
                    index = "01",
                    icon = HugeIcons.BubbleChat,
                    title = "主对话模型 · 灵魂与逻辑核心",
                    detail = "Claude / Gemini / DeepSeek 等主力大模型",
                    highlighted = true,
                    accent = MaterialTheme.colorScheme.primary,
                )
                AnimatedVisibility(
                    visible = beat >= 1,
                    enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 4 },
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Idea01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "把高智商窗口完全留给叙事演绎、角色扮演与 MCP/工作区工具调用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PptMiniPill(text = "02 Embedding · 一阶段粗召回", modifier = Modifier.weight(1f), active = false)
                    PptMiniPill(text = "03 Decision · 二阶段精排", modifier = Modifier.weight(1f), active = false)
                }
            }

            2 -> {
                PptRoleRow(
                    index = "02",
                    icon = HugeIcons.Database02,
                    title = "向量嵌入模型 · Stage 1 海选",
                    detail = "topics 档案 + 溢出窗口早年历史 → VEC1 缓存",
                    highlighted = true,
                    accent = MaterialTheme.colorScheme.secondary,
                )
                AnimatedVisibility(
                    visible = beat >= 1,
                    enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 4 },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PptMiniPill(text = "记忆档案切片", modifier = Modifier.weight(1f), active = beat >= 1)
                        PptMiniPill(text = "早期对话兜底", modifier = Modifier.weight(1f), active = beat >= 2)
                        PptMiniPill(text = "本地毫秒召回", modifier = Modifier.weight(1f), active = beat >= 3)
                    }
                }
                Text(
                    text = "无嵌入模型时会自动走本地词法相关性兜底，配好云端嵌入后召回质量质变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            3 -> {
                PptRoleRow(
                    index = "03",
                    icon = HugeIcons.Sparkles,
                    title = "决策重排模型 · Stage 2 裁决 + 缓存护盾",
                    detail = "推荐 gemini-decision-1（单前向打分）或轻量模型",
                    highlighted = true,
                    accent = MaterialTheme.colorScheme.tertiary,
                )
                AnimatedVisibility(
                    visible = beat >= 1,
                    enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 4 },
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PptMiniPill(
                                text = "1. 头部设定 & profile.md 🔒 字节级不变 · 前缀缓存 100% 命中",
                                modifier = Modifier.fillMaxWidth(),
                                active = beat >= 1,
                            )
                            PptMiniPill(
                                text = "2. Decision 剔除伪相关记忆 → 仅将 Top-6 从尾部注入 ✨",
                                modifier = Modifier.fillMaxWidth(),
                                active = beat >= 2,
                            )
                            PptMiniPill(
                                text = "3. 你的最新输入 💬 逻辑不断档 · 长叙事极其省钱",
                                modifier = Modifier.fillMaxWidth(),
                                active = beat >= 3,
                            )
                        }
                    }
                }
            }

            else -> {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Idea01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "上手路线 · 只需两步",
                                style = MaterialTheme.typography.titleSmallEmphasized,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        PptCheckStep(number = "1", text = "打开「提供商」填入 API Key", visible = beat >= 1)
                        PptCheckStep(number = "2", text = "在「模型设置」选好 01 对话 · 02 嵌入 · 03 决策重排", visible = beat >= 2)
                        PptCheckStep(number = "✓", text = "专注沉浸 RP，同时内置 MCP / Skills / 工作区轻量 Agent", visible = beat >= 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun PptRoleRow(
    index: String,
    icon: ImageVector,
    title: String,
    detail: String,
    highlighted: Boolean,
    accent: Color,
) {
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) {
            accent.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "ppt_row_bg_$index",
    )
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) accent.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "ppt_row_border_$index",
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = if (highlighted) accent else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = index,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (highlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PptMiniPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mini_pill_bg",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun PptCheckStep(
    number: String,
    text: String,
    visible: Boolean,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240)) + slideInHorizontally(tween(240)) { -it / 6 },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (number == "✓") {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 累计启动满 10 次后弹出一次的 GitHub Star 邀请框（只打扰一次，关闭或跳转即永久记住）。
 */
@Composable
fun GithubStarDialog(
    launchCount: Int,
    onStar: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Github,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        title = {
            Text(
                text = "第 $launchCount 次打开 Kairos 了",
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Text(
                text = "如果 Kairos 的现代化 RP 理念与三模型协作记忆系统合你胃口，欢迎去 GitHub 给仓库点一颗免费的 Star！",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onStar,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("去点个 Star")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("下次再说")
            }
        },
    )
}
