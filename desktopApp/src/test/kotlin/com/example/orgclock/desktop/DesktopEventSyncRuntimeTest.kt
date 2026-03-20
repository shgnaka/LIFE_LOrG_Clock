package com.example.orgclock.desktop

import com.example.orgclock.model.HeadingPath
import com.example.orgclock.sync.AppendClockEventResult
import com.example.orgclock.sync.ClockEvent
import com.example.orgclock.sync.ClockEventCausalOrder
import com.example.orgclock.sync.ClockEventCursor
import com.example.orgclock.sync.ClockEventFetchRequest
import com.example.orgclock.sync.ClockEventFetchResponse
import com.example.orgclock.sync.ClockEventPushRequest
import com.example.orgclock.sync.ClockEventPushResponse
import com.example.orgclock.sync.ClockEventStore
import com.example.orgclock.sync.ClockEventStoreSnapshot
import com.example.orgclock.sync.ClockEventSyncTransport
import com.example.orgclock.sync.ClockEventSyncDirection
import com.example.orgclock.sync.ClockEventSyncQuarantineEntry
import com.example.orgclock.sync.ClockEventSyncQuarantineStore
import com.example.orgclock.sync.ClockEventSyncRejectKind
import com.example.orgclock.sync.ClockEventTransportAck
import com.example.orgclock.sync.ClockEventTransportAckResult
import com.example.orgclock.sync.ClockEventType
import com.example.orgclock.sync.PeerSyncCheckpoint
import com.example.orgclock.sync.PeerSyncCheckpointStore
import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.PeerTrustRole
import com.example.orgclock.sync.StoredClockEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopEventSyncRuntimeTest {
    @Test
    fun syncNow_updatesCheckpointPublishesSnapshot_andUsesTransport() = runTest {
        val localEvent = sampleEvent(
            eventId = "local-1",
            cursor = 1L,
            deviceId = "device-local",
        )
        val remoteEvent = sampleEvent(
            eventId = "remote-1",
            cursor = 2L,
            deviceId = "device-remote",
        )
        val store = InMemoryClockEventStore().also { it.seed(localEvent) }
        val trustStore = InMemoryPeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-a",
                deviceId = "device-remote",
                displayName = "Remote",
                publicKeyBase64 = "pk-a",
                role = PeerTrustRole.Full,
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
        val checkpoints = InMemoryPeerSyncCheckpointStore()
        val quarantine = InMemoryClockEventSyncQuarantineStore()
        val transport = RecordingClockEventSyncTransport(
            fetchResponse = ClockEventFetchResponse(
                sourcePeerId = "device-local",
                targetPeerId = "peer-a",
                events = listOf(StoredClockEvent(cursor = ClockEventCursor(2), event = remoteEvent)),
            ),
            pushResponse = ClockEventPushResponse(
                sourcePeerId = "peer-a",
                targetPeerId = "device-local",
                acceptedCursor = ClockEventCursor(1),
            ),
        )
        val snapshots = mutableListOf<ClockEventStoreSnapshot>()
        val runtime = DesktopEventSyncRuntime(
            clockEventStoreProvider = { store },
            peerTrustStoreProvider = { trustStore },
            peerSyncCheckpointStoreProvider = { checkpoints },
            quarantineStoreProvider = { quarantine },
            deviceIdProvider = { "device-local" },
            snapshotPublisher = { snapshots += it },
            transportProvider = DesktopEventSyncTransportProvider { peerId ->
                if (peerId == "peer-a") transport else null
            },
            scope = backgroundScope,
        )

        runtime.syncNow("manual")

        val replayedIds = store.readAllForReplay().map { it.event.eventId }
        assertEquals(listOf("local-1", "remote-1"), replayedIds)
        val checkpoint = checkpoints.get("peer-a")
        assertNotNull(checkpoint)
        assertEquals(ClockEventCursor(2), checkpoint.lastSeenCursor)
        assertEquals(ClockEventCursor(1), checkpoint.lastSentCursor)
        assertEquals(1, transport.fetchRequests.size)
        assertEquals(1, transport.pushRequests.size)
        assertEquals(1, transport.ackRequests.size)
        assertTrue(snapshots.isNotEmpty())
        assertEquals(0, snapshots.last().pendingSyncCount)
        assertEquals(ClockEventCursor(2), snapshots.last().lastSyncedCursor)
        assertTrue(runtime.state.value.lastError == null)
        assertEquals(1, runtime.state.value.lastPeerCount)
        assertEquals(0, quarantine.list().size)
    }

    @Test
    fun syncNow_rejectsViewerPeerEvents_andQuarantinesThem() = runTest {
        val localEvent = sampleEvent(
            eventId = "local-1",
            cursor = 1L,
            deviceId = "device-local",
        )
        val viewerEvent = sampleEvent(
            eventId = "viewer-1",
            cursor = 2L,
            deviceId = "device-viewer",
        )
        val store = InMemoryClockEventStore().also { it.seed(localEvent) }
        val trustStore = InMemoryPeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-viewer",
                deviceId = "device-viewer",
                displayName = "Viewer",
                publicKeyBase64 = "pk-viewer",
                role = PeerTrustRole.Viewer,
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
        val checkpoints = InMemoryPeerSyncCheckpointStore()
        val quarantine = InMemoryClockEventSyncQuarantineStore()
        val transport = RecordingClockEventSyncTransport(
            fetchResponse = ClockEventFetchResponse(
                sourcePeerId = "device-local",
                targetPeerId = "peer-viewer",
                events = listOf(StoredClockEvent(cursor = ClockEventCursor(2), event = viewerEvent)),
            ),
            pushResponse = ClockEventPushResponse(
                sourcePeerId = "peer-viewer",
                targetPeerId = "device-local",
                acceptedCursor = ClockEventCursor(1),
            ),
        )
        val snapshots = mutableListOf<ClockEventStoreSnapshot>()
        val runtime = DesktopEventSyncRuntime(
            clockEventStoreProvider = { store },
            peerTrustStoreProvider = { trustStore },
            peerSyncCheckpointStoreProvider = { checkpoints },
            quarantineStoreProvider = { quarantine },
            deviceIdProvider = { "device-local" },
            snapshotPublisher = { snapshots += it },
            transportProvider = DesktopEventSyncTransportProvider { peerId ->
                if (peerId == "peer-viewer") transport else null
            },
            scope = backgroundScope,
        )

        runtime.syncNow("manual")

        assertEquals(listOf("local-1"), store.readAllForReplay().map { it.event.eventId })
        assertEquals(1, quarantine.list().size)
        assertEquals(ClockEventSyncDirection.Incoming, quarantine.list().single().direction)
        assertEquals(ClockEventSyncRejectKind.ViewerPeerRejected, quarantine.list().single().kind)
        assertEquals(0, transport.ackRequests.size)
        assertTrue(snapshots.last().lastRejectReason?.contains("viewer peers") == true)
        assertEquals(1, snapshots.last().quarantinedEventCount)
    }
}

