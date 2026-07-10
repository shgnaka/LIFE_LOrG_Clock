package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.api.CancelOutcome
import io.github.shgnaka.orgclock.synccore.api.AckIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.FlushSummary
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingReceipt
import io.github.shgnaka.orgclock.synccore.api.IncomingRoute
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingPage
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.OutgoingSummary
import io.github.shgnaka.orgclock.synccore.api.PeerRevokeOutcome
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.RetryOutcome
import io.github.shgnaka.orgclock.synccore.api.StartOutcome
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncHealth
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncRandom
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreOutgoingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingState
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.MessageDirection
import io.github.shgnaka.orgclock.synccore.api.TopicAuthorization
import io.github.shgnaka.orgclock.synccore.api.TopicPolicy
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

internal class InMemorySyncCore(
    private val clock: SyncClock,
    private val transport: SyncTransport,
    private val random: SyncRandom = SyncRandom { 0.0 },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val dispatchLeasePolicy: DispatchLeasePolicy = DispatchLeasePolicy(),
    private val dispatchConcurrencyPolicy: DispatchConcurrencyPolicy = DispatchConcurrencyPolicy(),
    private val incomingLeasePolicy: IncomingLeasePolicy = IncomingLeasePolicy(),
    private val capacityPolicy: CapacityPolicy = CapacityPolicy(),
    private val retentionPolicy: RetentionPolicy = RetentionPolicy(),
    private val topicPolicy: TopicPolicy = AllowAllTopicPolicy,
    private val trustedPeerResolver: TrustedPeerResolver = TrustedPeerResolver { null },
    private val store: InMemorySyncStore = InMemorySyncStore(),
    private val durableStore: SyncStore? = null,
) : SyncCore {
    private val mutex = Mutex()
    private val liveDeliveryEvents = MutableSharedFlow<DeliveryEvent>(extraBufferCapacity = 128)
    private var running = false
    private var durableLoaded = durableStore == null
    private var lastRetentionCleanupEpochMs: Long? = null

    override suspend fun start(): StartOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            cleanupRetentionBestEffortLocked(force = true)
            if (running) StartOutcome.AlreadyStarted else {
                running = true
                StartOutcome.Started
            }
        }
    } catch (error: SyncStoreAccessException) {
        StartOutcome.Failed(error.syncError)
    }

    override suspend fun stop() {
        mutex.withLock {
            ensureLoadedLocked()
            running = false
        }
    }

    override suspend fun submit(message: SyncMessage): SubmitOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            val beforeSnapshot = store.toSnapshot()
            val existing = store.outgoing[message.messageId]
        if (existing != null) {
            return@withLock if (existing.message == message) {
                SubmitOutcome.AlreadySubmitted
            } else {
                SubmitOutcome.Rejected(
                    SyncError(SyncErrorCode.MessageIdConflict, "messageId already exists with different content"),
                )
            }
        }
        val activeOutgoing = store.outgoing.values.filter { !it.state.isTerminal() }
        if (activeOutgoing.size >= capacityPolicy.maxPendingOutgoingTotal) {
            return@withLock SubmitOutcome.Rejected(
                SyncError(SyncErrorCode.QueueFull, "pending outgoing capacity reached"),
            )
        }
        if (activeOutgoing.count { it.message.targetPeerId == message.targetPeerId } >= capacityPolicy.maxPendingOutgoingPerPeer) {
            return@withLock SubmitOutcome.Rejected(
                SyncError(SyncErrorCode.QueueFull, "pending outgoing peer capacity reached"),
            )
        }
        val record = OutgoingRecord(
            sequence = store.nextRecordSequence++,
            message = message,
            state = DeliveryState.Pending,
            attempt = 0,
            nextAttemptAtEpochMs = message.createdAtEpochMs,
            lastError = null,
            leaseUntilEpochMs = null,
            terminalAtEpochMs = null,
        )
        store.outgoing[message.messageId] = record
        store.submittedTotal += 1
        emitLocked(record, DeliveryState.Pending, null)
            persistLockedOrRestore(beforeSnapshot)
            SubmitOutcome.Submitted
        }
    } catch (error: SyncStoreAccessException) {
        SubmitOutcome.Failed(error.syncError)
    }

    override suspend fun cancel(messageId: MessageId): CancelOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            val beforeSnapshot = store.toSnapshot()
            val record = store.outgoing[messageId] ?: return@withLock CancelOutcome.NotFound
            if (record.state.isTerminal()) return@withLock CancelOutcome.AlreadyTerminal
            val error = SyncError(SyncErrorCode.Cancelled)
            record.state = DeliveryState.Cancelled
            record.nextAttemptAtEpochMs = null
            record.leaseUntilEpochMs = null
            record.lastError = error
            record.terminalAtEpochMs = clock.nowEpochMs()
            emitLocked(record, DeliveryState.Cancelled, error)
            persistLockedOrRestore(beforeSnapshot)
            CancelOutcome.Cancelled
        }
    } catch (error: SyncStoreAccessException) {
        CancelOutcome.Failed(error.syncError)
    }

    override suspend fun retry(messageId: MessageId): RetryOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            val beforeSnapshot = store.toSnapshot()
            val record = store.outgoing[messageId] ?: return@withLock RetryOutcome.NotFound
            if (record.state != DeliveryState.Failed) return@withLock RetryOutcome.NotRetryable
            record.state = DeliveryState.Pending
            record.attempt = 0
            record.nextAttemptAtEpochMs = clock.nowEpochMs()
            record.leaseUntilEpochMs = null
            record.lastError = null
            record.terminalAtEpochMs = null
            emitLocked(record, DeliveryState.Pending, null)
            persistLockedOrRestore(beforeSnapshot)
            RetryOutcome.Scheduled
        }
    } catch (error: SyncStoreAccessException) {
        RetryOutcome.Failed(error.syncError)
    }
    override suspend fun flushDue(): FlushSummary {
        var considered = 0
        var dispatched = 0
        var accepted = 0
        var rejected = 0
        var retryScheduled = 0
        var expired = 0
        var failed = 0
        var persistenceFailures = 0
        var lastPersistenceError: SyncError? = null

        fun summary() = FlushSummary(
            considered = considered,
            dispatched = dispatched,
            accepted = accepted,
            rejected = rejected,
            retryScheduled = retryScheduled,
            expired = expired,
            failed = failed,
            persistenceFailures = persistenceFailures,
            lastPersistenceError = lastPersistenceError,
        )

        fun recordPersistenceFailure(error: SyncStoreAccessException): FlushSummary {
            persistenceFailures += 1
            lastPersistenceError = error.syncError
            return summary()
        }

        try {
            mutex.withLock {
                ensureLoadedLocked()
                cleanupRetentionBestEffortLocked(force = false)
            }
            recoverExpiredDispatchLeases()
        } catch (error: SyncStoreAccessException) {
            return recordPersistenceFailure(error)
        }

        while (true) {
            val batch = try {
                mutex.withLock {
                    ensureLoadedLocked()
                    val beforeSnapshot = store.toSnapshot()
                    val now = clock.nowEpochMs()
                    val selected = mutableListOf<DispatchCandidate.Ready>()
                    val peerHeadsSeen = mutableSetOf<PeerId>()
                    var expiredInBatch = 0
                    var changed = false

                    for (record in store.outgoing.values.sortedBy { it.sequence }) {
                        if (selected.size >= dispatchConcurrencyPolicy.maxGlobalInFlight) break
                        val targetPeerId = record.message.targetPeerId
                        if (record.state.isTerminal()) continue
                        if (!peerHeadsSeen.add(targetPeerId)) continue
                        if (record.state != DeliveryState.Pending && record.state != DeliveryState.RetryWait) continue
                        if (!record.isDueAt(now)) continue

                        considered += 1
                        if (record.isExpiredAt(now)) {
                            expireLocked(record)
                            expiredInBatch += 1
                            changed = true
                            peerHeadsSeen.remove(targetPeerId)
                            continue
                        }

                        record.state = DeliveryState.Dispatching
                        record.attempt += 1
                        record.nextAttemptAtEpochMs = null
                        record.leaseUntilEpochMs = now + dispatchLeasePolicy.leaseMs
                        record.terminalAtEpochMs = null
                        emitLocked(record, DeliveryState.Dispatching, null)
                        selected += DispatchCandidate.Ready(record.message, record.attempt)
                        changed = true
                    }

                    if (changed) {
                        persistLockedOrRestore(beforeSnapshot)
                    }

                    DispatchBatch(selected, expiredInBatch)
                }
            } catch (error: SyncStoreAccessException) {
                return recordPersistenceFailure(error)
            }
            expired += batch.expired

            if (batch.ready.isEmpty()) {
                if (batch.expired == 0) break
                continue
            }

            dispatched += batch.ready.size
            val results = coroutineScope {
                batch.ready.map { dispatch ->
                    async {
                        DispatchResult(
                            dispatch = dispatch,
                            outcome = transport.dispatch(dispatch.message, dispatch.attempt),
                        )
                    }
                }.awaitAll()
            }

            val outcomeCounts = try {
                mutex.withLock {
                    val beforeSnapshot = store.toSnapshot()
                    var changed = false
                    var batchAccepted = 0
                    var batchRejected = 0
                    var batchRetryScheduled = 0
                    var batchExpired = 0
                    var batchFailed = 0
                    for (result in results) {
                        when (val outcome = result.outcome) {
                            DispatchOutcome.Accepted -> {
                                val record = store.outgoing[result.dispatch.message.messageId] ?: continue
                                if (record.state != DeliveryState.Dispatching) continue
                                record.state = DeliveryState.Acked
                                record.nextAttemptAtEpochMs = null
                                record.leaseUntilEpochMs = null
                                record.lastError = null
                                record.terminalAtEpochMs = clock.nowEpochMs()
                                store.acceptedTotal += 1
                                batchAccepted += 1
                                store.lastSuccessfulDispatchByPeer[record.message.targetPeerId] = clock.nowEpochMs()
                                emitLocked(record, DeliveryState.Acked, null)
                                changed = true
                            }
                            is DispatchOutcome.Rejected -> {
                                val record = store.outgoing[result.dispatch.message.messageId] ?: continue
                                if (record.state != DeliveryState.Dispatching) continue
                                record.state = DeliveryState.Rejected
                                record.nextAttemptAtEpochMs = null
                                record.leaseUntilEpochMs = null
                                record.lastError = outcome.error
                                record.terminalAtEpochMs = clock.nowEpochMs()
                                store.rejectedTotal += 1
                                batchRejected += 1
                                emitLocked(record, DeliveryState.Rejected, outcome.error)
                                changed = true
                            }
                            is DispatchOutcome.Retryable -> {
                                val record = store.outgoing[result.dispatch.message.messageId] ?: continue
                                if (record.state != DeliveryState.Dispatching) continue
                                store.retryAttemptsTotal += 1
                                if (record.isExpiredAt(clock.nowEpochMs())) {
                                    expireLocked(record)
                                    batchExpired += 1
                                } else if (record.attempt >= retryPolicy.maxAttempts) {
                                    val error = SyncError(SyncErrorCode.RetryExhausted, outcome.error.detail)
                                    record.state = DeliveryState.Failed
                                    record.nextAttemptAtEpochMs = null
                                    record.leaseUntilEpochMs = null
                                    record.lastError = error
                                    record.terminalAtEpochMs = clock.nowEpochMs()
                                    batchFailed += 1
                                    emitLocked(record, DeliveryState.Failed, error)
                                } else {
                                    val nextAttemptAt = clock.nowEpochMs() + retryPolicy.delayAfterAttempt(record.attempt, random)
                                    val error = outcome.error.copy(retryAtEpochMs = nextAttemptAt)
                                    record.state = DeliveryState.RetryWait
                                    record.nextAttemptAtEpochMs = nextAttemptAt
                                    record.leaseUntilEpochMs = null
                                    record.lastError = error
                                    record.terminalAtEpochMs = null
                                    batchRetryScheduled += 1
                                    emitLocked(record, DeliveryState.RetryWait, error)
                                }
                                changed = true
                            }
                        }
                    }
                    if (changed) {
                        persistLockedOrRestore(beforeSnapshot)
                    }
                    DispatchOutcomeCounts(
                        accepted = batchAccepted,
                        rejected = batchRejected,
                        retryScheduled = batchRetryScheduled,
                        expired = batchExpired,
                        failed = batchFailed,
                    )
                }
            } catch (error: SyncStoreAccessException) {
                return recordPersistenceFailure(error)
            }
            accepted += outcomeCounts.accepted
            rejected += outcomeCounts.rejected
            retryScheduled += outcomeCounts.retryScheduled
            expired += outcomeCounts.expired
            failed += outcomeCounts.failed
        }

        return summary()
    }
    private suspend fun recoverExpiredDispatchLeases() {
        mutex.withLock {
            ensureLoadedLocked()
            val beforeSnapshot = store.toSnapshot()
            val now = clock.nowEpochMs()
            var changed = false
            store.outgoing.values
                .filter { it.state == DeliveryState.Dispatching }
                .filter { record -> record.leaseUntilEpochMs?.let { it <= now } == true }
                .sortedBy { it.sequence }
                .forEach { record ->
                    record.state = DeliveryState.Pending
                    record.nextAttemptAtEpochMs = now
                    record.leaseUntilEpochMs = null
                    record.lastError = SyncError(SyncErrorCode.Timeout, "dispatch lease expired")
                    record.terminalAtEpochMs = null
                    store.expiredLeaseRecoveryTotal += 1
                    emitLocked(record, DeliveryState.Pending, record.lastError)
                    changed = true
                }
            if (changed) {
                persistLockedOrRestore(beforeSnapshot)
            }
        }
    }

    override suspend fun listOutgoing(query: OutgoingQuery): OutgoingPage = mutex.withLock {
        ensureLoadedLocked()
        val filtered = store.outgoing.values.asSequence()
            .filter { query.afterSequence == null || it.sequence > query.afterSequence }
            .filter { query.peerId == null || it.message.targetPeerId == query.peerId }
            .filter { query.topic == null || it.message.topic == query.topic }
            .filter { query.states.isEmpty() || it.state in query.states }
            .sortedBy { it.sequence }
            .take(query.limit + 1)
            .toList()
        val pageItems = filtered.take(query.limit)
        val nextSequence = if (filtered.size > query.limit) pageItems.lastOrNull()?.sequence else null
        OutgoingPage(pageItems.map { it.toSummary() }, nextSequence)
    }

    override suspend fun receiveVerified(envelope: VerifiedEnvelope): IngressOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            val beforeSnapshot = store.toSnapshot()
            val key = IncomingKey(envelope.senderPeerId, envelope.messageId)
            val trustedPeer = trustedPeerResolver.resolve(envelope.senderPeerId)
            if (trustedPeer == null || !trustedPeer.active) {
                store.incomingRejectedTotal += 1
                val outcome = IngressOutcome.Rejected(
                    httpStatus = 401,
                    error = SyncError(SyncErrorCode.PeerNotTrusted, "peer is not trusted"),
                )
                persistLockedOrRestore(beforeSnapshot)
                return@withLock outcome
            }
            if (topicPolicy.authorize(trustedPeer, envelope.topic, MessageDirection.Incoming) != TopicAuthorization.Allowed) {
                store.incomingRejectedTotal += 1
                val outcome = IngressOutcome.Rejected(
                    httpStatus = 403,
                    error = SyncError(SyncErrorCode.PeerNotAuthorized, "peer is not authorized for topic"),
                )
                persistLockedOrRestore(beforeSnapshot)
                return@withLock outcome
            }
            val existing = store.incoming[key]
            if (existing != null) {
                return@withLock if (existing.hasSameContent(envelope)) {
                    IngressOutcome.AlreadyAccepted
                } else {
                    store.incomingRejectedTotal += 1
                    val outcome = IngressOutcome.Rejected(
                        httpStatus = 409,
                        error = SyncError(SyncErrorCode.ReplayConflict, "sender/messageId already exists with different content"),
                    )
                    persistLockedOrRestore(beforeSnapshot)
                    outcome
                }
            }
            if (store.incoming.values.count { !it.state.isTerminal() } >= capacityPolicy.maxUnprocessedIncoming) {
                return@withLock IngressOutcome.RetryLater(
                    httpStatus = 503,
                    retryAfterSeconds = capacityPolicy.capacityRetryAfterSeconds,
                    error = SyncError(SyncErrorCode.InboxFull, "incoming inbox capacity reached"),
                )
            }

            store.incoming[key] = IncomingRecord(
                receiptId = "in-${store.nextReceiptSequence++}",
                senderPeerId = envelope.senderPeerId,
                targetPeerId = envelope.targetPeerId,
                messageId = envelope.messageId,
                topic = envelope.topic,
                payloadJson = envelope.payloadJson,
                payloadSha256 = envelope.payloadSha256,
                receivedAtEpochMs = clock.nowEpochMs(),
                state = IncomingState.Available,
                availableAtEpochMs = clock.nowEpochMs(),
                deliveryCount = 0,
                lastError = null,
                processedAtEpochMs = null,
            )
            persistLockedOrRestore(beforeSnapshot)
            IngressOutcome.Accepted
        }
    } catch (error: SyncStoreAccessException) {
        IngressOutcome.RetryLater(
            httpStatus = 503,
            retryAfterSeconds = capacityPolicy.capacityRetryAfterSeconds,
            error = error.syncError,
        )
    }
    override suspend fun claimIncoming(limit: Int): ClaimIncomingOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            require(limit in 1..500) { "limit must be between 1 and 500." }
            val beforeSnapshot = store.toSnapshot()
            val now = clock.nowEpochMs()
            val receipts = store.incoming.values
                .asSequence()
                .filter { it.isClaimableAt(now) }
                .sortedBy { it.receivedAtEpochMs }
                .take(limit)
                .map { record ->
                    record.state = IncomingState.Claimed
                    record.availableAtEpochMs = now + incomingLeasePolicy.processingLeaseMs
                    record.deliveryCount += 1
                    record.processedAtEpochMs = null
                    record.toReceipt()
                }
                .toList()
            if (receipts.isNotEmpty()) persistLockedOrRestore(beforeSnapshot)
            ClaimIncomingOutcome.Claimed(receipts)
        }
    } catch (error: SyncStoreAccessException) {
        ClaimIncomingOutcome.Failed(error.syncError)
    }

    override suspend fun resolveIncomingRoute(messageId: MessageId): IncomingRoute? = mutex.withLock {
        ensureLoadedLocked()
        val matches = store.incoming.values.filter { it.messageId == messageId && !it.state.isTerminal() }
        if (matches.size != 1) return@withLock null
        matches.single().toRoute()
    }

    override suspend fun ackIncoming(receiptId: String, outcome: IncomingProcessingOutcome): AckIncomingOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            val record = store.incoming.values.firstOrNull { it.receiptId == receiptId }
                ?: return@withLock AckIncomingOutcome.NotFound
            val beforeSnapshot = store.toSnapshot()
            when (outcome) {
                IncomingProcessingOutcome.Processed -> {
                    record.state = IncomingState.Processed
                    record.availableAtEpochMs = null
                    record.lastError = null
                    record.processedAtEpochMs = clock.nowEpochMs()
                }
                is IncomingProcessingOutcome.Rejected -> {
                    record.state = IncomingState.Rejected
                    record.availableAtEpochMs = null
                    record.lastError = outcome.error
                    record.processedAtEpochMs = clock.nowEpochMs()
                }
                is IncomingProcessingOutcome.RetryLater -> {
                    record.state = IncomingState.RetryLater
                    record.availableAtEpochMs = outcome.retryAtEpochMs ?: clock.nowEpochMs()
                    record.lastError = null
                    record.processedAtEpochMs = null
                }
            }
            persistLockedOrRestore(beforeSnapshot)
            AckIncomingOutcome.Acked
        }
    } catch (error: SyncStoreAccessException) {
        AckIncomingOutcome.Failed(error.syncError)
    }

    override suspend fun revokePeer(peerId: PeerId): PeerRevokeOutcome = try {
        mutex.withLock {
            ensureLoadedLocked()
            val beforeSnapshot = store.toSnapshot()
            val now = clock.nowEpochMs()
            val error = SyncError(SyncErrorCode.PeerNotTrusted, "peer revoked")
            var outgoingRejected = 0
            var incomingRejected = 0

            store.outgoing.values
                .filter { it.message.targetPeerId == peerId && !it.state.isTerminal() }
                .sortedBy { it.sequence }
                .forEach { record ->
                    record.state = DeliveryState.Rejected
                    record.nextAttemptAtEpochMs = null
                    record.leaseUntilEpochMs = null
                    record.lastError = error
                    record.terminalAtEpochMs = now
                    store.rejectedTotal += 1
                    outgoingRejected += 1
                    emitLocked(record, DeliveryState.Rejected, error)
                }

            store.incoming.values
                .filter { it.senderPeerId == peerId && !it.state.isTerminal() }
                .sortedBy { it.receivedAtEpochMs }
                .forEach { record ->
                    record.state = IncomingState.Rejected
                    record.availableAtEpochMs = null
                    record.lastError = error
                    record.processedAtEpochMs = now
                    store.incomingRejectedTotal += 1
                    incomingRejected += 1
                }

            if (outgoingRejected > 0 || incomingRejected > 0) {
                persistLockedOrRestore(beforeSnapshot)
            }
            PeerRevokeOutcome.Revoked(outgoingRejected, incomingRejected)
        }
    } catch (error: SyncStoreAccessException) {
        PeerRevokeOutcome.Failed(error.syncError)
    }

    override fun observeDeliveryEvents(afterSequence: Long?): Flow<DeliveryEvent> = flow {
        val replay = mutex.withLock {
            ensureLoadedLocked()
            store.deliveryEvents.filter { afterSequence == null || it.sequence > afterSequence }
        }
        replay.forEach { emit(it) }
        emitAll(liveDeliveryEvents.filter { afterSequence == null || it.sequence > afterSequence })
    }

    override suspend fun metricsSnapshot(): SyncMetrics = mutex.withLock {
        ensureLoadedLocked()
        val active = store.outgoing.values.filter { !it.state.isTerminal() }
        val oldest = active.minOfOrNull { it.message.createdAtEpochMs }
        SyncMetrics(
            submittedTotal = store.submittedTotal,
            acceptedTotal = store.acceptedTotal,
            rejectedTotal = store.rejectedTotal,
            retryAttemptsTotal = store.retryAttemptsTotal,
            queueDepth = active.size.toLong(),
            oldestPendingAgeMs = oldest?.let { clock.nowEpochMs() - it },
            incomingRejectedTotal = store.incomingRejectedTotal,
            inboxUnprocessedDepth = store.incoming.values.count { !it.state.isTerminal() }.toLong(),
            expiredLeaseRecoveryTotal = store.expiredLeaseRecoveryTotal,
            persistenceErrorTotal = store.persistenceErrorTotal,
            lastSuccessfulDispatchByPeer = store.lastSuccessfulDispatchByPeer.toMap(),
        )
    }

    override suspend fun healthCheck(): SyncHealth = when (val durableHealth = durableStore?.healthCheck()) {
        null, StoreHealth.Healthy -> SyncHealth.Healthy
        is StoreHealth.Unavailable -> SyncHealth.Unavailable(durableHealth.error)
    }

    private suspend fun ensureLoadedLocked() {
        if (durableLoaded) return
        when (val result = durableStore?.load()) {
            null -> Unit
            is SyncStoreLoadResult.Loaded -> {
                val transientPersistenceErrors = store.persistenceErrorTotal
                store.replaceWith(result.snapshot, capacityPolicy.maxDeliveryEventSnapshot)
                store.persistenceErrorTotal += transientPersistenceErrors
            }
            is SyncStoreLoadResult.Failed -> {
                store.persistenceErrorTotal += 1
                throw SyncStoreAccessException(result.error)
            }
        }
        durableLoaded = true
    }

    private suspend fun persistLocked() {
        val target = durableStore ?: return
        when (val result = target.save(store.toSnapshot())) {
            SyncStoreSaveResult.Saved -> Unit
            is SyncStoreSaveResult.Failed -> {
                store.persistenceErrorTotal += 1
                throw SyncStoreAccessException(result.error)
            }
        }
    }

    private suspend fun persistLockedOrRestore(beforeSnapshot: SyncStoreSnapshot) {
        try {
            persistLocked()
        } catch (error: CancellationException) {
            store.replaceWith(beforeSnapshot, capacityPolicy.maxDeliveryEventSnapshot)
            throw error
        } catch (error: SyncStoreAccessException) {
            val persistenceErrorTotal = store.persistenceErrorTotal
            store.replaceWith(beforeSnapshot, capacityPolicy.maxDeliveryEventSnapshot)
            store.persistenceErrorTotal = persistenceErrorTotal
            throw error
        }
    }

    private suspend fun cleanupRetentionBestEffortLocked(force: Boolean) {
        val nowEpochMs = clock.nowEpochMs()
        val lastCleanupEpochMs = lastRetentionCleanupEpochMs
        if (!force && lastCleanupEpochMs != null && nowEpochMs - lastCleanupEpochMs < retentionPolicy.cleanupIntervalMs) return
        lastRetentionCleanupEpochMs = nowEpochMs
        if (!hasRetentionWorkLocked(nowEpochMs)) return

        val beforeSnapshot = store.toSnapshot()
        if (!pruneRetentionLocked(nowEpochMs)) return

        try {
            persistLocked()
        } catch (error: SyncStoreAccessException) {
            val persistenceErrorTotal = store.persistenceErrorTotal
            store.replaceWith(beforeSnapshot, capacityPolicy.maxDeliveryEventSnapshot)
            store.persistenceErrorTotal = persistenceErrorTotal
        }
    }

    private fun hasRetentionWorkLocked(nowEpochMs: Long): Boolean {
        val deliveryEventCutoffEpochMs = nowEpochMs - retentionPolicy.deliveryEventTtlMs
        val outgoingCutoffEpochMs = nowEpochMs - retentionPolicy.terminalOutgoingTtlMs
        val incomingCutoffEpochMs = nowEpochMs - retentionPolicy.terminalIncomingTtlMs
        return store.outgoing.values.any { record ->
            record.state.isTerminal() && record.terminalAtEpochMs?.let { it < outgoingCutoffEpochMs } == true
        } ||
            store.outgoing.values.count { it.state.isTerminal() } > retentionPolicy.maxTerminalOutgoingRecords ||
            store.incoming.values.any { record ->
                record.state.isTerminal() && record.processedAtEpochMs?.let { it < incomingCutoffEpochMs } == true
            } ||
            store.incoming.values.count { it.state.isTerminal() } > retentionPolicy.maxTerminalIncomingRecords ||
            store.deliveryEvents.size > retentionPolicy.maxDeliveryEvents ||
            store.deliveryEvents.any { it.occurredAtEpochMs < deliveryEventCutoffEpochMs }
    }

    private fun pruneRetentionLocked(nowEpochMs: Long): Boolean {
        val deliveryEventCutoffEpochMs = nowEpochMs - retentionPolicy.deliveryEventTtlMs
        val outgoingCutoffEpochMs = nowEpochMs - retentionPolicy.terminalOutgoingTtlMs
        val incomingCutoffEpochMs = nowEpochMs - retentionPolicy.terminalIncomingTtlMs
        val outgoingBeforeCount = store.outgoing.size
        val incomingBeforeCount = store.incoming.size
        val beforeDeliveryEventCount = store.deliveryEvents.size

        val expiredOutgoing = store.outgoing
            .filter { (_, record) ->
                record.state.isTerminal() && record.terminalAtEpochMs?.let { it < outgoingCutoffEpochMs } == true
            }
            .map { it.key }
        expiredOutgoing.forEach { store.outgoing.remove(it) }
        trimTerminalOutgoingByCapLocked()

        val expiredIncoming = store.incoming
            .filter { (_, record) ->
                record.state.isTerminal() && record.processedAtEpochMs?.let { it < incomingCutoffEpochMs } == true
            }
            .map { it.key }
        expiredIncoming.forEach { store.incoming.remove(it) }
        trimTerminalIncomingByCapLocked()

        store.deliveryEvents.removeAll { it.occurredAtEpochMs < deliveryEventCutoffEpochMs }
        while (store.deliveryEvents.size > retentionPolicy.maxDeliveryEvents) {
            store.deliveryEvents.removeAt(0)
        }
        return store.outgoing.size != outgoingBeforeCount ||
            store.incoming.size != incomingBeforeCount ||
            store.deliveryEvents.size != beforeDeliveryEventCount
    }

    private fun trimTerminalOutgoingByCapLocked() {
        val excess = store.outgoing.values.count { it.state.isTerminal() } - retentionPolicy.maxTerminalOutgoingRecords
        if (excess <= 0) return
        store.outgoing.values
            .filter { it.state.isTerminal() && it.terminalAtEpochMs != null }
            .sortedWith(compareBy<OutgoingRecord> { it.terminalAtEpochMs ?: Long.MAX_VALUE }.thenBy { it.sequence })
            .take(excess)
            .map { it.message.messageId }
            .forEach { store.outgoing.remove(it) }
    }

    private fun trimTerminalIncomingByCapLocked() {
        val excess = store.incoming.values.count { it.state.isTerminal() } - retentionPolicy.maxTerminalIncomingRecords
        if (excess <= 0) return
        store.incoming.values
            .filter { it.state.isTerminal() && it.processedAtEpochMs != null }
            .sortedWith(compareBy<IncomingRecord> { it.processedAtEpochMs ?: Long.MAX_VALUE }.thenBy { it.receivedAtEpochMs })
            .take(excess)
            .map { IncomingKey(it.senderPeerId, it.messageId) }
            .forEach { store.incoming.remove(it) }
    }

    private fun expireLocked(record: OutgoingRecord) {
        val error = SyncError(SyncErrorCode.Expired)
        record.state = DeliveryState.Expired
        record.nextAttemptAtEpochMs = null
        record.leaseUntilEpochMs = null
        record.lastError = error
        record.terminalAtEpochMs = clock.nowEpochMs()
        emitLocked(record, DeliveryState.Expired, error)
    }

    private fun emitLocked(record: OutgoingRecord, state: DeliveryState, error: SyncError?) {
        val event = DeliveryEvent(
            sequence = store.nextEventSequence++,
            messageId = record.message.messageId,
            peerId = record.message.targetPeerId,
            topic = record.message.topic,
            state = state,
            attempt = record.attempt,
            occurredAtEpochMs = clock.nowEpochMs(),
            error = error,
        )
        store.deliveryEvents += event
        trimDeliveryEventsLocked()
        liveDeliveryEvents.tryEmit(event)
    }

    private fun trimDeliveryEventsLocked() {
        while (store.deliveryEvents.size > capacityPolicy.maxDeliveryEventSnapshot) {
            store.deliveryEvents.removeAt(0)
        }
    }

    private fun OutgoingRecord.toSummary() = OutgoingSummary(
        sequence = sequence,
        messageId = message.messageId,
        peerId = message.targetPeerId,
        topic = message.topic,
        state = state,
        attempt = attempt,
        createdAtEpochMs = message.createdAtEpochMs,
        nextAttemptAtEpochMs = nextAttemptAtEpochMs,
        expiresAtEpochMs = message.expiresAtEpochMs,
        lastError = lastError,
    )

    private fun IncomingRecord.toReceipt() = IncomingReceipt(
        receiptId = receiptId,
        messageId = messageId,
        senderPeerId = senderPeerId,
        topic = topic,
        payloadJson = payloadJson,
        receivedAtEpochMs = receivedAtEpochMs,
        deliveryCount = deliveryCount,
    )

    private fun IncomingRecord.toRoute() = IncomingRoute(
        receiptId = receiptId,
        messageId = messageId,
        senderPeerId = senderPeerId,
        topic = topic,
        receivedAtEpochMs = receivedAtEpochMs,
    )

    private fun IncomingRecord.hasSameContent(envelope: VerifiedEnvelope): Boolean =
        targetPeerId == envelope.targetPeerId &&
            topic == envelope.topic &&
            payloadJson == envelope.payloadJson &&
            payloadSha256 == envelope.payloadSha256

    private fun IncomingRecord.isClaimableAt(nowEpochMs: Long): Boolean = when (state) {
        IncomingState.Available -> true
        IncomingState.RetryLater, IncomingState.Claimed -> {
            val availableAt = availableAtEpochMs
            availableAt == null || availableAt <= nowEpochMs
        }
        IncomingState.Processed, IncomingState.Rejected -> false
    }

    private fun OutgoingRecord.isDueAt(nowEpochMs: Long): Boolean {
        val nextAttemptAt = nextAttemptAtEpochMs
        return nextAttemptAt == null || nextAttemptAt <= nowEpochMs
    }

    private fun OutgoingRecord.isExpiredAt(nowEpochMs: Long): Boolean =
        message.expiresAtEpochMs?.let { nowEpochMs >= it } == true

    private data class DispatchBatch(
        val ready: List<DispatchCandidate.Ready>,
        val expired: Int,
    )

    private data class DispatchOutcomeCounts(
        val accepted: Int,
        val rejected: Int,
        val retryScheduled: Int,
        val expired: Int,
        val failed: Int,
    )

    private sealed interface DispatchCandidate {
        data class Ready(val message: SyncMessage, val attempt: Int) : DispatchCandidate
    }

    private data class DispatchResult(
        val dispatch: DispatchCandidate.Ready,
        val outcome: DispatchOutcome,
    )
}

