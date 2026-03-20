package com.example.orgclock.desktop

import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.PeerTrustRole
import java.util.prefs.Preferences
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPeerTrustStoreTest {
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanup() {
        nodes.asReversed().forEach { node ->
            runCatching {
                node.removeNode()
                node.parent()?.flush()
            }
        }
        nodes.clear()
    }

    @Test
    fun revoke_removesActiveTrustWithoutDiscardingRecord() {
        val store = DesktopPeerTrustStore(testNode())
        val record = PeerTrustRecord(
            peerId = "peer-a",
            deviceId = "device-a",
            displayName = "Desktop Host",
            publicKeyBase64 = "pk-a",
            role = PeerTrustRole.Full,
            registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            lastSeenAt = Instant.parse("2026-03-10T09:10:00Z"),
        )

        store.trust(record)
        assertTrue(store.isTrusted("peer-a"))

        store.revoke("peer-a")

        assertFalse(store.isTrusted("peer-a"))
        assertTrue(store.listTrusted().isEmpty())
        val revoked = store.getTrustRecord("peer-a")
        assertNotNull(revoked)
        assertFalse(revoked.isActive)
    }

    private fun testNode(): Preferences {
        val node = Preferences.userRoot().node("com/example/orgclock/desktop/trust-test-${nodes.size}")
        nodes += node
        return node
    }
}
