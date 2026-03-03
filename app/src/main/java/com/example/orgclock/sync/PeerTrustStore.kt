package com.example.orgclock.sync

import android.content.SharedPreferences

interface PeerTrustStore {
    fun isTrusted(peerId: String): Boolean
    fun trust(peerId: String)
    fun revoke(peerId: String)
}

class SharedPreferencesPeerTrustStore(
    private val prefs: SharedPreferences,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean {
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet()) ?: emptySet()
        return trusted.contains(peerId)
    }

    override fun trust(peerId: String) {
        if (peerId.isBlank()) return
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.add(peerId)
        prefs.edit().putStringSet(KEY_TRUSTED_PEER_IDS, trusted).apply()
    }

    override fun revoke(peerId: String) {
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.remove(peerId)
        prefs.edit().putStringSet(KEY_TRUSTED_PEER_IDS, trusted).apply()
    }

    companion object {
        const val KEY_TRUSTED_PEER_IDS = "sync_trusted_peer_ids"
    }
}
