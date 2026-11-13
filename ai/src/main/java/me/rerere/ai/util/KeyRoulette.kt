package me.rerere.ai.util

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

interface KeyRoulette {
    /**
     * 从逗号/空格分隔的 key 列表里选一把。
     * sessionId 非空且存在多把 key 时，按 (providerId, sessionId) 确定性选 key，
     * 保证同一轮对话的每次请求粘住同一把 key，避免打断供应商前缀/KV 缓存；
     * 若粘住的 key 因 429/5xx/401/402 处于冷却期，则自动切向健康的备用 key。
     */
    fun next(keys: String, providerId: String = "", sessionId: String? = null): String

    /** 汇报单次请求失败（429/5xx/401/402 等），触发短时冷却以自动切流 */
    fun reportError(key: String, statusCode: Int, retryAfterMs: Long? = null) {}

    /** 汇报单次请求成功，立即解除该 key 的冷却 */
    fun reportSuccess(key: String) {}

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU 轮询，持久化存储到 cacheDir/lru_key_roulette.json
         * 通过 providerId 区分同类型的多个 provider 实例，在 next() 调用时传入；
         * 传入 sessionId 且多把 key 时优先使用会话粘性以保前缀缓存。
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

private data class KeyCooldownState(
    val consecutiveErrors: Int = 0,
    val cooldownUntilMs: Long = 0L,
)

private object KeyCooldownTracker {
    private val map = ConcurrentHashMap<String, KeyCooldownState>()

    fun isHealthy(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val state = map[key] ?: return true
        return now >= state.cooldownUntilMs
    }

    fun cooldownUntil(key: String): Long = map[key]?.cooldownUntilMs ?: 0L

    fun reportError(key: String, statusCode: Int, retryAfterMs: Long? = null) {
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        map.compute(key) { _, prev ->
            val errors = (prev?.consecutiveErrors ?: 0) + 1
            val durationMs = when {
                statusCode == 429 -> retryAfterMs?.takeIf { it > 0 }
                    ?: min(120_000L, 15_000L * (1L shl min(errors - 1, 3)))
                statusCode in 401..403 -> 300_000L
                statusCode in 500..599 -> 15_000L
                else -> 0L
            }
            if (durationMs <= 0L) prev else KeyCooldownState(errors, now + durationMs)
        }
    }

    fun reportSuccess(key: String) {
        if (key.isNotBlank()) map.remove(key)
    }

    fun pickStickyWithCooldown(keyList: List<String>, providerId: String, sessionId: String): String {
        val primary = keyList[stickyIndexOf(providerId, sessionId, keyList.size)]
        val now = System.currentTimeMillis()
        if (isHealthy(primary, now)) return primary
        val healthy = keyList.filter { isHealthy(it, now) }
        return if (healthy.isNotEmpty()) {
            healthy[stickyIndexOf(providerId, sessionId, healthy.size)]
        } else {
            keyList.minByOrNull { cooldownUntil(it) } ?: primary
        }
    }
}

private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex() // 空格换行和逗号

private fun splitKey(key: String): List<String> {
    return key
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun stickyIndexOf(providerId: String, sessionId: String, size: Int): Int {
    val digest = java.security.MessageDigest.getInstance("MD5")
        .digest("$providerId|$sessionId".toByteArray())
    return Math.floorMod(
        ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff),
        size,
    )
}

private class DefaultKeyRoulette : KeyRoulette {
    override fun next(keys: String, providerId: String, sessionId: String?): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        if (!sessionId.isNullOrEmpty() && keyList.size > 1) {
            return KeyCooldownTracker.pickStickyWithCooldown(keyList, providerId, sessionId)
        }
        val healthy = keyList.filter { KeyCooldownTracker.isHealthy(it) }
        return (if (healthy.isNotEmpty()) healthy else keyList).random()
    }

    override fun reportError(key: String, statusCode: Int, retryAfterMs: Long?) =
        KeyCooldownTracker.reportError(key, statusCode, retryAfterMs)

    override fun reportSuccess(key: String) = KeyCooldownTracker.reportSuccess(key)
}

private const val LRU_CACHE_FILE = "lru_key_roulette.json"
private const val EXPIRE_DURATION_MS = 24 * 60 * 60 * 1000L // 1 天

// 全局文件锁，防止多个 provider 实例并发读写同一文件
private object LruFileLock

// 文件结构: Map<providerId, Map<apiKey, lastUsedTimestamp>>
private typealias LruCache = Map<String, Map<String, Long>>

private class LruKeyRoulette(
    private val context: Context,
) : KeyRoulette {

    override fun next(keys: String, providerId: String, sessionId: String?): String {
        val keyList = splitKey(keys)
        if (keyList.isEmpty()) return keys
        if (!sessionId.isNullOrEmpty() && keyList.size > 1) {
            return KeyCooldownTracker.pickStickyWithCooldown(keyList, providerId, sessionId)
        }

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val candidates = keyList.filter { KeyCooldownTracker.isHealthy(it, now) }
                .ifEmpty { keyList }

            // 取本 provider 的记录，过滤掉已过期条目和不在当前 key 列表中的条目
            val providerCache = (allCache[providerId] ?: emptyMap())
                .filter { (k, lastUsed) -> k in keyList && now - lastUsed < EXPIRE_DURATION_MS }
                .toMutableMap()

            // 优先选从未使用的健康 key，否则选最久未使用的健康 key
            val selected = candidates.firstOrNull { it !in providerCache }
                ?: candidates.minByOrNull { providerCache[it] ?: 0L }!!

            providerCache[selected] = now
            allCache[providerId] = providerCache

            // 清理整个 provider 条目均已过期的记录
            allCache.entries.removeIf { (id, cache) ->
                id != providerId && cache.values.all { now - it >= EXPIRE_DURATION_MS }
            }

            saveCache(allCache)
            return selected
        }
    }

    override fun reportError(key: String, statusCode: Int, retryAfterMs: Long?) =
        KeyCooldownTracker.reportError(key, statusCode, retryAfterMs)

    override fun reportSuccess(key: String) = KeyCooldownTracker.reportSuccess(key)

    private fun loadCache(): LruCache {
        return try {
            val file = File(context.cacheDir, LRU_CACHE_FILE)
            if (!file.exists()) return emptyMap()
            Json.decodeFromString(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCache(cache: LruCache) {
        try {
            File(context.cacheDir, LRU_CACHE_FILE).writeText(Json.encodeToString(cache))
        } catch (_: Exception) {
        }
    }
}
