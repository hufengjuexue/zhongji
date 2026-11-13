package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rerere.ui.toast.ToastType
import me.rerere.ui.toast.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.SelectiveLogic
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
    onImportLorebook: ((Lorebook) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(
            onImport = onUpdate,
            onImportLorebook = onImportLorebook,
        )
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant) -> Unit,
    onImportLorebook: ((Lorebook) -> Unit)? = null,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            onImportLorebook = onImportLorebook,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            onImportLorebook = onImportLorebook,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

internal data class ImportedCharaCard(
    val assistant: Assistant,
    val embeddedLorebook: Lorebook? = null,
)

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): ImportedCharaCard
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"
    override fun parse(context: Context, json: JsonObject, background: String?): ImportedCharaCard =
        parseCharaCardData(context, json, background)
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"
    override fun parse(context: Context, json: JsonObject, background: String?): ImportedCharaCard =
        parseCharaCardData(context, json, background)
}

private fun parseCharaCardData(
    context: Context,
    json: JsonObject,
    background: String?,
): ImportedCharaCard {
    val data = json["data"]?.jsonObject ?: error(context.getString(R.string.assistant_importer_missing_data_field))
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_name_field))
    val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
    val alternateGreetings = (data["alternate_greetings"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.takeIf(String::isNotBlank) }
        .orEmpty()
    val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
    val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
    val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
    val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
    val creatorNotes = data["creator_notes"]?.jsonPrimitiveOrNull?.contentOrNull
    val mesExample = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull
    val postHistory = data["post_history_instructions"]?.jsonPrimitiveOrNull?.contentOrNull

    val prompt = buildString {
        appendLine("You are roleplaying as $name.")
        appendLine()
        if (!system.isNullOrBlank()) {
            appendLine(system.trim())
            appendLine()
        }
        appendLine("## Description of the character")
        appendLine(description?.takeIf { it.isNotBlank() } ?: "Empty")
        appendLine()
        appendLine("## Personality of the character")
        appendLine(personality?.takeIf { it.isNotBlank() } ?: "Empty")
        appendLine()
        appendLine("## Scenario")
        append(scenario?.takeIf { it.isNotBlank() } ?: "Empty")
        if (!mesExample.isNullOrBlank()) {
            appendLine()
            appendLine()
            appendLine("## Example Dialogue")
            append(mesExample.trim())
        }
        if (!postHistory.isNullOrBlank()) {
            appendLine()
            appendLine()
            appendLine("## Additional Instructions")
            append(postHistory.trim())
        }
    }

    val embeddedLorebook = parseEmbeddedCharacterBook(data, name)
    val regexScripts = parseEmbeddedRegexes(data)

    val assistant = Assistant(
        name = name,
        avatar = if (background != null) Avatar.Image(background) else Avatar.Dummy,
        useAssistantAvatar = background != null,
        presetMessages = if (!firstMessage.isNullOrBlank()) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
        systemPrompt = prompt,
        background = background,
        lorebookIds = if (embeddedLorebook != null) setOf(embeddedLorebook.id) else emptySet(),
        regexes = regexScripts,
        alternateGreetings = alternateGreetings,
        cardDescription = description.orEmpty(),
        cardPersonality = personality.orEmpty(),
        cardScenario = scenario.orEmpty(),
        cardCreatorNotes = creatorNotes.orEmpty(),
    )
    return ImportedCharaCard(assistant = assistant, embeddedLorebook = embeddedLorebook)
}

