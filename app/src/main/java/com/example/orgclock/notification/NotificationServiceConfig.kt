package com.example.orgclock.notification

data class NotificationServiceConfig(
    val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    val maxLines: Int = DEFAULT_MAX_LINES,
) {
    companion object {
        const val DEFAULT_SCAN_INTERVAL_MS: Long = 5 * 60 * 1000L
        const val DEFAULT_MAX_LINES: Int = 6
    }
}