private class SyncStoreAccessException(val syncError: SyncError) : RuntimeException(syncError.detail)

private fun InMemorySyncStore.replaceWith(snapshot: SyncStoreSnapshot, deliveryEventSnapshotLimit: Int) {
    outgoing.clear()
    snapshot.outgoing.sortedBy { it.sequence }.forEach { record ->
        outgoing[record.message.messageId] = record.toEngineRecord()
    }
    incoming.clear()
    snapshot.incoming.sortedBy { it.receivedAtEpochMs }.forEach { record ->
        incoming[IncomingKey(record.senderPeerId, record.messageId)] = record.toEngineRecord()
    }
    nextRecordSequence = snapshot.nextRecordSequence
    nextEventSequence = snapshot.nextEventSequence
    nextReceiptSequence = snapshot.nextReceiptSequence
    submittedTotal = snapshot.metrics.submittedTotal
    acceptedTotal = snapshot.metrics.acceptedTotal
    rejectedTotal = snapshot.metrics.rejectedTotal
    retryAttemptsTotal = snapshot.metrics.retryAttemptsTotal
    incomingRejectedTotal = snapshot.metrics.incomingRejectedTotal
    expiredLeaseRecoveryTotal = snapshot.metrics.expiredLeaseRecoveryTotal
    persistenceErrorTotal = snapshot.metrics.persistenceErrorTotal
    deliveryEvents.clear()
    deliveryEvents += snapshot.deliveryEvents.sortedBy { it.sequence }.takeLast(deliveryEventSnapshotLimit)
    lastSuccessfulDispatchByPeer.clear()
    lastSuccessfulDispatchByPeer += snapshot.metrics.lastSuccessfulDispatchByPeer
}