internal fun parseEmbeddedCharacterBook(data: JsonObject, charName: String): Lorebook? {
    val bookObj = runCatching { data["character_book"]?.jsonObject }.getOrNull() ?: return null
    val rawEntries = when (val entriesElem = bookObj["entries"]) {
        is JsonArray -> entriesElem.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        is JsonObject -> entriesElem.values.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        else -> emptyList()
    }
    if (rawEntries.isEmpty()) return null

    val bookScanDepth = bookObj["scan_depth"]?.jsonPrimitiveOrNull?.intOrNull ?: 4
    val entries = rawEntries.mapNotNull { entry ->
        val content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return@mapNotNull null

        val primaryKeys = (entry["keys"] as? JsonArray ?: entry["key"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
        val secondaryKeys = (entry["secondary_keys"] as? JsonArray ?: entry["keysecondary"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()

        val entryExt = runCatching { entry["extensions"]?.jsonObject }.getOrNull()
        val enabled = entry["enabled"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: !(entry["disable"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false)
        val constant = entry["constant"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val selective = entry["selective"]?.jsonPrimitiveOrNull?.booleanOrNull ?: secondaryKeys.isNotEmpty()
        val selectiveLogic = when (entry["selectiveLogic"]?.jsonPrimitiveOrNull?.intOrNull
            ?: entryExt?.get("selective_logic")?.jsonPrimitiveOrNull?.intOrNull ?: 0) {
            1 -> SelectiveLogic.NOT_ALL
            2 -> SelectiveLogic.NOT_ANY
            3 -> SelectiveLogic.AND_ALL
            else -> SelectiveLogic.AND_ANY
        }
        val useRegex = entry["use_regex"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: entryExt?.get("use_regex")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false
        val matchWholeWords = entryExt?.get("match_whole_words")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: entry["match_whole_words"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false
        val caseSensitive = entry["case_sensitive"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: entry["caseSensitive"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: false
        val order = entry["insertion_order"]?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["order"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 100
        val depth = entryExt?.get("depth")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["depth"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 4
        val scanDepth = entryExt?.get("scan_depth")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["scanDepth"]?.jsonPrimitiveOrNull?.intOrNull
            ?: bookScanDepth
        val probability = entryExt?.get("probability")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["probability"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 100
        val useProbability = entryExt?.get("useProbability")?.jsonPrimitiveOrNull?.booleanOrNull
            ?: entry["useProbability"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: (probability < 100)
        val sticky = entryExt?.get("sticky")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["sticky"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 0
        val cooldown = entryExt?.get("cooldown")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["cooldown"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 0
        val delay = entryExt?.get("delay")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["delay"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 0
        val role = when (val r = entryExt?.get("role")?.jsonPrimitiveOrNull ?: entry["role"]?.jsonPrimitiveOrNull) {
            null -> MessageRole.SYSTEM
            else -> when {
                r.intOrNull == 1 || r.contentOrNull.equals("user", ignoreCase = true) -> MessageRole.USER
                r.intOrNull == 2 || r.contentOrNull.equals("assistant", ignoreCase = true) -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            }
        }
        val rawPos = entryExt?.get("position")?.jsonPrimitiveOrNull?.intOrNull
            ?: entry["position"]?.jsonPrimitiveOrNull?.intOrNull
        val rawPosStr = entry["position"]?.jsonPrimitiveOrNull?.contentOrNull
        val position = when {
            rawPos == 0 || rawPosStr == "before_char" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            rawPos == 1 || rawPosStr == "after_char" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            rawPos == 2 || rawPos == 3 -> InjectionPosition.TOP_OF_CHAT
            rawPos == 4 -> InjectionPosition.AT_DEPTH
            else -> InjectionPosition.AFTER_SYSTEM_PROMPT
        }
        val entryName = entry["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: primaryKeys.firstOrNull()
            ?: ""

        PromptInjection.RegexInjection(
            id = Uuid.random(),
            name = entryName,
            enabled = enabled,
            priority = order,
            position = position,
            injectDepth = depth,
            role = role,
            content = content,
            keywords = primaryKeys,
            secondaryKeys = secondaryKeys,
            selective = selective,
            selectiveLogic = selectiveLogic,
            useRegex = useRegex,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
            scanDepth = scanDepth,
            constantActive = constant,
            probability = probability,
            useProbability = useProbability,
            sticky = sticky,
            cooldown = cooldown,
            delay = delay,
        )
    }
    if (entries.isEmpty()) return null

    val bookName = bookObj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: "$charName 世界书"
    val bookDesc = bookObj["description"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    return Lorebook(
        id = Uuid.random(),
        name = bookName,
        description = bookDesc,
        enabled = true,
        entries = entries,
    )
}

internal fun parseEmbeddedRegexes(data: JsonObject): List<AssistantRegex> {
    val extensions = runCatching { data["extensions"]?.jsonObject }.getOrNull() ?: return emptyList()
    val scripts = extensions["regex_scripts"] as? JsonArray ?: return emptyList()
    return scripts.mapNotNull { elem ->
        val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
        val rawFind = obj["findRegex"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
        if (rawFind.isEmpty()) return@mapNotNull null
        val normalizedRegex = normalizeJsRegexLiteral(rawFind)
        val replaceStr = obj["replaceString"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        val disabled = obj["disabled"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val visualOnly = obj["markdownOnly"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val placement = (obj["placement"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.intOrNull }
            .orEmpty()
        val scope = buildSet {
            if (1 in placement) add(AssistantAffectScope.USER)
            if (2 in placement || placement.isEmpty()) add(AssistantAffectScope.ASSISTANT)
        }
        AssistantRegex(
            id = Uuid.random(),
            name = obj["scriptName"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
            enabled = !disabled,
            findRegex = normalizedRegex,
            replaceString = replaceStr,
            affectingScope = scope,
            visualOnly = visualOnly,
        )
    }
}

private fun normalizeJsRegexLiteral(raw: String): String {
    if (!raw.startsWith("/") || raw.length < 2) return raw
    val lastSlash = raw.lastIndexOf('/')
    if (lastSlash <= 0) return raw
    val pattern = raw.substring(1, lastSlash)
    val flags = raw.substring(lastSlash + 1)
    if (flags.any { it !in "gimsuyd" }) return raw
    val inlineFlags = buildString {
        if ('i' in flags) append('i')
        if ('m' in flags) append('m')
        if ('s' in flags) append('s')
    }
    return if (inlineFlags.isNotEmpty()) "(?$inlineFlags)$pattern" else pattern
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): ImportedCharaCard {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant) -> Unit,
    onImportLorebook: ((Lorebook) -> Unit)? = null,
    toaster: ToasterState,
    filesManager: FilesManager,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data ->
                        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val imported = parseAssistantFromJson(context = context, json = json, background = backgroundStr)
        imported.embeddedLorebook?.let { book ->
            if (onImportLorebook != null) {
                onImportLorebook(book)
            }
        }
        val finalAssistant = if (imported.embeddedLorebook != null && onImportLorebook == null) {
            imported.assistant.copy(lorebookIds = imported.assistant.lorebookIds - imported.embeddedLorebook.id)
        } else {
            imported.assistant
        }
        onImport(finalAssistant)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
