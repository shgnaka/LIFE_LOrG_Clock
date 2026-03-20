package com.example.orgclock.desktop

import com.example.orgclock.sync.AppendClockEventResult
import com.example.orgclock.sync.ClockEventCursor
import com.example.orgclock.sync.ClockEventFetchRequest
import com.example.orgclock.sync.ClockEventPushRequest
import com.example.orgclock.sync.ClockEventStore
import com.example.orgclock.sync.ClockEventStoreSnapshot
import com.example.orgclock.sync.ClockEventSyncTransport
import com.example.orgclock.sync.ClockEventSyncDirection
import com.example.orgclock.sync.ClockEventSyncQuarantineEntry
import com.example.orgclock.sync.ClockEventSyncQuarantineStore
import com.example.orgclock.sync.ClockEventSyncRejectKind
import com.example.orgclock.sync.ClockEventTransportAck
import com.example.orgclock.sync.ClockEventTransportAckResult
import com.example.orgclock.sync.ClockEventTransportAckResult.Rejected
import com.example.orgclock.sync.DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT
import com.example.orgclock.sync.PeerSyncCheckpointStore
import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.PeerTrustRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import java.util.logging.Logger

enum class DesktopEventSyncMode {
    Host,
    Listener,
}

data class DesktopEventSyncRuntimeState(
    val running: Boolean = false,
    val mode: DesktopEventSyncMode = DesktopEventSyncMode.Host,
    val lastSyncAtEpochMs: Long? = null,
    val lastError: String? = null,
    val lastReason: String? = null,
    val lastPeerCount: Int = 0,
    val lastRejectReason: String? = null,
    val quarantinedEventCount: Int = 0,
)

fun interface DesktopEventSyncTransportProvider {
    fun transportFor(peerId: String): ClockEventSyncTransport?
}

object DesktopEventSyncRuntimeEntryPoint {
    @Volatile
    var runtime: DesktopEventSyncRuntime? = null
}

