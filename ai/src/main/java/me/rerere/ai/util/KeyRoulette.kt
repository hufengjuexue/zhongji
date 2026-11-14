package me.rerere.ai.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.KeyRotationMode
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

@Serializable
enum class KeyCooldownReason {
    READY,
    RPM_LIMIT,
    RPD_EXHAUSTED,
    AUTH_ERROR,
    SERVER_ERROR,
}

data class KeyCooldownInfo(
    val key: String,
    val reason: KeyCooldownReason,
    val cooldownUntilMs: Long,
    val consecutiveErrors: Int,
)

private val SPLIT_KEY_REGEX = "[\\s,;]+".toRegex()
private val RETRY_DELAY_REGEX = "\"retryDelay\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)s\"".toRegex()

fun parseApiKeys(raw: String): List<String> = raw
    .split(SPLIT_KEY_REGEX)
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()

fun maskApiKey(key: String): String = when {
    key.length > 14 -> "${key.take(8)}...${key.takeLast(4)}"
    key.length > 6 -> "${key.take(4)}...${key.takeLast(2)}"
    key.isNotBlank() -> "***"
    else -> ""
}

interface KeyRoulette {
    /**
     * 从逗号/空格/换行分隔的 key 列表里选一把。
     * sessionId 非空且存在多把 key 且模式为 [KeyRotationMode.STICKY] 时，按 (providerId, sessionId)
     * 确定性选 key，保证同一轮对话的每次请求粘住同一把 key，避免打断供应商前缀/KV 缓存；
     * 若粘住的 key 处于冷却期，则自动切向健康的备用 key。
     */
    fun next(keys: String, providerId: String = "", sessionId: String? = null): String

    fun next(
        keys: String,
        providerId: String = "",
        sessionId: String? = null,
        rotationMode: KeyRotationMode = KeyRotationMode.STICKY,
    ): String = next(
        keys = keys,
        providerId = providerId,
        sessionId = if (rotationMode == KeyRotationMode.ROUND_ROBIN) null else sessionId,
    )

    /** 汇报单次请求失败（429/5xx/401/402 等），触发短时冷却以自动切流 */
    fun reportError(key: String, statusCode: Int, retryAfterMs: Long? = null) {}

    /** 汇报 Google 请求失败：自动区分 RPM 瞬时限流与每日 RPD 耗尽（冷却至下个 PT 零点） */
    fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String? = null,
        responseBody: String? = null,
    ) {
        KeyCooldownTracker.reportGoogleError(key, statusCode, retryAfterHeader, responseBody)
    }

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

object KeyPoolMonitor {
    val statusVersion: StateFlow<Long>
        get() = KeyCooldownTracker.statusVersion

    fun inspect(keysRaw: String, now: Long = System.currentTimeMillis()): List<KeyCooldownInfo> =
        parseApiKeys(keysRaw).map { key -> KeyCooldownTracker.inspectKey(key, now) }

    fun reset(keysRaw: String) {
        KeyCooldownTracker.resetKeys(parseApiKeys(keysRaw))
    }

    fun resetAll() {
        KeyCooldownTracker.resetAll()
    }
}

@Serializable
private data class KeyCooldownState(
    val consecutiveErrors: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val reason: KeyCooldownReason = KeyCooldownReason.READY,
)

private const val COOLDOWN_CACHE_FILE = "key_cooldowns.json"

internal object KeyCooldownTracker {
    private val map = ConcurrentHashMap<String, KeyCooldownState>()
    private val _statusVersion = MutableStateFlow(0L)
    val statusVersion: StateFlow<Long> = _statusVersion.asStateFlow()

    @Volatile
    private var persistenceFile: File? = null
    private val persistenceLock = Any()

    fun attachPersistenceDir(cacheDir: File) {
        val file = File(cacheDir, COOLDOWN_CACHE_FILE)
        var shouldLoad = false
        synchronized(persistenceLock) {
            if (persistenceFile?.absolutePath != file.absolutePath) {
                persistenceFile = file
                shouldLoad = true
            }
        }
        if (shouldLoad) loadFromDisk(file)
    }

