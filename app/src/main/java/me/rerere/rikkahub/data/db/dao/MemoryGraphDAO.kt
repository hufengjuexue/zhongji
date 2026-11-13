package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryGraphEdgeEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphNodeEntity

@Dao
interface MemoryGraphDAO {
    @Query("SELECT * FROM memory_graph_node WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    fun getNodesFlow(assistantId: String): Flow<List<MemoryGraphNodeEntity>>

    @Query("SELECT * FROM memory_graph_node WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    suspend fun getNodes(assistantId: String): List<MemoryGraphNodeEntity>

    @Query("SELECT * FROM memory_graph_node WHERE id = :id LIMIT 1")
    suspend fun getNodeById(id: String): MemoryGraphNodeEntity?

    @Query("SELECT * FROM memory_graph_edge WHERE assistant_id = :assistantId ORDER BY valid_from DESC, created_at DESC")
    fun getEdgesFlow(assistantId: String): Flow<List<MemoryGraphEdgeEntity>>

    @Query("SELECT * FROM memory_graph_edge WHERE assistant_id = :assistantId ORDER BY valid_from DESC, created_at DESC")
    suspend fun getEdges(assistantId: String): List<MemoryGraphEdgeEntity>

    @Query("SELECT * FROM memory_graph_edge WHERE id = :id LIMIT 1")
    suspend fun getEdgeById(id: String): MemoryGraphEdgeEntity?

    @Upsert
    suspend fun upsertNode(node: MemoryGraphNodeEntity)

    @Upsert
    suspend fun upsertNodes(nodes: List<MemoryGraphNodeEntity>)

    @Upsert
    suspend fun upsertEdge(edge: MemoryGraphEdgeEntity)

    @Upsert
    suspend fun upsertEdges(edges: List<MemoryGraphEdgeEntity>)

    @Query("UPDATE memory_graph_edge SET is_current = 0, valid_to = :validTo, updated_at = :updatedAt WHERE id = :id")
    suspend fun markEdgeSuperseded(id: String, validTo: Long, updatedAt: Long)

    @Query("UPDATE memory_graph_edge SET is_current = 1, valid_to = NULL, updated_at = :updatedAt WHERE id = :id")
    suspend fun restoreEdge(id: String, updatedAt: Long)

    @Query("DELETE FROM memory_graph_edge WHERE id = :id")
    suspend fun deleteEdgeById(id: String)

    @Query("DELETE FROM memory_graph_edge WHERE from_id = :nodeId OR to_id = :nodeId")
    suspend fun deleteEdgesByNode(nodeId: String)

    @Query("DELETE FROM memory_graph_node WHERE id = :id")
    suspend fun deleteNodeById(id: String)

    @Query("DELETE FROM memory_graph_edge WHERE assistant_id = :assistantId")
    suspend fun deleteEdgesOfAssistant(assistantId: String)

    @Query("DELETE FROM memory_graph_node WHERE assistant_id = :assistantId")
    suspend fun deleteNodesOfAssistant(assistantId: String)
}
