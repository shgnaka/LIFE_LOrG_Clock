package com.example.orgclock.sync

import android.content.SharedPreferences
import java.util.UUID

interface DeviceIdProvider {
    fun getOrCreate(): String
}

class SharedPreferencesDeviceIdProvider(
    private val prefs: SharedPreferences,
) : DeviceIdProvider {
    override fun getOrCreate(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "dev_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    private companion object {
        const val KEY_DEVICE_ID = "sync_device_id"
    }
}
