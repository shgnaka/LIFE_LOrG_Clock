package com.example.orgclock.sync

import android.content.SharedPreferences
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

interface PeerTrustStore {
    fun isTrusted(peerId: String): Boolean
    fun listTrusted(): List<String>
    fun trust(peerId: String)
    fun trust(peerId: String, publicKeyBase64: String)
    fun trust(record: PeerTrustRecord) {
        trust(record.peerId, record.publicKeyBase64)
    }
    fun getTrustRecord(peerId: String): PeerTrustRecord? = null
    fun listTrustRecords(): List<PeerTrustRecord> = emptyList()
    fun revoke(peerId: String)
    fun repair(peerId: String) {}
    fun getTrustedPublicKey(peerId: String): String?
}

class SharedPreferencesPeerTrustStore(
    private val prefs: SharedPreferences,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return false
        return getTrustRecord(normalizedPeerId)?.isActive == true ||
            (prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet()) ?: emptySet()).contains(normalizedPeerId)
    }

    override fun listTrusted(): List<String> {
        val legacy = (prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet()) ?: emptySet())
            .asSequence()
            .filter { it.isNotBlank() }
        val records = listTrustRecords().asSequence().filter { it.isActive }.map { it.peerId }
        return (legacy + records)
            .distinct()
            .sorted()
            .toList()
    }

    override fun trust(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.add(normalized)
        prefs.edit().putStringSet(KEY_TRUSTED_PEER_IDS, trusted).apply()
    }

    override fun trust(peerId: String, publicKeyBase64: String) {
        val normalizedPeerId = peerId.trim()
        val normalizedKey = publicKeyBase64.trim()
        if (normalizedPeerId.isBlank() || normalizedKey.isBlank()) return
        trust(
            PeerTrustRecord(
                peerId = normalizedPeerId,
                deviceId = normalizedPeerId,
                displayName = normalizedPeerId,
                publicKeyBase64 = normalizedKey,
                registeredAt = Clock.System.now(),
                lastSeenAt = Clock.System.now(),
            ),
        )
    }

    override fun trust(record: PeerTrustRecord) {
        val normalized = record.peerId.trim()
        if (normalized.isBlank()) return
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.add(normalized)
        val trustedRecords = prefs.getStringSet(KEY_TRUST_RECORD_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trustedRecords.add(normalized)
        prefs.edit()
            .putStringSet(KEY_TRUSTED_PEER_IDS, trusted)
            .putStringSet(KEY_TRUST_RECORD_IDS, trustedRecords)
            .putString(recordPeerDisplayNameKey(normalized), record.displayName)
            .putString(recordPeerDeviceIdKey(normalized), record.deviceId)
            .putString(recordPeerPublicKeyKey(normalized), record.publicKeyBase64)
            .putString(recordPeerRoleKey(normalized), record.role.name)
            .putString(recordPeerEndpointKey(normalized), record.endpoint)
            .putLong(recordPeerRegisteredAtKey(normalized), record.registeredAt.toEpochMilliseconds())
            .putLong(recordPeerLastSeenAtKey(normalized), record.lastSeenAt?.toEpochMilliseconds() ?: MISSING_EPOCH_MILLIS)
            .putLong(recordPeerRevokedAtKey(normalized), record.revokedAt?.toEpochMilliseconds() ?: MISSING_EPOCH_MILLIS)
            .apply()
    }

    override fun getTrustRecord(peerId: String): PeerTrustRecord? {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return null
        return listTrustRecords().firstOrNull { it.peerId == normalized }
    }

    override fun listTrustRecords(): List<PeerTrustRecord> {
        val ids = (prefs.getStringSet(KEY_TRUST_RECORD_IDS, emptySet()) ?: emptySet())
            .asSequence()
            .filter { it.isNotBlank() }
            .sorted()
            .toList()
        return ids.mapNotNull { peerId ->
            readRecord(peerId)
        }
    }

    override fun revoke(peerId: String) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return
        val trusted = prefs.getStringSet(KEY_TRUSTED_PEER_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        trusted.remove(normalizedPeerId)
        val trustedRecords = prefs.getStringSet(KEY_TRUST_RECORD_IDS, emptySet())?.toMutableSet() ?: linkedSetOf()
        if (!trustedRecords.contains(normalizedPeerId)) {
            prefs.edit()
                .putStringSet(KEY_TRUSTED_PEER_IDS, trusted)
                .apply()
            return
        }
        val nowMs = Clock.System.now().toEpochMilliseconds()
        prefs.edit()
            .putStringSet(KEY_TRUSTED_PEER_IDS, trusted)
            .putStringSet(KEY_TRUST_RECORD_IDS, trustedRecords)
            .putLong(recordPeerRevokedAtKey(normalizedPeerId), nowMs)
            .apply()
    }

    override fun repair(peerId: String) {
        val normalizedPeerId = peerId.trim()
        if (normalizedPeerId.isBlank()) return
        val current = readRecord(normalizedPeerId) ?: return
        trust(
            current.repair(Clock.System.now()).markSeen(Clock.System.now()),
        )
    }

    override fun getTrustedPublicKey(peerId: String): String? {
        return getTrustRecord(peerId)?.publicKeyBase64?.takeIf { it.isNotBlank() }
    }

    private fun readRecord(peerId: String): PeerTrustRecord? {
        val displayName = prefs.getString(recordPeerDisplayNameKey(peerId), null)?.takeIf { it.isNotBlank() }
            ?: peerId
        val deviceId = prefs.getString(recordPeerDeviceIdKey(peerId), null)?.takeIf { it.isNotBlank() }
            ?: peerId
        val publicKey = prefs.getString(recordPeerPublicKeyKey(peerId), null)?.takeIf { it.isNotBlank() }
        val registeredAt = prefs.getLong(recordPeerRegisteredAtKey(peerId), MISSING_EPOCH_MILLIS)
        if (publicKey.isNullOrBlank() || registeredAt == MISSING_EPOCH_MILLIS) {
            return null
        }
        val role = prefs.getString(recordPeerRoleKey(peerId), PeerTrustRole.Full.name)
            ?.let { runCatching { PeerTrustRole.valueOf(it) }.getOrNull() }
            ?: PeerTrustRole.Full
        val endpoint = prefs.getString(recordPeerEndpointKey(peerId), null)?.takeIf { it.isNotBlank() }
        val lastSeenAt = prefs.getLong(recordPeerLastSeenAtKey(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
            ?.let { Instant.fromEpochMilliseconds(it) }
        val revokedAt = prefs.getLong(recordPeerRevokedAtKey(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
            ?.let { Instant.fromEpochMilliseconds(it) }
        return PeerTrustRecord(
            peerId = peerId,
            deviceId = deviceId,
            displayName = displayName,
            publicKeyBase64 = publicKey,
            role = role,
            endpoint = endpoint,
            registeredAt = Instant.fromEpochMilliseconds(registeredAt),
            lastSeenAt = lastSeenAt,
            revokedAt = revokedAt,
        )
    }

    private fun recordPeerDisplayNameKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_display_name"
    private fun recordPeerDeviceIdKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_device_id"
    private fun recordPeerPublicKeyKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_public_key"
    private fun recordPeerRoleKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_role"
    private fun recordPeerEndpointKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_endpoint"
    private fun recordPeerRegisteredAtKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_registered_at"
    private fun recordPeerLastSeenAtKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_last_seen_at"
    private fun recordPeerRevokedAtKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_revoked_at"

    private fun sanitize(peerId: String): String {
        return peerId.trim()
            .replace("%", "%25")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace(" ", "%20")
    }

    private companion object {
        const val KEY_TRUSTED_PEER_IDS = "sync_trusted_peer_ids"
        const val KEY_TRUST_RECORD_IDS = "sync_trust_record_ids"
        const val KEY_TRUST_RECORD_PREFIX = "sync_trust_record_"
        const val MISSING_EPOCH_MILLIS = Long.MIN_VALUE
    }
}
