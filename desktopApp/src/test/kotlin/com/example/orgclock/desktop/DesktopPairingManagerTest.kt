package com.example.orgclock.desktop

import com.example.orgclock.sync.PeerTrustRole
import com.example.orgclock.sync.SyncPairingExchangeRequest
import com.example.orgclock.sync.SyncPairingExchangeRequestV2
import com.example.orgclock.sync.SyncTransportCredential
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPairingManagerTest {
    @Test
    fun invitationIsSingleUseAndCreatesPerDeviceCredential() {
        var now = 1_000L
        val manager = DesktopPairingManager(nowEpochMs = { now }, tokenLifetimeMs = 120_000L)
        val fingerprint = "ab".repeat(32)
        val invitation = manager.currentInvitation(fingerprint)
        val prefs = Preferences.userRoot().node("orgclock-test/${UUID.randomUUID()}")
        try {
            val store = DesktopPeerTrustStore(prefs)
            val credentialStore = RecordingDesktopTransportCredentialStore()
            val encoded = manager.exchange(
                SyncPairingExchangeRequest(invitation.token, "phone-1", "Phone"),
                store,
                fingerprint,
                credentialStore,
            )
            assertNotNull(store.getTrustRecord("phone-1"))
            assertTrue(SyncTransportCredentialCodec.decode(encoded).isSuccess)
            assertEquals(SyncTransportCredentialCodec.decode(encoded).getOrThrow(), credentialStore.get("phone-1"))
            assertFailsWith<IllegalStateException> {
                manager.exchange(SyncPairingExchangeRequest(invitation.token, "phone-2", "Other"), store, fingerprint)
            }
        } finally {
            prefs.removeNode()
        }
    }

    @Test
    fun invitationV2IsSingleUseAndCreatesSignedPeerRecord() {
        var now = 1_000L
        val manager = DesktopPairingManager(nowEpochMs = { now }, tokenLifetimeMs = 120_000L)
        val fingerprint = "ef".repeat(32)
        val invitation = manager.currentInvitationV2(
            certificateSha256 = fingerprint,
            endpoint = "https://desktop.local:8787/",
            hostPeerId = "desktop-peer",
            hostDeviceId = "desktop-device",
            hostDisplayName = "Desktop Host",
            hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
            capabilities = listOf("sync-core.envelope.v1"),
        )
        val prefs = Preferences.userRoot().node("orgclock-test/${UUID.randomUUID()}")
        try {
            val store = DesktopPeerTrustStore(prefs)
            val credentialStore = RecordingDesktopTransportCredentialStore()
            val response = manager.exchangeV2(
                request = SyncPairingExchangeRequestV2(
                    invitationToken = invitation.token,
                    requesterPeerId = "phone-peer",
                    requesterDeviceId = "phone-device",
                    requesterDisplayName = "Phone",
                    requesterSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                    requestedRole = PeerTrustRole.Viewer,
                ),
                trustStore = store,
                certificateSha256 = fingerprint,
                hostPeerId = "desktop-peer",
                hostDeviceId = "desktop-device",
                hostDisplayName = "Desktop Host",
                hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                transportCredentialStore = credentialStore,
            )

            val record = store.getTrustRecord("phone-peer")
            assertEquals("phone-device", record?.deviceId)
            assertEquals("Phone", record?.displayName)
            assertEquals(ED25519_PUBLIC_KEY_BASE64, record?.signingPublicKeyBase64)
            assertEquals(PeerTrustRole.Viewer, record?.role)
            assertEquals(fingerprint, record?.certificateSha256)
            assertTrue(SyncTransportCredentialCodec.decode(response.encodedTransportCredential).isSuccess)
            assertEquals(
                SyncTransportCredentialCodec.decode(response.encodedTransportCredential).getOrThrow(),
                credentialStore.get("phone-peer"),
            )
            assertEquals("desktop-peer", response.hostPeerId)
            assertEquals("desktop-device", response.hostDeviceId)
            assertEquals(ED25519_PUBLIC_KEY_BASE64, response.hostSigningPublicKeyBase64)
            assertFailsWith<IllegalStateException> {
                manager.exchangeV2(
                    request = SyncPairingExchangeRequestV2(
                        invitationToken = invitation.token,
                        requesterPeerId = "phone-peer-2",
                        requesterDeviceId = "phone-device-2",
                        requesterDisplayName = "Other",
                        requesterSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                    ),
                    trustStore = store,
                    certificateSha256 = fingerprint,
                    hostPeerId = "desktop-peer",
                    hostDeviceId = "desktop-device",
                    hostDisplayName = "Desktop Host",
                    hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
                )
            }
        } finally {
            prefs.removeNode()
        }
    }

    @Test
    fun expiredInvitationIsRejected() {
        var now = 1_000L
        val manager = DesktopPairingManager(nowEpochMs = { now }, tokenLifetimeMs = 10L)
        val invitation = manager.currentInvitation("cd".repeat(32))
        now = 1_011L
        val prefs = Preferences.userRoot().node("orgclock-test/${UUID.randomUUID()}")
        try {
            assertFailsWith<IllegalArgumentException> {
                manager.exchange(SyncPairingExchangeRequest(invitation.token, "phone", "Phone"), DesktopPeerTrustStore(prefs), invitation.certificateSha256)
            }
        } finally {
            prefs.removeNode()
        }
    }
}

private const val ED25519_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="

private class RecordingDesktopTransportCredentialStore : DesktopSyncCoreTransportCredentialStore {
    private val credentials = linkedMapOf<String, SyncTransportCredential>()

    override fun put(peerId: String, credential: SyncTransportCredential) {
        credentials[peerId.trim()] = credential
    }

    override fun get(peerId: String): SyncTransportCredential? = credentials[peerId.trim()]

    override fun delete(peerId: String) {
        credentials.remove(peerId.trim())
    }
}
