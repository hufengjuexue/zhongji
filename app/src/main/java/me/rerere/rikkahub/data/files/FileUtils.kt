package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlin.uuid.Uuid

object FileUtils {
    private const val TAG = "FileUtils"

    private val APP_FILES_URL_REGEX = Regex(
        """^(file://)?/data/(?:user/\d+|data)/[^/]+/files/((?:upload|skills|fonts|media_creation|images|tool_outputs)/.+)$"""
    )

    /**
     * 将其他包名（如上游 me.rerere.rikkahub）或其他用户目录的私有 filesDir 路径/URI
     * 重映射到当前应用的 [filesDir]，保证跨应用导入备份后图片、文档与头像仍可访问。
     */
    fun remapAppFileUrl(filesDir: File, raw: String): String {
        val match = APP_FILES_URL_REGEX.matchEntire(raw) ?: return raw
        val scheme = match.groupValues[1]
        val relativePath = match.groupValues[2]
        val baseDir = filesDir.invariantSeparatorsPath.trimEnd('/')
        return if (scheme.isNotEmpty()) {
            "file://$baseDir/$relativePath"
        } else {
            "$baseDir/$relativePath"
        }
    }

    /**
     * 解析本地附件或图片路径，若原路径不存在则自动回退到重映射后的当前应用 [Context.filesDir]。
     */
    fun resolveLocalFile(context: Context, url: String): File? {
        val direct = toRawFile(url)
        if (direct != null && direct.exists()) return direct
        val remapped = remapAppFileUrl(context.filesDir, url)
        if (remapped != url) {
            return toRawFile(remapped) ?: direct
        }
        return direct
    }

    private fun toRawFile(url: String): File? = when {
        url.startsWith("file:") -> runCatching { url.toUri().toFile() }.getOrNull()
        url.startsWith("/") -> File(url)
        else -> null
    }

    fun buildUuidFileName(displayName: String?, mimeType: String?): String {
        val extFromName = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it != displayName }
            ?.lowercase()
        val extFromMime = mimeType
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) }
            ?.takeIf { it.isNotBlank() }
            ?.lowercase()
        val ext = extFromName ?: extFromMime ?: "bin"
        return "${Uuid.random()}.$ext"
    }

    fun buildRelativePath(folder: String, file: File): String =
        "$folder/${file.name}"

    fun getRelativePathInFilesDir(filesDir: File, file: File): String? {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val canonicalFilesDir = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
        val basePath = canonicalFilesDir.path
        val filePath = canonicalFile.path
        if (!filePath.startsWith("$basePath${File.separator}")) {
            return null
        }
        return canonicalFile.relativeTo(canonicalFilesDir).path.replace(File.separatorChar, '/')
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            var fileName: String? = null
            val projection = arrayOf(
                OpenableColumns.DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val documentDisplayNameIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (documentDisplayNameIndex != -1) {
                        fileName = cursor.getString(documentDisplayNameIndex)
                    } else {
                        val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (openableDisplayNameIndex != -1) {
                            fileName = cursor.getString(openableDisplayNameIndex)
                        }
                    }
                }
            }
            fileName
        }.onFailure {
            Log.w(TAG, "getFileNameFromUri: Failed to query display name for $uri", it)
        }.getOrNull()
    }

    fun getFileMimeType(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> runCatching {
                context.contentResolver.getType(uri)
            }.onFailure {
                Log.w(TAG, "getFileMimeType: Failed to resolve MIME for $uri", it)
            }.getOrNull()
            else -> null
        }
    }

    fun guessMimeType(file: File, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
        return sniffMimeType(file)
    }

    fun compressBitmapToPng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }

    private fun sniffMimeType(file: File): String {
        val header = ByteArray(16)
        val read = runCatching {
            FileInputStream(file).use { input ->
                input.read(header)
            }
        }.getOrDefault(-1)

        if (read <= 0) return "application/octet-stream"

        if (header.startsWithBytes(0x89, 0x50, 0x4E, 0x47)) return "image/png"
        if (header.startsWithBytes(0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (header.startsWithBytes(0x47, 0x49, 0x46, 0x38)) return "image/gif"
        if (header.startsWithBytes(0x25, 0x50, 0x44, 0x46)) return "application/pdf"
        if (header.startsWithBytes(0x50, 0x4B, 0x03, 0x04)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x05, 0x06)) return "application/zip"
        if (header.startsWithBytes(0x50, 0x4B, 0x07, 0x08)) return "application/zip"
        if (header.startsWithBytes(0x52, 0x49, 0x46, 0x46) && header.sliceArray(8..11)
                .contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) {
            return "image/webp"
        }
        // HEIF/HEIC/AVIF: ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        if (read >= 12 && header.sliceArray(4..7).toString(Charsets.US_ASCII) == "ftyp") {
            when (header.sliceArray(8..11).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                    -> return "image/heic"

                "avif", "avis" -> return "image/avif"
            }
        }

        val textSample = runCatching {
            val sample = ByteArray(512)
            FileInputStream(file).use { input ->
                val len = input.read(sample)
                if (len <= 0) return@runCatching null
                sample.copyOf(len)
            }
        }.getOrNull()
        if (textSample != null && isLikelyText(textSample)) {
            return "text/plain"
        }

        return "application/octet-stream"
    }

    private fun isLikelyText(bytes: ByteArray): Boolean {
        var printable = 0
        var total = 0
        bytes.forEach { b ->
            val c = b.toInt() and 0xFF
            total += 1
            if (c == 0x09 || c == 0x0A || c == 0x0D) {
                printable += 1
            } else if (c in 0x20..0x7E) {
                printable += 1
            }
        }
        return total > 0 && printable.toDouble() / total >= 0.8
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (this.size < values.size) return false
        for (i in values.indices) {
            if ((this[i].toInt() and 0xFF) != values[i]) return false
        }
        return true
    }
}
