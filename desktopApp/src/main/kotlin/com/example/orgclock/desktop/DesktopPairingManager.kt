package com.example.orgclock.desktop

import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.SyncPairingExchangeRequest
import com.example.orgclock.sync.SyncPairingInvitation
import com.example.orgclock.sync.SyncPairingExchangeResponseV2
import com.example.orgclock.sync.SyncPairingExchangeRequestV2
import com.example.orgclock.sync.SyncPairingInvitationV2
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
    private var activeV2: SyncPairingInvitationV2? = null

    @Synchronized
    fun currentInvitation(certificateSha256: String): SyncPairingInvitation {
        val now = nowEpochMs()
        val existing = active
        if (existing != null && existing.expiresAtEpochMs > now && existing.certificateSha256 == certificateSha256) return existing
        return SyncPairingInvitation(randomToken(), certificateSha256, now + tokenLifetimeMs).also { active = it }
    }

    @Synchronized
    fun currentInvitationV2(
        certificateSha256: String,
        endpoint: String,
        hostPeerId: String,
        hostDeviceId: String,
        hostDisplayName: String,
        hostSigningPublicKeyBase64: String,
        hostSigningAlg: String = com.example.orgclock.sync.DEFAULT_SYNC_SIGNING_ALG,
        capabilities: List<String> = emptyList(),
    ): SyncPairingInvitationV2 {
        val now = nowEpochMs()
        val normalizedEndpoint = endpoint.trimEnd('/')
        val existing = activeV2
        if (
            existing != null &&
            existing.expiresAtEpochMs > now &&
            existing.certificateSha256 == certificateSha256 &&
            existing.endpoint == normalizedEndpoint &&
            existing.hostPeerId == hostPeerId &&
            existing.hostDeviceId == hostDeviceId &&
            existing.hostDisplayName == hostDisplayName &&
            existing.hostSigningPublicKeyBase64 == hostSigningPublicKeyBase64 &&
            existing.hostSigningAlg == hostSigningAlg &&
            existing.capabilities == capabilities
        ) {
            return existing
        }
        return SyncPairingInvitationV2(
            token = randomToken(),
            hostPeerId = hostPeerId,
            hostDeviceId = hostDeviceId,
            hostDisplayName = hostDisplayName,
            hostSigningPublicKeyBase64 = hostSigningPublicKeyBase64,
            hostSigningAlg = hostSigningAlg,
            certificateSha256 = certificateSha256,
            endpoint = normalizedEndpoint,
            expiresAtEpochMs = now + tokenLifetimeMs,
            capabilities = capabilities,
        ).also { activeV2 = it }
    }
    @Synchronized
    fun exchange(
        request: SyncPairingExchangeRequest,
        trustStore: PeerTrustStore,
        certificateSha256: String,
        transportCredentialStore: DesktopSyncCoreTransportCredentialStore? = null,
    ): String {
        val invitation = active ?: error("pairing invitation is not active")
        require(invitation.expiresAtEpochMs > nowEpochMs()) { "pairing invitation expired" }
        require(invitation.token == request.invitationToken) { "invalid pairing invitation" }
        require(request.deviceId.isNotBlank()) { "device id is empty" }
        active = null
        val credential = SyncTransportCredential(randomToken(), certificateSha256)
        transportCredentialStore?.put(request.deviceId, credential)
        trustStore.trust(
            PeerTrustRecord(
                peerId = request.deviceId,
                deviceId = request.deviceId,
                displayName = request.displayName.trim().ifBlank { request.deviceId },
                publicKeyBase64 = SyncTransportCredentialCodec.encode(credential),
                transportCredentialRef = request.deviceId,
                certificateSha256 = certificateSha256,
                registeredAt = Clock.System.now(),
                lastSeenAt = Clock.System.now(),
            ),
        )
        return SyncTransportCredentialCodec.encode(credential)
    }


    @Synchronized
    fun exchangeV2(
        request: SyncPairingExchangeRequestV2,
        trustStore: PeerTrustStore,
        certificateSha256: String,
        hostPeerId: String,
        hostDeviceId: String,
        hostDisplayName: String,
        hostSigningPublicKeyBase64: String,
        hostSigningAlg: String = com.example.orgclock.sync.DEFAULT_SYNC_SIGNING_ALG,
        transportCredentialStore: DesktopSyncCoreTransportCredentialStore? = null,
        maxGrantedRole: com.example.orgclock.sync.PeerTrustRole = com.example.orgclock.sync.PeerTrustRole.Full,
    ): SyncPairingExchangeResponseV2 {
        val invitation = activeV2 ?: error("pairing invitation is not active")
        require(invitation.expiresAtEpochMs > nowEpochMs()) { "pairing invitation expired" }
        require(invitation.token == request.invitationToken) { "invalid pairing invitation" }
        require(request.requesterPeerId.isNotBlank()) { "requester peer id is empty" }
        activeV2 = null
        val grantedRole = reduceRole(request.requestedRole, maxGrantedRole)
        val credential = SyncTransportCredential(randomToken(), certificateSha256)
        val encodedCredential = SyncTransportCredentialCodec.encode(credential)
        transportCredentialStore?.put(request.requesterPeerId, credential)
        trustStore.trust(
            PeerTrustRecord(
                peerId = request.requesterPeerId,
                deviceId = request.requesterDeviceId,
                displayName = request.requesterDisplayName.trim().ifBlank { request.requesterPeerId },
                publicKeyBase64 = encodedCredential,
                signingPublicKeyBase64 = request.requesterSigningPublicKeyBase64,
                signingAlg = request.requesterSigningAlg,
                transportCredentialRef = request.requesterPeerId,
                certificateSha256 = certificateSha256,
                role = grantedRole,
                registeredAt = Clock.System.now(),
                lastSeenAt = Clock.System.now(),
            ),
        )
        return SyncPairingExchangeResponseV2(
            hostPeerId = hostPeerId,
            hostDeviceId = hostDeviceId,
            hostDisplayName = hostDisplayName,
            hostSigningPublicKeyBase64 = hostSigningPublicKeyBase64,
            hostSigningAlg = hostSigningAlg,
            grantedRole = grantedRole,
            encodedTransportCredential = encodedCredential,
            certificateSha256 = certificateSha256,
        )
    }

    private fun reduceRole(
        requestedRole: com.example.orgclock.sync.PeerTrustRole,
        maxGrantedRole: com.example.orgclock.sync.PeerTrustRole,
    ): com.example.orgclock.sync.PeerTrustRole {
        return if (maxGrantedRole == com.example.orgclock.sync.PeerTrustRole.Viewer) {
            com.example.orgclock.sync.PeerTrustRole.Viewer
        } else {
            requestedRole
        }
    }

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
}
