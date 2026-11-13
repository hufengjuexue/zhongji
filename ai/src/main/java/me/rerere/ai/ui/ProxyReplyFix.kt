package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole

private val RESPONSE_PREFIX_REGEX =
    Regex("(?i)^response(?=\\s|:|$|[\\u4e00-\\u9fff\\u3040-\\u30ff])\\s*:?\\s*")

/**
 * 中转站兼容：修复 Gemini 经 OpenAI 兼容中转时 reasoning_content 吞掉正文的问题。
 *
 * 只在流结束后整条消息评估：正文剥离 "Response:" 前缀后为空且推理非空、
 * 且非工具调用轮次时，将推理提升为正文。
 */
fun List<UIMessage>.fixProxyPromotedReply(): List<UIMessage> {
    val last = lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT } ?: return this
    if (last.parts.any { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }) return this
    val reasoning = last.parts.filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("") { it.reasoning }
    if (reasoning.isBlank()) return this
    val text = last.parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
    if (RESPONSE_PREFIX_REGEX.replace(text, "").trimStart().isNotEmpty()) return this
    val promoted = UIMessagePart.Text(RESPONSE_PREFIX_REGEX.replace(reasoning, "").trimStart())
    val kept = last.parts.filter { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning }
    val insertIdx = kept.indexOfFirst { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }
        .takeIf { it >= 0 } ?: kept.size
    val parts = kept.toMutableList().apply { add(insertIdx, promoted) }
    return dropLast(1) + last.copy(parts = parts)
}
