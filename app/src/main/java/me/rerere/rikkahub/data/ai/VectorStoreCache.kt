package me.rerere.rikkahub.data.ai

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 向量存储（Vector Storage）本地缓存：
 * - 以 (嵌入模型ID, 文本内容) 的 SHA-256 为键持久化到 filesDir/vector_cache；
 * - 新写入采用紧凑二进制格式（VEC1 + 维度 + Float32 数组），同时向后兼容读取旧版纯文本格式；
 * - 带有轻量上限清理，避免长期修改记忆后遗留大量死缓存。
 */
object VectorStoreCache {
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private const val MAX_CACHE_FILES = 2500
    private const val PRUNE_CHECK_INTERVAL = 64
    private val writeCounter = AtomicInteger(0)

    private fun dir(context: Context): File =
        File(context.filesDir, "vector_cache").apply { if (!exists()) mkdirs() }

    private fun file(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir(context), "$name.vec")
    }

    fun get(context: Context, key: String): FloatArray? {
        val f = file(context, key)
        if (!f.exists()) return null
        return runCatching {
            val bytes = f.readBytes()
            if (isBinaryFormat(bytes)) {
                decodeBinary(bytes)
            } else {
                bytes.decodeToString()
                    .split(",")
                    .mapNotNull { it.trim().toFloatOrNull() }
                    .toFloatArray()
                    .takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    fun put(context: Context, key: String, vector: FloatArray) {
        if (vector.isEmpty()) return
        runCatching {
            val buffer = ByteBuffer.allocate(8 + vector.size * 4).order(ByteOrder.BIG_ENDIAN)
            buffer.put(MAGIC)
            buffer.putInt(vector.size)
            for (v in vector) {
                buffer.putFloat(v)
            }
            file(context, key).writeBytes(buffer.array())
            if (writeCounter.incrementAndGet() % PRUNE_CHECK_INTERVAL == 0) {
                pruneOverflow(dir(context))
            }
        }
    }

    private fun isBinaryFormat(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == MAGIC[0] &&
            bytes[1] == MAGIC[1] &&
            bytes[2] == MAGIC[2] &&
            bytes[3] == MAGIC[3]

    private fun decodeBinary(bytes: ByteArray): FloatArray? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val dim = buffer.int
        if (dim <= 0 || bytes.size != 8 + dim * 4) return null
        return FloatArray(dim) { buffer.float }
    }

    private fun pruneOverflow(cacheDir: File) {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".vec") } ?: return
        val excess = files.size - MAX_CACHE_FILES
        if (excess <= 0) return
        files.sortedBy { it.lastModified() }
            .take(excess)
            .forEach { it.delete() }
    }

    /** 余弦相似度；任一向量全零或维度不一致返回 0 */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}
