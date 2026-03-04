package com.example.orgclock.sync

import android.content.SharedPreferences

interface SyncRuntimePrefs {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun selectedMode(): SyncRuntimeMode
    fun setSelectedMode(mode: SyncRuntimeMode)
    fun defaultPeerId(): String?
    fun setDefaultPeerId(peerId: String?)
    fun inboundClockSkewSeconds(): Long = DEFAULT_INBOUND_CLOCK_SKEW_SECONDS
    fun setInboundClockSkewSeconds(seconds: Long) {}
    fun inboundMaxRequestsPerMinute(): Int = DEFAULT_INBOUND_MAX_REQUESTS_PER_MINUTE
    fun setInboundMaxRequestsPerMinute(requests: Int) {}
    fun resultSigningKeyAlias(): String = DEFAULT_RESULT_SIGNING_KEY_ALIAS
    fun setResultSigningKeyAlias(alias: String) {}

    companion object {
        const val DEFAULT_INBOUND_CLOCK_SKEW_SECONDS = 300L
        const val DEFAULT_INBOUND_MAX_REQUESTS_PER_MINUTE = 120
        const val DEFAULT_RESULT_SIGNING_KEY_ALIAS = "orgclock_result_signing_ed25519_v1"
    }
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

    override fun inboundClockSkewSeconds(): Long {
        return prefs.getLong(
            KEY_SYNC_INBOUND_CLOCK_SKEW_SECONDS,
            SyncRuntimePrefs.DEFAULT_INBOUND_CLOCK_SKEW_SECONDS,
        ).coerceAtLeast(30L)
    }

    override fun setInboundClockSkewSeconds(seconds: Long) {
        prefs.edit()
            .putLong(KEY_SYNC_INBOUND_CLOCK_SKEW_SECONDS, seconds.coerceAtLeast(30L))
            .apply()
    }

    override fun inboundMaxRequestsPerMinute(): Int {
        return prefs.getInt(
            KEY_SYNC_INBOUND_MAX_REQUESTS_PER_MINUTE,
            SyncRuntimePrefs.DEFAULT_INBOUND_MAX_REQUESTS_PER_MINUTE,
        ).coerceAtLeast(10)
    }

    override fun setInboundMaxRequestsPerMinute(requests: Int) {
        prefs.edit()
            .putInt(KEY_SYNC_INBOUND_MAX_REQUESTS_PER_MINUTE, requests.coerceAtLeast(10))
            .apply()
    }

    override fun resultSigningKeyAlias(): String {
        return prefs.getString(
            KEY_SYNC_RESULT_SIGNING_KEY_ALIAS,
            SyncRuntimePrefs.DEFAULT_RESULT_SIGNING_KEY_ALIAS,
        )?.takeIf { it.isNotBlank() } ?: SyncRuntimePrefs.DEFAULT_RESULT_SIGNING_KEY_ALIAS
    }

    override fun setResultSigningKeyAlias(alias: String) {
        val normalized = alias.trim().ifBlank { SyncRuntimePrefs.DEFAULT_RESULT_SIGNING_KEY_ALIAS }
        prefs.edit().putString(KEY_SYNC_RESULT_SIGNING_KEY_ALIAS, normalized).apply()
    }

    companion object {
        const val KEY_SYNC_RUNTIME_ENABLED = "sync_runtime_enabled"
        const val KEY_SYNC_RUNTIME_MODE = "sync_runtime_mode"
        const val KEY_SYNC_DEFAULT_PEER_ID = "sync_default_peer_id"
        const val KEY_SYNC_INBOUND_CLOCK_SKEW_SECONDS = "sync_inbound_clock_skew_seconds"
        const val KEY_SYNC_INBOUND_MAX_REQUESTS_PER_MINUTE = "sync_inbound_max_requests_per_minute"
        const val KEY_SYNC_RESULT_SIGNING_KEY_ALIAS = "sync_result_signing_key_alias"
    }
}
