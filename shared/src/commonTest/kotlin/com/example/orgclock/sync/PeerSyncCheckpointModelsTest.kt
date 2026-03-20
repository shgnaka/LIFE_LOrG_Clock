package com.example.orgclock.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerSyncCheckpointModelsTest {
    @Test
    fun checkpoint_markSeen_and_markSent_updateCursorAndTimestamps() {
        val checkpoint = PeerSyncCheckpoint(peerId = "peer-a")

        val seen = checkpoint.markSeen(ClockEventCursor(4), seenAtEpochMs = 111L)
        val sent = seen.markSent(ClockEventCursor(7), syncedAtEpochMs = 222L)

        assertEquals(ClockEventCursor(4), seen.lastSeenCursor)
        assertEquals(111L, seen.lastSeenAtEpochMs)
        assertNull(seen.lastSentCursor)
        assertEquals(ClockEventCursor(7), sent.lastSentCursor)
        assertEquals(222L, sent.lastSyncedAtEpochMs)
        assertEquals(222L, sent.updatedAtEpochMs)
    }

    @Test
    fun inMemoryStore_persistsPerPeerProgress() {
        val store = InMemoryPeerSyncCheckpointStore()

        store.markSeen("peer-a", ClockEventCursor(2), seenAtEpochMs = 100L)
        store.markSent("peer-a", ClockEventCursor(5), syncedAtEpochMs = 200L)

        val checkpoint = store.get("peer-a")
        assertEquals("peer-a", checkpoint?.peerId)
        assertEquals(ClockEventCursor(2), checkpoint?.lastSeenCursor)
        assertEquals(ClockEventCursor(5), checkpoint?.lastSentCursor)
        assertEquals(100L, checkpoint?.lastSeenAtEpochMs)
        assertEquals(200L, checkpoint?.lastSyncedAtEpochMs)
    }
}