private class InMemoryPeerTrustStore(
    private val record: PeerTrustRecord,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean = peerId == record.peerId

    override fun listTrusted(): List<String> = listOf(record.peerId)

    override fun trust(peerId: String) {}

    override fun trust(peerId: String, publicKeyBase64: String) {}

    override fun trust(record: PeerTrustRecord) {}

    override fun getTrustRecord(peerId: String): PeerTrustRecord? = if (peerId == record.peerId) record else null

    override fun listTrustRecords(): List<PeerTrustRecord> = listOf(record)

    override fun revoke(peerId: String) {}

    override fun repair(peerId: String) {}

    override fun getTrustedPublicKey(peerId: String): String? = if (peerId == record.peerId) record.publicKeyBase64 else null
}

private class RecordingClockEventSyncTransport(
    private val fetchResponse: ClockEventFetchResponse,
    private val pushResponse: ClockEventPushResponse,
) : ClockEventSyncTransport {
    val fetchRequests = mutableListOf<ClockEventFetchRequest>()
    val pushRequests = mutableListOf<ClockEventPushRequest>()
    val ackRequests = mutableListOf<ClockEventTransportAck>()

    override suspend fun fetch(request: ClockEventFetchRequest): ClockEventFetchResponse {
        fetchRequests += request
        return fetchResponse.copy(
            sourcePeerId = request.sourcePeerId,
            targetPeerId = request.targetPeerId ?: fetchResponse.targetPeerId,
        )
    }

    override suspend fun push(request: ClockEventPushRequest): ClockEventPushResponse {
        pushRequests += request
        return pushResponse.copy(sourcePeerId = request.targetPeerId, targetPeerId = request.sourcePeerId)
    }

    override suspend fun acknowledge(ack: ClockEventTransportAck): ClockEventTransportAckResult {
        ackRequests += ack
        return ClockEventTransportAckResult.Accepted
    }
}

