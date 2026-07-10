package com.example.orgclock.desktop

import com.example.orgclock.sync.PeerTrustRole
import com.example.orgclock.sync.SyncPairingExchangeRequestV2
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingV2ContractTest {
    @Test
    fun bidirectionalIdentity() {
        val manager = DesktopPairingManager(nowEpochMs = { 1_000L }, tokenLifetimeMs = 120_000L)
        val invitation = manager.currentInvitationV2(
            certificateSha256 = "ab".repeat(32),
            endpoint = "https://desktop.local:8787",
            hostPeerId = "desktop-peer",
            hostDeviceId = "desktop-device",
            hostDisplayName = "Desktop Host",
            hostSigningPublicKeyBase64 = HOST_SIGNING_KEY,
        )
        val prefs = Preferences.userRoot().node("orgclock-test/${UUID.randomUUID()}")
        try {
            val trustStore = DesktopPeerTrustStore(prefs)
            val credentialStore = RecordingPairingV2CredentialStore()

            val response = manager.exchangeV2(
                request = SyncPairingExchangeRequestV2(
                    invitationToken = invitation.token,
                    requesterPeerId = "android-peer",
                    requesterDeviceId = "android-device",
                    requesterDisplayName = "Android",
                    requesterSigningPublicKeyBase64 = ANDROID_SIGNING_KEY,
                    requestedRole = PeerTrustRole.Full,
                ),
                trustStore = trustStore,
                certificateSha256 = invitation.certificateSha256,
                hostPeerId = invitation.hostPeerId,
                hostDeviceId = invitation.hostDeviceId,
                hostDisplayName = invitation.hostDisplayName,
                hostSigningPublicKeyBase64 = invitation.hostSigningPublicKeyBase64,
                transportCredentialStore = credentialStore,
            )

            val requesterRecord = trustStore.getTrustRecord("android-peer")
            assertEquals(ANDROID_SIGNING_KEY, requesterRecord?.signingPublicKeyBase64)
            assertEquals("android-peer", requesterRecord?.transportCredentialRef)
            assertEquals(credentialStore.get("android-peer"), SyncTransportCredentialCodec.decode(response.encodedTransportCredential).getOrThrow())
            assertEquals("desktop-peer", response.hostPeerId)
            assertEquals("desktop-device", response.hostDeviceId)
            assertEquals(HOST_SIGNING_KEY, response.hostSigningPublicKeyBase64)
            assertTrue(response.encodedTransportCredential != HOST_SIGNING_KEY)
        } finally {
            prefs.removeNode()
        }
    }

    @Test
    fun roleReduction() {
        val manager = DesktopPairingManager(nowEpochMs = { 1_000L }, tokenLifetimeMs = 120_000L)
        val invitation = manager.currentInvitationV2(
            certificateSha256 = "cd".repeat(32),
            endpoint = "https://desktop.local:8787",
            hostPeerId = "desktop-peer",
            hostDeviceId = "desktop-device",
            hostDisplayName = "Desktop Host",
            hostSigningPublicKeyBase64 = HOST_SIGNING_KEY,
        )
        val prefs = Preferences.userRoot().node("orgclock-test/${UUID.randomUUID()}")
        try {
            val trustStore = DesktopPeerTrustStore(prefs)

            val response = manager.exchangeV2(
                request = SyncPairingExchangeRequestV2(
                    invitationToken = invitation.token,
                    requesterPeerId = "android-peer",
                    requesterDeviceId = "android-device",
                    requesterDisplayName = "Android",
                    requesterSigningPublicKeyBase64 = ANDROID_SIGNING_KEY,
                    requestedRole = PeerTrustRole.Full,
                ),
                trustStore = trustStore,
                certificateSha256 = invitation.certificateSha256,
                hostPeerId = invitation.hostPeerId,
                hostDeviceId = invitation.hostDeviceId,
                hostDisplayName = invitation.hostDisplayName,
                hostSigningPublicKeyBase64 = invitation.hostSigningPublicKeyBase64,
                maxGrantedRole = PeerTrustRole.Viewer,
            )

            assertEquals(PeerTrustRole.Viewer, response.grantedRole)
            assertEquals(PeerTrustRole.Viewer, trustStore.getTrustRecord("android-peer")?.role)
        } finally {
            prefs.removeNode()
        }
    }
}

private class RecordingPairingV2CredentialStore : DesktopSyncCoreTransportCredentialStore {
    private val credentials = linkedMapOf<String, com.example.orgclock.sync.SyncTransportCredential>()

    override fun put(peerId: String, credential: com.example.orgclock.sync.SyncTransportCredential) {
        credentials[peerId.trim()] = credential
    }

    override fun get(peerId: String): com.example.orgclock.sync.SyncTransportCredential? = credentials[peerId.trim()]

    override fun delete(peerId: String) {
        credentials.remove(peerId.trim())
    }
}

private const val HOST_SIGNING_KEY = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
private const val ANDROID_SIGNING_KEY = "MCowBQYDK2VwAyEAkyoR24N2gkBN6ILcDvUPuMRuydOwaZuYk9ZPYuKj9+8="
