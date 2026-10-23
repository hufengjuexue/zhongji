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
 * 记忆库自动 Embedding 尾部注入转换器：
 * - profile.md 与 preferences.md 已在头部常驻，本转换器专门对其余记忆文件（如 topics 目录下的 md）按语义段落切块并向量化；
 * - 每轮自动用最新用户输入做 Embedding 相似度检索，将命中的 Top-K 记忆片段以独立 synthetic system 消息注入到最后一条 User 消息之前；
 * - 同一用户轮（agentic 工具多步循环）内冻结首次召回结果，保护前缀缓存不分叉，且与世界书、MCP 工具完全正交共存。
 */
object MemoryVectorTailTransformer : InputMessageTransformer, KoinComponent {

    private val providerManager: ProviderManager by inject()
    private val memoryRepository: MemoryRepository by inject()

    private data class MemoryChunk(
        val path: String,
        val title: String,
        val order: Int,
        val text: String,
    ) {
        val embedText: String get() = "[$path ($title)]\n$text"
    }

    private data class FrozenMemoryRecall(
        val lastUserMsgId: String,
        val memoryVersionHash: Int,
        val prompt: String,
    )

    private val frozenTurnRecall = ConcurrentHashMap<String, FrozenMemoryRecall>()
    private const val MAX_FROZEN_TURNS = 64
    private const val EMBED_BATCH_SIZE = 64
    private const val EMBED_FAILURE_TTL_MS = 60_000L
    private const val MAX_RECALLED_CHUNKS = 6
    private const val MAX_SECTION_CHARS = 600
    private const val MIN_CHUNK_CHARS = 80

    @Volatile
    private var embedFailureUntil = 0L

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableMemory) return messages
        if (ctx.settings.huadengSettings.enableTavernMode) return messages
        if (!ctx.settings.vectorStorageEnabled) return messages

        val allFiles = ctx.memories.ifEmpty {
            runCatching { memoryRepository.getFiles(ctx.assistant.memoryId) }.getOrDefault(emptyList())
        }
        val candidateFiles = allFiles.filter {
            it.path != MemoryFile.PROFILE_PATH && it.path != MemoryFile.PREFERENCES_PATH
        }
        if (candidateFiles.isEmpty()) return messages

        val turnKey = "${ctx.assistant.id}:${ctx.conversationId ?: "no-conversation"}"
        val lastUserMsgId = messages.lastOrNull { it.role == MessageRole.USER && !it.isSynthetic }?.id?.toString()
        val versionHash = candidateFiles.fold(1) { acc, f -> 31 * acc + f.version.hashCode() }

        val recalledPrompt = if (lastUserMsgId != null) {
            val frozen = frozenTurnRecall[turnKey]?.takeIf {
                it.lastUserMsgId == lastUserMsgId && it.memoryVersionHash == versionHash
            }
            if (frozen != null) {
                frozen.prompt
            } else {
                val resolved = resolveRecalledPrompt(ctx.context, ctx, messages, candidateFiles)
                if (frozenTurnRecall.size >= MAX_FROZEN_TURNS) frozenTurnRecall.clear()
                frozenTurnRecall[turnKey] = FrozenMemoryRecall(lastUserMsgId, versionHash, resolved)
                resolved
            }
        } else {
            resolveRecalledPrompt(ctx.context, ctx, messages, candidateFiles)
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
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val settings = ctx.settings
            val (providerSetting, model) = settings.resolveEmbeddingModel() ?: return@runCatching ""
            val chunks = chunkMemoryFiles(files)
            if (chunks.isEmpty()) return@runCatching ""

            val query = messages
                .filter { it.role != MessageRole.SYSTEM && !it.isSynthetic }
                .takeLast(settings.vectorStorageScanDepth.coerceAtLeast(1))
                .joinToString("\n") { it.toText() }
                .trim()
            if (query.isBlank()) return@runCatching ""

            val providerHandler = providerManager.getProviderByType(providerSetting)
            val vectorsByKey = chunks.associate { chunk ->
                val key = cacheKey(model.id, chunk.embedText)
                key to VectorStoreCache.get(context, key)
            }.toMutableMap()

            val uncached = chunks.filter { vectorsByKey[cacheKey(model.id, it.embedText)] == null }

            val queryVector = runCatching {
                providerHandler.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(model = model, input = listOf(query)),
                ).embeddings.firstOrNull()?.toFloatArray()
            }.getOrElse {
                embedFailureUntil = System.currentTimeMillis() + EMBED_FAILURE_TTL_MS
                null
            } ?: return@runCatching ""

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
                                val key = cacheKey(model.id, chunk.embedText)
                                VectorStoreCache.put(context, key, floatArr)
                                vectorsByKey[key] = floatArr
                            }
                        }
                    }.onFailure {
                        embedFailureUntil = System.currentTimeMillis() + EMBED_FAILURE_TTL_MS
                    }
                }
            }

            val hits = chunks.mapNotNull { chunk ->
                val vec = vectorsByKey[cacheKey(model.id, chunk.embedText)] ?: return@mapNotNull null
                val sim = VectorStoreCache.cosineSimilarity(queryVector, vec)
                if (sim >= settings.vectorStorageThreshold) (chunk to sim) else null
            }
                .sortedByDescending { it.second }
                .take(MAX_RECALLED_CHUNKS)
                .map { it.first }
                .sortedWith(compareBy({ it.path }, { it.order }))

            if (hits.isEmpty()) return@runCatching ""
            Log.d(TAG, "recalled ${hits.size} memory chunks from ${hits.map { it.path }.distinct()}")

            buildString {
                appendLine("<recalled_memory>")
                hits.groupBy { it.path to it.title }.forEach { (meta, fileChunks) ->
                    appendLine("[${meta.first} | ${meta.second}]")
                    fileChunks.forEach { chunk ->
                        appendLine(chunk.text.trim())
                    }
                    appendLine()
                }
                append("</recalled_memory>")
            }
        }.getOrDefault("")
    }

    private fun chunkMemoryFiles(files: List<MemoryFile>): List<MemoryChunk> {
        val result = mutableListOf<MemoryChunk>()
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
                result.add(MemoryChunk(path = file.path, title = file.title, order = globalOrder++, text = text))
            }
        }
        return result
    }

    private fun cacheKey(modelId: Uuid, text: String): String = "$modelId\nmem_chunk\n${text.trim()}"
}
