package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.db.dao.MemoryGraphDAO
import me.rerere.rikkahub.data.db.entity.MemoryGraphEdgeEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphNodeEntity
import me.rerere.rikkahub.data.model.GraphNodeType
import me.rerere.rikkahub.data.model.MemoryGraphEdge
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.model.MemoryGraphSnapshot
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

data class GraphEntityInput(
    val name: String,
    val type: GraphNodeType = GraphNodeType.CHARACTER,
    val aliases: List<String> = emptyList(),
    val summary: String = "",
)

data class GraphEdgeInput(
    val fromName: String,
    val toName: String,
    val relation: String,
    val fromType: GraphNodeType? = null,
    val toType: GraphNodeType? = null,
    val excerpt: String = "",
    val supersedeEdgeIds: List<String> = emptyList(),
    val replaceRelationTypes: List<String> = emptyList(),
)

data class GraphUpsertSummary(
    val nodesCreated: Int = 0,
    val nodesUpdated: Int = 0,
    val edgesCreated: Int = 0,
    val edgesUpdated: Int = 0,
    val edgesSuperseded: Int = 0,
    val touchedEdgeIds: List<String> = emptyList(),
)

enum class GraphRecallMode(val wireName: String) {
    NETWORK("network"),
    TIMELINE("timeline"),
    NEIGHBORS("neighbors"),
    SEARCH("search");

    companion object {
        fun fromWire(raw: String?): GraphRecallMode = when (raw?.trim()?.lowercase()) {
            "timeline" -> TIMELINE
            "neighbors" -> NEIGHBORS
            "search" -> SEARCH
            else -> NETWORK
        }
    }
}

data class GraphRecallResult(
    val mode: GraphRecallMode,
    val matchedNodes: List<MemoryGraphNode>,
    val edges: List<MemoryGraphEdge>,
    val paths: List<List<MemoryGraphEdge>>,
    val snapshot: MemoryGraphSnapshot,
)

