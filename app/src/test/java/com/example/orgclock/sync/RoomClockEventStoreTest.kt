package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class RoomClockEventStoreTest {

    @Test
    fun append_duplicate_and_snapshot_work() = runTest {
        val dao = FakeClockEventDao()
        val store = RoomClockEventStore(dao)
        val first = sampleEvent("evt-1", 1)
        val second = sampleEvent("evt-2", 2)

        val firstResult = assertIs<AppendClockEventResult.Appended>(store.append(first))
        val secondResult = assertIs<AppendClockEventResult.Appended>(store.append(second))
        val duplicateResult = assertIs<AppendClockEventResult.Duplicate>(store.append(first))

        assertEquals(firstResult.cursor, duplicateResult.cursor)
        assertTrue(store.contains("evt-1"))
        assertFalse(store.contains("evt-3"))
        assertEquals(listOf("evt-1", "evt-2"), store.readAllForReplay().map { it.event.eventId })

        store.updateSyncCheckpoint(firstResult.cursor)
        val snapshot = store.readSnapshot()
        assertEquals(1, snapshot.pendingSyncCount)
        assertEquals(LocalClockEventSyncStatus.Pending, snapshot.syncStatus)
        assertEquals(secondResult.cursor, snapshot.lastCursor)
    }

    private fun sampleEvent(eventId: String, counter: Long): ClockEvent = ClockEvent(
        eventId = eventId,
        eventType = ClockEventType.Started,
        deviceId = "device-a",
        createdAt = Instant.parse("2026-03-18T10:15:30Z"),
        logicalDay = LocalDate.parse("2026-03-18"),
        fileName = "2026-03-18.org",
        headingPath = HeadingPath.parse("Work/Project A"),
        causalOrder = ClockEventCausalOrder(counter = counter),
    )
}

private class FakeClockEventDao : ClockEventDao {
    private val events = linkedMapOf<String, ClockEventEntity>()
    private var nextCursor = 1L
    private var syncState: ClockEventSyncStateEntity? = null

    override fun insert(event: ClockEventEntity): Long {
        if (events.containsKey(event.eventId)) return -1L
        val stored = event.copy(seq = nextCursor++)
        events[event.eventId] = stored
        return stored.seq
    }

    override fun findCursorByEventId(eventId: String): Long? = events[eventId]?.seq

    override fun exists(eventId: String): Boolean = events.containsKey(eventId)

    override fun readAll(): List<ClockEventEntity> = events.values.sortedBy { it.seq }

    override fun listSince(cursorExclusive: Long, limit: Int): List<ClockEventEntity> =
        events.values.filter { it.seq > cursorExclusive }.sortedBy { it.seq }.take(limit)

    override fun findLastCursor(): Long? = events.values.maxOfOrNull { it.seq }

    override fun countAfter(cursorExclusive: Long): Int = events.values.count { it.seq > cursorExclusive }

    override fun cursorExists(cursor: Long): Boolean = events.values.any { it.seq == cursor }

    override fun readSyncState(): ClockEventSyncStateEntity? = syncState

    override fun saveSyncState(state: ClockEventSyncStateEntity) {
        syncState = state
    }
}
