package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.datetime.TimeZone as KtzTimeZone

data class PlaceholderCtx(
    val context: Context?,
    val settingsStore: SettingsStore?,
    val settings: Settings = settingsStore?.settingsFlow?.value ?: Settings(),
    val model: Model,
    val assistant: Assistant,
    val messages: List<UIMessage> = emptyList(),
    val conversationId: Any? = null,
    val frozenTime: LocalDateTime = alignedFiveMinuteNow(),
    val frozenLastCharText: String? = null,
)

private fun alignedFiveMinuteNow(): LocalDateTime {
    val now = LocalDateTime.now()
    val flooredMin = (now.minute / 5) * 5
    return now.withMinute(flooredMin).withSecond(0).withNano(0)
}

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            resolveUserName(it)
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            resolveUserName(it)
        }

        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            it.frozenTime.toLocalDate().toDateString()
        }

        placeholder("isodate", { Text("ISO Date") }) {
            it.frozenTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        }

        placeholder("time", { Text("Time (5m)") }) {
            it.frozenTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        }

        placeholder("weekday", { Text("Weekday") }) {
            it.frozenTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }
    }

    private fun resolveUserName(ctx: PlaceholderCtx): String {
        val s = ctx.settingsStore?.settingsFlow?.value ?: ctx.settings
        val persona = s.resolveActivePersona(ctx.assistant.id)
        return persona?.name?.takeIf { it.isNotBlank() } ?: s.displaySetting.userNickname.ifBlank { "user" }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider
    private val turnSnapshots = ConcurrentHashMap<String, Pair<String, TurnSnapshot>>()
    private const val MAX_TURN_SNAPSHOTS = 64

    private class TurnSnapshot(
        val frozenTime: LocalDateTime,
        val lastCharText: String,
    )

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        val convKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER }?.id?.toString()
        val existing = lastUserMsgId?.let { id ->
            turnSnapshots[convKey]?.takeIf { it.first == id }?.second
        }
        val snapshot = existing ?: run {
            val lastChar = messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.isSynthetic }
                ?.toText().orEmpty()
            TurnSnapshot(alignedFiveMinuteNow(), lastChar).also { snap ->
                if (lastUserMsgId != null) {
                    if (turnSnapshots.size >= MAX_TURN_SNAPSHOTS) turnSnapshots.clear()
                    turnSnapshots[convKey] = lastUserMsgId to snap
                }
            }
        }

        val pCtx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = settingsStore,
            settings = ctx.settings,
            model = ctx.model,
            assistant = ctx.assistant,
            messages = messages,
            conversationId = ctx.conversationId,
            frozenTime = snapshot.frozenTime,
            frozenLastCharText = snapshot.lastCharText,
        )

        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) {
                        part.copy(text = replacePlaceholders(part.text, pCtx))
                    } else {
                        part
                    }
                }
            )
        }
    }

    /** 开场白/备选开场白替换 `{{user}}` / `{{char}}` 等安全子集宏 */
    fun substituteGreetingMacros(
        text: String,
        settings: Settings,
        assistant: Assistant,
        model: Model = Model(modelId = "kairos", displayName = "Kairos"),
    ): String {
        val ctx = PlaceholderCtx(
            context = null,
            settingsStore = null,
            settings = settings,
            model = model,
            assistant = assistant,
        )
        return replacePlaceholders(text, ctx)
    }

    private const val ESCAPE_SENTINEL = "\u0000ESCAPED_LBRACE\u0000"
    private val macroBlockIfRegex = Regex(
        """\{\{if\s+([^}]+)\}\}(.*?)(?:\{\{else\}\}(.*?))?\{\{/if\}\}""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val macroTokenRegex = Regex("""\{\{([^{}]+)\}\}""")
    private val trimRegex = Regex("""(?:\r?\n)?\s*\{\{trim\}\}\s*(?:\r?\n)?""", RegexOption.IGNORE_CASE)
    private val diceRegex = Regex("""^(\d+)?d(\d+)([+-]\d+)?$""", RegexOption.IGNORE_CASE)

    fun replacePlaceholders(text: String, ctx: PlaceholderCtx): String {
        if (!text.contains("{{")) return text
        var result = text.replace("\\{{", ESCAPE_SENTINEL)

        // 先展开块级 {{if cond}}...{{else}}...{{/if}}
        if (result.contains("{{if ", ignoreCase = true)) {
            result = macroBlockIfRegex.replace(result) { m ->
                val cond = m.groupValues[1].trim()
                val trueBranch = m.groupValues[2]
                val falseBranch = m.groupValues[3]
                if (evalCondition(cond, ctx)) trueBranch else falseBranch
            }
        }

        // 单遍扫描所有 {{...}} 宏（绝不二次展开替换结果，防止自引用指数爆炸）
        var occurrence = 0
        val seedBase = "${ctx.conversationId ?: ""}:${ctx.messages.lastOrNull { it.role == MessageRole.USER }?.id ?: ""}"
        result = macroTokenRegex.replace(result) { match ->
            val raw = match.groupValues[1]
            val idx = occurrence++
            resolveMacro(raw, ctx, "$seedBase:$raw:$idx") ?: match.value
        }

        result = trimRegex.replace(result) { "" }
        return result.replace(ESCAPE_SENTINEL, "{{")
    }

    private fun evalCondition(expr: String, ctx: PlaceholderCtx): Boolean {
        val trimmed = expr.trim()
        if (trimmed.startsWith("!")) return !evalCondition(trimmed.drop(1), ctx)
        val eqIdx = trimmed.indexOf("==")
        if (eqIdx > 0) {
            val left = resolveOperand(trimmed.substring(0, eqIdx).trim(), ctx)
            val right = resolveOperand(trimmed.substring(eqIdx + 2).trim(), ctx)
            return left.equals(right, ignoreCase = true)
        }
        val neqIdx = trimmed.indexOf("!=")
        if (neqIdx > 0) {
            val left = resolveOperand(trimmed.substring(0, neqIdx).trim(), ctx)
            val right = resolveOperand(trimmed.substring(neqIdx + 2).trim(), ctx)
            return !left.equals(right, ignoreCase = true)
        }
        val value = resolveOperand(trimmed, ctx)
        return value.isNotBlank() && value != "false" && value != "0"
    }

    private fun resolveOperand(token: String, ctx: PlaceholderCtx): String {
        val unquoted = token.removeSurrounding("\"").removeSurrounding("'")
        if (unquoted != token) return unquoted
        return resolveSimpleKey(unquoted, ctx) ?: unquoted
    }

    private fun resolveMacro(raw: String, ctx: PlaceholderCtx, seedKey: String): String? {
        val trimmed = raw.trim()
        if (trimmed.equals("trim", ignoreCase = true)) return null // 交给 trimRegex 处理换行
        if (trimmed.startsWith("//")) return "" // {{// 注释}}
        if (trimmed.equals("noop", ignoreCase = true)) return ""

        // 行内 {{if::cond::yes||no}}
        if (trimmed.startsWith("if::", ignoreCase = true)) {
            val parts = trimmed.split("::", limit = 3)
            if (parts.size < 3) return ""
            val branches = parts[2].split("||", limit = 2)
            return if (evalCondition(parts[1], ctx)) branches[0] else branches.getOrElse(1) { "" }
        }

        // 带 :: 或 : 参数的宏
        val sepIdx = trimmed.indexOf("::").let { if (it >= 0) it else trimmed.indexOf(':') }
        if (sepIdx > 0) {
            val cmd = trimmed.substring(0, sepIdx).trim().lowercase(Locale.ROOT)
            val arg = trimmed.substring(trimmed.indexOf(':', sepIdx).let {
                if (trimmed.startsWith("::", sepIdx)) sepIdx + 2 else sepIdx + 1
            })
            return when (cmd) {
                "random", "pick" -> {
                    val items = if (trimmed.contains("::")) arg.split("::") else arg.split(',')
                    val clean = items.map { it.trim() }.filter { it.isNotEmpty() }
                    if (clean.isEmpty()) "" else clean[seededRandom(seedKey).nextInt(clean.size)]
                }
                "roll" -> evalRoll(arg.trim(), seededRandom(seedKey))
                "newline" -> "\n".repeat((arg.trim().toIntOrNull() ?: 1).coerceIn(0, 20))
                "space" -> " ".repeat((arg.trim().toIntOrNull() ?: 1).coerceIn(0, 80))
                "reverse" -> arg.reversed()
                "datetimeformat" -> runCatching {
                    ctx.frozenTime.format(DateTimeFormatter.ofPattern(arg.trim(), Locale.getDefault()))
                }.getOrElse { "" }
                "time" -> runCatching {
                    val offsetStr = arg.trim().removePrefix("UTC").removePrefix("utc").trim()
                    val hours = offsetStr.toIntOrNull() ?: 0
                    val utcNow = ctx.frozenTime.atZone(TimeZone.getDefault().toZoneId()).withZoneSameInstant(ZoneOffset.ofHours(hours.coerceIn(-18, 18)))
                    utcNow.format(DateTimeFormatter.ofPattern("HH:mm"))
                }.getOrElse { "" }
                "comment" -> ""
                else -> null
            }
        }

        return resolveSimpleKey(trimmed, ctx)
    }

    private fun resolveSimpleKey(key: String, ctx: PlaceholderCtx): String? {
        defaultProvider.placeholders.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let {
            return it.value.resolver(ctx)
        }
        val lower = key.lowercase(Locale.ROOT)
        return when (lower) {
            "isotime" -> ctx.frozenTime.format(DateTimeFormatter.ofPattern("HH:mm"))
            "newline" -> "\n"
            "space" -> " "
            "description" -> ctx.assistant.cardDescription
            "personality" -> ctx.assistant.cardPersonality
            "scenario" -> ctx.assistant.cardScenario
            "creatornotes", "creator_notes" -> ctx.assistant.cardCreatorNotes
            "persona" -> (ctx.settingsStore?.settingsFlow?.value ?: ctx.settings)
                .resolveActivePersona(ctx.assistant.id)?.description.orEmpty()
            "lastusermessage" -> ctx.messages.lastOrNull { it.role == MessageRole.USER && !it.isSynthetic }?.toText().orEmpty()
            "lastcharmessage", "lastmessage" -> ctx.frozenLastCharText
                ?: ctx.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.isSynthetic }?.toText().orEmpty()
            "idleduration", "idle_duration" -> computeIdleDuration(ctx.messages, ctx.frozenTime)
            else -> null
        }
    }

    private fun computeIdleDuration(messages: List<UIMessage>, nowTime: LocalDateTime): String {
        val lastAt = messages.lastOrNull { !it.isSynthetic }?.createdAt ?: return "a moment"
        val nowInstant = Clock.System.now()
        val msgInstant = lastAt.toInstant(KtzTimeZone.currentSystemDefault())
        val mins = (nowInstant - msgInstant).inWholeMinutes.coerceAtLeast(0)
        // 按 5 分钟对齐，保证 KV 前缀稳定
        val bucketMins = (mins / 5) * 5
        return when {
            bucketMins < 5 -> "just now"
            bucketMins < 60 -> "$bucketMins minutes"
            bucketMins < 1440 -> "${bucketMins / 60} hours"
            else -> "${bucketMins / 1440} days"
        }
    }

    private fun seededRandom(seedKey: String): Random {
        val digest = MessageDigest.getInstance("MD5").digest(seedKey.toByteArray())
        var seed = 0L
        for (i in 0 until 8) {
            seed = (seed shl 8) or (digest[i].toLong() and 0xffL)
        }
        return Random(seed)
    }

    private fun evalRoll(expr: String, rnd: Random): String {
        val m = diceRegex.matchEntire(expr) ?: return ""
        val count = (m.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 50)
        val sides = (m.groupValues[2].toIntOrNull() ?: 6).coerceIn(2, 10000)
        val mod = m.groupValues[3].toIntOrNull() ?: 0
        var total = mod
        repeat(count) { total += rnd.nextInt(1, sides + 1) }
        return total.toString()
    }
}

