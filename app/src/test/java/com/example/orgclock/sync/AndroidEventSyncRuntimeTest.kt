package com.example.orgclock.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals(ClockEventCursor(2), checkpoint.lastSentCursor)
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

    @Test
    fun syncNow_followsEmptyProgressPageWithoutSkippingLaterEvent() = runTest {
        val remoteEvent = sampleEvent("remote-later", 9L, "device-remote")
        val store = InMemoryClockEventStore()
        val trustStore = StaticPeerTrustStore(peerRecord("peer-a", "device-remote"))
        val checkpoints = InMemoryPeerSyncCheckpointStore()
        val transport = RecordingClockEventSyncTransport(
            fetchResponse = emptyFetchResponse(),
            pushResponse = emptyPushResponse(),
        ).apply {
            fetchResponses += ClockEventFetchResponse(
                sourcePeerId = "device-local",
                targetPeerId = "peer-a",
                events = emptyList(),
                nextCursor = ClockEventCursor(8),
                hasMore = true,
            )
            fetchResponses += ClockEventFetchResponse(
                sourcePeerId = "device-local",
                targetPeerId = "peer-a",
                events = listOf(StoredClockEvent(ClockEventCursor(9), remoteEvent)),
                nextCursor = ClockEventCursor(9),
                hasMore = false,
            )
        }
        val runtime = runtime(store, trustStore, checkpoints, transport, backgroundScope)

        runtime.syncNow("manual")

        assertEquals(listOf(null, ClockEventCursor(8)), transport.fetchRequests.map { it.sinceCursor })
        assertEquals(listOf("remote-later"), store.readAllForReplay().map { it.event.eventId })
        assertEquals(ClockEventCursor(9), checkpoints.get("peer-a")?.lastSeenCursor)
    }

    @Test
    fun syncNow_doesNotAdvanceOutgoingCheckpointOnPartialAcceptance() = runTest {
        val store = InMemoryClockEventStore().also { it.seed(sampleEvent("local-1", 1L, "device-local")) }
        val trustStore = StaticPeerTrustStore(peerRecord("peer-a", "device-remote"))
        val checkpoints = InMemoryPeerSyncCheckpointStore()
        val transport = RecordingClockEventSyncTransport(
            fetchResponse = emptyFetchResponse(),
            pushResponse = ClockEventPushResponse(
                sourcePeerId = "peer-a",
                targetPeerId = "device-local",
                acceptedCursor = null,
                rejectedEventIds = listOf("local-1"),
            ),
        )
        val runtime = runtime(store, trustStore, checkpoints, transport, backgroundScope)

        runtime.syncNow("manual")

        assertNull(checkpoints.get("peer-a")?.lastSentCursor)
        assertTrue(runtime.state.value.lastError?.contains("rejected") == true)
    }

    @Test
    fun syncNow_pushesAllLocalBatches() = runTest {
        val store = InMemoryClockEventStore().also { target ->
            repeat(DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT + 1) { index ->
                target.seed(sampleEvent("local-$index", index + 1L, "device-local"))
            }
        }
        val trustStore = StaticPeerTrustStore(peerRecord("peer-a", "device-remote"))
        val checkpoints = InMemoryPeerSyncCheckpointStore()
        val transport = RecordingClockEventSyncTransport(
            fetchResponse = emptyFetchResponse(),
            pushResponse = emptyPushResponse(),
            acceptRequestCursor = true,
        )
        val runtime = runtime(store, trustStore, checkpoints, transport, backgroundScope)

        runtime.syncNow("manual")

        assertEquals(2, transport.pushRequests.size)
        assertEquals(DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT, transport.pushRequests.first().events.size)
        assertEquals(1, transport.pushRequests.last().events.size)
        assertEquals(
            ClockEventCursor((DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT + 1).toLong()),
            checkpoints.get("peer-a")?.lastSentCursor,
        )
    }

    @Test
    fun syncNow_keepsGlobalPendingWhileAnyFullPeerHasNoOutgoingCheckpoint() = runTest {
        val store = InMemoryClockEventStore().also { it.seed(sampleEvent("local-1", 1L, "device-local")) }
        val trustStore = StaticPeerTrustStore(
            peerRecord("peer-a", "device-a"),
            peerRecord("peer-b", "device-b"),
        )
        val checkpoints = InMemoryPeerSyncCheckpointStore()
        val transport = RecordingClockEventSyncTransport(
            fetchResponse = emptyFetchResponse(),
            pushResponse = emptyPushResponse(),
            acceptRequestCursor = true,
        )
        val runtime = AndroidEventSyncRuntime(
            clockEventStore = store,
            peerTrustStore = trustStore,
            peerSyncCheckpointStore = checkpoints,
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-local"
            },
            transportProvider = AndroidEventSyncTransportProvider { peerId ->
                if (peerId == "peer-a") transport else null
            },
            scope = backgroundScope,
        )

        runtime.syncNow("manual")

        assertEquals(ClockEventCursor(1), checkpoints.get("peer-a")?.lastSentCursor)
        assertNull(checkpoints.get("peer-b"))
        assertEquals(1, store.readSnapshot().pendingSyncCount)
        assertNull(store.readSnapshot().lastSyncedCursor)
    }

    private fun runtime(
        store: ClockEventStore,
        trustStore: PeerTrustStore,
        checkpoints: PeerSyncCheckpointStore,
        transport: ClockEventSyncTransport,
        scope: CoroutineScope,
    ) = AndroidEventSyncRuntime(
        clockEventStore = store,
        peerTrustStore = trustStore,
        peerSyncCheckpointStore = checkpoints,
        deviceIdProvider = object : DeviceIdProvider {
            override fun getOrCreate(): String = "device-local"
        },
        transportProvider = AndroidEventSyncTransportProvider { peerId ->
            if (peerId == "peer-a") transport else null
        },
        scope = scope,
    )
}

