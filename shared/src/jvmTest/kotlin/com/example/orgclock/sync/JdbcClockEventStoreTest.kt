package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class JdbcClockEventStoreTest {

    @Test
    fun append_duplicate_snapshot_and_listSince_work() = runTest {
        val tempDir = Files.createTempDirectory("clock-event-store-test")
        val store = JdbcClockEventStore.create(tempDir.resolve("events.db"))

        val first = sampleEvent("evt-1", counter = 1)
        val second = sampleEvent("evt-2", counter = 2)

        val firstResult = assertIs<AppendClockEventResult.Appended>(store.append(first))
        val secondResult = assertIs<AppendClockEventResult.Appended>(store.append(second))
        val duplicateResult = assertIs<AppendClockEventResult.Duplicate>(store.append(first))

        assertEquals(firstResult.cursor, duplicateResult.cursor)
        assertTrue(store.contains("evt-1"))
        assertFalse(store.contains("evt-missing"))

        val replay = store.readAllForReplay()
        assertEquals(listOf("evt-1", "evt-2"), replay.map { it.event.eventId })

        val afterFirst = store.listSince(firstResult.cursor, limit = 10)
        assertEquals(listOf("evt-2"), afterFirst.map { it.event.eventId })

        store.updateSyncCheckpoint(secondResult.cursor)
        val snapshot = store.readSnapshot()
        assertEquals(0, snapshot.pendingSyncCount)
        assertEquals(LocalClockEventSyncStatus.Synced, snapshot.syncStatus)
        assertEquals(secondResult.cursor, snapshot.lastSyncedCursor)
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