private fun InMemorySyncStore.toSnapshot(): SyncStoreSnapshot = SyncStoreSnapshot(
    outgoing = outgoing.values.sortedBy { it.sequence }.map { it.toStoreRecord() },
    incoming = incoming.values.sortedBy { it.receivedAtEpochMs }.map { it.toStoreRecord() },
    deliveryEvents = deliveryEvents.sortedBy { it.sequence },
    metrics = SyncStoreMetrics(
        submittedTotal = submittedTotal,
        acceptedTotal = acceptedTotal,
        rejectedTotal = rejectedTotal,
        retryAttemptsTotal = retryAttemptsTotal,
        incomingRejectedTotal = incomingRejectedTotal,
        expiredLeaseRecoveryTotal = expiredLeaseRecoveryTotal,
        persistenceErrorTotal = persistenceErrorTotal,
        lastSuccessfulDispatchByPeer = lastSuccessfulDispatchByPeer.toMap(),
    ),
    nextRecordSequence = nextRecordSequence,
    nextEventSequence = nextEventSequence,
    nextReceiptSequence = nextReceiptSequence,
)

private fun SyncStoreOutgoingRecord.toEngineRecord(): OutgoingRecord = OutgoingRecord(
    sequence = sequence,
    message = message,
    state = state,
    attempt = attempt,
    nextAttemptAtEpochMs = nextAttemptAtEpochMs,
    lastError = lastError,
    leaseUntilEpochMs = leaseUntilEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
)

