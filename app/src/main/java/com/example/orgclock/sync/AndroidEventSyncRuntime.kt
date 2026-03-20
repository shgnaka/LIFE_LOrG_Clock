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
                    clockEventStore.readSnapshot().lastCursor?.let { cursor ->
                        clockEventStore.updateSyncCheckpoint(cursor)
                    }
                    quarantineStore.clear(peer.peerId)
                }
                publishSnapshot(null)
                syncedPeerCount++
            }
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
        val checkpoint = peerSyncCheckpointStore.get(peer.peerId)
        val pending = clockEventStore.listSince(checkpoint?.lastSentCursor, DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT)
        if (pending.isEmpty()) return
        val response = transport.push(
            ClockEventPushRequest(
                sourcePeerId = localPeerId,
                targetPeerId = peer.peerId,
                events = pending,
            ),
        )
        response.rejectReason?.takeIf { it.isNotBlank() }?.let { reason ->
            recordQuarantine(
                peer = peer,
                direction = ClockEventSyncDirection.Outgoing,
                kind = ClockEventSyncRejectKind.TransportRejected,
                reason = reason,
            )
            throw IllegalStateException("push rejected for ${peer.peerId}: $reason")
        }
        val acceptedCursor = response.acceptedCursor ?: pending.last().cursor
        peerSyncCheckpointStore.markSent(peer.peerId, acceptedCursor, Clock.System.now().toEpochMilliseconds())
    }

    private suspend fun fetchRemoteEvents(
        localPeerId: String,
        peer: PeerTrustRecord,
        transport: ClockEventSyncTransport,
    ): Boolean {
        val checkpoint = peerSyncCheckpointStore.get(peer.peerId)
        val response = transport.fetch(
            ClockEventFetchRequest(
                sourcePeerId = localPeerId,
                targetPeerId = peer.peerId,
                sinceCursor = checkpoint?.lastSeenCursor,
                batchLimit = DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT,
            ),
        )
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
            when (clockEventStore.append(stored.event)) {
                is AppendClockEventResult.Appended -> stored
                is AppendClockEventResult.Duplicate -> null
            }
        }
        val seenCursor = response.lastSeenCursor ?: response.nextCursor?.let { ClockEventCursor(it.value - 1) }
        if (seenCursor != null) {
            peerSyncCheckpointStore.markSeen(peer.peerId, seenCursor, Clock.System.now().toEpochMilliseconds())
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
        }
        publishSnapshot(null)
        return true
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
