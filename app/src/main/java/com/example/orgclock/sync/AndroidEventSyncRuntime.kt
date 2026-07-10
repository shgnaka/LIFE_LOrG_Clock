package com.example.orgclock.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import java.util.logging.Logger

data class AndroidEventSyncRuntimeState(
    val running: Boolean = false,
    val lastSyncAtEpochMs: Long? = null,
    val lastError: String? = null,
    val lastReason: String? = null,
    val lastPeerCount: Int = 0,
)

fun interface AndroidEventSyncTransportProvider {
    fun transportFor(peerId: String): ClockEventSyncTransport?
}

object AndroidEventSyncRuntimeEntryPoint {
    @Volatile
    var runtime: AndroidEventSyncRuntime? = null
}

class AndroidEventSyncRuntime(
    private val clockEventStore: ClockEventStore,
    private val peerTrustStore: PeerTrustStore,
    private val peerSyncCheckpointStore: PeerSyncCheckpointStore,
    private val quarantineStore: ClockEventSyncQuarantineStore = NoOpClockEventSyncQuarantineStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val snapshotPublisher: (ClockEventStoreSnapshot) -> Unit = {},
    private val remoteEventApplier: RemoteClockEventApplier = RemoteClockEventApplier { Result.success(Unit) },
    private val transportProvider: AndroidEventSyncTransportProvider = AndroidEventSyncTransportProvider { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(AndroidEventSyncRuntimeState())
    val state: StateFlow<AndroidEventSyncRuntimeState> = _state.asStateFlow()

    fun onAppStarted() {
        _state.update { it.copy(running = true, lastReason = "startup") }
        scope.launch { syncNow("startup") }
    }

    fun onAppResumed() {
        scope.launch { syncNow("resume") }
    }

    fun onPeriodicTick() {
        scope.launch { syncNow("periodic") }
    }

    suspend fun flushNow(reason: String = "manual") = syncNow(reason)

    fun stop() {
        _state.update { it.copy(running = false) }
    }

    suspend fun syncNow(reason: String = "manual") {
        runCatching { runSyncCycle(reason) }
            .onFailure { error ->
                logger.fine("android.event_sync.failed reason=$reason error=${error.message ?: "unknown"}")
                _state.update {
                    it.copy(
                        lastError = error.message ?: "event sync failed",
                        lastReason = reason,
                    )
                }
            }
    }

    fun scheduleSync(reason: String = "manual") {
        scope.launch {
            syncNow(reason)
        }
    }

    private suspend fun runSyncCycle(reason: String) {
        if (!syncMutex.tryLock()) {
            logger.fine("android.event_sync.skipped busy reason=$reason")
            return
        }
        try {
            val localPeerId = deviceIdProvider.getOrCreate()
            val trustedPeers = peerTrustStore.listTrustRecords()
                .filter { it.isActive }
                .filter { it.peerId != localPeerId }
            var syncedPeerCount = 0
            for (peer in trustedPeers) {
                val transport = transportProvider.transportFor(peer.peerId) ?: continue
                if (peer.role == PeerTrustRole.Full) {
                    pushLocalEvents(localPeerId, peer, transport)
                }
                val synced = fetchRemoteEvents(localPeerId, peer, transport)
                if (synced) {
                    if (peer.role == PeerTrustRole.Full) {
                        pushLocalEvents(localPeerId, peer, transport)
                    }
                    quarantineStore.clear(peer.peerId)
                }
                publishSnapshot(null)
                syncedPeerCount++
            }
            updateGlobalOutgoingCheckpoint(trustedPeers)
            publishSnapshot(null)
            _state.update {
                it.copy(
                    running = true,
                    lastSyncAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                    lastError = null,
                    lastReason = reason,
                    lastPeerCount = syncedPeerCount,
                )
            }
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun pushLocalEvents(
        localPeerId: String,
        peer: PeerTrustRecord,
        transport: ClockEventSyncTransport,
    ) {
        var scanCursor = peerSyncCheckpointStore.get(peer.peerId)?.lastSentCursor
        while (true) {
            val scanned = clockEventStore.listSince(scanCursor, DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT)
            if (scanned.isEmpty()) return
            val pending = scanned.filter { it.event.deviceId == localPeerId }
            if (pending.isNotEmpty()) {
                val response = transport.push(
                    ClockEventPushRequest(
                        sourcePeerId = localPeerId,
                        targetPeerId = peer.peerId,
                        events = pending,
                    ),
                )
                validatePushAccepted(peer, pending, response)
            }
            scanCursor = scanned.last().cursor
            peerSyncCheckpointStore.markSent(peer.peerId, scanCursor, Clock.System.now().toEpochMilliseconds())
            if (scanned.size < DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT) return
        }
    }

    private suspend fun fetchRemoteEvents(
        localPeerId: String,
        peer: PeerTrustRecord,
        transport: ClockEventSyncTransport,
    ): Boolean {
        var fetchCursor = peerSyncCheckpointStore.get(peer.peerId)?.lastSeenCursor
        while (true) {
            val response = transport.fetch(
                ClockEventFetchRequest(
                    sourcePeerId = localPeerId,
                    targetPeerId = peer.peerId,
                    sinceCursor = fetchCursor,
                    batchLimit = DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT,
                ),
            )
            val responseNextCursor = response.nextCursor
            if (response.hasMore && (responseNextCursor == null || responseNextCursor.value <= (fetchCursor?.value ?: 0L))) {
                recordQuarantine(
                    peer = peer,
                    direction = ClockEventSyncDirection.Incoming,
                    kind = ClockEventSyncRejectKind.BatchOrderInvalid,
                    reason = "remote fetch cursor did not advance",
                )
                publishSnapshot("remote fetch cursor did not advance")
                return false
            }
        if (!response.events.zipWithNext().all { (left, right) -> left.cursor.value < right.cursor.value }) {
            recordQuarantine(
                peer = peer,
                direction = ClockEventSyncDirection.Incoming,
                kind = ClockEventSyncRejectKind.BatchOrderInvalid,
                reason = "remote batch cursors must be strictly increasing",
            )
            publishSnapshot("batch-order-invalid")
            return false
        }
        val invalidStored = response.events.firstOrNull { validateIncomingEvent(peer, it) != null }
        if (invalidStored != null) {
            val reason = validateIncomingEvent(peer, invalidStored)!!
            recordQuarantine(
                peer = peer,
                direction = ClockEventSyncDirection.Incoming,
                kind = reason.kind,
                reason = reason.reason,
                eventId = invalidStored.event.eventId,
                cursor = invalidStored.cursor,
            )
            publishSnapshot(reason.reason)
            return false
        }
        val appended = response.events.mapNotNull { stored ->
            if (clockEventStore.contains(stored.event.eventId)) return@mapNotNull null
            remoteEventApplier.apply(stored.event).getOrThrow()
            when (clockEventStore.append(stored.event)) {
                is AppendClockEventResult.Appended -> stored
                is AppendClockEventResult.Duplicate -> null
            }
        }
        val seenCursor = responseNextCursor
        if (seenCursor != null) {
            when (val ackResult = transport.acknowledge(
                ClockEventTransportAck(
                    sourcePeerId = localPeerId,
                    targetPeerId = peer.peerId,
                    seenCursor = seenCursor,
                    acknowledgedEventIds = appended.map { it.event.eventId },
                    acknowledgedAt = Clock.System.now(),
                ),
            )) {
                is ClockEventTransportAckResult.Accepted -> Unit
                is ClockEventTransportAckResult.Rejected -> {
                    recordQuarantine(
                        peer = peer,
                        direction = ClockEventSyncDirection.Outgoing,
                        kind = ClockEventSyncRejectKind.AckRejected,
                        reason = ackResult.reason,
                        cursor = seenCursor,
                    )
                    throw IllegalStateException("ack rejected for ${peer.peerId}: ${ackResult.reason}")
                }
            }
            peerSyncCheckpointStore.markSeen(peer.peerId, seenCursor, Clock.System.now().toEpochMilliseconds())
            fetchCursor = seenCursor
        }
        publishSnapshot(null)
            if (!response.hasMore) return true
        }
    }

    private fun validatePushAccepted(
        peer: PeerTrustRecord,
        pending: List<StoredClockEvent>,
        response: ClockEventPushResponse,
    ) {
        val reason = when {
            !response.rejectReason.isNullOrBlank() -> response.rejectReason
            response.rejectedEventIds.isNotEmpty() -> "remote rejected events: ${response.rejectedEventIds.joinToString()}"
            response.acceptedCursor != pending.last().cursor -> "remote accepted cursor mismatch"
            else -> null
        } ?: return
        recordQuarantine(
            peer = peer,
            direction = ClockEventSyncDirection.Outgoing,
            kind = ClockEventSyncRejectKind.TransportRejected,
            reason = reason,
        )
        throw IllegalStateException("push rejected for ${peer.peerId}: $reason")
    }

    private suspend fun updateGlobalOutgoingCheckpoint(peers: List<PeerTrustRecord>) {
        val fullPeers = peers.filter { it.role == PeerTrustRole.Full }
        if (fullPeers.isEmpty()) return
        val cursors = fullPeers.map { peerSyncCheckpointStore.get(it.peerId)?.lastSentCursor ?: return }
        clockEventStore.updateSyncCheckpoint(cursors.minBy { it.value })
    }

    private fun validateIncomingEvent(
        peer: PeerTrustRecord,
        stored: StoredClockEvent,
    ): QuarantineDecision? {
        if (peer.role == PeerTrustRole.Viewer) {
            return QuarantineDecision(
                kind = ClockEventSyncRejectKind.ViewerPeerRejected,
                reason = "viewer peers are not allowed to send clock events",
            )
        }
        if (stored.event.deviceId != peer.deviceId) {
            return QuarantineDecision(
                kind = ClockEventSyncRejectKind.DeviceMismatch,
                reason = "device mismatch: expected ${peer.deviceId}, got ${stored.event.deviceId}",
            )
        }
        return null
    }

    private fun recordQuarantine(
        peer: PeerTrustRecord,
        direction: ClockEventSyncDirection,
        kind: ClockEventSyncRejectKind,
        reason: String,
        eventId: String? = null,
        cursor: ClockEventCursor? = null,
    ) {
        quarantineStore.record(
            ClockEventSyncQuarantineEntry(
                peerId = peer.peerId,
                direction = direction,
                kind = kind,
                reason = reason,
                eventId = eventId,
                cursor = cursor,
            ),
        )
    }

    private suspend fun publishSnapshot(lastRejectReason: String? = null) {
        val snapshot = clockEventStore.readSnapshot()
        val quarantineRecords = quarantineStore.list()
        val effectiveReason = lastRejectReason ?: quarantineRecords.lastOrNull()?.reason
        snapshotPublisher(
            snapshot.copy(
                lastRejectReason = effectiveReason,
                quarantinedEventCount = quarantineRecords.size,
                lastQuarantineAtEpochMs = quarantineRecords.lastOrNull()?.quarantinedAtEpochMs,
            ),
        )
    }

    private data class QuarantineDecision(
        val kind: ClockEventSyncRejectKind,
        val reason: String,
    )

    private companion object {
        private val logger: Logger = Logger.getLogger(AndroidEventSyncRuntime::class.java.name)
    }
}
