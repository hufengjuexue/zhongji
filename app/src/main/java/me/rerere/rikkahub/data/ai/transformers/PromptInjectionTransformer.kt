package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入，
 * 并按用户轮冻结激活集合与尾部注入锚点，保证 agentic 工具循环期间前缀缓存稳定。
 */
object PromptInjectionTransformer : InputMessageTransformer {
    private val stickyTracker = ConcurrentHashMap<String, MutableMap<Uuid, Int>>()
    private val cooldownTracker = ConcurrentHashMap<String, MutableMap<Uuid, Int>>()
    private val lastTickedUserTurn = ConcurrentHashMap<String, String>()
    private val frozenTurnInjections = ConcurrentHashMap<String, Triple<String, Int, FrozenTurnState>>()
    private const val MAX_FROZEN_TURNS = 64

    private class FrozenTurnState(
        val injections: List<PromptInjection>,
        val anchors: ConcurrentHashMap<String, String>,
    )

    private fun injectionFingerprint(ctx: TransformerContext): Int {
        var h = ctx.assistant.modeInjectionIds.hashCode()
        h = 31 * h + ctx.assistant.lorebookIds.hashCode()
        for (id in ctx.assistant.modeInjectionIds) {
            ctx.settings.modeInjections.firstOrNull { it.id == id }?.let { h = 31 * h + it.hashCode() }
        }
        for (id in ctx.assistant.lorebookIds) {
            ctx.settings.lorebooks.firstOrNull { it.id == id }?.let { h = 31 * h + it.hashCode() }
        }
        return h
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val convKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
        val fingerprint = injectionFingerprint(ctx)
        val cached = lastUserMsgId?.let { id ->
            frozenTurnInjections[convKey]?.takeIf { it.first == id && it.second == fingerprint }?.third
        }
        val alreadyTicked = lastUserMsgId != null && lastTickedUserTurn[convKey] == lastUserMsgId
        if (lastUserMsgId != null && !alreadyTicked) {
            lastTickedUserTurn[convKey] = lastUserMsgId
        }
        val stickyMap = stickyTracker.getOrPut(convKey) { ConcurrentHashMap() }
        val cooldownMap = cooldownTracker.getOrPut(convKey) { ConcurrentHashMap() }
        val anchors = cached?.anchors ?: ConcurrentHashMap()
        var resolvedForTurn: List<PromptInjection>? = null

        val result = transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            activeStickyEntries = stickyMap,
            cooldownEntries = cooldownMap,
            tickState = !alreadyTicked,
            frozenInjections = cached?.injections,
            anchors = anchors,
            onResolved = { resolvedForTurn = it },
        )

        if (cached == null && lastUserMsgId != null) {
            if (frozenTurnInjections.size >= MAX_FROZEN_TURNS) frozenTurnInjections.clear()
            frozenTurnInjections[convKey] = Triple(
                lastUserMsgId,
                fingerprint,
                FrozenTurnState(resolvedForTurn ?: emptyList(), anchors),
            )
        }
        return result
    }
}

