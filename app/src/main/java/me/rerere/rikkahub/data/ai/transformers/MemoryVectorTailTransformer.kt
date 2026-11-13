package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.DecisionGenerationParams
import me.rerere.ai.provider.DecisionQuestion
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.VectorStoreCache
import me.rerere.rikkahub.data.ai.resolveDecisionModel
import me.rerere.rikkahub.data.ai.resolveEmbeddingModel
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "MemoryVectorTail"

/**
 * 记忆库 + 溢出历史对话自动尾部近因注入转换器（两阶段检索 + 决策精排）：
 * - profile.md 与 preferences.md 已在头部常驻；
 * - 一阶段（粗召回）：优先走云端 Embedding 余弦相似度并持久化到 VectorStoreCache，无模型时退回本地词法相关性；
 * - 二阶段（决策重排）：若配置了 Google `gemini-decision-1`（Decisions API 单前向批量打分）或轻量快速对话模型，
 *   对扩召回候选池（Embedding + 词法并集）做精准上下文裁决，过滤误召回并保留 Top-K；接口异常自动冷却并无感回退一阶段结果；
 * - 将命中片段以独立 synthetic system 消息注入最后一条 User 消息前，同轮工具多步循环冻结召回结果，100% 保护前缀缓存。
 */
object MemoryVectorTailTransformer : InputMessageTransformer, KoinComponent {

    private val providerManager: ProviderManager by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val usageRecorder: UsageRecorder by inject()

    internal data class RecallChunk(
        val namespace: String,
        val path: String,
        val title: String,
        val order: Int,
        val text: String,
    ) {
        val embedText: String get() = "[$path ($title)]\n$text"
    }

    private data class FrozenMemoryRecall(
        val lastUserMsgId: String,
        val stateHash: Int,
        val prompt: String,
    )

    private val frozenTurnRecall = ConcurrentHashMap<String, FrozenMemoryRecall>()
    private const val MAX_FROZEN_TURNS = 64
    private const val EMBED_BATCH_SIZE = 64
    private const val EMBED_FAILURE_TTL_MS = 60_000L
    private const val DECISION_FAILURE_TTL_MS = 60_000L
    private const val MAX_RECALLED_CHUNKS = 6
    private const val MAX_LEXICAL_CHUNKS = 4
    private const val MAX_DECISION_CANDIDATES = 24
    private const val MAX_LEXICAL_CANDIDATES = 8
    private const val COARSE_EMBED_FLOOR = 0.12f
    private const val COARSE_LEXICAL_FLOOR = 0.04f
    private const val DECISION_MIN_SCORE = 1.5
    private const val MAX_CONTEXT_CHARS_FOR_DECISION = 2400
    private const val MAX_CHUNK_CHARS_FOR_DECISION = 700
    private const val MAX_SECTION_CHARS = 600
    private const val MIN_CHUNK_CHARS = 80
    private const val DIALOGUE_PATH = "/history/dialogue"
    private const val DIALOGUE_TITLE = "早期对话回顾"
    private val DECISION_SCORE_CRITERIA = listOf(
        "0: Completely irrelevant to what the current conversation needs",
        "1: Weakly tangential (e.g. generic keyword overlap) and unhelpful for the next reply",
        "2: Relevant background, person, preference, or event that meaningfully aids continuity",
        "3: Directly referenced, asked about, or essential for maintaining continuity in this turn",
    )

    @Volatile
    private var embedFailureUntil = 0L

