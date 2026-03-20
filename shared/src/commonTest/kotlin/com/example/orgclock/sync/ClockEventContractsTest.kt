package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class ClockEventContractsTest {

    @Test
    fun `event type maps from wire value`() {
        assertEquals(ClockEventType.Started, ClockEventType.fromWireValue("clock_started"))
        assertEquals(ClockEventType.Stopped, ClockEventType.fromWireValue("clock_stopped"))
        assertEquals(ClockEventType.Cancelled, ClockEventType.fromWireValue("clock_cancelled"))
        assertNull(ClockEventType.fromWireValue("clock.unknown"))
    }

    @Test
    fun `clock event defaults to v1 schema`() {
        val event = ClockEvent(
            eventId = "evt-01",
            eventType = ClockEventType.Started,
            deviceId = "device-a",
            createdAt = Instant.parse("2026-03-18T10:15:30Z"),
            logicalDay = LocalDate.parse("2026-03-18"),
            fileName = "2026-03-18.org",
            headingPath = HeadingPath.parse("Work/Project A"),
            causalOrder = ClockEventCausalOrder(counter = 42),
        )

        assertEquals(CLOCK_EVENT_SCHEMA_V1, event.schema)
    }

    @Test
    fun `clock event rejects unsupported schema`() {
        assertFailsWith<IllegalArgumentException> {
            ClockEvent(
                schema = "clock.event.v0",
                eventId = "evt-01",
                eventType = ClockEventType.Started,
                deviceId = "device-a",
                createdAt = Instant.parse("2026-03-18T10:15:30Z"),
                logicalDay = LocalDate.parse("2026-03-18"),
                fileName = "2026-03-18.org",
                headingPath = HeadingPath.parse("Work/Project A"),
                causalOrder = ClockEventCausalOrder(counter = 42),
            )
        }
    }

    @Test
    fun `cursor must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ClockEventCursor(0)
        }
    }

    @Test
    fun `snapshot reports pending status when unsynced events remain`() {
        val snapshot = ClockEventStoreSnapshot(
            lastCursor = ClockEventCursor(10),
            lastSyncedCursor = ClockEventCursor(7),
            pendingSyncCount = 3,
        )

        assertEquals(LocalClockEventSyncStatus.Pending, snapshot.syncStatus)
    }

    @Test
    fun `snapshot reports synced status when no pending events remain`() {
        val snapshot = ClockEventStoreSnapshot(
            lastCursor = ClockEventCursor(10),
            lastSyncedCursor = ClockEventCursor(10),
            pendingSyncCount = 0,
        )

        assertEquals(LocalClockEventSyncStatus.Synced, snapshot.syncStatus)
    }

    @Test
    fun `snapshot converts to local clock event sync state`() {
        val snapshot = ClockEventStoreSnapshot(
            lastCursor = ClockEventCursor(12),
            lastSyncedCursor = ClockEventCursor(8),
            pendingSyncCount = 4,
        )

        val state = snapshot.toClockEventSyncState()

        assertEquals(ClockEventSyncStatus.Pending, state.status)
        assertEquals(4, state.pendingLocalEventCount)
        assertEquals(12L, state.lastCursor)
        assertEquals(8L, state.lastSyncedCursor)
    }

    @Test
    fun `snapshot converter marks recovery required when quarantine exists`() {
        val snapshot = ClockEventStoreSnapshot(
            lastCursor = ClockEventCursor(1),
            lastSyncedCursor = null,
            pendingSyncCount = 0,
            lastRejectReason = "viewer peers are not allowed to send clock events",
            quarantinedEventCount = 1,
        )

        val state = snapshot.toClockEventSyncState()

        assertEquals(ClockEventSyncStatus.RecoveryRequired, state.status)
        assertEquals("viewer peers are not allowed to send clock events", state.lastRejectReason)
        assertEquals(1, state.quarantinedEventCount)
    }

    @Test
    fun `snapshot converter marks error when last error exists`() {
        val snapshot = ClockEventStoreSnapshot(
            lastCursor = ClockEventCursor(1),
            lastSyncedCursor = null,
            pendingSyncCount = 1,
        )

        val state = snapshot.toClockEventSyncState(lastError = "disk full")

        assertEquals(ClockEventSyncStatus.Error, state.status)
        assertEquals("disk full", state.lastError)
    }
}
