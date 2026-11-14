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

enum class GoogleQuotaBucket(
    val shortLabel: String,
    val displayName: String,
    val perKeyDailyLimit: Int,
) {
    GEMINI_3_6("3.6", "3.6 Flash", 20),
    GEMINI_3_8("3.8", "3.8 Flash", 20),
    GEMINI_4_HYDROGEN("H4", "Hydrogen", 20),
    EMBEDDING("Emb", "Embedding", 1000),
    DECISION("Dec", "Decision", 10000),
    ;

    companion object {
        val CHAT_RP_BUCKETS = listOf(GEMINI_3_6, GEMINI_3_8, GEMINI_4_HYDROGEN)
    }
}

data class KeyCooldownInfo(
    val key: String,
    val reason: KeyCooldownReason,
    val cooldownUntilMs: Long,
    val consecutiveErrors: Int,
)

data class GoogleBucketUsage(
    val used: Int = 0,
    val reason: KeyCooldownReason = KeyCooldownReason.READY,
    val cooldownUntilMs: Long = 0L,
)

data class KeyPoolItemSnapshot(
    val key: String,
    val globalReason: KeyCooldownReason,
    val globalCooldownUntilMs: Long,
    val bucketUsage: Map<GoogleQuotaBucket, GoogleBucketUsage>,
    val otherChatCount: Int = 0,
) {
    val hasAnyCooling: Boolean
        get() = globalReason != KeyCooldownReason.READY ||
            bucketUsage.values.any { it.reason != KeyCooldownReason.READY }
}

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

internal fun normalizeQuotaModelId(modelId: String?): String =
    modelId.orEmpty().trim().removePrefix("models/").lowercase()

fun classifyGoogleModelBucket(modelId: String): GoogleQuotaBucket? {
    val m = normalizeQuotaModelId(modelId)
    if (m.isBlank()) return null
    return when {
        "decision" in m -> GoogleQuotaBucket.DECISION
        "embed" in m -> GoogleQuotaBucket.EMBEDDING
        "hydrogen" in m -> GoogleQuotaBucket.GEMINI_4_HYDROGEN
        "3.8" in m || "3-8" in m -> GoogleQuotaBucket.GEMINI_3_8
        "3.6" in m || "3-6" in m -> GoogleQuotaBucket.GEMINI_3_6
        else -> null
    }
}

interface KeyRoulette {
    /**
     * 从逗号/空格/换行分隔的 key 列表里选一把。
     */
    fun next(keys: String, providerId: String = "", sessionId: String? = null): String

    fun next(
        keys: String,
        providerId: String = "",
        sessionId: String? = null,
        rotationMode: KeyRotationMode = KeyRotationMode.SEQUENTIAL,
        modelId: String? = null,
    ): String = next(
        keys = keys,
        providerId = providerId,
        sessionId = if (rotationMode == KeyRotationMode.ROUND_ROBIN) null else sessionId,
    )

    /** 汇报单次请求失败（429/5xx/401/402 等），触发短时冷却以自动切流 */
    fun reportError(key: String, statusCode: Int, retryAfterMs: Long? = null) {}

    /** 汇报 Google 请求失败：按 (key, modelId) 精准区分 RPM 瞬时限流与每日 RPD 耗尽 */
    fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String? = null,
        responseBody: String? = null,
        modelId: String? = null,
    ) {
        KeyCooldownTracker.reportGoogleError(key, statusCode, retryAfterHeader, responseBody, modelId)
    }

    /** 汇报单次请求成功，立即解除该 key(+model) 冷却，并在提供 modelId 时累计今日官方用量次数 */
    fun reportSuccess(key: String) {}

    fun reportSuccess(key: String, modelId: String? = null) {
        if (modelId.isNullOrBlank()) {
            reportSuccess(key)
        } else {
            KeyCooldownTracker.reportSuccess(key, modelId)
        }
    }

    companion object {
        fun default(): KeyRoulette = DefaultKeyRoulette()

        /**
         * LRU / 顺序 / 粘性多模轮询，持久化存储到 cacheDir
         */
        fun lru(context: Context): KeyRoulette = LruKeyRoulette(context)
    }
}

