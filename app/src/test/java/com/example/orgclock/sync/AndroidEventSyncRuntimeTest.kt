package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidEventSyncRuntimeTest {
    @Test
    fun syncNow_fetchesRemoteEvents_pushesLocalEvents_andUpdatesCheckpoint() = runTest {
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
        val trustStore = StaticPeerTrustStore(
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
                hasMore = false,
            ),
            pushResponse = ClockEventPushResponse(
                sourcePeerId = "peer-a",
                targetPeerId = "device-local",
                acceptedCursor = ClockEventCursor(1),
            ),
        )
        val runtime = AndroidEventSyncRuntime(
            clockEventStore = store,
            peerTrustStore = trustStore,
            peerSyncCheckpointStore = checkpoints,
            quarantineStore = quarantine,
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-local"
            },
            transportProvider = AndroidEventSyncTransportProvider { peerId ->
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
        assertTrue(runtime.state.value.lastError == null)
        assertEquals(1, runtime.state.value.lastPeerCount)
        assertEquals(0, quarantine.list().size)
    }

    @Test
    fun syncNow_rejectsRemoteEventsWithMismatchedDeviceId_andQuarantinesThem() = runTest {
        val localEvent = sampleEvent(
            eventId = "local-1",
            cursor = 1L,
            deviceId = "device-local",
        )
        val invalidRemoteEvent = sampleEvent(
            eventId = "remote-invalid",
            cursor = 2L,
            deviceId = "device-other",
        )
        val store = InMemoryClockEventStore().also { it.seed(localEvent) }
        val trustStore = StaticPeerTrustStore(
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
                events = listOf(StoredClockEvent(cursor = ClockEventCursor(2), event = invalidRemoteEvent)),
                hasMore = false,
            ),
            pushResponse = ClockEventPushResponse(
                sourcePeerId = "peer-a",
                targetPeerId = "device-local",
                acceptedCursor = ClockEventCursor(1),
            ),
        )
        val snapshots = mutableListOf<ClockEventStoreSnapshot>()
        val runtime = AndroidEventSyncRuntime(
            clockEventStore = store,
            peerTrustStore = trustStore,
            peerSyncCheckpointStore = checkpoints,
            quarantineStore = quarantine,
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-local"
            },
            snapshotPublisher = { snapshots += it },
            transportProvider = AndroidEventSyncTransportProvider { peerId ->
                if (peerId == "peer-a") transport else null
            },
            scope = backgroundScope,
        )

        runtime.syncNow("manual")

        val replayedIds = store.readAllForReplay().map { it.event.eventId }
        assertEquals(listOf("local-1"), replayedIds)
        assertEquals(1, quarantine.list().size)
        assertEquals("device mismatch: expected device-remote, got device-other", quarantine.list().single().reason)
        assertEquals(1, transport.fetchRequests.size)
        assertEquals(0, transport.ackRequests.size)
        assertTrue(snapshots.last().lastRejectReason?.contains("device mismatch") == true)
        assertEquals(1, snapshots.last().quarantinedEventCount)
    }

    @Test
    fun syncNow_clearsQuarantineStateAfterSubsequentSuccessfulSync() = runTest {
        val localEvent = sampleEvent(
            eventId = "local-1",
            cursor = 1L,
            deviceId = "device-local",
        )
        val invalidRemoteEvent = sampleEvent(
            eventId = "remote-invalid",
            cursor = 2L,
            deviceId = "device-other",
        )
        val validRemoteEvent = sampleEvent(
            eventId = "remote-valid",
            cursor = 2L,
            deviceId = "device-remote",
        )
        val store = InMemoryClockEventStore().also { it.seed(localEvent) }
        val trustStore = StaticPeerTrustStore(
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
                events = listOf(StoredClockEvent(cursor = ClockEventCursor(2), event = invalidRemoteEvent)),
                hasMore = false,
            ),
            pushResponse = ClockEventPushResponse(
                sourcePeerId = "peer-a",
                targetPeerId = "device-local",
                acceptedCursor = ClockEventCursor(1),
            ),
        )
        val snapshots = mutableListOf<ClockEventStoreSnapshot>()
        val runtime = AndroidEventSyncRuntime(
            clockEventStore = store,
            peerTrustStore = trustStore,
            peerSyncCheckpointStore = checkpoints,
            quarantineStore = quarantine,
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-local"
            },
            snapshotPublisher = { snapshots += it },
            transportProvider = AndroidEventSyncTransportProvider { peerId ->
                if (peerId == "peer-a") transport else null
            },
            scope = backgroundScope,
        )

        runtime.syncNow("first-pass")
        assertEquals(1, quarantine.list().size)
        assertTrue(snapshots.last().lastRejectReason?.contains("device mismatch") == true)

        transport.fetchResponse = ClockEventFetchResponse(
            sourcePeerId = "device-local",
            targetPeerId = "peer-a",
            events = listOf(StoredClockEvent(cursor = ClockEventCursor(2), event = validRemoteEvent)),
            hasMore = false,
        )

        runtime.syncNow("second-pass")

        assertEquals(listOf("local-1", "remote-valid"), store.readAllForReplay().map { it.event.eventId })
        assertEquals(0, quarantine.list().size)
        assertTrue(snapshots.last().lastRejectReason == null)
        assertEquals(0, snapshots.last().quarantinedEventCount)
    }
}

private class StaticPeerTrustStore(
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
    var fetchResponse: ClockEventFetchResponse,
    var pushResponse: ClockEventPushResponse,
) : ClockEventSyncTransport {
    val fetchRequests = mutableListOf<ClockEventFetchRequest>()
    val pushRequests = mutableListOf<ClockEventPushRequest>()
    val ackRequests = mutableListOf<ClockEventTransportAck>()

    override suspend fun fetch(request: ClockEventFetchRequest): ClockEventFetchResponse {
        fetchRequests += request
        return fetchResponse.copy(sourcePeerId = request.sourcePeerId, targetPeerId = request.targetPeerId ?: fetchResponse.targetPeerId)
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
        return ClockEventStoreSnapshot(
            lastCursor = lastCursor,
            lastSyncedCursor = null,
            pendingSyncCount = events.size,
        )
    }

    override suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor) {}
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
        createdAt = Instant.parse("2026-03-18T10:15:30Z"),
        logicalDay = LocalDate.parse("2026-03-18"),
        fileName = "2026-03-18.org",
        headingPath = com.example.orgclock.model.HeadingPath.parse("Work/Project A"),
        causalOrder = ClockEventCausalOrder(counter = cursor),
    )
}
