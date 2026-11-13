package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 45,
    val maxIntervalMinutes: Int = 120,
    val assistantId: String = "",
    val conversationId: String = "",
    val quietHoursEnabled: Boolean = true,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 8,
) {
    companion object {
        const val MIN_INTERVAL_MINUTES = 5
        const val MAX_INTERVAL_MINUTES = 60 * 24 * 7
    }

    fun normalized(): ProactiveMessageSetting {
        val lo = minIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        val hi = maxIntervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        return copy(
            minIntervalMinutes = minOf(lo, hi),
            maxIntervalMinutes = maxOf(lo, hi),
            quietStartHour = quietStartHour.coerceIn(0, 23),
            quietEndHour = quietEndHour.coerceIn(0, 23),
        )
    }
}