private fun OutgoingRecord.toStoreRecord(): SyncStoreOutgoingRecord = SyncStoreOutgoingRecord(
    sequence = sequence,
    message = message,
    state = state,
    attempt = attempt,
    nextAttemptAtEpochMs = nextAttemptAtEpochMs,
    lastError = lastError,
    leaseUntilEpochMs = leaseUntilEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
)

private fun SyncStoreIncomingRecord.toEngineRecord(): IncomingRecord = IncomingRecord(
    receiptId = receiptId,
    senderPeerId = senderPeerId,
    targetPeerId = targetPeerId,
    messageId = messageId,
    topic = topic,
    payloadJson = payloadJson,
    payloadSha256 = payloadSha256,
    receivedAtEpochMs = receivedAtEpochMs,
    state = state.toEngineState(),
    availableAtEpochMs = availableAtEpochMs,
    deliveryCount = deliveryCount,
    lastError = lastError,
    processedAtEpochMs = processedAtEpochMs,
)

private fun IncomingRecord.toStoreRecord(): SyncStoreIncomingRecord = SyncStoreIncomingRecord(
    receiptId = receiptId,
    senderPeerId = senderPeerId,
    targetPeerId = targetPeerId,
    messageId = messageId,
    topic = topic,
    payloadJson = payloadJson,
    payloadSha256 = payloadSha256,
    receivedAtEpochMs = receivedAtEpochMs,
    state = state.toStoreState(),
    availableAtEpochMs = availableAtEpochMs,
    deliveryCount = deliveryCount,
    lastError = lastError,
    processedAtEpochMs = processedAtEpochMs,
)