class MemoryGraphRepository(
    private val dao: MemoryGraphDAO,
) {
    private val writeLock = Mutex()

    fun observeSnapshot(assistantId: String): Flow<MemoryGraphSnapshot> =
        combine(dao.getNodesFlow(assistantId), dao.getEdgesFlow(assistantId)) { nodes, edges ->
            MemoryGraphSnapshot(
                nodes = nodes.map { it.toDomain() },
                edges = edges.map { it.toDomain() },
            )
        }

    suspend fun getSnapshot(assistantId: String): MemoryGraphSnapshot {
        val nodes = dao.getNodes(assistantId).map { it.toDomain() }
        val edges = dao.getEdges(assistantId).map { it.toDomain() }
        return MemoryGraphSnapshot(nodes = nodes, edges = edges)
    }

    suspend fun recallRelations(
        assistantId: String,
        entityQuery: String,
        mode: GraphRecallMode = GraphRecallMode.NETWORK,
        typeFilter: GraphNodeType? = null,
        depth: Int = 3,
        limit: Int = 12,
    ): GraphRecallResult {
        val snapshot = getSnapshot(assistantId)
        val matched = snapshot.matchNodes(entityQuery, typeFilter).take(limit)
        if (matched.isEmpty()) {
            return GraphRecallResult(
                mode = mode,
                matchedNodes = emptyList(),
                edges = emptyList(),
                paths = emptyList(),
                snapshot = snapshot,
            )
        }

        val anchorIds = matched.map { it.id }.toSet()
        return when (mode) {
            GraphRecallMode.SEARCH -> GraphRecallResult(
                mode = mode,
                matchedNodes = matched,
                edges = emptyList(),
                paths = emptyList(),
                snapshot = snapshot,
            )

            GraphRecallMode.NEIGHBORS -> {
                val center = matched.first()
                val neighborEdges = snapshot.edgesOf(center.id, includeHistorical = false).take(limit)
                GraphRecallResult(
                    mode = mode,
                    matchedNodes = matched,
                    edges = neighborEdges,
                    paths = emptyList(),
                    snapshot = snapshot,
                )
            }

            GraphRecallMode.TIMELINE -> {
                val timelineEdges = snapshot.edges
                    .filter { it.fromId in anchorIds || it.toId in anchorIds }
                    .sortedWith(compareBy<MemoryGraphEdge> { it.validFrom }.thenBy { it.createdAt })
                    .take(limit)
                GraphRecallResult(
                    mode = mode,
                    matchedNodes = matched,
                    edges = timelineEdges,
                    paths = emptyList(),
                    snapshot = snapshot,
                )
            }

            GraphRecallMode.NETWORK -> {
                val cappedDepth = depth.coerceIn(1, 3)
                val paths = snapshot.findNetworkPaths(anchorIds, maxDepth = cappedDepth, limit = limit)
                val pathEdges = paths.flatten().distinctBy { it.id }
                GraphRecallResult(
                    mode = mode,
                    matchedNodes = matched,
                    edges = pathEdges,
                    paths = paths,
                    snapshot = snapshot,
                )
            }
        }
    }

    suspend fun upsertGraph(
        assistantId: String,
        entities: List<GraphEntityInput>,
        edges: List<GraphEdgeInput>,
        sourceConversationId: String? = null,
    ): GraphUpsertSummary = writeLock.withLock {
        val now = System.currentTimeMillis()
        val existingNodes = dao.getNodes(assistantId).map { it.toDomain() }.toMutableList()
        val existingEdges = dao.getEdges(assistantId).map { it.toDomain() }.toMutableList()

        var nodesCreated = 0
        var nodesUpdated = 0
        var edgesCreated = 0
        var edgesUpdated = 0
        var edgesSuperseded = 0
        val touchedEdgeIds = mutableListOf<String>()

        suspend fun resolveOrCreateNode(
            rawName: String,
            preferredType: GraphNodeType?,
            aliases: List<String> = emptyList(),
            summary: String = "",
            allowUpdateMetadata: Boolean = false,
        ): MemoryGraphNode? {
            val cleanName = rawName.trim()
            if (cleanName.isEmpty()) return null
            val norm = MemoryGraphSnapshot.normalizeGraphName(cleanName)

            val found = existingNodes.firstOrNull { node ->
                (preferredType == null || node.type == preferredType) &&
                    (node.nameNormalized == norm || node.aliases.any { MemoryGraphSnapshot.normalizeGraphName(it) == norm })
            } ?: existingNodes.firstOrNull { node ->
                node.nameNormalized == norm || node.aliases.any { MemoryGraphSnapshot.normalizeGraphName(it) == norm }
            }

            if (found != null) {
                if (!allowUpdateMetadata && aliases.isEmpty() && summary.isBlank()) {
                    return found
                }
                val mergedAliases = (found.aliases + aliases)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && MemoryGraphSnapshot.normalizeGraphName(it) != found.nameNormalized }
                    .distinctBy { MemoryGraphSnapshot.normalizeGraphName(it) }
                val nextSummary = summary.trim().ifEmpty { found.summary }
                val changed = mergedAliases != found.aliases || nextSummary != found.summary
                if (changed) {
                    val updated = found.copy(
                        aliases = mergedAliases,
                        summary = nextSummary,
                        updatedAt = now,
                    )
                    dao.upsertNode(updated.toEntity())
                    val idx = existingNodes.indexOfFirst { it.id == found.id }
                    if (idx >= 0) existingNodes[idx] = updated
                    nodesUpdated++
                    return updated
                }
                return found
            }

            val resolvedType = preferredType ?: GraphNodeType.CHARACTER
            val created = MemoryGraphNode(
                id = Uuid.random().toString(),
                assistantId = assistantId,
                type = resolvedType,
                name = cleanName,
                nameNormalized = norm,
                aliases = aliases
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && MemoryGraphSnapshot.normalizeGraphName(it) != norm }
                    .distinctBy { MemoryGraphSnapshot.normalizeGraphName(it) },
                summary = summary.trim(),
                createdAt = now,
                updatedAt = now,
            )
            dao.upsertNode(created.toEntity())
            existingNodes += created
            nodesCreated++
            return created
        }

        for (entity in entities) {
            resolveOrCreateNode(
                rawName = entity.name,
                preferredType = entity.type,
                aliases = entity.aliases,
                summary = entity.summary,
                allowUpdateMetadata = true,
            )
        }

        for (edgeInput in edges) {
            val relationClean = edgeInput.relation.trim()
            if (relationClean.isEmpty()) continue
            val fromNode = resolveOrCreateNode(edgeInput.fromName, edgeInput.fromType) ?: continue
            val toNode = resolveOrCreateNode(edgeInput.toName, edgeInput.toType) ?: continue
            val relationNorm = MemoryGraphSnapshot.normalizeGraphName(relationClean)

            // 1. 显式指定的旧边 id 标为已失效
            for (supId in edgeInput.supersedeEdgeIds) {
                val target = existingEdges.firstOrNull { it.id == supId && it.assistantId == assistantId && it.isCurrent }
                if (target != null) {
                    dao.markEdgeSuperseded(target.id, validTo = now, updatedAt = now)
                    val idx = existingEdges.indexOfFirst { it.id == target.id }
                    if (idx >= 0) {
                        existingEdges[idx] = target.copy(isCurrent = false, validTo = now, updatedAt = now)
                    }
                    edgesSuperseded++
                }
            }

            // 2. 按关系类型替换同一对节点间的过时状态边
            val replaceNorms = edgeInput.replaceRelationTypes
                .map(MemoryGraphSnapshot::normalizeGraphName)
                .filter { it.isNotEmpty() && it != relationNorm }
                .toSet()
            if (replaceNorms.isNotEmpty()) {
                existingEdges.forEachIndexed { idx, old ->
                    val samePair = (old.fromId == fromNode.id && old.toId == toNode.id) ||
                        (old.fromId == toNode.id && old.toId == fromNode.id)
                    if (samePair && old.isCurrent && MemoryGraphSnapshot.normalizeGraphName(old.relation) in replaceNorms) {
                        dao.markEdgeSuperseded(old.id, validTo = now, updatedAt = now)
                        existingEdges[idx] = old.copy(isCurrent = false, validTo = now, updatedAt = now)
                        edgesSuperseded++
                    }
                }
            }

            // 3. 若同向同谓词当前边已存在，则就地刷新摘要，避免重复边
            val existingSameIdx = existingEdges.indexOfFirst {
                it.isCurrent &&
                    it.fromId == fromNode.id &&
                    it.toId == toNode.id &&
                    MemoryGraphSnapshot.normalizeGraphName(it.relation) == relationNorm
            }
            if (existingSameIdx >= 0) {
                val current = existingEdges[existingSameIdx]
                val nextExcerpt = edgeInput.excerpt.trim().ifEmpty { current.excerpt }
                val updated = current.copy(
                    excerpt = nextExcerpt,
                    sourceConversationId = sourceConversationId ?: current.sourceConversationId,
                    updatedAt = now,
                )
                dao.upsertEdge(updated.toEntity())
                existingEdges[existingSameIdx] = updated
                touchedEdgeIds += updated.id
                edgesUpdated++
            } else {
                val created = MemoryGraphEdge(
                    id = Uuid.random().toString(),
                    assistantId = assistantId,
                    fromId = fromNode.id,
                    toId = toNode.id,
                    relation = relationClean,
                    excerpt = edgeInput.excerpt.trim(),
                    validFrom = now,
                    validTo = null,
                    isCurrent = true,
                    sourceConversationId = sourceConversationId,
                    createdAt = now,
                    updatedAt = now,
                )
                dao.upsertEdge(created.toEntity())
                existingEdges += created
                touchedEdgeIds += created.id
                edgesCreated++
            }
        }

        GraphUpsertSummary(
            nodesCreated = nodesCreated,
            nodesUpdated = nodesUpdated,
            edgesCreated = edgesCreated,
            edgesUpdated = edgesUpdated,
            edgesSuperseded = edgesSuperseded,
            touchedEdgeIds = touchedEdgeIds,
        )
    }

    suspend fun supersedeEdge(edgeId: String) = writeLock.withLock {
        val now = System.currentTimeMillis()
        dao.markEdgeSuperseded(edgeId, validTo = now, updatedAt = now)
    }

    suspend fun restoreEdge(edgeId: String) = writeLock.withLock {
        dao.restoreEdge(edgeId, updatedAt = System.currentTimeMillis())
    }

    suspend fun deleteEdge(edgeId: String) = writeLock.withLock {
        dao.deleteEdgeById(edgeId)
    }

    suspend fun deleteNode(nodeId: String) = writeLock.withLock {
        dao.deleteEdgesByNode(nodeId)
        dao.deleteNodeById(nodeId)
    }

    suspend fun updateNodeSummary(nodeId: String, summary: String) = writeLock.withLock {
        val existing = dao.getNodeById(nodeId) ?: return@withLock
        dao.upsertNode(existing.copy(summary = summary.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteGraphOfAssistant(assistantId: String) = writeLock.withLock {
        dao.deleteEdgesOfAssistant(assistantId)
        dao.deleteNodesOfAssistant(assistantId)
    }

    suspend fun copyGraph(fromAssistantId: String, toAssistantId: String) = writeLock.withLock {
        val nodes = dao.getNodes(fromAssistantId)
        val edges = dao.getEdges(fromAssistantId)
        if (nodes.isEmpty() && edges.isEmpty()) return@withLock
        val idMap = nodes.associate { it.id to Uuid.random().toString() }
        val copiedNodes = nodes.map { node ->
            node.copy(
                id = idMap[node.id] ?: Uuid.random().toString(),
                assistantId = toAssistantId,
            )
        }
        val copiedEdges = edges.mapNotNull { edge ->
            val nextFrom = idMap[edge.fromId] ?: return@mapNotNull null
            val nextTo = idMap[edge.toId] ?: return@mapNotNull null
            edge.copy(
                id = Uuid.random().toString(),
                assistantId = toAssistantId,
                fromId = nextFrom,
                toId = nextTo,
            )
        }
        dao.upsertNodes(copiedNodes)
        dao.upsertEdges(copiedEdges)
    }
}

private fun MemoryGraphNodeEntity.toDomain(): MemoryGraphNode = MemoryGraphNode(
    id = id,
    assistantId = assistantId,
    type = GraphNodeType.fromWire(nodeType),
    name = name,
    nameNormalized = nameNormalized,
    aliases = parseAliasesJson(aliasesJson),
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MemoryGraphNode.toEntity(): MemoryGraphNodeEntity = MemoryGraphNodeEntity(
    id = id,
    assistantId = assistantId,
    nodeType = type.wireName,
    name = name,
    nameNormalized = nameNormalized,
    aliasesJson = JsonArray(aliases.map(::JsonPrimitive)).toString(),
    summary = summary,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MemoryGraphEdgeEntity.toDomain(): MemoryGraphEdge = MemoryGraphEdge(
    id = id,
    assistantId = assistantId,
    fromId = fromId,
    toId = toId,
    relation = edgeType,
    excerpt = excerpt,
    validFrom = validFrom,
    validTo = validTo,
    isCurrent = isCurrent,
    sourceConversationId = sourceConversationId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MemoryGraphEdge.toEntity(): MemoryGraphEdgeEntity = MemoryGraphEdgeEntity(
    id = id,
    assistantId = assistantId,
    fromId = fromId,
    toId = toId,
    edgeType = relation,
    excerpt = excerpt,
    validFrom = validFrom,
    validTo = validTo,
    isCurrent = isCurrent,
    sourceConversationId = sourceConversationId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun parseAliasesJson(raw: String): List<String> = runCatching {
    val arr = JsonInstant.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
    arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
}.getOrDefault(emptyList())