private class InMemoryClockEventStore : ClockEventStore {
    private val events = linkedMapOf<String, StoredClockEvent>()
    private var nextCursor = 1L
    private var lastSyncedCursor: ClockEventCursor? = null

    fun seed(event: ClockEvent) {
        events[event.eventId] = StoredClockEvent(cursor = ClockEventCursor(nextCursor++), event = event)
    }

    override suspend fun append(event: ClockEvent): AppendClockEventResult {
        val existing = events[event.eventId]
        if (existing != null) return AppendClockEventResult.Duplicate(existing.cursor)
        val stored = StoredClockEvent(cursor = ClockEventCursor(nextCursor++), event = event)
        events[event.eventId] = stored
        return AppendClockEventResult.Appended(stored.cursor)
    }

    override suspend fun contains(eventId: String): Boolean = events.containsKey(eventId)

    override suspend fun readAllForReplay(): List<StoredClockEvent> = events.values.sortedBy { it.cursor.value }

    override suspend fun listSince(cursorExclusive: ClockEventCursor?, limit: Int): List<StoredClockEvent> {
        return readAllForReplay()
            .filter { cursorExclusive == null || it.cursor.value > cursorExclusive.value }
            .take(limit)
    }

    override suspend fun readSnapshot(): ClockEventStoreSnapshot {
        val lastCursor = events.values.maxByOrNull { it.cursor.value }?.cursor
        val pendingSyncCount = if (lastSyncedCursor == null) {
            events.size
        } else {
            events.values.count { it.cursor.value > lastSyncedCursor!!.value }
        }
        return ClockEventStoreSnapshot(
            lastCursor = lastCursor,
            lastSyncedCursor = lastSyncedCursor,
            pendingSyncCount = pendingSyncCount,
        )
    }

    override suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor) {
        lastSyncedCursor = cursorInclusive
    }
}

private class InMemoryPeerSyncCheckpointStore : PeerSyncCheckpointStore {
    private val checkpoints = linkedMapOf<String, PeerSyncCheckpoint>()

    override fun get(peerId: String): PeerSyncCheckpoint? = checkpoints[peerId.trim()]

    override fun list(): List<PeerSyncCheckpoint> = checkpoints.values.toList()

    override fun save(checkpoint: PeerSyncCheckpoint) {
        checkpoints[checkpoint.peerId.trim()] = checkpoint
    }

    override fun markSeen(peerId: String, cursor: ClockEventCursor, seenAtEpochMs: Long) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = checkpoints[normalized] ?: PeerSyncCheckpoint(peerId = normalized)
        checkpoints[normalized] = current.markSeen(cursor, seenAtEpochMs)
    }

    override fun markSent(peerId: String, cursor: ClockEventCursor, syncedAtEpochMs: Long) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = checkpoints[normalized] ?: PeerSyncCheckpoint(peerId = normalized)
        checkpoints[normalized] = current.markSent(cursor, syncedAtEpochMs)
    }

    override fun clear(peerId: String) {
        checkpoints.remove(peerId.trim())
    }
}

private class InMemoryClockEventSyncQuarantineStore : ClockEventSyncQuarantineStore {
    private val entries = mutableListOf<ClockEventSyncQuarantineEntry>()

    override fun list(): List<ClockEventSyncQuarantineEntry> = entries.toList()

    override fun record(entry: ClockEventSyncQuarantineEntry) {
        entries += entry
    }

    override fun clear(peerId: String?) {
        if (peerId == null) {
            entries.clear()
            return
        }
        val normalized = peerId.trim()
        entries.removeAll { it.peerId == normalized }
    }
}

private fun sampleEvent(eventId: String, cursor: Long, deviceId: String): ClockEvent {
    return ClockEvent(
        eventId = eventId,
        eventType = ClockEventType.Started,
        deviceId = deviceId,
        createdAt = Clock.System.now(),
        logicalDay = LocalDate.parse("2026-03-18"),
        fileName = "2026-03-18.org",
        headingPath = HeadingPath.parse("Work/Project A"),
        causalOrder = ClockEventCausalOrder(counter = cursor),
    )
}
