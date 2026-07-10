package com.example.orgclock.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncPairingCodeTest {
    @Test
    fun roundTripsUnicodeAndEndpoint() {
        val code = SyncPairingCode(
            peerId = "192.168.1.20:8787",
            deviceId = "dev-desktop",
            displayName = "仕事用 PC",
            publicKeyBase64 = "pairing-secret",
            endpoint = "http://192.168.1.20:8787",
        )

        val encoded = SyncPairingCodeCodec.encode(code)
        val decoded = SyncPairingCodeCodec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith(ORG_CLOCK_PAIRING_URI_PREFIX))
        assertEquals(code, decoded)
    }

    @Test
    fun rejectsUnknownScheme() {
        assertTrue(SyncPairingCodeCodec.decode("https://example.com").isFailure)
    }
}

class SyncTransportCredentialTest {
    @Test
    fun pairingV2MessagesRoundTrip() {
        val invitation = SyncPairingInvitationV2(
            token = "one-time",
            hostPeerId = "desktop-peer",
            hostDeviceId = "desktop-device",
            hostDisplayName = "Desktop",
            hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
            certificateSha256 = "ab".repeat(32),
            endpoint = "https://desktop.local:39091",
            expiresAtEpochMs = 123456L,
            capabilities = listOf("clock.command.v1"),
        )
        assertEquals(invitation, SyncPairingInvitationV2Codec.decode(SyncPairingInvitationV2Codec.encode(invitation)).getOrThrow())

        val request = SyncPairingExchangeRequestV2(
            invitationToken = "one-time",
            requesterPeerId = "android-peer",
            requesterDeviceId = "android-device",
            requesterDisplayName = "Android",
            requesterSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
            requestedRole = PeerTrustRole.Viewer,
            capabilities = listOf("clock.command.v1"),
        )
        assertEquals(request, SyncPairingExchangeV2JsonCodec.decodeRequest(SyncPairingExchangeV2JsonCodec.encodeRequest(request)))

        val response = SyncPairingExchangeResponseV2(
            hostPeerId = "desktop-peer",
            hostDeviceId = "desktop-device",
            hostDisplayName = "Desktop",
            hostSigningPublicKeyBase64 = ED25519_PUBLIC_KEY_BASE64,
            grantedRole = PeerTrustRole.Viewer,
            encodedTransportCredential = SyncTransportCredentialCodec.encode(SyncTransportCredential("secret", "ab".repeat(32))),
            certificateSha256 = "ab".repeat(32),
            capabilities = listOf("clock.command.v1"),
        )
        assertEquals(response, SyncPairingExchangeV2JsonCodec.decodeResponse(SyncPairingExchangeV2JsonCodec.encodeResponse(response)))
    }
    @Test
    fun credentialRoundTrips() {
        val credential = SyncTransportCredential("secret", "ab".repeat(32))
        assertEquals(credential, SyncTransportCredentialCodec.decode(SyncTransportCredentialCodec.encode(credential)).getOrThrow())
        val invitation = SyncPairingInvitation("one-time", "cd".repeat(32), 123456L)
        assertEquals(invitation, SyncPairingInvitationCodec.decode(SyncPairingInvitationCodec.encode(invitation)).getOrThrow())
        val exchange = SyncPairingExchangeRequest("one-time", "phone-1", "Phone")
        assertEquals(exchange, SyncPairingExchangeJsonCodec.decodeRequest(SyncPairingExchangeJsonCodec.encodeRequest(exchange)))
    }
}

private const val ED25519_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