object KeyPoolMonitor {
    val statusVersion: StateFlow<Long>
        get() = KeyCooldownTracker.statusVersion

    fun inspect(keysRaw: String, now: Long = System.currentTimeMillis()): List<KeyCooldownInfo> =
        parseApiKeys(keysRaw).map { key -> KeyCooldownTracker.inspectKey(key, now) }

    fun inspectPool(keysRaw: String, now: Long = System.currentTimeMillis()): List<KeyPoolItemSnapshot> =
        parseApiKeys(keysRaw).map { key -> KeyCooldownTracker.inspectKeyDetail(key, now) }

    fun reset(keysRaw: String) {
        KeyCooldownTracker.resetKeys(parseApiKeys(keysRaw))
    }

    fun resetDailyUsage(keysRaw: String) {
        KeyCooldownTracker.resetDailyUsage(parseApiKeys(keysRaw))
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

@Serializable
private data class PersistedDailyUsage(
    val ptDate: String = "",
    // key -> (normalizedModelId -> count)
    val counts: Map<String, Map<String, Int>> = emptyMap(),
)

private const val COOLDOWN_CACHE_FILE = "key_cooldowns_v2.json"
private const val DAILY_USAGE_CACHE_FILE = "key_daily_usage_v1.json"

internal object KeyCooldownTracker {
    // 键格式: key 级误差用 "apiKey"，模型级 429 用 "apiKey|normalizedModelId"
    private val map = ConcurrentHashMap<String, KeyCooldownState>()
    private val dailyCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    @Volatile
    private var currentPtDate: String = currentPacificDateString(System.currentTimeMillis())

    private val _statusVersion = MutableStateFlow(0L)
    val statusVersion: StateFlow<Long> = _statusVersion.asStateFlow()

    @Volatile
    private var persistenceDir: File? = null
    private val persistenceLock = Any()

    private fun entryKey(key: String, modelId: String?): String {
        val norm = normalizeQuotaModelId(modelId)
        return if (norm.isBlank()) key else "$key|$norm"
    }

    fun attachPersistenceDir(cacheDir: File) {
        var shouldLoad = false
        synchronized(persistenceLock) {
            if (persistenceDir?.absolutePath != cacheDir.absolutePath) {
                persistenceDir = cacheDir
                shouldLoad = true
            }
        }
        if (shouldLoad) loadFromDisk(cacheDir)
    }

    private fun bumpVersion() {
        _statusVersion.value = _statusVersion.value + 1L
    }

    private fun ensureCurrentPtDateLocked(now: Long) {
        val ptToday = currentPacificDateString(now)
        if (ptToday != currentPtDate) {
            dailyCounts.clear()
            currentPtDate = ptToday
        }
    }

    fun isHealthy(key: String, modelId: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        val globalState = map[key]
        if (globalState != null) {
            if (now < globalState.cooldownUntilMs) return false
            map.remove(key, globalState)
        }
        val norm = normalizeQuotaModelId(modelId)
        if (norm.isNotBlank()) {
            val mk = "$key|$norm"
            val modelState = map[mk]
            if (modelState != null) {
                if (now < modelState.cooldownUntilMs) return false
                map.remove(mk, modelState)
            }
        }
        return true
    }

    fun inspectKey(key: String, now: Long = System.currentTimeMillis()): KeyCooldownInfo {
        val activeStates = collectActiveStatesForKey(key, now)
        if (activeStates.isEmpty()) {
            return KeyCooldownInfo(
                key = key,
                reason = KeyCooldownReason.READY,
                cooldownUntilMs = 0L,
                consecutiveErrors = 0,
            )
        }
        // 取最显著的状态返回，供简版检查接口使用
        val primary = activeStates.maxByOrNull { it.cooldownUntilMs }!!
        return KeyCooldownInfo(
            key = key,
            reason = primary.reason,
            cooldownUntilMs = primary.cooldownUntilMs,
            consecutiveErrors = primary.consecutiveErrors,
        )
    }

    fun inspectKeyDetail(key: String, now: Long = System.currentTimeMillis()): KeyPoolItemSnapshot {
        val globalState = map[key]?.takeIf { now < it.cooldownUntilMs }
            ?: KeyCooldownState(reason = KeyCooldownReason.READY)

        val ptToday = currentPacificDateString(now)
        val modelCounts = if (ptToday == currentPtDate) {
            dailyCounts[key]?.toMap().orEmpty()
        } else {
            emptyMap()
        }

        val prefix = "$key|"
        val modelCooldowns = map.entries
            .asSequence()
            .filter { (ek, state) -> ek.startsWith(prefix) && now < state.cooldownUntilMs }
            .associate { (ek, state) -> ek.removePrefix(prefix) to state }

        val bucketCounts = mutableMapOf<GoogleQuotaBucket, Int>()
        val bucketStates = mutableMapOf<GoogleQuotaBucket, KeyCooldownState>()
        var otherChat = 0

        modelCounts.forEach { (normModel, count) ->
            val bucket = classifyGoogleModelBucket(normModel)
            if (bucket != null) {
                bucketCounts[bucket] = (bucketCounts[bucket] ?: 0) + count
            } else {
                otherChat += count
            }
        }
        modelCooldowns.forEach { (normModel, state) ->
            val bucket = classifyGoogleModelBucket(normModel) ?: return@forEach
            val prev = bucketStates[bucket]
            if (prev == null || state.cooldownUntilMs > prev.cooldownUntilMs) {
                bucketStates[bucket] = state
            }
        }

        val bucketUsage = GoogleQuotaBucket.entries.associateWith { bucket ->
            val st = bucketStates[bucket]
            GoogleBucketUsage(
                used = bucketCounts[bucket] ?: 0,
                reason = st?.reason ?: KeyCooldownReason.READY,
                cooldownUntilMs = st?.cooldownUntilMs ?: 0L,
            )
        }

        return KeyPoolItemSnapshot(
            key = key,
            globalReason = globalState.reason,
            globalCooldownUntilMs = globalState.cooldownUntilMs,
            bucketUsage = bucketUsage,
            otherChatCount = otherChat,
        )
    }

    private fun collectActiveStatesForKey(key: String, now: Long): List<KeyCooldownState> {
        val prefix = "$key|"
        return map.entries.mapNotNull { (ek, state) ->
            if (ek == key || ek.startsWith(prefix)) {
                if (now < state.cooldownUntilMs) state else {
                    map.remove(ek, state)
                    null
                }
            } else null
        }
    }

    fun cooldownUntil(key: String, modelId: String? = null): Long {
        val g = map[key]?.cooldownUntilMs ?: 0L
        val m = normalizeQuotaModelId(modelId).let { norm ->
            if (norm.isBlank()) 0L else map["$key|$norm"]?.cooldownUntilMs ?: 0L
        }
        return maxOf(g, m)
    }

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
            persistAllIfAttached(now)
        }
    }

    fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String? = null,
        responseBody: String? = null,
        modelId: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (key.isBlank()) return
        var changed = false
        val targetEntryKey = when {
            statusCode == 429 && !modelId.isNullOrBlank() -> entryKey(key, modelId)
            else -> key
        }
        map.compute(targetEntryKey) { _, prev ->
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
            persistAllIfAttached(now)
        }
    }

    fun reportSuccess(key: String, modelId: String? = null, now: Long = System.currentTimeMillis()) {
        if (key.isBlank()) return
        var changed = map.remove(key) != null
        val norm = normalizeQuotaModelId(modelId)
        if (norm.isNotBlank()) {
            if (map.remove("$key|$norm") != null) changed = true
            synchronized(persistenceLock) {
                ensureCurrentPtDateLocked(now)
                val perKey = dailyCounts.computeIfAbsent(key) { ConcurrentHashMap() }
                perKey.compute(norm) { _, prev -> (prev ?: 0) + 1 }
            }
            changed = true
        }
        if (changed) {
            bumpVersion()
            persistAllIfAttached(now)
        }
    }

    fun resetKeys(keys: List<String>) {
        var changed = false
        keys.forEach { key ->
            val prefix = "$key|"
            map.keys.filter { it == key || it.startsWith(prefix) }.forEach { ek ->
                if (map.remove(ek) != null) changed = true
            }
        }
        if (changed) {
            bumpVersion()
            persistAllIfAttached(System.currentTimeMillis())
        }
    }

    fun resetDailyUsage(keys: List<String>) {
        var changed = false
        synchronized(persistenceLock) {
            keys.forEach { key ->
                if (dailyCounts.remove(key) != null) changed = true
            }
        }
        if (changed) {
            bumpVersion()
            persistAllIfAttached(System.currentTimeMillis())
        }
    }

    fun resetAll() {
        var changed = false
        if (map.isNotEmpty()) {
            map.clear()
            changed = true
        }
        synchronized(persistenceLock) {
            if (dailyCounts.isNotEmpty()) {
                dailyCounts.clear()
                changed = true
            }
        }
        if (changed) {
            bumpVersion()
            persistAllIfAttached(System.currentTimeMillis())
        }
    }

    fun pickSequentialWithCooldown(keyList: List<String>, modelId: String? = null): String {
        val now = System.currentTimeMillis()
        return keyList.firstOrNull { isHealthy(it, modelId, now) }
            ?: keyList.minByOrNull { cooldownUntil(it, modelId) }
            ?: keyList.first()
    }

    fun pickStickyWithCooldown(
        keyList: List<String>,
        providerId: String,
        sessionId: String,
        modelId: String? = null,
    ): String {
        val primary = keyList[stickyIndexOf(providerId, sessionId, keyList.size)]
        val now = System.currentTimeMillis()
        if (isHealthy(primary, modelId, now)) return primary
        val healthy = keyList.filter { isHealthy(it, modelId, now) }
        return if (healthy.isNotEmpty()) {
            healthy[stickyIndexOf(providerId, sessionId, healthy.size)]
        } else {
            keyList.minByOrNull { cooldownUntil(it, modelId) } ?: primary
        }
    }

    private fun loadFromDisk(cacheDir: File) {
        synchronized(persistenceLock) {
            val now = System.currentTimeMillis()
            try {
                val cooldownFile = File(cacheDir, COOLDOWN_CACHE_FILE)
                if (cooldownFile.exists()) {
                    val loaded: Map<String, KeyCooldownState> = Json.decodeFromString(cooldownFile.readText())
                    loaded.forEach { (ek, state) ->
                        if (state.cooldownUntilMs > now && state.reason != KeyCooldownReason.READY) {
                            map.putIfAbsent(ek, state)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            try {
                val usageFile = File(cacheDir, DAILY_USAGE_CACHE_FILE)
                val ptToday = currentPacificDateString(now)
                currentPtDate = ptToday
                if (usageFile.exists()) {
                    val loaded: PersistedDailyUsage = Json.decodeFromString(usageFile.readText())
                    if (loaded.ptDate == ptToday) {
                        loaded.counts.forEach { (key, perModel) ->
                            val target = dailyCounts.computeIfAbsent(key) { ConcurrentHashMap() }
                            perModel.forEach { (m, c) -> if (c > 0) target.putIfAbsent(m, c) }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            bumpVersion()
        }
    }

    private fun persistAllIfAttached(now: Long) {
        val dir = persistenceDir ?: return
        synchronized(persistenceLock) {
            try {
                val cooldownFile = File(dir, COOLDOWN_CACHE_FILE)
                val active = map.entries
                    .filter { (_, state) -> state.cooldownUntilMs > now && state.reason != KeyCooldownReason.READY }
                    .associate { (k, v) -> k to v }
                if (active.isEmpty()) {
                    if (cooldownFile.exists()) cooldownFile.delete()
                } else {
                    cooldownFile.writeText(Json.encodeToString(active))
                }
            } catch (_: Exception) {
            }
            try {
                val usageFile = File(dir, DAILY_USAGE_CACHE_FILE)
                ensureCurrentPtDateLocked(now)
                val snapshot = dailyCounts.entries
                    .mapNotNull { (k, v) ->
                        val inner = v.toMap().filterValues { it > 0 }
                        if (inner.isEmpty()) null else k to inner
                    }
                    .toMap()
                if (snapshot.isEmpty()) {
                    if (usageFile.exists()) usageFile.delete()
                } else {
                    usageFile.writeText(
                        Json.encodeToString(PersistedDailyUsage(ptDate = currentPtDate, counts = snapshot))
                    )
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

internal fun currentPacificDateString(nowMs: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.of("America/Los_Angeles"))
        .toLocalDate()
        .toString()

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

    override fun next(keys: String, providerId: String, sessionId: String?): String =
        next(keys, providerId, sessionId, KeyRotationMode.STICKY, null)

    override fun next(
        keys: String,
        providerId: String,
        sessionId: String?,
        rotationMode: KeyRotationMode,
        modelId: String?,
    ): String {
        val keyList = parseApiKeys(keys)
        if (keyList.isEmpty()) return keys
        if (keyList.size == 1) return keyList.first()

        return when (rotationMode) {
            KeyRotationMode.SEQUENTIAL -> KeyCooldownTracker.pickSequentialWithCooldown(keyList, modelId)
            KeyRotationMode.STICKY -> {
                if (!sessionId.isNullOrEmpty()) {
                    KeyCooldownTracker.pickStickyWithCooldown(keyList, providerId, sessionId, modelId)
                } else {
                    KeyCooldownTracker.pickSequentialWithCooldown(keyList, modelId)
                }
            }
            KeyRotationMode.ROUND_ROBIN -> {
                val healthy = keyList.filter { KeyCooldownTracker.isHealthy(it, modelId) }
                val pool = if (healthy.isNotEmpty()) healthy else keyList
                val idx = Math.floorMod(roundRobinCursor.getAndIncrement(), pool.size)
                pool[idx]
            }
        }
    }

    override fun reportError(key: String, statusCode: Int, retryAfterMs: Long?) =
        KeyCooldownTracker.reportError(key, statusCode, retryAfterMs)

    override fun reportGoogleError(
        key: String,
        statusCode: Int,
        retryAfterHeader: String?,
        responseBody: String?,
        modelId: String?,
    ) = KeyCooldownTracker.reportGoogleError(key, statusCode, retryAfterHeader, responseBody, modelId)

    override fun reportSuccess(key: String) = KeyCooldownTracker.reportSuccess(key)

    override fun reportSuccess(key: String, modelId: String?) = KeyCooldownTracker.reportSuccess(key, modelId)
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

    override fun next(keys: String, providerId: String, sessionId: String?): String =
        next(keys, providerId, sessionId, KeyRotationMode.STICKY, null)

    override fun next(
        keys: String,
        providerId: String,
        sessionId: String?,
        rotationMode: KeyRotationMode,
        modelId: String?,
    ): String {
        val keyList = parseApiKeys(keys)
        if (keyList.isEmpty()) return keys
        if (keyList.size == 1) return keyList.first()

        when (rotationMode) {
            KeyRotationMode.SEQUENTIAL -> return KeyCooldownTracker.pickSequentialWithCooldown(keyList, modelId)
            KeyRotationMode.STICKY -> {
                if (!sessionId.isNullOrEmpty()) {
                    return KeyCooldownTracker.pickStickyWithCooldown(keyList, providerId, sessionId, modelId)
                }
            }
            KeyRotationMode.ROUND_ROBIN -> Unit
        }

        synchronized(LruFileLock) {
            val now = System.currentTimeMillis()
            val allCache = loadCache().toMutableMap()
            val candidates = keyList.filter { KeyCooldownTracker.isHealthy(it, modelId, now) }
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
        modelId: String?,
    ) = KeyCooldownTracker.reportGoogleError(key, statusCode, retryAfterHeader, responseBody, modelId)

    override fun reportSuccess(key: String) = KeyCooldownTracker.reportSuccess(key)

    override fun reportSuccess(key: String, modelId: String?) = KeyCooldownTracker.reportSuccess(key, modelId)

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
