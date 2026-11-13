package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.PersonaInjectionPosition
import java.util.concurrent.ConcurrentHashMap

/**
 * 玩家人设 (Persona) 注入转换器：
 * - IN_PROMPT 合并进头部系统区
 * - TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH 使用按用户轮次冻结的锚点，确保 agentic 工具循环不断 KV 缓存
 */
object PersonaInjectionTransformer : InputMessageTransformer {
    private val frozenAnchors = ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val persona = ctx.settings.resolveActivePersona(ctx.assistant.id) ?: return messages
        val desc = persona.description.trim()
        if (desc.isEmpty() || persona.position == PersonaInjectionPosition.NONE) return messages

        val block = if (persona.name.isNotBlank()) {
            "[User Persona: ${persona.name}]\n$desc"
        } else {
            "[User Persona]\n$desc"
        }

        if (persona.position == PersonaInjectionPosition.IN_PROMPT) {
            val working = messages.toMutableList()
            val sysIdx = working.indexOfFirst { it.role == MessageRole.SYSTEM }
            val sysText = working.getOrNull(sysIdx)?.toText().orEmpty()
            val merged = if (sysText.isNotBlank()) "$sysText\n\n$block" else block
            val sysMsg = UIMessage.system(merged).copy(isSynthetic = true)
            if (sysIdx >= 0) working[sysIdx] = sysMsg else working.add(0, sysMsg)
            return working
        }

        val working = messages.toMutableList()
        val firstNonSystemIndex = working.indexOfFirst { it.role != MessageRole.SYSTEM }.let {
            if (it == -1) working.size else it
        }
        val msg = UIMessage(
            role = persona.role,
            parts = listOf(UIMessagePart.Text(block)),
        ).copy(isSynthetic = true)

        val insertIdx = when (persona.position) {
            PersonaInjectionPosition.TOP_OF_CHAT -> firstNonSystemIndex
            PersonaInjectionPosition.BOTTOM_OF_CHAT,
            PersonaInjectionPosition.AT_DEPTH -> {
                val depth = if (persona.position == PersonaInjectionPosition.BOTTOM_OF_CHAT) {
                    0
                } else {
                    persona.depth.coerceAtLeast(0)
                }
                val lastUserMsgId = working.lastOrNull { it.role == MessageRole.USER && !it.isSynthetic }?.id?.toString()
                val cacheKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conv"}:persona:${persona.id}:$depth"
                resolveFrozenInsertionIndex(
                    cacheKey = cacheKey,
                    lastUserMsgId = lastUserMsgId,
                    minIndex = firstNonSystemIndex,
                    messages = working,
                    depth = depth,
                )
            }
            PersonaInjectionPosition.IN_PROMPT,
            PersonaInjectionPosition.NONE -> working.size
        }

        working.add(insertIdx.coerceIn(0, working.size), msg)
        return working
    }

    private fun resolveFrozenInsertionIndex(
        cacheKey: String,
        lastUserMsgId: String?,
        minIndex: Int,
        messages: List<UIMessage>,
        depth: Int,
    ): Int {
        fun fallback(): Int = (messages.size - depth).coerceIn(minIndex, messages.size)
        val cached = lastUserMsgId?.let { id ->
            frozenAnchors[cacheKey]?.takeIf { it.first == id }?.second
        }
        if (cached != null) {
            val mode = cached.substringBefore(':')
            val anchorId = cached.substringAfter(':')
            val idx = messages.indexOfFirst { it.id.toString() == anchorId }
            if (idx >= minIndex) {
                return if (mode == "after") (idx + 1).coerceAtMost(messages.size) else idx
            }
        }
        val computed = fallback()
        if (lastUserMsgId != null && messages.isNotEmpty()) {
            val mode = if (computed >= messages.size) "after" else "before"
            val anchorIdx = if (mode == "after") messages.lastIndex else computed
            messages.getOrNull(anchorIdx)?.id?.toString()?.let { anchor ->
                if (frozenAnchors.size >= 256) frozenAnchors.clear()
                frozenAnchors[cacheKey] = lastUserMsgId to "$mode:$anchor"
            }
        }
        return computed
    }
}