/**
 * 核心注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    activeStickyEntries: MutableMap<Uuid, Int> = mutableMapOf(),
    cooldownEntries: MutableMap<Uuid, Int> = mutableMapOf(),
    tickState: Boolean = true,
    frozenInjections: List<PromptInjection>? = null,
    anchors: ConcurrentHashMap<String, String>? = null,
    onResolved: ((List<PromptInjection>) -> Unit)? = null,
): List<UIMessage> {
    val injections = frozenInjections ?: collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        activeStickyEntries = activeStickyEntries,
        cooldownEntries = cooldownEntries,
    )

    if (injections.isEmpty()) {
        if (tickState) {
            tickStickyAndCooldown(activeStickyEntries, cooldownEntries, emptyList())
        }
        onResolved?.invoke(emptyList())
        return messages
    }

    onResolved?.invoke(injections)

    // 按位置和优先级分组（priority 升序：小优先级先写入，大优先级靠后靠近最新上下文）
    val byPosition = injections
        .sortedBy { it.priority }
        .groupBy { it.position }

    val result = applyInjections(messages, byPosition, anchors)

    if (tickState) {
        tickStickyAndCooldown(
            activeStickyEntries,
            cooldownEntries,
            injections.filterIsInstance<PromptInjection.RegexInjection>(),
        )
    }

    return result
}

private fun tickStickyAndCooldown(
    stickyMap: MutableMap<Uuid, Int>,
    cooldownMap: MutableMap<Uuid, Int>,
    activated: List<PromptInjection.RegexInjection>,
) {
    // 先递减已有冷却与粘性
    val cdIter = cooldownMap.entries.iterator()
    while (cdIter.hasNext()) {
        val e = cdIter.next()
        if (e.value <= 1) cdIter.remove() else e.setValue(e.value - 1)
    }
    val stIter = stickyMap.entries.iterator()
    while (stIter.hasNext()) {
        val e = stIter.next()
        if (e.value <= 1) stIter.remove() else e.setValue(e.value - 1)
    }
    for (entry in activated) {
        if (entry.sticky > 0 && !stickyMap.containsKey(entry.id)) {
            stickyMap[entry.id] = entry.sticky
        }
        if (entry.cooldown > 0 && !stickyMap.containsKey(entry.id)) {
            cooldownMap[entry.id] = entry.cooldown
        }
    }
}

/**
 * 收集需要注入的内容
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    activeStickyEntries: Map<Uuid, Int> = emptyMap(),
    cooldownEntries: Map<Uuid, Int> = emptyMap(),
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()

    // 1. 获取关联的 ModeInjection
    modeInjections
        .filter { it.enabled && assistant.modeInjectionIds.contains(it.id) }
        .forEach { injections.add(it) }

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection
    val enabledLorebooks = lorebooks.filter {
        it.enabled && assistant.lorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isNotEmpty()) {
        val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }

        enabledLorebooks.forEach { lorebook ->
            lorebook.entries
                .filter { entry ->
                    if (!entry.enabled) return@filter false
                    if (entry.delay > 0 && nonSystemMessages.size < entry.delay) return@filter false
                    if (cooldownEntries.containsKey(entry.id) && !activeStickyEntries.containsKey(entry.id)) {
                        return@filter false
                    }
                    if (entry.constantActive || activeStickyEntries.containsKey(entry.id)) {
                        return@filter true
                    }
                    val context = extractContextForMatching(nonSystemMessages, entry.scanDepth.coerceAtLeast(1))
                    entry.isTriggered(context)
                }
                .forEach { injections.add(it) }
        }
    }

    return injections
}

/**
 * 应用注入到消息列表
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>,
    anchors: ConcurrentHashMap<String, String>? = null,
): List<UIMessage> {
    val result = messages.toMutableList()

    fun resolveTailIndex(computedIndex: Int, anchorKey: String): Int {
        if (anchors == null) return computedIndex
        val existingAnchorId = anchors[anchorKey]
        if (existingAnchorId != null) {
            val anchoredIdx = result.indexOfFirst { it.id.toString() == existingAnchorId }
            if (anchoredIdx >= 0) return anchoredIdx
            return result.size
        }
        val anchorId = result.getOrNull(computedIndex)?.id?.toString()
        if (anchorId != null) {
            anchors[anchorKey] = anchorId
        }
        return computedIndex
    }

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(newText)),
                isSynthetic = true,
            )
        }
    } else {
        // 没有系统消息时，创建一个新的系统消息
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
            }
            if (afterContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            result.add(0, UIMessage.system(combinedContent).copy(isSynthetic = true))
        }
    }

    // 处理 TOP_OF_CHAT：在第一条用户消息之前插入
    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        // 重新计算索引（因为可能插入了系统消息）
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(topInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 BOTTOM_OF_CHAT：在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = resolveTailIndex((result.size - 1).coerceAtLeast(0), "bottom_of_chat")
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            val safeDepth = depth.coerceAtLeast(1)
            var insertIndex = resolveTailIndex(
                (result.size - safeDepth).coerceIn(0, result.size),
                "at_depth_$safeDepth",
            )
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    return result
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                MessageRole.SYSTEM -> UIMessage.system(mergedContent)
                else -> UIMessage.user(mergedContent)
            }.copy(
                isSynthetic = true,
            )
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
