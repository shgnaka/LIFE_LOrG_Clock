package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ClockEventTransportJsonCodec {
    fun encodeFetchRequest(value: ClockEventFetchRequest): String =
        json.encodeToString(FetchRequestWire.serializer(), value.toWire())

    fun decodeFetchRequest(raw: String): ClockEventFetchRequest =
        json.decodeFromString(FetchRequestWire.serializer(), raw).toModel()

    fun encodeFetchResponse(value: ClockEventFetchResponse): String =
        json.encodeToString(FetchResponseWire.serializer(), value.toWire())

    fun decodeFetchResponse(raw: String): ClockEventFetchResponse =
        json.decodeFromString(FetchResponseWire.serializer(), raw).toModel()

    fun encodePushRequest(value: ClockEventPushRequest): String =
        json.encodeToString(PushRequestWire.serializer(), value.toWire())

    fun decodePushRequest(raw: String): ClockEventPushRequest =
        json.decodeFromString(PushRequestWire.serializer(), raw).toModel()

    fun encodePushResponse(value: ClockEventPushResponse): String =
        json.encodeToString(PushResponseWire.serializer(), value.toWire())

    fun decodePushResponse(raw: String): ClockEventPushResponse =
        json.decodeFromString(PushResponseWire.serializer(), raw).toModel()

    fun encodeAck(value: ClockEventTransportAck): String =
        json.encodeToString(AckWire.serializer(), value.toWire())

    fun decodeAck(raw: String): ClockEventTransportAck =
        json.decodeFromString(AckWire.serializer(), raw).toModel()

    fun encodeAckResult(value: ClockEventTransportAckResult): String =
        json.encodeToString(
            AckResultWire.serializer(),
            when (value) {
                ClockEventTransportAckResult.Accepted -> AckResultWire(accepted = true)
                is ClockEventTransportAckResult.Rejected -> AckResultWire(false, value.reason)
            },
        )

    fun decodeAckResult(raw: String): ClockEventTransportAckResult {
        val wire = json.decodeFromString(AckResultWire.serializer(), raw)
        return if (wire.accepted) ClockEventTransportAckResult.Accepted
        else ClockEventTransportAckResult.Rejected(wire.reason ?: "ack rejected")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

@Serializable
private data class FetchRequestWire(
    val schema: String,
    val sourcePeerId: String,
    val targetPeerId: String? = null,
    val sinceCursor: Long? = null,
    val batchLimit: Int,
)

@Serializable
private data class FetchResponseWire(
    val schema: String,
    val sourcePeerId: String,
    val targetPeerId: String,
    val events: List<StoredEventWire>,
    val hasMore: Boolean,
)

@Serializable
private data class PushRequestWire(
    val schema: String,
    val sourcePeerId: String,
    val targetPeerId: String,
    val events: List<StoredEventWire>,
)

@Serializable
private data class PushResponseWire(
    val schema: String,
    val sourcePeerId: String,
    val targetPeerId: String,
    val acceptedCursor: Long? = null,
    val duplicateEventIds: List<String>,
    val rejectedEventIds: List<String>,
    val rejectReason: String? = null,
)

@Serializable
private data class AckWire(
    val schema: String,
    val sourcePeerId: String,
    val targetPeerId: String,
    val seenCursor: Long? = null,
    val acknowledgedEventIds: List<String>,
    val acknowledgedAt: String,
)

@Serializable
private data class AckResultWire(val accepted: Boolean, val reason: String? = null)

@Serializable
private data class StoredEventWire(val cursor: Long, val event: EventWire)

@Serializable
private data class EventWire(
    val schema: String,
    val eventId: String,
    val eventType: String,
    val deviceId: String,
    val createdAt: String,
    val logicalDay: String,
    val fileName: String,
    val headingPath: List<String>,
    val causalKind: String,
    val causalCounter: Long,
)

private fun ClockEventFetchRequest.toWire() = FetchRequestWire(
    schema, sourcePeerId, targetPeerId, sinceCursor?.value, batchLimit,
)

private fun FetchRequestWire.toModel() = ClockEventFetchRequest(
    schema, sourcePeerId, targetPeerId, sinceCursor?.let(::ClockEventCursor), batchLimit,
)

private fun ClockEventFetchResponse.toWire() = FetchResponseWire(
    schema, sourcePeerId, targetPeerId, events.map(StoredClockEvent::toWire), hasMore,
)

private fun FetchResponseWire.toModel() = ClockEventFetchResponse(
    schema = schema,
    sourcePeerId = sourcePeerId,
    targetPeerId = targetPeerId,
    events = events.map(StoredEventWire::toModel),
    hasMore = hasMore,
)

private fun ClockEventPushRequest.toWire() = PushRequestWire(
    schema, sourcePeerId, targetPeerId, events.map(StoredClockEvent::toWire),
)

private fun PushRequestWire.toModel() = ClockEventPushRequest(
    schema, sourcePeerId, targetPeerId, events.map(StoredEventWire::toModel),
)

private fun ClockEventPushResponse.toWire() = PushResponseWire(
    schema, sourcePeerId, targetPeerId, acceptedCursor?.value,
    duplicateEventIds, rejectedEventIds, rejectReason,
)

private fun PushResponseWire.toModel() = ClockEventPushResponse(
    schema, sourcePeerId, targetPeerId, acceptedCursor?.let(::ClockEventCursor),
    duplicateEventIds, rejectedEventIds, rejectReason,
)

private fun ClockEventTransportAck.toWire() = AckWire(
    schema, sourcePeerId, targetPeerId, seenCursor?.value,
    acknowledgedEventIds, acknowledgedAt.toString(),
)

private fun AckWire.toModel() = ClockEventTransportAck(
    schema, sourcePeerId, targetPeerId, seenCursor?.let(::ClockEventCursor),
    acknowledgedEventIds, Instant.parse(acknowledgedAt),
)

private fun StoredClockEvent.toWire() = StoredEventWire(
    cursor.value,
    EventWire(
        event.schema,
        event.eventId,
        event.eventType.wireValue,
        event.deviceId,
        event.createdAt.toString(),
        event.logicalDay.toString(),
        event.fileName,
        event.headingPath.segments,
        event.causalOrder.kind,
        event.causalOrder.counter,
    ),
)

private fun StoredEventWire.toModel() = StoredClockEvent(
    ClockEventCursor(cursor),
    ClockEvent(
        schema = event.schema,
        eventId = event.eventId,
        eventType = ClockEventType.fromWireValue(event.eventType)
            ?: error("Unsupported event type: ${event.eventType}"),
        deviceId = event.deviceId,
        createdAt = Instant.parse(event.createdAt),
        logicalDay = LocalDate.parse(event.logicalDay),
        fileName = event.fileName,
        headingPath = HeadingPath(event.headingPath),
        causalOrder = ClockEventCausalOrder(event.causalKind, event.causalCounter),
    ),
)
