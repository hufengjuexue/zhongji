package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.math.ceil
import kotlin.uuid.Uuid

/**
 * 提示词查看器：缓存每次生成实际发送给模型的完整消息列表（按对话隔离，保留上一次与本次），
 * 供聊天抽屉 "查看提示词" 调试入口渲染成 HTML 页面。
 * 内存 LRU 缓存，进程被杀即失效。
 */
object PromptDebugCache {
    private data class DebugEntry(
        val current: List<UIMessage>,
        val previous: List<UIMessage>?,
    )

    private const val MAX_ENTRIES = 8
    private const val SEP = "\u001E"

    private val cache = object : LinkedHashMap<String, DebugEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DebugEntry>): Boolean =
            size > MAX_ENTRIES
    }

    @Volatile
    private var redactions: List<String> = emptyList()

    fun setRedactions(fragments: List<String>) {
        redactions = fragments.filter { it.isNotBlank() }.sortedByDescending { it.length }
    }

    fun clearRedactions() {
        redactions = emptyList()
    }

    private const val REDACTED_PLACEHOLDER = "[插件注入内容 · 已遮蔽]"

    private fun redact(text: String): String {
        if (redactions.isEmpty() || text.isBlank()) return text
        var out = text
        for (frag in redactions) {
            if (out.contains(frag)) out = out.replace(frag, REDACTED_PLACEHOLDER)
        }
        return out
    }

    private fun redactMessage(msg: UIMessage): UIMessage {
        if (redactions.isEmpty()) return msg
        return msg.copy(parts = msg.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(text = redact(part.text))
                is UIMessagePart.Reasoning -> part.copy(reasoning = redact(part.reasoning))
                else -> part
            }
        })
    }

    fun store(conversationId: Uuid?, messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        val masked = if (redactions.isEmpty()) messages else messages.map { redactMessage(it) }
        val key = conversationId?.toString() ?: ""
        synchronized(cache) {
            val previous = cache[key]?.current
            cache[key] = DebugEntry(current = masked, previous = previous)
        }
    }

    fun get(conversationId: Uuid?): String? {
        val entry = synchronized(cache) { cache[conversationId?.toString() ?: ""] } ?: return null
        return buildHtml(entry.current, entry.previous)
    }

    private fun estimateTokens(messages: List<UIMessage>): Int {
        var chars = 0
        messages.forEach { msg ->
            msg.parts.forEach { part ->
                chars += when (part) {
                    is UIMessagePart.Text -> part.text.length
                    is UIMessagePart.Reasoning -> part.reasoning.length
                    is UIMessagePart.Tool -> part.toolName.length + part.input.length
                    is UIMessagePart.ToolResult -> part.content.toString().length
                    else -> 64
                }
            }
        }
        return ceil(chars * 0.35).toInt()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun roleStyle(role: MessageRole): Pair<String, String> = when (role) {
        MessageRole.SYSTEM -> "system" to "#b45309"
        MessageRole.USER -> "user" to "#1d4ed8"
        MessageRole.ASSISTANT -> "assistant" to "#047857"
        else -> "tool" to "#7c3aed"
    }

    private fun messageSignature(message: UIMessage): String = buildString {
        append(message.role.name)
        append(SEP)
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    append('T'); append(part.text)
                }
                is UIMessagePart.Tool -> {
                    append('C'); append(part.toolName); append(SEP); append(part.input)
                }
                is UIMessagePart.ToolResult -> {
                    append('R'); append(part.toolCallId); append(SEP)
                    append(part.content.toString())
                }
                else -> {}
            }
            append(SEP)
        }
    }

    private fun commonPrefixCount(previous: List<UIMessage>, current: List<UIMessage>): Int {
        val n = minOf(previous.size, current.size)
        for (i in 0 until n) {
            if (messageSignature(previous[i]) != messageSignature(current[i])) return i
        }
        return n
    }

    private fun excerpt(message: UIMessage, maxChars: Int = 160): String {
        val text = fullText(message)
        return if (text.length <= maxChars) text else text.take(maxChars) + "…"
    }

    private fun fullText(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .ifBlank { "（无文本内容）" }

    private fun firstDiffIndex(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            if (a[i] != b[i]) return i
        }
        return n
    }

    private fun diffWindow(text: String, diffAt: Int, before: Int = 60, after: Int = 120): String {
        if (text.isBlank()) return "（空）"
        val start = (diffAt - before).coerceAtLeast(0)
        val end = (diffAt + after).coerceAtMost(text.length)
        val segment = text.substring(start, end)
        val pos = (diffAt - start).coerceIn(0, segment.length)
        return (if (start > 0) "…" else "") +
            segment.substring(0, pos) + "▶" + segment.substring(pos) +
            (if (end < text.length) "…" else "")
    }

    private fun diagnoseDivergence(
        previous: List<UIMessage>,
        current: List<UIMessage>,
        common: Int,
    ): DivergenceInfo {
        if (common >= previous.size && current.size >= previous.size) {
            return DivergenceInfo.AppendOnly
        }
        val changed = common < previous.size && common < current.size &&
            previous[common].role == current[common].role
        if (changed) {
            val oldText = fullText(previous[common])
            val newText = fullText(current[common])
            if (oldText.isNotBlank() && newText.startsWith(oldText)) {
                return DivergenceInfo.PrefixAppended(
                    index = common,
                    newText = excerpt(current[common]),
                )
            }
            return DivergenceInfo.ContentChanged(
                index = common,
                oldText = oldText,
                newText = newText,
            )
        }
        for (shift in 1..8) {
            val idx = common + shift
            if (idx < current.size && messageSignature(previous[common]) == messageSignature(current[idx])) {
                return DivergenceInfo.Inserted(index = common, inserted = shift)
            }
        }
        return DivergenceInfo.Removed(index = common)
    }

    private sealed interface DivergenceInfo {
        data object AppendOnly : DivergenceInfo
        data class PrefixAppended(val index: Int, val newText: String) : DivergenceInfo
        data class ContentChanged(val index: Int, val oldText: String, val newText: String) : DivergenceInfo
        data class Inserted(val index: Int, val inserted: Int) : DivergenceInfo
        data class Removed(val index: Int) : DivergenceInfo
    }

    private fun buildDiagnosticBanner(current: List<UIMessage>, previous: List<UIMessage>?): String {
        if (previous == null) {
            return """
                <div class="diag diag-info">
                  <b>前缀分叉诊断</b>：首次请求（无上次快照可比对）。发送下一轮后再次打开本页即可看到分叉位置。
                </div>
            """.trimIndent()
        }

        val common = commonPrefixCount(previous, current)
        val prefixTokens = estimateTokens(current.take(common))
        val totalTokens = estimateTokens(current)
        val percent = if (totalTokens > 0) (prefixTokens * 100 / totalTokens).coerceIn(0, 100) else 0

        val (cssClass, detail) = when (val info = diagnoseDivergence(previous, current, common)) {
            is DivergenceInfo.AppendOnly -> "diag-ok" to
                "两次请求之间前缀<b>完全稳定（纯追加）</b>，缓存可全额命中"
            is DivergenceInfo.PrefixAppended -> "diag-warn" to buildString {
                append("前缀在 <b>#${info.index + 1}</b> 条消息处<b>末尾追加了内容</b>：token 缓存命中到追加点，仅追加部分及其后失效。")
                append("<br><b>新：${escapeHtml(info.newText)}</b>")
            }
            is DivergenceInfo.ContentChanged -> "diag-bad" to buildString {
                val role = previous.getOrNull(info.index)?.role?.name?.lowercase() ?: "?"
                append("前缀在 <b>#${info.index + 1}</b> 条（$role）消息处分叉：<b>该消息每次请求内容都在变化</b>，其后的缓存全部失效。")
                val diffAt = firstDiffIndex(info.oldText, info.newText)
                append("<br>首个差异在第 <b>$diffAt</b> 字符（旧 ${info.oldText.length} 字符 / 新 ${info.newText.length} 字符）：")
                append("<br><s style=\"opacity:.7\">旧：${escapeHtml(diffWindow(info.oldText, diffAt))}</s>")
                append("<br><b>新：${escapeHtml(diffWindow(info.newText, diffAt))}</b>")
                append("<br><span class=\"hint\">▶ 标记处即首个差异字符。常见原因：动态时间或未冻结的宏落在了系统前缀区。</span>")
            }
            is DivergenceInfo.Inserted -> "diag-warn" to
                "在 <b>#${info.index + 1}</b> 条消息前插入了 <b>${info.inserted}</b> 条新消息（世界书/作者备注/人设 @depth 注入或工具消息），其后缓存失效。"
            is DivergenceInfo.Removed -> "diag-warn" to
                "上次的 <b>#${info.index + 1}</b> 条消息本次缺失（上下文截断/分支切换/编辑历史），其后的缓存全部失效"
        }

        return """
            <div class="diag $cssClass">
              <b>前缀分叉诊断</b>：公共前缀 <b>$common</b> 条消息（约 <b>$prefixTokens</b> / $totalTokens tokens，估算最高命中率 <b>$percent%</b>）
              <br>$detail
            </div>
        """.trimIndent()
    }

    private fun buildHtml(messages: List<UIMessage>, previous: List<UIMessage>?): String {
        val divergence = if (previous == null) messages.size else commonPrefixCount(previous, messages)
        val body = StringBuilder()
        var totalChars = 0
        messages.forEachIndexed { index, message ->
            val (roleName, roleColor) = roleStyle(message.role)
            val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
            val reasoningParts = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
            val toolParts = message.parts.filterIsInstance<UIMessagePart.Tool>()
            val content = textParts.joinToString("\n") { it.text }
            totalChars += content.length
            val borderStyle = if (previous != null && index >= divergence) {
                "border-left: 3px solid #dc2626;"
            } else {
                "border-left: 3px solid #16a34a;"
            }
            body.append(
                """
                <div class="msg" style="$borderStyle">
                  <div class="head" style="color: $roleColor">#${index + 1} · ${roleName.uppercase()}${
                    message.name?.takeIf { it.isNotBlank() }?.let { " · ${escapeHtml(it)}" } ?: ""
                }</div>
                  ${if (content.isNotBlank()) """<pre class="text">${escapeHtml(content)}</pre>""" else ""}
                  ${
                    reasoningParts.filter { it.reasoning.isNotBlank() }.joinToString("") { r ->
                        """<details class="reasoning"><summary>思考过程</summary><pre>${escapeHtml(r.reasoning)}</pre></details>"""
                    }
                }
                  ${
                    toolParts.joinToString("") { t ->
                        """<details class="tool"><summary>工具调用 · ${escapeHtml(t.toolName)}</summary><pre>${escapeHtml(t.input)}</pre></details>"""
                    }
                }
                </div>
                """.trimIndent()
            )
        }
        val header = """
            <div class="summary">
              共 ${messages.size} 条消息 · 约 $totalChars 字符 · 估算 ${estimateTokens(messages)} tokens
            </div>
            ${buildDiagnosticBanner(messages, previous)}
        """.trimIndent()
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <title>Prompt</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              html, body { margin: 0; padding: 0; background: #f8fafc; }
              body { color: #0f172a; font-family: sans-serif; padding: 12px; }
              .summary { color: #475569; font-size: 13px; padding: 8px 4px; }
              .diag { border-radius: 10px; padding: 10px 12px; font-size: 13px; line-height: 1.6; margin-bottom: 12px; }
              .diag-info { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
              .diag-ok { background: #f0fdf4; border: 1px solid #bbf7d0; color: #166534; }
              .diag-warn { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
              .diag-bad { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; }
              .diag .hint { opacity: .75; font-size: 12px; }
              .msg { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; margin-bottom: 10px; overflow: hidden; }
              .head { font-size: 12px; font-weight: 700; letter-spacing: 0.05em; padding: 8px 12px; background: #f1f5f9; }
              .text { white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 13.5px; line-height: 1.55; margin: 0; padding: 10px 12px; }
              pre { white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 12.5px; line-height: 1.5; margin: 0; padding: 8px 12px; color: #334155; }
              details { border-top: 1px dashed #e2e8f0; }
              summary { padding: 8px 12px; font-size: 12px; color: #64748b; cursor: pointer; }
              .reasoning pre { color: #64748b; }
              .tool pre { font-family: monospace; }
            </style>
            </head>
            <body>
            $header
            $body
            </body>
            </html>
        """.trimIndent()
    }
}
