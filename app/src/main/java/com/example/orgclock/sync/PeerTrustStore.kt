package com.example.orgclock.sync

import android.content.SharedPreferences

interface PeerTrustStore {
    fun isTrusted(peerId: String): Boolean
    fun listTrusted(): List<String>
    fun trust(peerId: String)
    fun trust(peerId: String, publicKeyBase64: String)
    fun revoke(peerId: String)
    fun getTrustedPublicKey(peerId: String): String?
}

class SharedPreferencesPeerTrustStore(
    private val prefs: SharedPreferences,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean {
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet()) ?: emptySet()
        return trusted.contains(peerId)
    }

    override fun listTrusted(): List<String> {
        return (prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet()) ?: emptySet())
            .asSequence()
            .filter { it.isNotBlank() }
            .sorted()
            .toList()
    }

    override fun trust(peerId: String) {
        if (peerId.isBlank()) return
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.add(peerId)
        prefs.edit().putStringSet(KEY_TRUSTED_PEER_IDS, trusted).apply()
    }

    override fun trust(peerId: String, publicKeyBase64: String) {
        val normalizedPeerId = peerId.trim()
        val normalizedKey = publicKeyBase64.trim()
        if (normalizedPeerId.isBlank() || normalizedKey.isBlank()) return
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.add(normalizedPeerId)
        prefs.edit()
            .putStringSet(KEY_TRUSTED_PEER_IDS, trusted)
            .putString(publicKeyKey(normalizedPeerId), normalizedKey)
            .apply()
    }

    override fun revoke(peerId: String) {
        val normalizedPeerId = peerId.trim()
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.remove(normalizedPeerId)
        prefs.edit()
            .putStringSet(KEY_TRUSTED_PEER_IDS, trusted)
            .remove(publicKeyKey(normalizedPeerId))
            .apply()
    }

    override fun getTrustedPublicKey(peerId: String): String? {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return null
        if (!isTrusted(normalizedPeerId)) return null
        return prefs.getString(publicKeyKey(normalizedPeerId), null)?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val KEY_TRUSTED_PEER_IDS = "sync_trusted_peer_ids"
        private const val KEY_TRUSTED_PEER_PUBLIC_KEY_PREFIX = "sync_trusted_peer_public_key_"

        private fun publicKeyKey(peerId: String): String {
            val sanitized = peerId.trim()
                .replace("%", "%25")
                .replace(":", "%3A")
                .replace("/", "%2F")
                .replace(" ", "%20")
            return "$KEY_TRUSTED_PEER_PUBLIC_KEY_PREFIX$sanitized"
        }
    }
}
