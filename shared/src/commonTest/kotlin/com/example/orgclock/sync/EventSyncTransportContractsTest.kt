package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class EventSyncTransportContractsTest {
    @Test
    fun fetchRequestRejectsNonPositiveBatchLimit() {
        assertFailsWith<IllegalArgumentException> {
            ClockEventFetchRequest(
                sourcePeerId = "peer-a",
                batchLimit = 0,
            )
        }
    }

    @Test
    fun fetchResponseExposesLastCursorAndNextFetchCursor() {
        val response = ClockEventFetchResponse(
            sourcePeerId = "peer-a",
            targetPeerId = "peer-b",
            events = listOf(
                storedEvent(1),
                storedEvent(2),
            ),
            hasMore = true,
        )

        assertEquals(ClockEventCursor(2), response.lastSeenCursor)
        assertEquals(ClockEventCursor(3), response.nextFetchCursor())
    }

    @Test
    fun cursorNextAdvancesByOne() {
        assertEquals(ClockEventCursor(11), ClockEventCursor(10).next())
    }

    @Test
    fun emptyFetchResponseRequiresNullNextCursor() {
        val response = ClockEventFetchResponse(
            sourcePeerId = "peer-a",
            targetPeerId = "peer-b",
            events = emptyList(),
        )

        assertNull(response.lastSeenCursor)
        assertNull(response.nextFetchCursor())
        assertNull(response.nextCursor)
    }

    @Test
    fun pushRequestRejectsOutOfOrderCursorBatch() {
        assertFailsWith<IllegalArgumentException> {
            ClockEventPushRequest(
                sourcePeerId = "peer-a",
                targetPeerId = "peer-b",
                events = listOf(
                    storedEvent(2),
                    storedEvent(1),
                ),
            )
        }
    }

    @Test
    fun ackCarriesSeenCursorAndTimestamp() {
        val ack = ClockEventTransportAck(
            sourcePeerId = "peer-b",
            targetPeerId = "peer-a",
            seenCursor = ClockEventCursor(12),
            acknowledgedEventIds = listOf("evt-01", "evt-02"),
            acknowledgedAt = Instant.parse("2026-03-10T09:00:00Z"),
        )

        assertEquals(CLOCK_EVENT_TRANSPORT_SCHEMA_V1, ack.schema)
        assertEquals(ClockEventCursor(12), ack.seenCursor)
        assertEquals(listOf("evt-01", "evt-02"), ack.acknowledgedEventIds)
    }

    private fun storedEvent(cursorValue: Long): StoredClockEvent {
        return StoredClockEvent(
            cursor = ClockEventCursor(cursorValue),
            event = ClockEvent(
                eventId = "evt-$cursorValue",
                eventType = ClockEventType.Started,
                deviceId = "device-a",
                createdAt = Instant.parse("2026-03-10T09:00:00Z"),
                logicalDay = LocalDate.parse("2026-03-10"),
                fileName = "2026-03-10.org",
                headingPath = HeadingPath.parse("Work/Project A"),
                causalOrder = ClockEventCausalOrder(counter = cursorValue),
            ),
        )
    }
}
