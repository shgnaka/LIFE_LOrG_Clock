package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class ClockEventProjectorTest {
    private val projector = ClockEventProjector()

    @Test
    fun `started then stopped produces one history entry`() {
        val projection = projector.project(
            listOf(
                storedEvent(1, "evt-1", ClockEventType.Started, "2026-03-18T09:00:00Z"),
                storedEvent(2, "evt-2", ClockEventType.Stopped, "2026-03-18T10:30:00Z"),
            ),
        )

        assertTrue(projection.activeClocks.isEmpty())
        assertTrue(projection.issues.isEmpty())
        assertEquals(1, projection.historyEntries.size)
        assertEquals(90, projection.historyEntries.single().durationMinutes)
    }

    @Test
    fun `started then cancelled leaves no history and no active clock`() {
        val projection = projector.project(
            listOf(
                storedEvent(1, "evt-1", ClockEventType.Started, "2026-03-18T09:00:00Z"),
                storedEvent(2, "evt-2", ClockEventType.Cancelled, "2026-03-18T09:10:00Z"),
            ),
        )

        assertTrue(projection.activeClocks.isEmpty())
        assertTrue(projection.historyEntries.isEmpty())
        assertTrue(projection.issues.isEmpty())
    }

    @Test
    fun `stop without open clock is recorded as issue`() {
        val projection = projector.project(
            listOf(
                storedEvent(1, "evt-1", ClockEventType.Stopped, "2026-03-18T10:30:00Z"),
            ),
        )

        assertTrue(projection.activeClocks.isEmpty())
        assertTrue(projection.historyEntries.isEmpty())
        assertEquals(listOf(ClockProjectionIssueCode.StopWithoutOpen), projection.issues.map { it.code })
    }

    @Test
    fun `duplicate start keeps original active clock and records issue`() {
        val projection = projector.project(
            listOf(
                storedEvent(1, "evt-1", ClockEventType.Started, "2026-03-18T09:00:00Z"),
                storedEvent(2, "evt-2", ClockEventType.Started, "2026-03-18T09:30:00Z"),
            ),
        )

        assertEquals(1, projection.activeClocks.size)
        assertEquals(Instant.parse("2026-03-18T09:00:00Z"), projection.activeClocks.single().start)
        assertEquals(listOf(ClockProjectionIssueCode.StartWhileOpen), projection.issues.map { it.code })
    }

    @Test
    fun `stop before start is recorded as issue and open clock remains`() {
        val projection = projector.project(
            listOf(
                storedEvent(1, "evt-1", ClockEventType.Started, "2026-03-18T09:00:00Z"),
                storedEvent(2, "evt-2", ClockEventType.Stopped, "2026-03-18T08:30:00Z"),
            ),
        )

        assertEquals(1, projection.activeClocks.size)
        assertTrue(projection.historyEntries.isEmpty())
        assertEquals(listOf(ClockProjectionIssueCode.StopBeforeStart), projection.issues.map { it.code })
    }

    private fun storedEvent(
        cursor: Long,
        eventId: String,
        type: ClockEventType,
        createdAt: String,
        logicalDay: String = "2026-03-18",
        fileName: String = "2026-03-18.org",
        headingPath: String = "Work/Project A",
    ): StoredClockEvent = StoredClockEvent(
        cursor = ClockEventCursor(cursor),
        event = ClockEvent(
            eventId = eventId,
            eventType = type,
            deviceId = "device-a",
            createdAt = Instant.parse(createdAt),
            logicalDay = LocalDate.parse(logicalDay),
            fileName = fileName,
            headingPath = HeadingPath.parse(headingPath),
            causalOrder = ClockEventCausalOrder(counter = cursor),
        ),
    )
}