private fun SyncStoreIncomingState.toEngineState(): IncomingState = when (this) {
    SyncStoreIncomingState.Available -> IncomingState.Available
    SyncStoreIncomingState.Claimed -> IncomingState.Claimed
    SyncStoreIncomingState.RetryLater -> IncomingState.RetryLater
    SyncStoreIncomingState.Processed -> IncomingState.Processed
    SyncStoreIncomingState.Rejected -> IncomingState.Rejected
}

private fun IncomingState.toStoreState(): SyncStoreIncomingState = when (this) {
    IncomingState.Available -> SyncStoreIncomingState.Available
    IncomingState.Claimed -> SyncStoreIncomingState.Claimed
    IncomingState.RetryLater -> SyncStoreIncomingState.RetryLater
    IncomingState.Processed -> SyncStoreIncomingState.Processed
    IncomingState.Rejected -> SyncStoreIncomingState.Rejected
}
private object AllowAllTopicPolicy : TopicPolicy {
    override fun authorize(peer: TrustedPeer, topic: Topic, direction: MessageDirection): TopicAuthorization = TopicAuthorization.Allowed
}
internal class InMemorySyncStore {
    val outgoing = linkedMapOf<MessageId, OutgoingRecord>()
    val incoming = linkedMapOf<IncomingKey, IncomingRecord>()
    var nextRecordSequence = 1L
    var nextEventSequence = 1L
    var nextReceiptSequence = 1L
    var submittedTotal = 0L
    var acceptedTotal = 0L
    var rejectedTotal = 0L
    var retryAttemptsTotal = 0L
    var incomingRejectedTotal = 0L
    var expiredLeaseRecoveryTotal = 0L
    var persistenceErrorTotal = 0L
    val deliveryEvents = mutableListOf<DeliveryEvent>()
    val lastSuccessfulDispatchByPeer = linkedMapOf<PeerId, Long>()
}

