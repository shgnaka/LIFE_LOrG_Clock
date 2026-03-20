package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class EventSyncTransportTest {
    @Test
    fun transportBindingRequiresPeerIds() {
        assertFailsWith<IllegalArgumentException> {
            ClockEventTransportBinding(localPeerId = "", remotePeerId = "peer-b")
        }
    }

    @Test
    fun roundTripCarriesFetchPushAndAckArtifacts() {
        val event = storedEvent(1)
        val request = ClockEventFetchRequest(sourcePeerId = "peer-a", targetPeerId = "peer-b", sinceCursor = null, batchLimit = 16)
        val response = ClockEventFetchResponse(sourcePeerId = "peer-b", targetPeerId = "peer-a", events = listOf(event))
        val ack = ClockEventTransportAck(
            sourcePeerId = "peer-a",
            targetPeerId = "peer-b",
            seenCursor = event.cursor,
            acknowledgedEventIds = listOf(event.event.eventId),
            acknowledgedAt = Instant.parse("2026-03-10T09:00:00Z"),
        )

        val roundTrip = ClockEventSyncRoundTrip(
            binding = ClockEventTransportBinding(localPeerId = "peer-a", remotePeerId = "peer-b"),
            request = request,
            response = response,
            ack = ack,
            ackResult = ClockEventTransportAckResult.Accepted,
        )

        assertEquals("peer-a", roundTrip.binding.localPeerId)
        assertEquals(1L, roundTrip.response.lastSeenCursor?.value)
        assertEquals("evt-1", roundTrip.ack.acknowledgedEventIds.single())
    }

    @Test
    fun ackResultCanRejectWithReason() {
        val result = ClockEventTransportAckResult.Rejected("revoked peer")

        assertEquals("revoked peer", result.reason)
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