class DesktopEventSyncRuntime(
    private val clockEventStoreProvider: () -> ClockEventStore?,
    private val peerTrustStoreProvider: () -> PeerTrustStore?,
    private val peerSyncCheckpointStoreProvider: () -> PeerSyncCheckpointStore?,
    private val quarantineStoreProvider: () -> ClockEventSyncQuarantineStore? = { null },
    private val deviceIdProvider: () -> String,
    private val snapshotFlowProvider: () -> StateFlow<ClockEventStoreSnapshot>? = { null },
    private val snapshotPublisher: (ClockEventStoreSnapshot) -> Unit = {},
    private val transportProvider: DesktopEventSyncTransportProvider = DesktopEventSyncTransportProvider { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(DesktopEventSyncRuntimeState())
    val state: StateFlow<DesktopEventSyncRuntimeState> = _state.asStateFlow()
    private var snapshotObserver: Job? = null

    fun onAppStarted() {
        start("startup")
    }

    fun onAppResumed() {
        scheduleSync("resume")
    }

    fun onRootOpened() {
        scheduleSync("root-opened")
    }

    fun onPeriodicTick() {
        scheduleSync("periodic")
    }

    suspend fun flushNow(reason: String = "manual") {
        syncNow(reason)
    }

    fun stop() {
        snapshotObserver?.cancel()
        snapshotObserver = null
        _state.update { it.copy(running = false) }
    }

    fun setMode(mode: DesktopEventSyncMode) {
        _state.update { it.copy(mode = mode) }
    }

    suspend fun syncNow(reason: String = "manual") {
        runCatching { runSyncCycle(reason) }
            .onFailure { error ->
                logger.fine("desktop.event_sync.failed reason=$reason error=${error.message ?: "unknown"}")
                _state.update {
                    it.copy(
                        lastError = error.message ?: "event sync failed",
                        lastReason = reason,
                    )
                }
            }
    }

    private fun start(reason: String) {
        if (_state.value.running) return
        _state.update { it.copy(running = true, lastReason = reason) }
        observeLocalSnapshotFlow()
        scheduleSync(reason)
    }

    private fun observeLocalSnapshotFlow() {
        if (snapshotObserver != null) return
        val flow = snapshotFlowProvider() ?: return
        snapshotObserver = scope.launch {
            var lastSnapshot: ClockEventStoreSnapshot? = null
            flow.collectLatest { snapshot ->
                if (snapshot == lastSnapshot) return@collectLatest
                lastSnapshot = snapshot
                if (snapshot.pendingSyncCount > 0) {
                    scheduleSync("local-change")
                }
            }
        }
    }

    private fun scheduleSync(reason: String) {
        scope.launch {
            syncNow(reason)
        }
    }

    private suspend fun runSyncCycle(reason: String) {
        if (!syncMutex.tryLock()) {
            logger.fine("desktop.event_sync.skipped busy reason=$reason")
            return
        }
        try {
            val store = clockEventStoreProvider() ?: return
            val trustStore = peerTrustStoreProvider() ?: return
            val checkpointStore = peerSyncCheckpointStoreProvider() ?: return
            val localPeerId = deviceIdProvider()
            val trustedPeers = trustStore.listTrustRecords()
                .filter { it.isActive }
                .filter { it.peerId != localPeerId }
            var syncedPeerCount = 0
            val currentMode = _state.value.mode
            for (peer in trustedPeers) {
                val transport = transportProvider.transportFor(peer.peerId) ?: continue
                if (currentMode == DesktopEventSyncMode.Host && peer.role == PeerTrustRole.Full) {
                    pushLocalEvents(localPeerId, peer, transport, store, checkpointStore)
                }
                val synced = fetchRemoteEvents(localPeerId, peer, transport, store, checkpointStore)
                if (synced) {
                    store.readSnapshot().lastCursor?.let { cursor ->
                        store.updateSyncCheckpoint(cursor)
                    }
                    checkpointQuarantineStore()?.clear(peer.peerId)
                }
                publishSnapshot(store, quarantineStoreProvider(), null)
                syncedPeerCount += 1
            }
            _state.update {
                it.copy(
                    running = true,
                    lastSyncAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                    lastError = null,
                    lastReason = reason,
                    lastPeerCount = syncedPeerCount,
                    mode = currentMode,
                    lastRejectReason = quarantineStoreProvider()?.list()?.lastOrNull()?.reason,
                    quarantinedEventCount = quarantineStoreProvider()?.list().orEmpty().size,
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
        store: ClockEventStore,
        checkpointStore: PeerSyncCheckpointStore,
    ) {
        val checkpoint = checkpointStore.get(peer.peerId)
        val pending = store.listSince(checkpoint?.lastSentCursor, DEFAULT_CLOCK_EVENT_TRANSPORT_BATCH_LIMIT)
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
                quarantineStore = checkpointQuarantineStore(),
                peer = peer,
                direction = ClockEventSyncDirection.Outgoing,
                kind = ClockEventSyncRejectKind.TransportRejected,
                reason = reason,
            )
            publishSnapshot(store, checkpointQuarantineStore(), reason)
            throw IllegalStateException("push rejected for ${peer.peerId}: $reason")
        }
        val acceptedCursor = response.acceptedCursor ?: pending.last().cursor
        checkpointStore.markSent(peer.peerId, acceptedCursor, Clock.System.now().toEpochMilliseconds())
    }

    private suspend fun fetchRemoteEvents(
        localPeerId: String,
        peer: PeerTrustRecord,
        transport: ClockEventSyncTransport,
        store: ClockEventStore,
        checkpointStore: PeerSyncCheckpointStore,
    ): Boolean {
        val checkpoint = checkpointStore.get(peer.peerId)
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
                quarantineStore = checkpointQuarantineStore(),
                peer = peer,
                direction = ClockEventSyncDirection.Incoming,
                kind = ClockEventSyncRejectKind.BatchOrderInvalid,
                reason = "remote batch cursors must be strictly increasing",
            )
            publishSnapshot(store, checkpointQuarantineStore(), "batch-order-invalid")
            return false
        }
        val invalidStored = response.events.firstOrNull { validateIncomingEvent(peer, it) != null }
        if (invalidStored != null) {
            val decision = validateIncomingEvent(peer, invalidStored)!!
            recordQuarantine(
                quarantineStore = checkpointQuarantineStore(),
                peer = peer,
                direction = ClockEventSyncDirection.Incoming,
                kind = decision.kind,
                reason = decision.reason,
                eventId = invalidStored.event.eventId,
                cursor = invalidStored.cursor,
            )
            publishSnapshot(store, checkpointQuarantineStore(), decision.reason)
            return false
        }
        val appended = response.events.mapNotNull { stored ->
            when (store.append(stored.event)) {
                is AppendClockEventResult.Appended -> stored
                is AppendClockEventResult.Duplicate -> null
            }
        }
        val seenCursor = response.lastSeenCursor ?: response.nextCursor?.let { ClockEventCursor(it.value - 1) }
        if (seenCursor != null) {
            checkpointStore.markSeen(peer.peerId, seenCursor, Clock.System.now().toEpochMilliseconds())
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
                is Rejected -> {
                    recordQuarantine(
                        quarantineStore = checkpointQuarantineStore(),
                        peer = peer,
                        direction = ClockEventSyncDirection.Outgoing,
                        kind = ClockEventSyncRejectKind.AckRejected,
                        reason = ackResult.reason,
                        cursor = seenCursor,
                    )
                    publishSnapshot(store, checkpointQuarantineStore(), ackResult.reason)
                    throw IllegalStateException("ack rejected for ${peer.peerId}: ${ackResult.reason}")
                }
            }
        }
        publishSnapshot(store, checkpointQuarantineStore(), null)
        return true
    }

    private fun checkpointQuarantineStore(): ClockEventSyncQuarantineStore? = quarantineStoreProvider()

    private fun validateIncomingEvent(
        peer: PeerTrustRecord,
        stored: com.example.orgclock.sync.StoredClockEvent,
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
        quarantineStore: ClockEventSyncQuarantineStore?,
        peer: PeerTrustRecord,
        direction: ClockEventSyncDirection,
        kind: ClockEventSyncRejectKind,
        reason: String,
        eventId: String? = null,
        cursor: ClockEventCursor? = null,
    ) {
        quarantineStore?.record(
            ClockEventSyncQuarantineEntry(
                peerId = peer.peerId,
                direction = direction,
                kind = kind,
                reason = reason,
                eventId = eventId,
                cursor = cursor,
            ),
        )
        _state.update {
            it.copy(
                lastError = reason,
                lastRejectReason = reason,
                quarantinedEventCount = quarantineStore?.list()?.size ?: it.quarantinedEventCount,
            )
        }
    }

    private suspend fun publishSnapshot(
        store: ClockEventStore,
        quarantineStore: ClockEventSyncQuarantineStore?,
        lastRejectReason: String?,
    ) {
        val quarantineRecords = quarantineStore?.list().orEmpty()
        val effectiveReason = lastRejectReason ?: quarantineRecords.lastOrNull()?.reason
        snapshotPublisher(
            runCatching { store.readSnapshot() }.getOrNull()?.copy(
                lastRejectReason = effectiveReason,
                quarantinedEventCount = quarantineRecords.size,
                lastQuarantineAtEpochMs = quarantineRecords.lastOrNull()?.quarantinedAtEpochMs,
            ) ?: ClockEventStoreSnapshot(
                lastCursor = null,
                lastSyncedCursor = null,
                pendingSyncCount = 0,
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
        private val logger: Logger = Logger.getLogger(DesktopEventSyncRuntime::class.java.name)
    }
}