    private fun bumpVersion() {
        _statusVersion.value = _statusVersion.value + 1L
    }

    fun isHealthy(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val state = map[key] ?: return true
        if (now >= state.cooldownUntilMs) {
            map.remove(key, state)
            return true
        }
        return false
    }

    fun inspectKey(key: String, now: Long = System.currentTimeMillis()): KeyCooldownInfo {
        val state = map[key]
        if (state == null || now >= state.cooldownUntilMs) {
            if (state != null) map.remove(key, state)
            return KeyCooldownInfo(
                key = key,
                reason = KeyCooldownReason.READY,
                cooldownUntilMs = 0L,
                consecutiveErrors = 0,
            )
        }
        return KeyCooldownInfo(
            key = key,
            reason = state.reason,
            cooldownUntilMs = state.cooldownUntilMs,
            consecutiveErrors = state.consecutiveErrors,
        )
    }

    fun cooldownUntil(key: String): Long = map[key]?.cooldownUntilMs ?: 0L

    fun reportError(key: String, statusCode: Int, retryAfterMs: Long? = null, now: Long = System.currentTimeMillis()) {
        if (key.isBlank()) return
        var changed = false
        map.compute(key) { _, prev ->
            val errors = (prev?.consecutiveErrors ?: 0) + 1
            val (reason, durationMs) = when {
                statusCode == 429 -> KeyCooldownReason.RPM_LIMIT to (
                    retryAfterMs?.takeIf { it > 0 }
                        ?: min(120_000L, 15_000L * (1L shl min(errors - 1, 3)))
                    )
                statusCode in 401..403 -> KeyCooldownReason.AUTH_ERROR to 300_000L
                statusCode in 500..599 -> KeyCooldownReason.SERVER_ERROR to 15_000L
                else -> KeyCooldownReason.READY to 0L
            }
            if (durationMs <= 0L) {
                prev
            } else {
                changed = true
                KeyCooldownState(errors, now + durationMs, reason)
            }
        }
        if (changed) {
            bumpVersion()
            persistIfAttached(now)
        }
    }

    fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String? = null,
        responseBody: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (key.isBlank()) return
        var changed = false
        map.compute(key) { _, prev ->
            val errors = (prev?.consecutiveErrors ?: 0) + 1
            val (reason, durationMs) = classifyGoogleCooldown(
                statusCode = statusCode,
                consecutiveErrors = errors,
                retryAfterHeader = retryAfterHeader,
                responseBody = responseBody,
                nowMs = now,
            )
            if (durationMs <= 0L) {
                prev
            } else {
                changed = true
                KeyCooldownState(errors, now + durationMs, reason)
            }
        }
        if (changed) {
            bumpVersion()
            persistIfAttached(now)
        }
    }

    fun reportSuccess(key: String) {
        if (key.isBlank()) return
        if (map.remove(key) != null) {
            bumpVersion()
            persistIfAttached(System.currentTimeMillis())
        }
    }

    fun resetKeys(keys: List<String>) {
        var changed = false
        keys.forEach { key ->
            if (map.remove(key) != null) changed = true
        }
        if (changed) {
            bumpVersion()
            persistIfAttached(System.currentTimeMillis())
        }
    }

    fun resetAll() {
        if (map.isNotEmpty()) {
            map.clear()
            bumpVersion()
            persistIfAttached(System.currentTimeMillis())
        }
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

    private fun loadFromDisk(file: File) {
        synchronized(persistenceLock) {
            try {
                if (!file.exists()) return
                val now = System.currentTimeMillis()
                val loaded: Map<String, KeyCooldownState> = Json.decodeFromString(file.readText())
                loaded.forEach { (key, state) ->
                    if (state.cooldownUntilMs > now && state.reason != KeyCooldownReason.READY) {
                        map.putIfAbsent(key, state)
                    }
                }
                bumpVersion()
            } catch (_: Exception) {
            }
        }
    }

    private fun persistIfAttached(now: Long) {
        val file = persistenceFile ?: return
        synchronized(persistenceLock) {
            try {
                val active = map.entries
                    .filter { (_, state) -> state.cooldownUntilMs > now && state.reason != KeyCooldownReason.READY }
                    .associate { (k, v) -> k to v }
                if (active.isEmpty()) {
                    if (file.exists()) file.delete()
                } else {
                    file.writeText(Json.encodeToString(active))
                }
            } catch (_: Exception) {
            }
        }
    }
}

