package com.example.orgclock.desktop

import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.SyncPairingExchangeRequest
import com.example.orgclock.sync.SyncPairingInvitation
import com.example.orgclock.sync.SyncTransportCredential
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.security.SecureRandom
import java.util.Base64
import kotlinx.datetime.Clock

class DesktopPairingManager(
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val tokenLifetimeMs: Long = 120_000L,
) {
    private val random = SecureRandom()
    private var active: SyncPairingInvitation? = null

    @Synchronized
    fun currentInvitation(certificateSha256: String): SyncPairingInvitation {
        val now = nowEpochMs()
        val existing = active
        if (existing != null && existing.expiresAtEpochMs > now && existing.certificateSha256 == certificateSha256) return existing
        return SyncPairingInvitation(randomToken(), certificateSha256, now + tokenLifetimeMs).also { active = it }
    }

    @Synchronized
    fun exchange(
        request: SyncPairingExchangeRequest,
        trustStore: PeerTrustStore,
        certificateSha256: String,
    ): String {
        val invitation = active ?: error("pairing invitation is not active")
        require(invitation.expiresAtEpochMs > nowEpochMs()) { "pairing invitation expired" }
        require(invitation.token == request.invitationToken) { "invalid pairing invitation" }
        require(request.deviceId.isNotBlank()) { "device id is empty" }
        active = null
        val credential = SyncTransportCredential(randomToken(), certificateSha256)
        trustStore.trust(
            PeerTrustRecord(
                peerId = request.deviceId,
                deviceId = request.deviceId,
                displayName = request.displayName.trim().ifBlank { request.deviceId },
                publicKeyBase64 = SyncTransportCredentialCodec.encode(credential),
                registeredAt = Clock.System.now(),
                lastSeenAt = Clock.System.now(),
            ),
        )
        return SyncTransportCredentialCodec.encode(credential)
    }

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
}