    @Volatile
    private var decisionFailureUntil = 0L

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.settings.vectorStorageEnabled) return messages

        val candidateFiles = if (ctx.assistant.enableMemory) {
            val allFiles = ctx.memories.ifEmpty {
                withContext(Dispatchers.IO) {
                    runCatching { memoryRepository.getFiles(ctx.assistant.memoryId) }.getOrDefault(emptyList())
                }
            }
            allFiles.filter {
                it.path != MemoryFile.PROFILE_PATH && it.path != MemoryFile.PREFERENCES_PATH
            }
        } else {
            emptyList()
        }

        val overflowMessages = ctx.overflowMessages
        if (candidateFiles.isEmpty() && overflowMessages.isEmpty()) return messages

        val turnKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER && !it.isSynthetic }?.id?.toString()
        val memoryHash = candidateFiles.fold(1) { acc, f -> 31 * acc + f.version.hashCode() }
        val overflowHash = overflowMessages.size * 31 + (overflowMessages.lastOrNull()?.id?.hashCode() ?: 0)
        val configHash = (ctx.settings.vectorStorageModelId?.hashCode() ?: 0) * 31 +
            (ctx.settings.vectorDecisionModelId?.hashCode() ?: 0)
        val stateHash = (memoryHash * 31 + overflowHash) * 31 + configHash

        val recalledPrompt = if (lastUserMsgId != null) {
            val frozen = frozenTurnRecall[turnKey]?.takeIf {
                it.lastUserMsgId == lastUserMsgId && it.stateHash == stateHash
            }
            if (frozen != null) {
                frozen.prompt
            } else {
                val resolved = resolveRecalledPrompt(
                    context = ctx.context,
                    ctx = ctx,
                    messages = messages,
                    files = candidateFiles,
                    overflowMessages = overflowMessages,
                )
                if (frozenTurnRecall.size >= MAX_FROZEN_TURNS) frozenTurnRecall.clear()
                frozenTurnRecall[turnKey] = FrozenMemoryRecall(lastUserMsgId, stateHash, resolved)
                resolved
            }
        } else {
            resolveRecalledPrompt(
                context = ctx.context,
                ctx = ctx,
                messages = messages,
                files = candidateFiles,
                overflowMessages = overflowMessages,
            )
        }

        if (recalledPrompt.isBlank()) return messages

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val recallMsg = UIMessage.system(recalledPrompt).copy(isSynthetic = true)
        if (lastUserIndex < 0) return messages + recallMsg

        val safeInsertIndex = findSafeInsertIndex(messages, lastUserIndex)
        return buildList {
            addAll(messages)
            add(safeInsertIndex, recallMsg)
        }
    }

    private suspend fun resolveRecalledPrompt(
        context: Context,
        ctx: TransformerContext,
        messages: List<UIMessage>,
        files: List<MemoryFile>,
        overflowMessages: List<UIMessage>,
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val settings = ctx.settings
            val chunks = buildList {
                addAll(chunkMemoryFiles(files))
                addAll(chunkOverflowDialogue(overflowMessages, ctx.assistant.name.ifBlank { "角色" }))
            }
            if (chunks.isEmpty()) return@runCatching ""

            val recentMessages = messages
                .filter { it.role != MessageRole.SYSTEM && !it.isSynthetic }
                .takeLast(settings.vectorStorageScanDepth.coerceAtLeast(1))
            val query = recentMessages
                .joinToString("\n") { it.toText() }
                .trim()
            if (query.isBlank()) return@runCatching ""

            val decisionModel = settings.resolveDecisionModel()
                ?.takeIf { System.currentTimeMillis() >= decisionFailureUntil }
            val embeddingModel = settings.resolveEmbeddingModel()

            val scoredEmbed = if (embeddingModel != null && System.currentTimeMillis() >= embedFailureUntil) {
                scoreByEmbedding(
                    context = context,
                    providerSetting = embeddingModel.first,
                    model = embeddingModel.second,
                    query = query,
                    chunks = chunks,
                )
            } else {
                null
            }

            val fallbackHits = if (scoredEmbed != null) {
                scoredEmbed
                    .asSequence()
                    .filter { it.second >= settings.vectorStorageThreshold }
                    .sortedByDescending { it.second }
                    .take(MAX_RECALLED_CHUNKS)
                    .map { it.first }
                    .sortedWith(compareBy({ it.path }, { it.order }))
                    .toList()
            } else {
                recallByLexical(query, chunks)
            }

            val hits = if (decisionModel != null) {
                val coarsePool = buildCoarseCandidatePool(
                    query = query,
                    chunks = chunks,
                    scoredEmbed = scoredEmbed,
                    threshold = settings.vectorStorageThreshold,
                )
                if (coarsePool.isEmpty()) {
                    emptyList()
                } else {
                    val decisionContext = formatDecisionContext(
                        recentMessages = recentMessages,
                        assistantName = ctx.assistant.name.ifBlank { "角色" },
                    )
                    rerankWithDecisionModel(
                        ctx = ctx,
                        providerSetting = decisionModel.first,
                        model = decisionModel.second,
                        decisionContext = decisionContext,
                        candidates = coarsePool,
                    ) ?: fallbackHits
                }
            } else {
                fallbackHits
            }

            if (hits.isEmpty()) return@runCatching ""
            Log.d(TAG, "recalled ${hits.size} chunks from ${hits.map { it.path }.distinct()}")

            buildString {
                appendLine("<recalled_memory>")
                hits.groupBy { it.path to it.title }.forEach { (meta, groupedChunks) ->
                    appendLine("[${meta.first} | ${meta.second}]")
                    groupedChunks.forEach { chunk ->
                        appendLine(chunk.text.trim())
                    }
                    appendLine()
                }
                append("</recalled_memory>")
            }
        }.getOrDefault("")
    }

    private suspend fun scoreByEmbedding(
        context: Context,
        providerSetting: ProviderSetting,
        model: Model,
        query: String,
        chunks: List<RecallChunk>,
    ): List<Pair<RecallChunk, Float>>? {
        val providerHandler = providerManager.getProviderByType(providerSetting)
        val vectorsByKey = chunks.associate { chunk ->
            val key = cacheKey(model.id, chunk.namespace, chunk.embedText)
            key to VectorStoreCache.get(context, key)
        }.toMutableMap()

        val uncached = chunks.filter { vectorsByKey[cacheKey(model.id, it.namespace, it.embedText)] == null }

        val queryVector = runCatching {
            providerHandler.generateEmbedding(
                providerSetting = providerSetting,
                params = EmbeddingGenerationParams(model = model, input = listOf(query)),
            ).embeddings.firstOrNull()?.toFloatArray()
        }.getOrElse {
            embedFailureUntil = System.currentTimeMillis() + EMBED_FAILURE_TTL_MS
            null
        } ?: return null

        if (uncached.isNotEmpty() && System.currentTimeMillis() >= embedFailureUntil) {
            uncached.chunked(EMBED_BATCH_SIZE).forEach { batch ->
                runCatching {
                    providerHandler.generateEmbedding(
                        providerSetting = providerSetting,
                        params = EmbeddingGenerationParams(
                            model = model,
                            input = batch.map { it.embedText },
                        ),
                    )
                }.onSuccess { result ->
                    batch.forEachIndexed { index, chunk ->
                        result.embeddings.getOrNull(index)?.let { vec ->
                            val floatArr = vec.toFloatArray()
                            val key = cacheKey(model.id, chunk.namespace, chunk.embedText)
                            VectorStoreCache.put(context, key, floatArr)
                            vectorsByKey[key] = floatArr
                        }
                    }
                }.onFailure {
                    embedFailureUntil = System.currentTimeMillis() + EMBED_FAILURE_TTL_MS
                }
            }
        }

        return chunks.mapNotNull { chunk ->
            val vec = vectorsByKey[cacheKey(model.id, chunk.namespace, chunk.embedText)] ?: return@mapNotNull null
            chunk to VectorStoreCache.cosineSimilarity(queryVector, vec)
        }
    }

    internal fun buildCoarseCandidatePool(
        query: String,
        chunks: List<RecallChunk>,
        scoredEmbed: List<Pair<RecallChunk, Float>>?,
        threshold: Float,
    ): List<RecallChunk> {
        val embedFloor = minOf(threshold, COARSE_EMBED_FLOOR)
        val embedTop = scoredEmbed.orEmpty()
            .asSequence()
            .filter { it.second >= embedFloor }
            .sortedByDescending { it.second }
            .take(MAX_DECISION_CANDIDATES)
            .map { it.first }
            .toList()
        val lexicalTop = scoreByLexical(query, chunks, minScore = COARSE_LEXICAL_FLOOR)
            .take(if (scoredEmbed == null) MAX_DECISION_CANDIDATES else MAX_LEXICAL_CANDIDATES)
            .map { it.first }

        return buildList {
            val seen = HashSet<String>()
            fun addUnique(chunk: RecallChunk) {
                if (size >= MAX_DECISION_CANDIDATES) return
                val key = "${chunk.namespace}\n${chunk.path}\n${chunk.order}"
                if (seen.add(key)) add(chunk)
            }
            embedTop.forEach(::addUnique)
            lexicalTop.forEach(::addUnique)
        }
    }

    private suspend fun rerankWithDecisionModel(
        ctx: TransformerContext,
        providerSetting: ProviderSetting,
        model: Model,
        decisionContext: String,
        candidates: List<RecallChunk>,
    ): List<RecallChunk>? {
        val rawProvider = providerManager.getProviderByType(providerSetting)
        val provider = usageRecorder.wrap(
            rawProvider,
            UsageTag(
                source = UsageSource.MEMORY,
                assistantId = ctx.assistant.id,
                conversationId = (ctx.conversationId as? Uuid)
                    ?: (ctx.conversationId as? String)?.let { runCatching { Uuid.parse(it) }.getOrNull() },
            ),
        )
        val useDecisionsApi = providerSetting is ProviderSetting.Google &&
            (model.type == ModelType.DECISION || model.modelId.contains("decision", ignoreCase = true))

        return runCatching {
            if (useDecisionsApi) {
                rerankByDecisionsApi(
                    provider = provider,
                    providerSetting = providerSetting,
                    model = model,
                    decisionContext = decisionContext,
                    candidates = candidates,
                )
            } else {
                rerankByChatModel(
                    provider = provider,
                    providerSetting = providerSetting,
                    model = model,
                    decisionContext = decisionContext,
                    candidates = candidates,
                )
            }
        }.getOrElse { error ->
            Log.w(TAG, "Decision rerank failed, falling back to stage-1 recall: ${error.message}")
            decisionFailureUntil = System.currentTimeMillis() + DECISION_FAILURE_TTL_MS
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : ProviderSetting> rerankByDecisionsApi(
        provider: Provider<T>,
        providerSetting: ProviderSetting,
        model: Model,
        decisionContext: String,
        candidates: List<RecallChunk>,
    ): List<RecallChunk> {
        val questions = buildDecisionQuestions(candidates)
        val result = provider.evaluateDecisions(
            providerSetting = providerSetting as T,
            params = DecisionGenerationParams(
                model = model,
                input = decisionContext,
                questions = questions,
            ),
        )
        return rankDecisionAnswers(candidates, result.answers)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : ProviderSetting> rerankByChatModel(
        provider: Provider<T>,
        providerSetting: ProviderSetting,
        model: Model,
        decisionContext: String,
        candidates: List<RecallChunk>,
    ): List<RecallChunk>? {
        val prompt = buildString {
            appendLine("Select only memory snippets that are genuinely relevant or helpful for continuing the conversation below.")
            appendLine("Output at most $MAX_RECALLED_CHUNKS comma-separated candidate indices ordered from most to least relevant (e.g. `2, 0, 5`), or `NONE` if none apply. Do not explain.")
            appendLine()
            appendLine("<conversation>")
            appendLine(decisionContext)
            appendLine("</conversation>")
            appendLine()
            appendLine("<candidates>")
            candidates.forEachIndexed { index, chunk ->
                appendLine("[$index] [${chunk.path} | ${chunk.title}] ${chunk.text.trim().take(MAX_CHUNK_CHARS_FOR_DECISION)}")
            }
            appendLine("</candidates>")
        }
        val response = provider.generateText(
            providerSetting = providerSetting as T,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                temperature = 0f,
                maxTokens = 48,
                disableReasoning = true,
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )
        val reply = response.message.toText()
        val pickedIndices = parseListwiseIndices(reply, candidates.size, MAX_RECALLED_CHUNKS) ?: return null
        return pickedIndices
            .map { candidates[it] }
            .sortedWith(compareBy({ it.path }, { it.order }))
    }

    internal fun buildDecisionQuestions(candidates: List<RecallChunk>): Map<String, DecisionQuestion> =
        buildMap {
            candidates.forEachIndexed { index, chunk ->
                val snippet = chunk.text.trim().take(MAX_CHUNK_CHARS_FOR_DECISION)
                put(
                    "c_$index",
                    DecisionQuestion.Score(
                        instructions = "Evaluate how relevant and helpful this memory snippet is for replying to the current conversation context:\n\n[${chunk.path} | ${chunk.title}]\n$snippet",
                        criteria = DECISION_SCORE_CRITERIA,
                    ),
                )
            }
        }

    internal fun rankDecisionAnswers(
        candidates: List<RecallChunk>,
        answers: Map<String, me.rerere.ai.provider.DecisionAnswer>,
        minScore: Double = DECISION_MIN_SCORE,
        limit: Int = MAX_RECALLED_CHUNKS,
    ): List<RecallChunk> = candidates.mapIndexedNotNull { index, chunk ->
        val answer = answers["c_$index"] ?: return@mapIndexedNotNull null
        val score = answer.score ?: answer.noulScore?.times(3.0) ?: return@mapIndexedNotNull null
        if (score < minScore) return@mapIndexedNotNull null
        Triple(chunk, score, answer.confidence ?: 0.0)
    }
        .sortedWith(compareByDescending<Triple<RecallChunk, Double, Double>> { it.second }.thenByDescending { it.third })
        .take(limit)
        .map { it.first }
        .sortedWith(compareBy({ it.path }, { it.order }))

    internal fun parseListwiseIndices(
        reply: String,
        candidateCount: Int,
        limit: Int = MAX_RECALLED_CHUNKS,
    ): List<Int>? {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return null
        val numbers = Regex("\\d+").findAll(trimmed)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 0 until candidateCount }
            .distinct()
            .take(limit)
            .toList()
        if (numbers.isNotEmpty()) return numbers
        if (Regex("\\bNONE\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return emptyList()
        }
        return null
    }

    internal fun formatDecisionContext(recentMessages: List<UIMessage>, assistantName: String): String {
        val joined = recentMessages.joinToString("\n") { msg ->
            val speaker = if (msg.role == MessageRole.ASSISTANT) assistantName else "User"
            "[$speaker]: ${msg.toText().trim()}"
        }.trim()
        return if (joined.length <= MAX_CONTEXT_CHARS_FOR_DECISION) {
            joined
        } else {
            "…" + joined.takeLast(MAX_CONTEXT_CHARS_FOR_DECISION)
        }
    }

    internal fun recallByLexical(query: String, chunks: List<RecallChunk>): List<RecallChunk> =
        scoreByLexical(query, chunks, minScore = 0.08f)
            .take(MAX_LEXICAL_CHUNKS)
            .map { it.first }
            .sortedWith(compareBy({ it.path }, { it.order }))

    private fun scoreByLexical(
        query: String,
        chunks: List<RecallChunk>,
        minScore: Float,
    ): List<Pair<RecallChunk, Float>> {
        val queryTokens = tokenizeForLexical(query)
        if (queryTokens.isEmpty()) return emptyList()
        return chunks.mapNotNull { chunk ->
            val docTokens = tokenizeForLexical(chunk.embedText)
            if (docTokens.isEmpty()) return@mapNotNull null
            val overlap = queryTokens.count { it in docTokens }
            if (overlap == 0) return@mapNotNull null
            val coverage = overlap.toFloat() / queryTokens.size.toFloat()
            val jaccard = overlap.toFloat() / (queryTokens.size + docTokens.size - overlap).toFloat()
            val score = coverage * 0.7f + jaccard * 0.3f
            if (score >= minScore) (chunk to score) else null
        }.sortedByDescending { it.second }
    }

    private fun tokenizeForLexical(text: String): Set<String> {
        val tokens = HashSet<String>()
        val lower = text.lowercase()
        val latinBuffer = StringBuilder()
        val cjkBuffer = StringBuilder()

        fun flushLatin() {
            if (latinBuffer.length >= 2) {
                tokens.add(latinBuffer.toString())
            }
            latinBuffer.setLength(0)
        }

        fun flushCjk() {
            if (cjkBuffer.length == 1) {
                tokens.add(cjkBuffer.toString())
            } else if (cjkBuffer.length >= 2) {
                for (i in 0 until cjkBuffer.length - 1) {
                    tokens.add(cjkBuffer.substring(i, i + 2))
                }
            }
            cjkBuffer.setLength(0)
        }

        for (ch in lower) {
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> {
                    flushCjk()
                    latinBuffer.append(ch)
                }
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                    flushLatin()
                    cjkBuffer.append(ch)
                }
                else -> {
                    flushLatin()
                    flushCjk()
                }
            }
        }
        flushLatin()
        flushCjk()
        return tokens
    }

    private fun chunkOverflowDialogue(
        overflowMessages: List<UIMessage>,
        assistantName: String,
    ): List<RecallChunk> {
        val entries = overflowMessages.mapNotNull { msg ->
            if (msg.role != MessageRole.USER && msg.role != MessageRole.ASSISTANT) return@mapNotNull null
            val text = msg.toText().trim()
            if (text.isEmpty()) return@mapNotNull null
            val speaker = if (msg.role == MessageRole.USER) "用户" else assistantName
            val clipped = if (text.length > 420) text.take(420) + "…" else text
            "$speaker: $clipped"
        }
        if (entries.isEmpty()) return emptyList()

        val result = mutableListOf<RecallChunk>()
        val buffer = StringBuilder()
        var order = 0
        for (entry in entries) {
            if (buffer.isNotEmpty() && buffer.length + entry.length + 1 > MAX_SECTION_CHARS) {
                result.add(
                    RecallChunk(
                        namespace = "dialog_chunk",
                        path = DIALOGUE_PATH,
                        title = DIALOGUE_TITLE,
                        order = order++,
                        text = buffer.toString().trim(),
                    )
                )
                buffer.setLength(0)
            }
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(entry)
        }
        if (buffer.isNotEmpty()) {
            result.add(
                RecallChunk(
                    namespace = "dialog_chunk",
                    path = DIALOGUE_PATH,
                    title = DIALOGUE_TITLE,
                    order = order,
                    text = buffer.toString().trim(),
                )
            )
        }
        return result
    }

    private fun chunkMemoryFiles(files: List<MemoryFile>): List<RecallChunk> {
        val result = mutableListOf<RecallChunk>()
        var globalOrder = 0
        for (file in files) {
            val body = file.editableBody.trim()
            if (body.isEmpty()) continue
            val sections = body.split(Regex("(?m)^(?=##\\s)"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val rawPieces = mutableListOf<String>()
            for (section in sections) {
                if (section.length <= MAX_SECTION_CHARS) {
                    rawPieces.add(section)
                } else {
                    val lines = section.lines()
                    val header = lines.firstOrNull()?.takeIf { it.startsWith("## ") }
                    val paragraphs = section.split(Regex("\\n\\s*\\n+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    for (p in paragraphs) {
                        if (header != null && !p.startsWith("## ")) {
                            rawPieces.add("$header\n$p")
                        } else {
                            rawPieces.add(p)
                        }
                    }
                }
            }
            val merged = mutableListOf<String>()
            var buffer = ""
            for (piece in rawPieces) {
                buffer = if (buffer.isEmpty()) piece else "$buffer\n\n$piece"
                if (buffer.length >= MIN_CHUNK_CHARS) {
                    merged.add(buffer)
                    buffer = ""
                }
            }
            if (buffer.isNotEmpty()) {
                if (merged.isNotEmpty()) {
                    merged[merged.lastIndex] = merged.last() + "\n\n" + buffer
                } else {
                    merged.add(buffer)
                }
            }
            for (text in merged) {
                result.add(
                    RecallChunk(
                        namespace = "mem_chunk",
                        path = file.path,
                        title = file.title,
                        order = globalOrder++,
                        text = text,
                    )
                )
            }
        }
        return result
    }

    private fun cacheKey(modelId: Uuid, namespace: String, text: String): String =
        "$modelId\n$namespace\n${text.trim()}"
}
