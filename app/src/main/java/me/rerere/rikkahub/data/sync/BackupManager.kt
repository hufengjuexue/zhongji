package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.mapLocalFileUrls
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.awaitLoaded()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                val settingsJson = json.encodeSettings(settings, settingsStore.launchCountFlow.first())
                zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            remapImportedDatabaseUrls(room.openHelper.writableDatabase, context.filesDir, json)
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
                        val decoded = json.decodeFromString<Settings>(migrated)
                        require(!decoded.init) { "Backup contains uninitialized settings" }
                        val settings = decoded.remapImportedFileUrls(context.filesDir)
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeSettings(settings, json.launchCountOf(migrated)))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in RESTORE_ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val LEGACY_IMAGES_FOLDER = "images"

        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        private val RESTORE_ATTACHMENT_FOLDERS = ATTACHMENT_FOLDERS + LEGACY_IMAGES_FOLDER

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS =
            setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION, LEGACY_IMAGES_FOLDER)

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"

        private fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings).jsonObject + (LAUNCH_COUNT_KEY to JsonPrimitive(launchCount))
            return encodeToString(JsonObject(fields))
        }

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context = context,
                    settings = json.decodeFromString<Settings>(settingsJson),
                    launchCount = json.launchCountOf(settingsJson),
                )
            }
        }
    }
}

/**
 * 将备份中的跨包名/跨设备本地附件 URI（例如上游 `me.rerere.rikkahub` 路径）重映射到当前 [filesDir]。
 */
internal fun Settings.remapImportedFileUrls(filesDir: File): Settings {
    val mappedUserAvatar = (displaySetting.userAvatar as? Avatar.Image)?.let {
        Avatar.Image(FileUtils.remapAppFileUrl(filesDir, it.url))
    } ?: displaySetting.userAvatar
    val mappedFontPath = if (displaySetting.chatCustomFontPath.isNotBlank()) {
        FileUtils.remapAppFileUrl(filesDir, displaySetting.chatCustomFontPath)
    } else {
        ""
    }
    val mappedDisplay = displaySetting.copy(
        userAvatar = mappedUserAvatar,
        chatCustomFontPath = mappedFontPath,
    )
    val mappedAssistants = assistants.map { assistant ->
        val mappedAvatar = (assistant.avatar as? Avatar.Image)?.let {
            Avatar.Image(FileUtils.remapAppFileUrl(filesDir, it.url))
        } ?: assistant.avatar
        val mappedBg = assistant.background?.let { FileUtils.remapAppFileUrl(filesDir, it) }
        val mappedPresets = assistant.presetMessages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    part.mapLocalFileUrls { FileUtils.remapAppFileUrl(filesDir, it) }
                }
            )
        }
        assistant.copy(
            avatar = mappedAvatar,
            background = mappedBg,
            presetMessages = mappedPresets,
        )
    }
    return copy(
        displaySetting = mappedDisplay,
        assistants = mappedAssistants,
    )
}

internal fun remapJsonFileUrls(element: JsonElement, filesDir: File): JsonElement = when (element) {
    is JsonPrimitive -> if (element.isString) {
        val remapped = FileUtils.remapAppFileUrl(filesDir, element.content)
        if (remapped == element.content) element else JsonPrimitive(remapped)
    } else {
        element
    }
    is JsonArray -> JsonArray(element.map { remapJsonFileUrls(it, filesDir) })
    is JsonObject -> JsonObject(element.mapValues { (_, v) -> remapJsonFileUrls(v, filesDir) })
}

private fun remapImportedDatabaseUrls(
    db: SupportSQLiteDatabase,
    filesDir: File,
    json: Json,
) {
    val nodeUpdates = mutableListOf<Pair<String, String>>()
    db.query("SELECT id, messages FROM message_node WHERE messages LIKE ?", arrayOf("%/files/%")).use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val rawMessages = cursor.getString(1) ?: continue
            runCatching {
                val parsed = json.decodeFromString<List<UIMessage>>(rawMessages)
                val remapped = parsed.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            part.mapLocalFileUrls { FileUtils.remapAppFileUrl(filesDir, it) }
                        }
                    )
                }
                if (remapped != parsed) {
                    nodeUpdates += id to json.encodeToString(remapped)
                }
            }.onFailure {
                Log.w("BackupManager", "remapImportedDatabaseUrls: skip unparseable message_node $id", it)
            }
        }
    }

    val favoriteUpdates = mutableListOf<Pair<String, String>>()
    db.query("SELECT id, snapshot_json FROM favorites WHERE snapshot_json LIKE ?", arrayOf("%/files/%")).use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val rawSnapshot = cursor.getString(1) ?: continue
            runCatching {
                val element = json.parseToJsonElement(rawSnapshot)
                val remapped = remapJsonFileUrls(element, filesDir)
                if (remapped != element) {
                    favoriteUpdates += id to json.encodeToString(remapped)
                }
            }.onFailure {
                Log.w("BackupManager", "remapImportedDatabaseUrls: skip unparseable favorite $id", it)
            }
        }
    }

    if (nodeUpdates.isEmpty() && favoriteUpdates.isEmpty()) return
    db.beginTransaction()
    try {
        for ((id, updatedJson) in nodeUpdates) {
            db.execSQL("UPDATE message_node SET messages = ? WHERE id = ?", arrayOf(updatedJson, id))
        }
        for ((id, updatedJson) in favoriteUpdates) {
            db.execSQL("UPDATE favorites SET snapshot_json = ? WHERE id = ?", arrayOf(updatedJson, id))
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}
