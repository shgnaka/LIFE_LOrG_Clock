package com.example.orgclock.desktop

import com.example.orgclock.sync.ClockEventCursor
import com.example.orgclock.sync.PeerSyncCheckpoint
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPeerSyncCheckpointStoreTest {
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
    fun preferencesStore_roundTripsCheckpointProgress() {
        val node = testNode()
        val store = DesktopPeerSyncCheckpointStore(node)
        store.markSeen("peer-a", ClockEventCursor(4), seenAtEpochMs = 111L)
        store.markSent("peer-a", ClockEventCursor(8), syncedAtEpochMs = 222L)

        val checkpoint = store.get("peer-a")
        assertEquals("peer-a", checkpoint?.peerId)
        assertEquals(ClockEventCursor(4), checkpoint?.lastSeenCursor)
        assertEquals(ClockEventCursor(8), checkpoint?.lastSentCursor)
        assertEquals(111L, checkpoint?.lastSeenAtEpochMs)
        assertEquals(222L, checkpoint?.lastSyncedAtEpochMs)

        val restored = DesktopPeerSyncCheckpointStore(node).get("peer-a")
        assertEquals(checkpoint, restored)
    }

    @Test
    fun clearRemovesCheckpoint() {
        val node = testNode()
        val store = DesktopPeerSyncCheckpointStore(node)
        store.save(
            PeerSyncCheckpoint(
                peerId = "peer-a",
                lastSeenCursor = ClockEventCursor(1),
            ),
        )
        store.clear("peer-a")

        assertNull(DesktopPeerSyncCheckpointStore(node).get("peer-a"))
    }

    private fun testNode(): Preferences {
        val node = Preferences.userRoot().node("com/example/orgclock/desktop/sync-test-${nodes.size}")
        nodes += node
        return node
    }
}
