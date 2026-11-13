package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class GraphNodeType(val wireName: String) {
    CHARACTER("character"),
    LOCATION("location"),
    FACTION("faction"),
    ITEM("item"),
    VOW("vow"),
    EVENT("event"),
    CONCEPT("concept");

    companion object {
        fun fromWire(raw: String?): GraphNodeType {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "character", "person", "人物", "角色" -> CHARACTER
                "location", "place", "地点", "场所" -> LOCATION
                "faction", "organization", "org", "势力", "组织" -> FACTION
                "item", "object", "artifact", "物品", "道具" -> ITEM
                "vow", "promise", "secret", "约定", "誓约", "伏笔" -> VOW
                "event", "arc", "plot", "事件", "剧情" -> EVENT
                else -> entries.find { it.wireName == normalized } ?: CONCEPT
            }
        }
    }
}

data class MemoryGraphNode(
    val id: String,
    val assistantId: String,
    val type: GraphNodeType,
    val name: String,
    val nameNormalized: String,
    val aliases: List<String> = emptyList(),
    val summary: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class MemoryGraphEdge(
    val id: String,
    val assistantId: String,
    val fromId: String,
    val toId: String,
    val relation: String,
    val excerpt: String = "",
    val validFrom: Long = 0L,
    val validTo: Long? = null,
    val isCurrent: Boolean = true,
    val sourceConversationId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class MemoryGraphSnapshot(
    val nodes: List<MemoryGraphNode> = emptyList(),
    val edges: List<MemoryGraphEdge> = emptyList(),
) {
    val nodeMap: Map<String, MemoryGraphNode> by lazy { nodes.associateBy { it.id } }

    fun currentEdges(): List<MemoryGraphEdge> = edges.filter { it.isCurrent }

    fun historicalEdges(): List<MemoryGraphEdge> = edges.filter { !it.isCurrent }

    fun edgesOf(nodeId: String, includeHistorical: Boolean = true): List<MemoryGraphEdge> =
        edges.filter {
            (it.fromId == nodeId || it.toId == nodeId) && (includeHistorical || it.isCurrent)
        }

    fun matchNodes(query: String, typeFilter: GraphNodeType? = null): List<MemoryGraphNode> {
        val norm = normalizeGraphName(query)
        val filtered = if (typeFilter == null) nodes else nodes.filter { it.type == typeFilter }
        if (norm.isEmpty()) return filtered

        val exact = mutableListOf<MemoryGraphNode>()
        val prefixOrAlias = mutableListOf<MemoryGraphNode>()
        val substring = mutableListOf<MemoryGraphNode>()

        for (node in filtered) {
            val aliasNorms = node.aliases.map(::normalizeGraphName)
            when {
                node.nameNormalized == norm || aliasNorms.any { it == norm } -> exact += node
                node.nameNormalized.startsWith(norm) || aliasNorms.any { it.startsWith(norm) } -> prefixOrAlias += node
                node.nameNormalized.contains(norm) ||
                    aliasNorms.any { it.contains(norm) } ||
                    normalizeGraphName(node.summary).contains(norm) -> substring += node
            }
        }
        return exact + prefixOrAlias + substring
    }

    /**
     * 在当前有效边（isCurrent = true）上进行 BFS 邻域与多跳路径探索，返回最短连通路径。
     */
    fun findNetworkPaths(
        anchorIds: Set<String>,
        maxDepth: Int = 3,
        limit: Int = 12,
    ): List<List<MemoryGraphEdge>> {
        if (anchorIds.isEmpty()) return emptyList()
        val activeEdges = currentEdges()
        if (activeEdges.isEmpty()) return emptyList()

        val adj = HashMap<String, MutableList<MemoryGraphEdge>>()
        for (edge in activeEdges) {
            adj.getOrPut(edge.fromId) { mutableListOf() }.add(edge)
            adj.getOrPut(edge.toId) { mutableListOf() }.add(edge)
        }

        val paths = mutableListOf<List<MemoryGraphEdge>>()
        val seenEdgeSeq = HashSet<String>()
        val queue = ArrayDeque<Pair<String, List<MemoryGraphEdge>>>()
        val visitedPerAnchor = HashMap<String, Int>()

        for (startId in anchorIds.take(4)) {
            queue.addLast(startId to emptyList())
            visitedPerAnchor[startId] = 0
        }

        while (queue.isNotEmpty() && paths.size < limit) {
            val (current, path) = queue.removeFirst()
            if (path.isNotEmpty()) {
                val sig = path.joinToString("->") { it.id }
                if (seenEdgeSeq.add(sig)) {
                    paths += path
                    if (paths.size >= limit) break
                }
            }
            if (path.size >= maxDepth) continue
            for (edge in adj[current].orEmpty()) {
                if (path.any { it.id == edge.id }) continue
                val nextId = if (edge.fromId == current) edge.toId else edge.fromId
                val nextDepth = path.size + 1
                val prevBest = visitedPerAnchor[nextId]
                if (prevBest == null || nextDepth <= prevBest + 1) {
                    if (prevBest == null || nextDepth < prevBest) {
                        visitedPerAnchor[nextId] = nextDepth
                    }
                    queue.addLast(nextId to (path + edge))
                }
            }
        }
        return paths
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun normalizeGraphName(raw: String): String =
            raw.trim().replace(WHITESPACE, " ").lowercase()
    }
}
