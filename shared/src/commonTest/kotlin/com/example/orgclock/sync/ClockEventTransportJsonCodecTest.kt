package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockEventTransportJsonCodecTest {
    private val stored = StoredClockEvent(
        cursor = ClockEventCursor(4),
        event = ClockEvent(
            eventId = "event-1",
            eventType = ClockEventType.Stopped,
            deviceId = "dev-a",
            createdAt = Instant.parse("2026-06-11T01:02:03Z"),
            logicalDay = LocalDate.parse("2026-06-11"),
            fileName = "2026-06-11.org",
            headingPath = HeadingPath(listOf("Work", "実装")),
            causalOrder = ClockEventCausalOrder(counter = 8),
        ),
    )

    @Test
    fun fetchPayloadsRoundTrip() {
        val request = ClockEventFetchRequest("clock.event.transport.v1", "phone", "desktop", ClockEventCursor(3), 64)
        val response = ClockEventFetchResponse(events = listOf(stored), sourcePeerId = "desktop", targetPeerId = "phone")
        assertEquals(request, ClockEventTransportJsonCodec.decodeFetchRequest(ClockEventTransportJsonCodec.encodeFetchRequest(request)))
        assertEquals(response, ClockEventTransportJsonCodec.decodeFetchResponse(ClockEventTransportJsonCodec.encodeFetchResponse(response)))
    }

    @Test
    fun pushAndAckPayloadsRoundTrip() {
        val push = ClockEventPushRequest(sourcePeerId = "phone", targetPeerId = "desktop", events = listOf(stored))
        val response = ClockEventPushResponse("clock.event.transport.v1", "desktop", "phone", ClockEventCursor(4), listOf("d"), emptyList(), null)
        val ack = ClockEventTransportAck(sourcePeerId = "phone", targetPeerId = "desktop", seenCursor = ClockEventCursor(4), acknowledgedEventIds = listOf("event-1"), acknowledgedAt = Instant.parse("2026-06-11T01:03:00Z"))
        assertEquals(push, ClockEventTransportJsonCodec.decodePushRequest(ClockEventTransportJsonCodec.encodePushRequest(push)))
        assertEquals(response, ClockEventTransportJsonCodec.decodePushResponse(ClockEventTransportJsonCodec.encodePushResponse(response)))
        assertEquals(ack, ClockEventTransportJsonCodec.decodeAck(ClockEventTransportJsonCodec.encodeAck(ack)))
        assertEquals(ClockEventTransportAckResult.Accepted, ClockEventTransportJsonCodec.decodeAckResult(ClockEventTransportJsonCodec.encodeAckResult(ClockEventTransportAckResult.Accepted)))
    }
}
