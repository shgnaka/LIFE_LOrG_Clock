package com.example.orgclock.desktop

import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.PeerTrustRole
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.prefs.Preferences

interface PeerTrustStore {
    fun isTrusted(peerId: String): Boolean

    fun listTrusted(): List<String>

    fun trust(peerId: String)

    fun trust(peerId: String, publicKeyBase64: String)

    fun trust(record: PeerTrustRecord)

    fun getTrustRecord(peerId: String): PeerTrustRecord?

    fun listTrustRecords(): List<PeerTrustRecord>

    fun revoke(peerId: String)

    fun repair(peerId: String)

    fun getTrustedPublicKey(peerId: String): String?
}

class DesktopPeerTrustStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return false
        val current = readRecord(normalized)
        if (current != null) return current.isActive
        return trustedPeerIds().contains(normalized)
    }

    override fun listTrusted(): List<String> {
        val records = listTrustRecords()
        val recordIds = records.asSequence().map { it.peerId }.toSet()
        val activeRecords = records.asSequence().filter { it.isActive }.map { it.peerId }
        val legacy = trustedPeerIds().asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it in recordIds }
        return (legacy + activeRecords).distinct().sorted().toList()
    }

    override fun trust(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = getTrustRecord(normalized)
        trust(
            current ?: PeerTrustRecord(
                peerId = normalized,
                deviceId = normalized,
                displayName = normalized,
                publicKeyBase64 = normalized,
                registeredAt = Clock.System.now(),
            ),
        )
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
        persistRecord(record.copy(activeTrust = true), isActive = true)
    }

    override fun getTrustRecord(peerId: String): PeerTrustRecord? {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return null
        return listTrustRecords().firstOrNull { it.peerId == normalized }
    }

    override fun listTrustRecords(): List<PeerTrustRecord> {
        val ids = trustRecordIds().sorted()
        return ids.mapNotNull(::readRecord)
    }

    override fun revoke(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = readRecord(normalized)
        if (current != null) {
            persistRecord(
                current.revoke(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())),
                isActive = false,
            )
            return
        }
        removeFromTrustedPeerIds(normalized)
    }

    override fun repair(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = readRecord(normalized) ?: return
        persistRecord(current.repair(Clock.System.now()), isActive = true)
    }

    override fun getTrustedPublicKey(peerId: String): String? {
        return getTrustRecord(peerId)?.publicKeyBase64?.takeIf { it.isNotBlank() }
    }

    private fun readRecord(peerId: String): PeerTrustRecord? {
        val displayName = preferences.get(recordPeerDisplayNameKey(peerId), null)?.takeIf { it.isNotBlank() }
            ?: peerId
        val deviceId = preferences.get(recordPeerDeviceIdKey(peerId), null)?.takeIf { it.isNotBlank() }
            ?: peerId
        val publicKey = preferences.get(recordPeerPublicKeyKey(peerId), null)?.takeIf { it.isNotBlank() }
        val registeredAt = preferences.getLong(recordPeerRegisteredAtKey(peerId), MISSING_EPOCH_MILLIS)
        if (publicKey.isNullOrBlank() || registeredAt == MISSING_EPOCH_MILLIS) {
            return null
        }
        val role = preferences.get(recordPeerRoleKey(peerId), PeerTrustRole.Full.name)
            ?.let { runCatching { PeerTrustRole.valueOf(it) }.getOrNull() }
            ?: PeerTrustRole.Full
        val endpoint = preferences.get(recordPeerEndpointKey(peerId), null)?.takeIf { it.isNotBlank() }
        val lastSeenAt = preferences.getLong(recordPeerLastSeenAtKey(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
            ?.let { Instant.fromEpochMilliseconds(it) }
        val revokedAt = preferences.getLong(recordPeerRevokedAtKey(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
            ?.let { Instant.fromEpochMilliseconds(it) }
        val activeTrust = preferences.get(recordPeerActiveTrustKey(peerId), null)
            ?.toBooleanStrictOrNull()
            ?: (revokedAt == null)
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
            activeTrust = activeTrust,
        )
    }

    private fun persistRecord(record: PeerTrustRecord, isActive: Boolean) {
        val normalized = record.peerId.trim()
        val trusted = trustedPeerIds().toMutableSet()
        if (isActive) {
            trusted.add(normalized)
        } else {
            trusted.remove(normalized)
        }
        val trustedRecords = trustRecordIds().toMutableSet()
        trustedRecords.add(normalized)
        preferences.put(KEY_TRUSTED_PEER_IDS, trusted.sorted().joinToString("\n"))
        preferences.put(KEY_TRUST_RECORD_IDS, trustedRecords.sorted().joinToString("\n"))
        preferences.put(recordPeerDisplayNameKey(normalized), record.displayName)
        preferences.put(recordPeerDeviceIdKey(normalized), record.deviceId)
        preferences.put(recordPeerPublicKeyKey(normalized), record.publicKeyBase64)
        preferences.put(recordPeerRoleKey(normalized), record.role.name)
        record.endpoint?.let { preferences.put(recordPeerEndpointKey(normalized), it) }
            ?: preferences.remove(recordPeerEndpointKey(normalized))
        preferences.putLong(recordPeerRegisteredAtKey(normalized), record.registeredAt.toEpochMilliseconds())
        preferences.putLong(recordPeerLastSeenAtKey(normalized), record.lastSeenAt?.toEpochMilliseconds() ?: MISSING_EPOCH_MILLIS)
        preferences.putLong(recordPeerRevokedAtKey(normalized), record.revokedAt?.toEpochMilliseconds() ?: MISSING_EPOCH_MILLIS)
        preferences.put(recordPeerActiveTrustKey(normalized), record.isActive.toString())
        preferences.flush()
    }

    private fun removeFromTrustedPeerIds(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val trusted = trustedPeerIds().toMutableSet()
        if (trusted.remove(normalized)) {
            preferences.put(KEY_TRUSTED_PEER_IDS, trusted.sorted().joinToString("\n"))
            preferences.flush()
        }
    }

    private fun recordPeerDisplayNameKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_display_name"
    private fun recordPeerDeviceIdKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_device_id"
    private fun recordPeerPublicKeyKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_public_key"
    private fun recordPeerRoleKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_role"
    private fun recordPeerEndpointKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_endpoint"
    private fun recordPeerRegisteredAtKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_registered_at"
    private fun recordPeerLastSeenAtKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_last_seen_at"
    private fun recordPeerRevokedAtKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_revoked_at"
    private fun recordPeerActiveTrustKey(peerId: String): String = "$KEY_TRUST_RECORD_PREFIX${sanitize(peerId)}_active_trust"

    private fun sanitize(peerId: String): String {
        return peerId.trim()
            .replace("%", "%25")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace(" ", "%20")
    }

    private fun trustedPeerIds(): Set<String> = parseIdSet(preferences.get(KEY_TRUSTED_PEER_IDS, null))

    private fun trustRecordIds(): Set<String> = parseIdSet(preferences.get(KEY_TRUST_RECORD_IDS, null))

    private fun parseIdSet(raw: String?): Set<String> {
        return raw
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop/sync/trust"
        const val KEY_TRUSTED_PEER_IDS = "trusted_peer_ids"
        const val KEY_TRUST_RECORD_IDS = "trust_record_ids"
        const val KEY_TRUST_RECORD_PREFIX = "trust_record_"
        const val MISSING_EPOCH_MILLIS = Long.MIN_VALUE
    }
}