internal data class OutgoingRecord(
    val sequence: Long,
    val message: SyncMessage,
    var state: DeliveryState,
    var attempt: Int,
    var nextAttemptAtEpochMs: Long?,
    var lastError: SyncError?,
    var leaseUntilEpochMs: Long?,
    var terminalAtEpochMs: Long? = null,
)

internal data class IncomingKey(val senderPeerId: PeerId, val messageId: MessageId)

internal data class IncomingRecord(
    val receiptId: String,
    val senderPeerId: PeerId,
    val targetPeerId: PeerId,
    val messageId: MessageId,
    val topic: Topic,
    val payloadJson: String,
    val payloadSha256: String,
    val receivedAtEpochMs: Long,
    var state: IncomingState,
    var availableAtEpochMs: Long?,
    var deliveryCount: Int,
    var lastError: SyncError?,
    var processedAtEpochMs: Long? = null,
)

internal enum class IncomingState {
    Available,
    Claimed,
    RetryLater,
    Processed,
    Rejected,
}

internal data class CapacityPolicy(
    val maxPendingOutgoingTotal: Int = 2_000,
    val maxPendingOutgoingPerPeer: Int = 500,
    val maxUnprocessedIncoming: Int = 2_000,
    val maxDeliveryEventSnapshot: Int = 100,
    val capacityRetryAfterSeconds: Int = 60,
) {
    init {
        require(maxPendingOutgoingTotal > 0) { "maxPendingOutgoingTotal must be > 0." }
        require(maxPendingOutgoingPerPeer > 0) { "maxPendingOutgoingPerPeer must be > 0." }
        require(maxUnprocessedIncoming > 0) { "maxUnprocessedIncoming must be > 0." }
        require(maxDeliveryEventSnapshot > 0) { "maxDeliveryEventSnapshot must be > 0." }
        require(capacityRetryAfterSeconds > 0) { "capacityRetryAfterSeconds must be > 0." }
    }
}

