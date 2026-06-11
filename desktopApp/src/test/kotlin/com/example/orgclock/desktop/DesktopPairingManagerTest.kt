package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncPairingExchangeRequest
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
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
            val encoded = manager.exchange(SyncPairingExchangeRequest(invitation.token, "phone-1", "Phone"), store, fingerprint)
            assertNotNull(store.getTrustRecord("phone-1"))
            assertTrue(SyncTransportCredentialCodec.decode(encoded).isSuccess)
            assertFailsWith<IllegalStateException> {
                manager.exchange(SyncPairingExchangeRequest(invitation.token, "phone-2", "Other"), store, fingerprint)
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
