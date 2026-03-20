package com.example.orgclock.sync

import kotlinx.datetime.Instant

const val CLOCK_EVENT_TRANSPORT_SCHEMA_V1 = "clock.event.transport.v1"
const val DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT = 128

data class ClockEventFetchRequest(
    val schema: String = CLOCK_EVENT_TRANSPORT_SCHEMA_V1,
    val sourcePeerId: String,
    val targetPeerId: String? = null,
    val sinceCursor: ClockEventCursor? = null,
    val batchLimit: Int = DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT,
) {
    init {
        require(schema == CLOCK_EVENT_TRANSPORT_SCHEMA_V1) { "Unsupported transport schema: $schema" }
        require(sourcePeerId.isNotBlank()) { "Source peer ID cannot be blank." }
        require(batchLimit > 0) { "Batch limit must be > 0." }
    }
}

data class ClockEventFetchResponse(
    val schema: String = CLOCK_EVENT_TRANSPORT_SCHEMA_V1,
    val sourcePeerId: String,
    val targetPeerId: String,
    val events: List<StoredClockEvent>,
    val nextCursor: ClockEventCursor? = events.lastOrNull()?.cursor,
    val hasMore: Boolean = false,
) {
    init {
        require(schema == CLOCK_EVENT_TRANSPORT_SCHEMA_V1) { "Unsupported transport schema: $schema" }
        require(sourcePeerId.isNotBlank()) { "Source peer ID cannot be blank." }
        require(targetPeerId.isNotBlank()) { "Target peer ID cannot be blank." }
        require(events.zipWithNext().all { (left, right) -> left.cursor.value < right.cursor.value }) {
            "Fetch response events must be sorted by cursor."
        }
        if (events.isEmpty()) {
            require(nextCursor == null) { "nextCursor must be null when no events are included." }
        } else {
            require(nextCursor == events.last().cursor) { "nextCursor must point at the last event cursor." }
        }
    }

    val lastSeenCursor: ClockEventCursor?
        get() = events.lastOrNull()?.cursor

    fun nextFetchCursor(): ClockEventCursor? = lastSeenCursor?.next()
}

data class ClockEventPushRequest(
    val schema: String = CLOCK_EVENT_TRANSPORT_SCHEMA_V1,
    val sourcePeerId: String,
    val targetPeerId: String,
    val events: List<StoredClockEvent>,
) {
    init {
        require(schema == CLOCK_EVENT_TRANSPORT_SCHEMA_V1) { "Unsupported transport schema: $schema" }
        require(sourcePeerId.isNotBlank()) { "Source peer ID cannot be blank." }
        require(targetPeerId.isNotBlank()) { "Target peer ID cannot be blank." }
        require(events.zipWithNext().all { (left, right) -> left.cursor.value < right.cursor.value }) {
            "Push request events must be sorted by cursor."
        }
    }
}

data class ClockEventPushResponse(
    val schema: String = CLOCK_EVENT_TRANSPORT_SCHEMA_V1,
    val sourcePeerId: String,
    val targetPeerId: String,
    val acceptedCursor: ClockEventCursor? = null,
    val duplicateEventIds: List<String> = emptyList(),
    val rejectedEventIds: List<String> = emptyList(),
    val rejectReason: String? = null,
) {
    init {
        require(schema == CLOCK_EVENT_TRANSPORT_SCHEMA_V1) { "Unsupported transport schema: $schema" }
        require(sourcePeerId.isNotBlank()) { "Source peer ID cannot be blank." }
        require(targetPeerId.isNotBlank()) { "Target peer ID cannot be blank." }
    }
}

data class ClockEventTransportAck(
    val schema: String = CLOCK_EVENT_TRANSPORT_SCHEMA_V1,
    val sourcePeerId: String,
    val targetPeerId: String,
    val seenCursor: ClockEventCursor? = null,
    val acknowledgedEventIds: List<String> = emptyList(),
    val acknowledgedAt: Instant,
) {
    init {
        require(schema == CLOCK_EVENT_TRANSPORT_SCHEMA_V1) { "Unsupported transport schema: $schema" }
        require(sourcePeerId.isNotBlank()) { "Source peer ID cannot be blank." }
        require(targetPeerId.isNotBlank()) { "Target peer ID cannot be blank." }
    }
}

fun ClockEventCursor.next(): ClockEventCursor = ClockEventCursor(value + 1)