internal data class RetentionPolicy(
    val terminalOutgoingTtlMs: Long = 7L * 24L * 60L * 60L * 1_000L,
    val terminalIncomingTtlMs: Long = 7L * 24L * 60L * 60L * 1_000L,
    val deliveryEventTtlMs: Long = 30L * 24L * 60L * 60L * 1_000L,
    val cleanupIntervalMs: Long = 60_000L,
    val maxTerminalOutgoingRecords: Int = 20_000,
    val maxTerminalIncomingRecords: Int = 100_000,
    val maxDeliveryEvents: Int = 100_000,
) {
    init {
        require(terminalOutgoingTtlMs > 0) { "terminalOutgoingTtlMs must be > 0." }
        require(terminalIncomingTtlMs > 0) { "terminalIncomingTtlMs must be > 0." }
        require(deliveryEventTtlMs > 0) { "deliveryEventTtlMs must be > 0." }
        require(cleanupIntervalMs > 0) { "cleanupIntervalMs must be > 0." }
        require(maxTerminalOutgoingRecords > 0) { "maxTerminalOutgoingRecords must be > 0." }
        require(maxTerminalIncomingRecords > 0) { "maxTerminalIncomingRecords must be > 0." }
        require(maxDeliveryEvents > 0) { "maxDeliveryEvents must be > 0." }
    }
}

