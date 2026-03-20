package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

const val CLOCK_EVENT_SCHEMA_V1 = "clock.event.v1"
const val DEFAULT_CLOCK_EVENT_BATCH_LIMIT = 256

enum class ClockEventType(val wireValue: String) {
    Started("clock_started"),
    Stopped("clock_stopped"),
    Cancelled("clock_cancelled"),
    ;

    companion object {
        fun fromWireValue(raw: String): ClockEventType? = entries.firstOrNull { it.wireValue == raw }
    }
}

data class ClockEventCausalOrder(
    val kind: String = "lamport_v1",
    val counter: Long,
) {
    init {
        require(kind.isNotBlank()) { "Causal order kind cannot be blank." }
        require(counter >= 0) { "Causal order counter must be >= 0." }
    }
}

data class ClockEvent(
    val schema: String = CLOCK_EVENT_SCHEMA_V1,
    val eventId: String,
    val eventType: ClockEventType,
    val deviceId: String,
    val createdAt: Instant,
    val logicalDay: LocalDate,
    val fileName: String,
    val headingPath: HeadingPath,
    val causalOrder: ClockEventCausalOrder,
) {
    init {
        require(schema == CLOCK_EVENT_SCHEMA_V1) { "Unsupported clock event schema: $schema" }
        require(eventId.isNotBlank()) { "Event ID cannot be blank." }
        require(deviceId.isNotBlank()) { "Device ID cannot be blank." }
        require(fileName.isNotBlank()) { "File name cannot be blank." }
    }
}

data class ClockEventCursor(val value: Long) {
    init {
        require(value > 0) { "Clock event cursor must be > 0." }
    }
}

data class StoredClockEvent(
    val cursor: ClockEventCursor,
    val event: ClockEvent,
)

sealed interface AppendClockEventResult {
    data class Appended(val cursor: ClockEventCursor) : AppendClockEventResult
    data class Duplicate(val cursor: ClockEventCursor?) : AppendClockEventResult
}

enum class LocalClockEventSyncStatus {
    Synced,
    Pending,
}

data class ClockEventStoreSnapshot(
    val lastCursor: ClockEventCursor?,
    val lastSyncedCursor: ClockEventCursor?,
    val pendingSyncCount: Int,
    val lastRejectReason: String? = null,
    val quarantinedEventCount: Int = 0,
    val lastQuarantineAtEpochMs: Long? = null,
) {
    init {
        require(pendingSyncCount >= 0) { "Pending sync count must be >= 0." }
        require(quarantinedEventCount >= 0) { "Quarantined event count must be >= 0." }
    }

    val syncStatus: LocalClockEventSyncStatus
        get() = if (pendingSyncCount == 0 && quarantinedEventCount == 0) LocalClockEventSyncStatus.Synced else LocalClockEventSyncStatus.Pending
}

interface ClockEventStore {
    suspend fun append(event: ClockEvent): AppendClockEventResult

    suspend fun contains(eventId: String): Boolean

    suspend fun readAllForReplay(): List<StoredClockEvent>

    suspend fun listSince(
        cursorExclusive: ClockEventCursor?,
        limit: Int = DEFAULT_CLOCK_EVENT_BATCH_LIMIT,
    ): List<StoredClockEvent>

    suspend fun readSnapshot(): ClockEventStoreSnapshot

    suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor)
}