internal fun classifyGoogleCooldown(
    statusCode: Int,
    consecutiveErrors: Int,
    retryAfterHeader: String? = null,
    responseBody: String? = null,
    nowMs: Long = System.currentTimeMillis(),
): Pair<KeyCooldownReason, Long> {
    return when {
        statusCode in 401..403 -> KeyCooldownReason.AUTH_ERROR to 300_000L
        statusCode in 500..599 -> KeyCooldownReason.SERVER_ERROR to 15_000L
        statusCode == 429 -> {
            if (isGoogleDailyQuotaExhausted(responseBody)) {
                KeyCooldownReason.RPD_EXHAUSTED to msUntilNextPacificMidnight(nowMs)
            } else {
                val retryAfterMs = parseRetryAfterMs(retryAfterHeader, responseBody)
                val durationMs = retryAfterMs?.takeIf { it > 0 }
                    ?: min(120_000L, 15_000L * (1L shl min(consecutiveErrors - 1, 3)))
                KeyCooldownReason.RPM_LIMIT to durationMs
            }
        }
        else -> KeyCooldownReason.READY to 0L
    }
}

internal fun isGoogleDailyQuotaExhausted(responseBody: String?): Boolean {
    if (responseBody.isNullOrBlank()) return false
    return responseBody.contains("PerDay", ignoreCase = true) ||
        responseBody.contains("per_day", ignoreCase = true) ||
        responseBody.contains("per day", ignoreCase = true) ||
        responseBody.contains("daily limit", ignoreCase = true)
}

internal fun parseRetryAfterMs(retryAfterHeader: String?, responseBody: String?): Long? {
    retryAfterHeader?.trim()?.toDoubleOrNull()?.let { sec ->
        if (sec > 0) return (sec * 1000.0).toLong()
    }
    if (!responseBody.isNullOrBlank()) {
        RETRY_DELAY_REGEX.find(responseBody)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { sec ->
            if (sec > 0) return (sec * 1000.0).toLong()
        }
    }
    return null
}

internal fun msUntilNextPacificMidnight(nowMs: Long): Long {
    val pacific = ZoneId.of("America/Los_Angeles")
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), pacific)
    // 太平洋时间 00:01 解冻，留出 60 秒缓冲避免零点时差提前重试又撞限
    val nextReset = zdt.toLocalDate().plusDays(1).atStartOfDay(pacific).plusSeconds(60)
    return (nextReset.toInstant().toEpochMilli() - nowMs).coerceAtLeast(60_000L)
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
    private val roundRobinCursor = AtomicInteger(0)

    override fun next(keys: String, providerId: String, sessionId: String?): String {
        val keyList = parseApiKeys(keys)
        if (keyList.isEmpty()) return keys
        if (!sessionId.isNullOrEmpty() && keyList.size > 1) {
            return KeyCooldownTracker.pickStickyWithCooldown(keyList, providerId, sessionId)
        }
        val healthy = keyList.filter { KeyCooldownTracker.isHealthy(it) }
        val pool = if (healthy.isNotEmpty()) healthy else keyList
        val idx = Math.floorMod(roundRobinCursor.getAndIncrement(), pool.size)
        return pool[idx]
    }

    override fun reportError(key: String, statusCode: Int, retryAfterMs: Long?) =
        KeyCooldownTracker.reportError(key, statusCode, retryAfterMs)

    override fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String?,
        responseBody: String?,
    ) = KeyCooldownTracker.reportGoogleError(key, statusCode, retryAfterHeader, responseBody)

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

    init {
        KeyCooldownTracker.attachPersistenceDir(context.cacheDir)
    }

    override fun next(keys: String, providerId: String, sessionId: String?): String {
        val keyList = parseApiKeys(keys)
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

    override fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String?,
        responseBody: String?,
    ) = KeyCooldownTracker.reportGoogleError(key, statusCode, retryAfterHeader, responseBody)

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