internal data class IncomingLeasePolicy(
    val processingLeaseMs: Long = 60_000L,
) {
    init { require(processingLeaseMs > 0) { "processingLeaseMs must be > 0." } }
}
private fun IncomingState.isTerminal(): Boolean = when (this) {
    IncomingState.Processed, IncomingState.Rejected -> true
    IncomingState.Available, IncomingState.Claimed, IncomingState.RetryLater -> false
}

internal data class DispatchConcurrencyPolicy(
    val maxGlobalInFlight: Int = 4,
) {
    init { require(maxGlobalInFlight > 0) { "maxGlobalInFlight must be > 0." } }
}
internal data class DispatchLeasePolicy(
    val leaseMs: Long = 30_000L,
) {
    init { require(leaseMs > 0) { "leaseMs must be > 0." } }
}

internal data class RetryPolicy(
    val initialDelayMs: Long = 1_000L,
    val multiplier: Int = 2,
    val maxDelayMs: Long = 60_000L,
    val jitterRatio: Double = 0.2,
    val maxAttempts: Int = 10,
) {
    init {
        require(initialDelayMs > 0) { "initialDelayMs must be > 0." }
        require(multiplier >= 1) { "multiplier must be >= 1." }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs." }
        require(jitterRatio >= 0.0) { "jitterRatio must be >= 0." }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1." }
    }

    fun delayAfterAttempt(attempt: Int, random: SyncRandom): Long {
        require(attempt >= 1) { "attempt must be >= 1." }
        var base = initialDelayMs
        repeat(attempt - 1) {
            base = min(maxDelayMs, base.saturatingMultiply(multiplier.toLong()))
        }
        val jitter = (base * jitterRatio * random.nextUnitDouble().coerceIn(0.0, 1.0)).toLong()
        return min(Long.MAX_VALUE - jitter, base) + jitter
    }
}

private fun Long.saturatingMultiply(other: Long): Long =
    if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private fun DeliveryState.isTerminal(): Boolean = when (this) {
    DeliveryState.Acked, DeliveryState.Rejected, DeliveryState.Failed, DeliveryState.Expired, DeliveryState.Cancelled -> true
    DeliveryState.Pending, DeliveryState.Dispatching, DeliveryState.RetryWait -> false
}










