package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

interface ClockEventRecorder {
    suspend fun recordStarted(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    )

    suspend fun recordStopped(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    )

    suspend fun recordCancelled(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    )
}

object NoOpClockEventRecorder : ClockEventRecorder {
    override suspend fun recordStarted(fileName: String, logicalDay: LocalDate, headingPath: HeadingPath, createdAt: Instant) = Unit

    override suspend fun recordStopped(fileName: String, logicalDay: LocalDate, headingPath: HeadingPath, createdAt: Instant) = Unit

    override suspend fun recordCancelled(fileName: String, logicalDay: LocalDate, headingPath: HeadingPath, createdAt: Instant) = Unit
}

class StoreBackedClockEventRecorder(
    private val store: ClockEventStore,
    private val deviceIdProvider: () -> String,
    private val eventIdFactory: () -> String = DefaultClockEventIdFactory()::next,
    private val snapshotPublisher: (ClockEventStoreSnapshot) -> Unit = {},
) : ClockEventRecorder {
    private val counterMutex = Mutex()
    private var nextCounter: Long? = null

    override suspend fun recordStarted(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    ) {
        appendEvent(
            fileName = fileName,
            logicalDay = logicalDay,
            headingPath = headingPath,
            createdAt = createdAt,
            eventType = ClockEventType.Started,
        )
    }

    override suspend fun recordStopped(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    ) {
        appendEvent(
            fileName = fileName,
            logicalDay = logicalDay,
            headingPath = headingPath,
            createdAt = createdAt,
            eventType = ClockEventType.Stopped,
        )
    }

    override suspend fun recordCancelled(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    ) {
        appendEvent(
            fileName = fileName,
            logicalDay = logicalDay,
            headingPath = headingPath,
            createdAt = createdAt,
            eventType = ClockEventType.Cancelled,
        )
    }

    private suspend fun appendEvent(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
        eventType: ClockEventType,
    ) {
        store.append(
            ClockEvent(
                eventId = eventIdFactory(),
                eventType = eventType,
                deviceId = deviceIdProvider(),
                createdAt = createdAt,
                logicalDay = logicalDay,
                fileName = fileName,
                headingPath = headingPath,
                causalOrder = ClockEventCausalOrder(counter = nextCounter()),
            ),
        )
        snapshotPublisher(store.readSnapshot())
    }

    private suspend fun nextCounter(): Long = counterMutex.withLock {
        val current = nextCounter ?: ((store.readSnapshot().lastCursor?.value ?: 0L) + 1L)
        nextCounter = current + 1L
        current
    }
}

class DefaultClockEventIdFactory {
    private val mutex = Mutex()
    private var sequence = 0L

    suspend fun nextSuspend(): String = mutex.withLock {
        sequence += 1L
        "evt-${Clock.System.now().toEpochMilliseconds()}-$sequence"
    }

    fun next(): String {
        sequence += 1L
        return "evt-${Clock.System.now().toEpochMilliseconds()}-$sequence"
    }
}
