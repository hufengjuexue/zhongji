package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_graph_node",
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["assistant_id", "node_type", "name_normalized"], unique = true),
    ],
)
data class MemoryGraphNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("node_type")
    val nodeType: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("name_normalized")
    val nameNormalized: String,
    @ColumnInfo("aliases_json")
    val aliasesJson: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "memory_graph_edge",
    indices = [
        Index(value = ["assistant_id", "is_current"]),
        Index(value = ["from_id"]),
        Index(value = ["to_id"]),
    ],
)
data class MemoryGraphEdgeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("from_id")
    val fromId: String,
    @ColumnInfo("to_id")
    val toId: String,
    @ColumnInfo("edge_type")
    val edgeType: String,
    @ColumnInfo("excerpt")
    val excerpt: String,
    @ColumnInfo("valid_from")
    val validFrom: Long,
    @ColumnInfo("valid_to")
    val validTo: Long?,
    @ColumnInfo("is_current")
    val isCurrent: Boolean,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String?,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
