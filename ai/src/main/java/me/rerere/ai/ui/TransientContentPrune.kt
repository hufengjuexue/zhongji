package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

private val PRUNE_JSON = Json { ignoreUnknownKeys = true }

const val DEFAULT_PRUNE_KEEP_USER_TURNS = 2

/**
 * 上下文瞬态内容裁剪（省 token）：
 * 保留最近 [keepUserTurns] 个用户轮次的完整多模态与网页搜索/抓取结果；
 * 更早轮次中的图片/视频/音频与网页搜索结果原地替换为轻量占位提示（保留消息条数与 tool_call 结构）。
 */
fun List<UIMessage>.pruneOldTransientContent(keepUserTurns: Int = DEFAULT_PRUNE_KEEP_USER_TURNS): List<UIMessage> {
    if (isEmpty()) return this
    var seenUserTurns = 0
    var boundary = -1
    for (index in lastIndex downTo 0) {
        if (this[index].role == MessageRole.USER) {
            seenUserTurns++
            if (seenUserTurns == keepUserTurns) {
                boundary = index
                break
            }
        }
    }
    if (boundary <= 0) return this

    val head = subList(0, boundary).map { it.pruneTransientParts() }
    return head + subList(boundary, size)
}

private const val PRUNE_NOTICE =
    "[历史瞬态内容已省略以节省上下文：%s。原始内容未删除，需要回顾时请调用 read_history_message 传入消息 ID %s]"

private fun UIMessage.pruneTransientParts(): UIMessage {
    var changed = false
    val prunedParts = parts.map { part ->
        val pruned: UIMessagePart? = when (part) {
            is UIMessagePart.Image -> transientPlaceholder("图片附件", id)
            is UIMessagePart.Video -> transientPlaceholder("视频附件", id)
            is UIMessagePart.Audio -> transientPlaceholder("音频附件", id)
            is UIMessagePart.ServerTool ->
                if (isWebContentTool(part.toolName)) {
                    transientPlaceholder("网页内容（${part.toolName}）", id)
                } else null
            is UIMessagePart.Tool ->
                if (isWebContentTool(part.toolName)) {
                    part.copy(output = listOf(transientPlaceholder(webToolHint(part), id)))
                } else null
            else -> null
        }
        if (pruned != null) {
            changed = true
            pruned
        } else {
            part
        }
    }
    return if (changed) copy(parts = prunedParts) else this
}

private fun webToolHint(part: UIMessagePart.Tool): String {
    if (part.output.isEmpty()) return "网页内容（${part.toolName}）"
    val hint = runCatching {
        PRUNE_JSON.parseToJsonElement(part.input.ifBlank { "{}" }).jsonObject.let { input ->
            input["query"]?.jsonPrimitive?.contentOrNull
                ?: input["keywords"]?.jsonPrimitive?.contentOrNull
                ?: input["url"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
    return if (hint.isNullOrBlank()) "网页内容（${part.toolName}）" else "网页内容：$hint"
}

private fun isWebContentTool(toolName: String): Boolean = toolName.startsWith("search_") ||
    toolName.startsWith("scrape_") ||
    toolName.startsWith("web_search") ||
    toolName.startsWith("web_fetch")

private fun transientPlaceholder(what: String, messageId: Uuid): UIMessagePart.Text =
    UIMessagePart.Text(PRUNE_NOTICE.format(what, messageId))
