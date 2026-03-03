package com.example.orgclock.sync

import android.content.SharedPreferences

interface SyncRuntimePrefs {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun selectedMode(): SyncRuntimeMode
    fun setSelectedMode(mode: SyncRuntimeMode)
    fun defaultPeerId(): String?
    fun setDefaultPeerId(peerId: String?)
}

class SharedPreferencesSyncRuntimePrefs(
    private val prefs: SharedPreferences,
) : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_SYNC_RUNTIME_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_RUNTIME_ENABLED, enabled).apply()
    }

    override fun selectedMode(): SyncRuntimeMode {
        val raw = prefs.getString(KEY_SYNC_RUNTIME_MODE, null)
        return raw?.let { runCatching { SyncRuntimeMode.valueOf(it) }.getOrNull() } ?: SyncRuntimeMode.Off
    }

    override fun setSelectedMode(mode: SyncRuntimeMode) {
        prefs.edit().putString(KEY_SYNC_RUNTIME_MODE, mode.name).apply()
    }

    override fun defaultPeerId(): String? {
        return prefs.getString(KEY_SYNC_DEFAULT_PEER_ID, null)?.takeIf { it.isNotBlank() }
    }

    override fun setDefaultPeerId(peerId: String?) {
        prefs.edit().putString(KEY_SYNC_DEFAULT_PEER_ID, peerId).apply()
    }

    companion object {
        const val KEY_SYNC_RUNTIME_ENABLED = "sync_runtime_enabled"
        const val KEY_SYNC_RUNTIME_MODE = "sync_runtime_mode"
        const val KEY_SYNC_DEFAULT_PEER_ID = "sync_default_peer_id"
    }
}
