package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_33_34"

/**
 * 新增轻量级时序记忆关系图谱两张表：memory_graph_node 与 memory_graph_edge。
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 33 to 34 (memory temporal graph)")
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_graph_node` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, " +
                    "`node_type` TEXT NOT NULL, `name` TEXT NOT NULL, `name_normalized` TEXT NOT NULL, " +
                    "`aliases_json` TEXT NOT NULL, `summary` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_graph_node_assistant_id` " +
                    "ON `memory_graph_node` (`assistant_id`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_graph_node_assistant_id_node_type_name_normalized` " +
                    "ON `memory_graph_node` (`assistant_id`, `node_type`, `name_normalized`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `memory_graph_edge` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, " +
                    "`from_id` TEXT NOT NULL, `to_id` TEXT NOT NULL, `edge_type` TEXT NOT NULL, " +
                    "`excerpt` TEXT NOT NULL, `valid_from` INTEGER NOT NULL, `valid_to` INTEGER, " +
                    "`is_current` INTEGER NOT NULL, `source_conversation_id` TEXT, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_graph_edge_assistant_id_is_current` " +
                    "ON `memory_graph_edge` (`assistant_id`, `is_current`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_graph_edge_from_id` ON `memory_graph_edge` (`from_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memory_graph_edge_to_id` ON `memory_graph_edge` (`to_id`)"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 33 to 34 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
