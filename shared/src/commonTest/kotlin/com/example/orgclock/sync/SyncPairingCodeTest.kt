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
    fun credentialRoundTrips() {
        val credential = SyncTransportCredential("secret", "ab".repeat(32))
        assertEquals(credential, SyncTransportCredentialCodec.decode(SyncTransportCredentialCodec.encode(credential)).getOrThrow())
        val invitation = SyncPairingInvitation("one-time", "cd".repeat(32), 123456L)
        assertEquals(invitation, SyncPairingInvitationCodec.decode(SyncPairingInvitationCodec.encode(invitation)).getOrThrow())
        val exchange = SyncPairingExchangeRequest("one-time", "phone-1", "Phone")
        assertEquals(exchange, SyncPairingExchangeJsonCodec.decodeRequest(SyncPairingExchangeJsonCodec.encodeRequest(exchange)))
    }
}
