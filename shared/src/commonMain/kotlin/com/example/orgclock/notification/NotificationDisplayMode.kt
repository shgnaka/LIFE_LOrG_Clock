package com.example.orgclock.notification

enum class NotificationDisplayMode(val storageValue: String) {
    Always("always"),
    ActiveOnly("active_only");

    companion object {
        fun fromStorage(raw: String?): NotificationDisplayMode {
            return entries.firstOrNull { it.storageValue == raw } ?: ActiveOnly
        }
    }
}
