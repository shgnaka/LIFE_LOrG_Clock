package com.example.orgclock.sync

import kotlinx.datetime.Instant

enum class PeerTrustRole {
    Full,
    Viewer,
}

data class PeerRegistrationRequest(
    val peerId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val role: PeerTrustRole = PeerTrustRole.Full,
    val endpoint: String? = null,
    val requestedAt: Instant,
) {
    init {
        require(peerId.isNotBlank()) { "Peer ID cannot be blank." }
        require(deviceId.isNotBlank()) { "Device ID cannot be blank." }
        require(displayName.isNotBlank()) { "Display name cannot be blank." }
        require(publicKeyBase64.isNotBlank()) { "Public key cannot be blank." }
    }
}

data class PeerPairingDraft(
    val peerId: String = "",
    val deviceId: String = "",
    val displayName: String = "",
    val publicKeyBase64: String = "",
    val endpoint: String = "",
    val viewerModeEnabled: Boolean = false,
) {
    val role: PeerTrustRole
        get() = if (viewerModeEnabled) PeerTrustRole.Viewer else PeerTrustRole.Full
}

data class PeerTrustRecord(
    val peerId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val role: PeerTrustRole = PeerTrustRole.Full,
    val endpoint: String? = null,
    val registeredAt: Instant,
    val lastSeenAt: Instant? = null,
    val revokedAt: Instant? = null,
    val activeTrust: Boolean = true,
) {
    init {
        require(peerId.isNotBlank()) { "Peer ID cannot be blank." }
        require(deviceId.isNotBlank()) { "Device ID cannot be blank." }
        require(displayName.isNotBlank()) { "Display name cannot be blank." }
        require(publicKeyBase64.isNotBlank()) { "Public key cannot be blank." }
    }

    val isRevoked: Boolean
        get() = !activeTrust

    val isActive: Boolean
        get() = activeTrust

    fun markSeen(at: Instant): PeerTrustRecord = copy(lastSeenAt = at)

    fun revoke(at: Instant): PeerTrustRecord = copy(revokedAt = at, activeTrust = false)

    fun repair(at: Instant): PeerTrustRecord = copy(activeTrust = true, lastSeenAt = at)
}

fun PeerRegistrationRequest.toPeerTrustRecord(): PeerTrustRecord {
    return PeerTrustRecord(
        peerId = peerId,
        deviceId = deviceId,
        displayName = displayName,
        publicKeyBase64 = publicKeyBase64,
        role = role,
        endpoint = endpoint,
        registeredAt = requestedAt,
        lastSeenAt = requestedAt,
    )
}

fun PeerPairingDraft.toRegistrationRequest(requestedAt: Instant): PeerRegistrationRequest {
    val normalizedPeerId = peerId.trim()
    val normalizedDeviceId = deviceId.trim()
    return PeerRegistrationRequest(
        peerId = normalizedPeerId,
        deviceId = normalizedDeviceId,
        displayName = displayName.trim().ifBlank { normalizedPeerId },
        publicKeyBase64 = publicKeyBase64.trim(),
        role = role,
        endpoint = endpoint.trim().takeIf { it.isNotBlank() },
        requestedAt = requestedAt,
    )
}