private class StaticPeerTrustStore(
    private vararg val records: PeerTrustRecord,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean = records.any { it.peerId == peerId }

    override fun listTrusted(): List<String> = records.map { it.peerId }

    override fun trust(peerId: String) {}

    override fun trust(peerId: String, publicKeyBase64: String) {}

    override fun trust(record: PeerTrustRecord) {}

    override fun getTrustRecord(peerId: String): PeerTrustRecord? = records.firstOrNull { it.peerId == peerId }

    override fun listTrustRecords(): List<PeerTrustRecord> = records.toList()

    override fun revoke(peerId: String) {}

    override fun repair(peerId: String) {}

    override fun getTrustedPublicKey(peerId: String): String? = getTrustRecord(peerId)?.publicKeyBase64
}

private class RecordingClockEventSyncTransport(
    var fetchResponse: ClockEventFetchResponse,
    var pushResponse: ClockEventPushResponse,
    private val acceptRequestCursor: Boolean = false,
) : ClockEventSyncTransport {
    val fetchResponses = ArrayDeque<ClockEventFetchResponse>()
    val fetchRequests = mutableListOf<ClockEventFetchRequest>()
    val pushRequests = mutableListOf<ClockEventPushRequest>()
    val ackRequests = mutableListOf<ClockEventTransportAck>()

    override suspend fun fetch(request: ClockEventFetchRequest): ClockEventFetchResponse {
        fetchRequests += request
        val response = if (fetchResponses.isEmpty()) fetchResponse else fetchResponses.removeFirst()
        return response.copy(sourcePeerId = request.sourcePeerId, targetPeerId = request.targetPeerId ?: response.targetPeerId)
    }

    override suspend fun push(request: ClockEventPushRequest): ClockEventPushResponse {
        pushRequests += request
        return pushResponse.copy(
            sourcePeerId = request.targetPeerId,
            targetPeerId = request.sourcePeerId,
            acceptedCursor = if (acceptRequestCursor) request.events.lastOrNull()?.cursor else pushResponse.acceptedCursor,
        )
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
        val pendingCount = events.values.count { stored ->
            lastSyncedCursor == null || stored.cursor.value > lastSyncedCursor!!.value
        }
        return ClockEventStoreSnapshot(
            lastCursor = lastCursor,
            lastSyncedCursor = lastSyncedCursor,
            pendingSyncCount = pendingCount,
        )
    }

    override suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor) {
        lastSyncedCursor = cursorInclusive
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
        createdAt = Instant.parse("2026-03-18T10:15:30Z"),
        logicalDay = LocalDate.parse("2026-03-18"),
        fileName = "2026-03-18.org",
        headingPath = com.example.orgclock.model.HeadingPath.parse("Work/Project A"),
        causalOrder = ClockEventCausalOrder(counter = cursor),
    )
}

private fun peerRecord(peerId: String, deviceId: String) = PeerTrustRecord(
    peerId = peerId,
    deviceId = deviceId,
    displayName = peerId,
    publicKeyBase64 = "pk-$peerId",
    role = PeerTrustRole.Full,
    registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
)

private fun emptyFetchResponse() = ClockEventFetchResponse(
    sourcePeerId = "device-local",
    targetPeerId = "peer-a",
    events = emptyList(),
)

private fun emptyPushResponse() = ClockEventPushResponse(
    sourcePeerId = "peer-a",
    targetPeerId = "device-local",
)
