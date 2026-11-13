package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.VectorStoreCache
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
 * 记忆库 + 溢出历史对话自动尾部近因注入转换器：
 * - profile.md 与 preferences.md 已在头部常驻；
 * - 本转换器对 topics/<name>.md 记忆切片与超出上下文窗口（limitContext）的早先原始对话按语义切块；
 * - 优先走云端 Embedding 余弦相似度检索并持久化到本地 VectorStoreCache；无可用 Embedding 模型或接口异常时自动退回本地词法相关性兜底；
 * - 将命中的 Top-K 片段以独立 synthetic system 消息注入最后一条 User 消息前，同轮工具多步循环冻结召回结果，100% 保护前缀缓存。
 */
object MemoryVectorTailTransformer : InputMessageTransformer, KoinComponent {

    private val providerManager: ProviderManager by inject()
    private val memoryRepository: MemoryRepository by inject()

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
    private const val MAX_RECALLED_CHUNKS = 6
    private const val MAX_LEXICAL_CHUNKS = 4
    private const val MAX_SECTION_CHARS = 600
    private const val MIN_CHUNK_CHARS = 80
    private const val DIALOGUE_PATH = "/history/dialogue"
    private const val DIALOGUE_TITLE = "早期对话回顾"

    @Volatile
    private var embedFailureUntil = 0L

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
        val stateHash = memoryHash * 31 + overflowHash

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

            val query = messages
                .filter { it.role != MessageRole.SYSTEM && !it.isSynthetic }
                .takeLast(settings.vectorStorageScanDepth.coerceAtLeast(1))
                .joinToString("\n") { it.toText() }
                .trim()
            if (query.isBlank()) return@runCatching ""

            val embeddingModel = settings.resolveEmbeddingModel()
            val hits = if (embeddingModel != null && System.currentTimeMillis() >= embedFailureUntil) {
                recallByEmbedding(
                    context = context,
                    providerSetting = embeddingModel.first,
                    model = embeddingModel.second,
                    query = query,
                    chunks = chunks,
                    threshold = settings.vectorStorageThreshold,
                ) ?: recallByLexical(query, chunks)
            } else {
                recallByLexical(query, chunks)
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

    private suspend fun recallByEmbedding(
        context: Context,
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        query: String,
        chunks: List<RecallChunk>,
        threshold: Float,
    ): List<RecallChunk>? {
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
            val sim = VectorStoreCache.cosineSimilarity(queryVector, vec)
            if (sim >= threshold) (chunk to sim) else null
        }
            .sortedByDescending { it.second }
            .take(MAX_RECALLED_CHUNKS)
            .map { it.first }
            .sortedWith(compareBy({ it.path }, { it.order }))
    }

    internal fun recallByLexical(query: String, chunks: List<RecallChunk>): List<RecallChunk> {
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
            if (score >= 0.08f) (chunk to score) else null
        }
            .sortedByDescending { it.second }
            .take(MAX_LEXICAL_CHUNKS)
            .map { it.first }
            .sortedWith(compareBy({ it.path }, { it.order }))
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
