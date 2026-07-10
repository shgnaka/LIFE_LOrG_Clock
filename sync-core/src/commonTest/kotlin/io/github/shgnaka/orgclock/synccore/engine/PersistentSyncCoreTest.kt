package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.claimedIncoming
import io.github.shgnaka.orgclock.synccore.api.CancelOutcome
import io.github.shgnaka.orgclock.synccore.api.AckIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerRevokeOutcome
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.RetryOutcome
import io.github.shgnaka.orgclock.synccore.api.StartOutcome
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingState
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreOutgoingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.MessageDirection
import io.github.shgnaka.orgclock.synccore.api.TopicAuthorization
import io.github.shgnaka.orgclock.synccore.api.TopicPolicy
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistentSyncCoreTest {
    private var now = 1_000L
    private val clock = SyncClock { now }

    @Test
    fun retentionPolicyDefaultsMatchDr01Baseline() {
        val policy = RetentionPolicy()

        assertEquals(7L * 24L * 60L * 60L * 1_000L, policy.terminalOutgoingTtlMs)
        assertEquals(7L * 24L * 60L * 60L * 1_000L, policy.terminalIncomingTtlMs)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, policy.deliveryEventTtlMs)
        assertEquals(20_000, policy.maxTerminalOutgoingRecords)
        assertEquals(100_000, policy.maxTerminalIncomingRecords)
        assertEquals(100_000, policy.maxDeliveryEvents)
    }

    @Test
    fun persistentStoreRestoresOutgoingAcrossCoreInstances() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )

        assertEquals(SubmitOutcome.Submitted, first.submit(message("cmd-1")))

        val second = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )

        assertEquals(listOf(MessageId("cmd-1")), second.listOutgoing(OutgoingQuery()).items.map { it.messageId })
        second.flushDue()
        assertEquals(1L, second.metricsSnapshot().acceptedTotal)
        assertEquals(0L, second.metricsSnapshot().queueDepth)
    }

    @Test
    fun persistentRetryWaitRestoresNextAttemptBoundary() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout)) },
        )
        assertEquals(SubmitOutcome.Submitted, first.submit(message("cmd-retry")))
        first.flushDue()
        val nextAttempt = first.listOutgoing(OutgoingQuery()).items.single().nextAttemptAtEpochMs!!
        var secondCalls = 0
        val second = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ ->
                secondCalls += 1
                DispatchOutcome.Accepted
            },
        )

        now = nextAttempt - 1
        val before = second.flushDue()
        now = nextAttempt
        val atBoundary = second.flushDue()

        assertEquals(0, before.dispatched)
        assertEquals(1, atBoundary.dispatched)
        assertEquals(1, secondCalls)
        val restored = second.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Acked, restored.state)
        assertEquals(2, restored.attempt)
        assertEquals(1L, second.metricsSnapshot().acceptedTotal)
    }

    @Test
    fun persistentStoreRestoresIncomingAcrossCoreInstances() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )

        assertEquals(IngressOutcome.Accepted, first.receiveVerified(envelope("cmd-in")))

        val second = SyncCoreFactory.createPersistent(store = store, clock = clock)
        val receipt = second.claimedIncoming().single()

        assertEquals(MessageId("cmd-in"), receipt.messageId)
        assertEquals(PeerId("peer-a"), receipt.senderPeerId)
        assertEquals(1, receipt.deliveryCount)
    }

    @Test
    fun persistentStoreRestoresIncomingRouteAcrossCoreInstances() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )

        assertEquals(IngressOutcome.Accepted, first.receiveVerified(envelope("cmd-route")))

        val second = SyncCoreFactory.createPersistent(store = store, clock = clock)
        val route = second.resolveIncomingRoute(MessageId("cmd-route"))

        requireNotNull(route)
        assertEquals("in-1", route.receiptId)
        assertEquals(MessageId("cmd-route"), route.messageId)
        assertEquals(PeerId("peer-a"), route.senderPeerId)
        assertEquals(Topic("clock.command.v1"), route.topic)
    }

    @Test
    fun persistentLoadFailureReturnsTypedStartFailure() = runTest {
        val core = SyncCoreFactory.createPersistent(
            store = RecordingSyncStore(loadError = SyncError(SyncErrorCode.StoreReadFailed, "boom")),
            clock = clock,
        )

        val outcome = core.start()

        val failed = kotlin.test.assertIs<StartOutcome.Failed>(outcome)
        assertEquals(SyncErrorCode.StoreReadFailed, failed.error.code)
    }

    @Test
    fun persistentSaveFailureReturnsTypedSubmitFailure() = runTest {
        val core = SyncCoreFactory.createPersistent(
            store = RecordingSyncStore(saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom")),
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )

        val outcome = core.submit(message("cmd-fail"))

        val failed = kotlin.test.assertIs<SubmitOutcome.Failed>(outcome)
        assertEquals(SyncErrorCode.StoreWriteFailed, failed.error.code)
        assertEquals(emptyList(), core.listOutgoing(OutgoingQuery()).items)
    }

    @Test
    fun persistentSaveFailureIncrementsPersistenceErrorMetricAndPersistsAfterRecovery() = runTest {
        val store = RecordingSyncStore(
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            saveFailures = 1,
        )
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
        )

        val failed = kotlin.test.assertIs<SubmitOutcome.Failed>(core.submit(message("cmd-fail-once")))
        assertEquals(SyncErrorCode.StoreWriteFailed, failed.error.code)
        assertEquals(1L, core.metricsSnapshot().persistenceErrorTotal)
        assertEquals(emptyList(), core.listOutgoing(OutgoingQuery()).items)

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-after-recovery")))

        val reopened = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
        )
        assertEquals(1L, reopened.metricsSnapshot().persistenceErrorTotal)
        assertEquals(listOf(MessageId("cmd-after-recovery")), reopened.listOutgoing(OutgoingQuery()).items.map { it.messageId })
    }

    @Test
    fun persistentSaveFailureReturnsTypedCancelFailureAndRollsBack() = runTest {
        val snapshot = SyncStoreSnapshot(
            outgoing = listOf(
                SyncStoreOutgoingRecord(
                    sequence = 1,
                    message = message("cmd-cancel"),
                    state = DeliveryState.Pending,
                    attempt = 0,
                    nextAttemptAtEpochMs = now,
                    lastError = null,
                    leaseUntilEpochMs = null,
                ),
            ),
            nextRecordSequence = 2,
        )
        val core = SyncCoreFactory.createPersistent(
            store = RecordingSyncStore(
                snapshot = snapshot,
                saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            ),
            clock = clock,
        )

        val outcome = core.cancel(MessageId("cmd-cancel"))

        val failed = kotlin.test.assertIs<CancelOutcome.Failed>(outcome)
        assertEquals(SyncErrorCode.StoreWriteFailed, failed.error.code)
        assertEquals(DeliveryState.Pending, core.listOutgoing(OutgoingQuery()).items.single().state)
    }

    @Test
    fun persistentSaveFailureReturnsTypedRetryFailureAndRollsBack() = runTest {
        val snapshot = SyncStoreSnapshot(
            outgoing = listOf(
                SyncStoreOutgoingRecord(
                    sequence = 1,
                    message = message("cmd-retry"),
                    state = DeliveryState.Failed,
                    attempt = 10,
                    nextAttemptAtEpochMs = null,
                    lastError = SyncError(SyncErrorCode.RetryExhausted),
                    leaseUntilEpochMs = null,
                ),
            ),
            nextRecordSequence = 2,
        )
        val core = SyncCoreFactory.createPersistent(
            store = RecordingSyncStore(
                snapshot = snapshot,
                saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            ),
            clock = clock,
        )

        val outcome = core.retry(MessageId("cmd-retry"))

        val failed = kotlin.test.assertIs<RetryOutcome.Failed>(outcome)
        assertEquals(SyncErrorCode.StoreWriteFailed, failed.error.code)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Failed, item.state)
        assertEquals(10, item.attempt)
    }

    @Test
    fun persistentSaveFailureReturnsIngressRetryLaterAndRollsBack() = runTest {
        val core = SyncCoreFactory.createPersistent(
            store = RecordingSyncStore(saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom")),
            clock = clock,
        )

        val outcome = core.receiveVerified(envelope("cmd-ingress-fail"))

        val retryLater = kotlin.test.assertIs<IngressOutcome.RetryLater>(outcome)
        assertEquals(503, retryLater.httpStatus)
        assertEquals(SyncErrorCode.StoreWriteFailed, retryLater.error.code)
        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun flushPersistsDispatchingLeaseBeforeTransportCall() = runTest {
        val store = RecordingSyncStore()
        val enteredTransport = CompletableDeferred<Unit>()
        val allowTransportReturn = CompletableDeferred<Unit>()
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ ->
                enteredTransport.complete(Unit)
                allowTransportReturn.await()
                DispatchOutcome.Accepted
            },
        )
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-lease")))

        val flushing = async { core.flushDue() }
        enteredTransport.await()

        val persisted = store.currentSnapshot().outgoing.single()
        assertEquals(DeliveryState.Dispatching, persisted.state)
        assertEquals(1, persisted.attempt)
        assertEquals(now + 30_000L, persisted.leaseUntilEpochMs)

        allowTransportReturn.complete(Unit)
        flushing.await()
        assertEquals(DeliveryState.Acked, store.currentSnapshot().outgoing.single().state)
        assertEquals(3, store.saveCallCount())
    }

    @Test
    fun flushDoesNotCallTransportWhenDispatchLeasePersistFails() = runTest {
        var transportCalls = 0
        val store = RecordingSyncStore(
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            saveFailures = 0,
            failOnSaveCalls = setOf(2),
        )
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ ->
                transportCalls += 1
                DispatchOutcome.Accepted
            },
        )
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-lease-fail")))

        val summary = core.flushDue()

        assertEquals(1, summary.persistenceFailures)
        assertEquals(SyncErrorCode.StoreWriteFailed, summary.lastPersistenceError?.code)
        assertEquals(0, summary.dispatched)
        assertEquals(0, transportCalls)
        assertEquals(DeliveryState.Pending, store.currentSnapshot().outgoing.single().state)
        assertEquals(0, store.currentSnapshot().outgoing.single().attempt)
        assertEquals(1L, core.metricsSnapshot().persistenceErrorTotal)
    }

    @Test
    fun flushRollsBackAcceptedOutcomeWhenResultPersistFails() = runTest {
        var transportCalls = 0
        val store = RecordingSyncStore(
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            saveFailures = 0,
            failOnSaveCalls = setOf(3),
        )
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ ->
                transportCalls += 1
                DispatchOutcome.Accepted
            },
        )
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-result-fail")))

        val summary = core.flushDue()

        assertEquals(1, summary.persistenceFailures)
        assertEquals(SyncErrorCode.StoreWriteFailed, summary.lastPersistenceError?.code)
        assertEquals(1, summary.dispatched)
        assertEquals(0, summary.accepted)
        assertEquals(1, transportCalls)
        val persisted = store.currentSnapshot().outgoing.single()
        assertEquals(DeliveryState.Dispatching, persisted.state)
        assertEquals(1, persisted.attempt)
        assertEquals(now + 30_000L, persisted.leaseUntilEpochMs)
        assertEquals(1L, core.metricsSnapshot().persistenceErrorTotal)
    }

    @Test
    fun claimIncomingRollsBackWhenPersistFails() = runTest {
        val store = RecordingSyncStore(
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            saveFailures = 0,
            failOnSaveCalls = setOf(2),
        )
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )
        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-claim-fail")))

        val failed = kotlin.test.assertIs<ClaimIncomingOutcome.Failed>(core.claimIncoming())

        assertEquals(SyncErrorCode.StoreWriteFailed, failed.error.code)
        val persisted = store.currentSnapshot().incoming.single()
        assertEquals(SyncStoreIncomingState.Available, persisted.state)
        assertEquals(0, persisted.deliveryCount)
        assertEquals(now, persisted.availableAtEpochMs)
        assertEquals(1L, core.metricsSnapshot().persistenceErrorTotal)
    }

    @Test
    fun ackIncomingRollsBackWhenPersistFails() = runTest {
        val store = RecordingSyncStore(
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            saveFailures = 0,
            failOnSaveCalls = setOf(3),
        )
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )
        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-ack-fail")))
        val receipt = core.claimedIncoming().single()

        val failed = kotlin.test.assertIs<AckIncomingOutcome.Failed>(
            core.ackIncoming(receipt.receiptId, IncomingProcessingOutcome.Processed),
        )

        assertEquals(SyncErrorCode.StoreWriteFailed, failed.error.code)
        val persisted = store.currentSnapshot().incoming.single()
        assertEquals(SyncStoreIncomingState.Claimed, persisted.state)
        assertEquals(now + 60_000L, persisted.availableAtEpochMs)
        assertEquals(1, persisted.deliveryCount)
        assertEquals(1L, core.metricsSnapshot().persistenceErrorTotal)
    }

    @Test
    fun incomingReplayConflictMetricPersistsAcrossCoreInstances() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )
        assertEquals(IngressOutcome.Accepted, first.receiveVerified(envelope("cmd-replay", payload = """{"op":"start"}""")))

        val rejected = kotlin.test.assertIs<IngressOutcome.Rejected>(
            first.receiveVerified(envelope("cmd-replay", payload = """{"op":"stop"}""", payloadSha256 = "sha-stop")),
        )
        assertEquals(SyncErrorCode.ReplayConflict, rejected.error.code)

        val second = SyncCoreFactory.createPersistent(store = store, clock = clock)
        assertEquals(1L, second.metricsSnapshot().incomingRejectedTotal)
        assertEquals("""{"op":"start"}""", second.claimedIncoming().single().payloadJson)
    }

    @Test
    fun incomingAuthorizationRejectMetricPersistsAcrossCoreInstances() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            topicPolicy = TopicPolicy { _: TrustedPeer, _: Topic, _: MessageDirection -> TopicAuthorization.Denied },
            trustedPeerResolver = trustedPeerResolver(),
        )

        val rejected = kotlin.test.assertIs<IngressOutcome.Rejected>(first.receiveVerified(envelope("cmd-denied")))
        assertEquals(SyncErrorCode.PeerNotAuthorized, rejected.error.code)

        val second = SyncCoreFactory.createPersistent(store = store, clock = clock)
        assertEquals(1L, second.metricsSnapshot().incomingRejectedTotal)
        assertEquals(0L, second.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun incomingInactivePeerRejectMetricPersistsAcrossCoreInstances() = runTest {
        val store = RecordingSyncStore()
        val first = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(active = false),
        )

        val rejected = kotlin.test.assertIs<IngressOutcome.Rejected>(first.receiveVerified(envelope("cmd-revoked")))
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)

        val second = SyncCoreFactory.createPersistent(store = store, clock = clock)
        assertEquals(1L, second.metricsSnapshot().incomingRejectedTotal)
        assertEquals(0L, second.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun peerRevocationRejectsActiveRowsDisablesRoutesAndRetainsHistory() = runTest {
        now = 10_000L
        val store = RecordingSyncStore(
            snapshot = SyncStoreSnapshot(
                outgoing = listOf(
                    outgoingRecord(sequence = 1, id = "cmd-pending", state = DeliveryState.Pending, targetPeerId = "peer-a"),
                    outgoingRecord(sequence = 2, id = "cmd-retry", state = DeliveryState.RetryWait, targetPeerId = "peer-a"),
                    outgoingRecord(sequence = 3, id = "cmd-acked", state = DeliveryState.Acked, targetPeerId = "peer-a", terminalAtEpochMs = now - 1_000L),
                    outgoingRecord(sequence = 4, id = "cmd-other", state = DeliveryState.Pending, targetPeerId = "peer-b"),
                ),
                incoming = listOf(
                    incomingRecord("cmd-in-active", SyncStoreIncomingState.Available, senderPeerId = "peer-a"),
                    incomingRecord("cmd-in-processed", SyncStoreIncomingState.Processed, senderPeerId = "peer-a", processedAtEpochMs = now - 1_000L),
                    incomingRecord("cmd-in-other", SyncStoreIncomingState.Available, senderPeerId = "peer-b"),
                ),
                nextRecordSequence = 5,
                nextReceiptSequence = 4,
            ),
        )
        val core = SyncCoreFactory.createPersistent(store = store, clock = clock)

        val outcome = kotlin.test.assertIs<PeerRevokeOutcome.Revoked>(core.revokePeer(PeerId("peer-a")))

        assertEquals(2, outcome.outgoingRejected)
        assertEquals(1, outcome.incomingRejected)
        val outgoing = store.currentSnapshot().outgoing.associateBy { it.message.messageId.value }
        assertEquals(DeliveryState.Rejected, outgoing.getValue("cmd-pending").state)
        assertEquals(DeliveryState.Rejected, outgoing.getValue("cmd-retry").state)
        assertEquals(DeliveryState.Acked, outgoing.getValue("cmd-acked").state)
        assertEquals(DeliveryState.Pending, outgoing.getValue("cmd-other").state)
        assertEquals(SyncErrorCode.PeerNotTrusted, outgoing.getValue("cmd-pending").lastError?.code)
        assertEquals(now, outgoing.getValue("cmd-pending").terminalAtEpochMs)
        assertEquals(
            listOf(MessageId("cmd-pending"), MessageId("cmd-retry")),
            store.currentSnapshot().deliveryEvents.map { it.messageId },
        )

        val incoming = store.currentSnapshot().incoming.associateBy { it.messageId.value }
        assertEquals(SyncStoreIncomingState.Rejected, incoming.getValue("cmd-in-active").state)
        assertEquals(SyncErrorCode.PeerNotTrusted, incoming.getValue("cmd-in-active").lastError?.code)
        assertEquals(now, incoming.getValue("cmd-in-active").processedAtEpochMs)
        assertEquals(SyncStoreIncomingState.Processed, incoming.getValue("cmd-in-processed").state)
        assertEquals(SyncStoreIncomingState.Available, incoming.getValue("cmd-in-other").state)
        assertEquals(null, core.resolveIncomingRoute(MessageId("cmd-in-active")))
        assertEquals(null, core.resolveIncomingRoute(MessageId("cmd-in-processed")))
        requireNotNull(core.resolveIncomingRoute(MessageId("cmd-in-other")))
        assertEquals(2L, core.metricsSnapshot().rejectedTotal)
        assertEquals(1L, core.metricsSnapshot().incomingRejectedTotal)
    }

    @Test
    fun incomingRejectMetricRollsBackWhenPersistFails() = runTest {
        val store = RecordingSyncStore(
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "boom"),
            saveFailures = 0,
            failOnSaveCalls = setOf(1),
        )
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            topicPolicy = TopicPolicy { _: TrustedPeer, _: Topic, _: MessageDirection -> TopicAuthorization.Denied },
            trustedPeerResolver = trustedPeerResolver(),
        )

        val outcome = kotlin.test.assertIs<IngressOutcome.RetryLater>(core.receiveVerified(envelope("cmd-denied-fail")))

        assertEquals(SyncErrorCode.StoreWriteFailed, outcome.error.code)
        assertEquals(0L, store.currentSnapshot().metrics.incomingRejectedTotal)
        assertEquals(emptyList(), store.currentSnapshot().incoming)
    }

    @Test
    fun persistentLoadTrimsDeliveryEventSnapshotToCapacity() = runTest {
        val store = RecordingSyncStore(
            snapshot = SyncStoreSnapshot(
                deliveryEvents = listOf(
                    event(sequence = 1, id = "cmd-1"),
                    event(sequence = 2, id = "cmd-2"),
                    event(sequence = 3, id = "cmd-3"),
                ),
                nextEventSequence = 4,
            ),
        )
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxDeliveryEventSnapshot = 2),
            durableStore = store,
        )

        val replayed = core.observeDeliveryEvents().take(2).toList()

        assertEquals(listOf(MessageId("cmd-2"), MessageId("cmd-3")), replayed.map { it.messageId })
        assertEquals(listOf(2L, 3L), replayed.map { it.sequence })
    }

    @Test
    fun outgoingTerminalTransitionPersistsTerminalAt() = runTest {
        val store = RecordingSyncStore()
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-terminal-at")))
        core.flushDue()

        val persisted = store.currentSnapshot().outgoing.single()
        assertEquals(DeliveryState.Acked, persisted.state)
        assertEquals(now, persisted.terminalAtEpochMs)
    }

    @Test
    fun incomingTerminalTransitionPersistsProcessedAt() = runTest {
        val store = RecordingSyncStore()
        val core = SyncCoreFactory.createPersistent(
            store = store,
            clock = clock,
            trustedPeerResolver = trustedPeerResolver(),
        )

        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-processed-at")))
        val receipt = core.claimedIncoming().single()
        core.ackIncoming(receipt.receiptId, IncomingProcessingOutcome.Processed)

        val persisted = store.currentSnapshot().incoming.single()
        assertEquals(SyncStoreIncomingState.Processed, persisted.state)
        assertEquals(now, persisted.processedAtEpochMs)
    }

    @Test
    fun retentionCleanupPrunesExpiredDeliveryEventsOnStart() = runTest {
        now = 10_000L
        val store = RecordingSyncStore(
            snapshot = SyncStoreSnapshot(
                deliveryEvents = listOf(
                    event(sequence = 1, id = "cmd-old", occurredAtEpochMs = now - 1_001L),
                    event(sequence = 2, id = "cmd-cutoff", occurredAtEpochMs = now - 1_000L),
                    event(sequence = 3, id = "cmd-current", occurredAtEpochMs = now),
                ),
                nextEventSequence = 4,
            ),
        )
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxDeliveryEventSnapshot = 10),
            retentionPolicy = RetentionPolicy(deliveryEventTtlMs = 1_000L, maxDeliveryEvents = 10),
            durableStore = store,
        )

        assertEquals(StartOutcome.Started, core.start())

        val retained = store.currentSnapshot().deliveryEvents
        assertEquals(listOf(MessageId("cmd-cutoff"), MessageId("cmd-current")), retained.map { it.messageId })
        assertEquals(listOf(2L, 3L), retained.map { it.sequence })
    }

    @Test
    fun retentionCleanupSaveFailureDoesNotBlockStartAndRollsBackSnapshot() = runTest {
        now = 10_000L
        val store = RecordingSyncStore(
            snapshot = SyncStoreSnapshot(
                deliveryEvents = listOf(event(sequence = 1, id = "cmd-old", occurredAtEpochMs = now - 1_001L)),
                nextEventSequence = 2,
            ),
            saveError = SyncError(SyncErrorCode.StoreWriteFailed, "cleanup failed"),
            saveFailures = 0,
            failOnSaveCalls = setOf(1),
        )
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxDeliveryEventSnapshot = 10),
            retentionPolicy = RetentionPolicy(deliveryEventTtlMs = 1_000L, maxDeliveryEvents = 10),
            durableStore = store,
        )

        assertEquals(StartOutcome.Started, core.start())

        assertEquals(listOf(MessageId("cmd-old")), store.currentSnapshot().deliveryEvents.map { it.messageId })
        assertEquals(1L, core.metricsSnapshot().persistenceErrorTotal)
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-after-cleanup-failure")))
    }

    @Test
    fun retentionCleanupPrunesExpiredTerminalOutgoingAndIncomingOnStart() = runTest {
        now = 10_000L
        val store = RecordingSyncStore(
            snapshot = SyncStoreSnapshot(
                outgoing = listOf(
                    outgoingRecord(sequence = 1, id = "cmd-old-out", state = DeliveryState.Acked, terminalAtEpochMs = now - 1_001L),
                    outgoingRecord(sequence = 2, id = "cmd-cutoff-out", state = DeliveryState.Acked, terminalAtEpochMs = now - 1_000L),
                    outgoingRecord(sequence = 3, id = "cmd-active-out", state = DeliveryState.Pending, terminalAtEpochMs = null),
                ),
                incoming = listOf(
                    incomingRecord("cmd-old-in", SyncStoreIncomingState.Processed, processedAtEpochMs = now - 1_001L),
                    incomingRecord("cmd-cutoff-in", SyncStoreIncomingState.Rejected, processedAtEpochMs = now - 1_000L),
                    incomingRecord("cmd-active-in", SyncStoreIncomingState.Available, processedAtEpochMs = null),
                ),
                nextRecordSequence = 4,
                nextReceiptSequence = 4,
            ),
        )
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxDeliveryEventSnapshot = 10),
            retentionPolicy = RetentionPolicy(
                terminalOutgoingTtlMs = 1_000L,
                terminalIncomingTtlMs = 1_000L,
                deliveryEventTtlMs = 30_000L,
                maxTerminalOutgoingRecords = 10,
                maxTerminalIncomingRecords = 10,
                maxDeliveryEvents = 10,
            ),
            durableStore = store,
        )

        assertEquals(StartOutcome.Started, core.start())

        assertEquals(
            listOf(MessageId("cmd-cutoff-out"), MessageId("cmd-active-out")),
            store.currentSnapshot().outgoing.map { it.message.messageId },
        )
        assertEquals(
            listOf(MessageId("cmd-cutoff-in"), MessageId("cmd-active-in")),
            store.currentSnapshot().incoming.map { it.messageId },
        )
    }

    @Test
    fun retentionCleanupCapsOldestEligibleTerminalOutgoingAndIncomingOnStart() = runTest {
        now = 10_000L
        val store = RecordingSyncStore(
            snapshot = SyncStoreSnapshot(
                outgoing = listOf(
                    outgoingRecord(sequence = 1, id = "cmd-oldest-out", state = DeliveryState.Acked, terminalAtEpochMs = now - 3_000L),
                    outgoingRecord(sequence = 2, id = "cmd-newest-out", state = DeliveryState.Acked, terminalAtEpochMs = now - 2_000L),
                    outgoingRecord(sequence = 3, id = "cmd-active-cap-out", state = DeliveryState.Pending, terminalAtEpochMs = null),
                ),
                incoming = listOf(
                    incomingRecord("cmd-oldest-in", SyncStoreIncomingState.Processed, processedAtEpochMs = now - 3_000L),
                    incomingRecord("cmd-newest-in", SyncStoreIncomingState.Rejected, processedAtEpochMs = now - 2_000L),
                    incomingRecord("cmd-active-cap-in", SyncStoreIncomingState.Available, processedAtEpochMs = null),
                ),
                nextRecordSequence = 4,
                nextReceiptSequence = 4,
            ),
        )
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxDeliveryEventSnapshot = 10),
            retentionPolicy = RetentionPolicy(
                terminalOutgoingTtlMs = 30_000L,
                terminalIncomingTtlMs = 30_000L,
                deliveryEventTtlMs = 30_000L,
                maxTerminalOutgoingRecords = 1,
                maxTerminalIncomingRecords = 1,
                maxDeliveryEvents = 10,
            ),
            durableStore = store,
        )

        assertEquals(StartOutcome.Started, core.start())

        assertEquals(
            listOf(MessageId("cmd-newest-out"), MessageId("cmd-active-cap-out")),
            store.currentSnapshot().outgoing.map { it.message.messageId },
        )
        assertEquals(
            listOf(MessageId("cmd-newest-in"), MessageId("cmd-active-cap-in")),
            store.currentSnapshot().incoming.map { it.messageId },
        )
    }

    private fun message(id: String) = io.github.shgnaka.orgclock.synccore.api.SyncMessage(
        messageId = MessageId(id),
        topic = Topic("clock.command.v1"),
        payloadJson = """{"op":"start"}""",
        targetPeerId = PeerId("peer-a"),
        createdAtEpochMs = now,
        expiresAtEpochMs = now + 10_000L,
    )

    private fun outgoingRecord(
        sequence: Long,
        id: String,
        state: DeliveryState,
        terminalAtEpochMs: Long? = null,
        targetPeerId: String = "peer-a",
    ) = SyncStoreOutgoingRecord(
        sequence = sequence,
        message = message(id).copy(targetPeerId = PeerId(targetPeerId)),
        state = state,
        attempt = 0,
        nextAttemptAtEpochMs = if (state == DeliveryState.Pending || state == DeliveryState.RetryWait) now else null,
        lastError = null,
        leaseUntilEpochMs = null,
        terminalAtEpochMs = terminalAtEpochMs,
    )

    private fun incomingRecord(
        id: String,
        state: SyncStoreIncomingState,
        processedAtEpochMs: Long? = null,
        senderPeerId: String = "peer-a",
    ) = SyncStoreIncomingRecord(
        receiptId = "in-$id",
        senderPeerId = PeerId(senderPeerId),
        targetPeerId = PeerId("peer-b"),
        messageId = MessageId(id),
        topic = Topic("clock.command.v1"),
        payloadJson = """{"op":"start"}""",
        payloadSha256 = "sha-$id",
        receivedAtEpochMs = now - 5_000L,
        state = state,
        availableAtEpochMs = if (state == SyncStoreIncomingState.Available) now else null,
        deliveryCount = 1,
        lastError = null,
        processedAtEpochMs = processedAtEpochMs,
    )

    private fun envelope(
        id: String,
        payload: String = """{"op":"start"}""",
        payloadSha256: String = "sha-$id",
    ) = VerifiedEnvelope(
        schemaVersion = 1,
        envelopeId = "env-$id",
        messageId = MessageId(id),
        senderPeerId = PeerId("peer-a"),
        targetPeerId = PeerId("peer-b"),
        topic = Topic("clock.command.v1"),
        payloadJson = payload,
        sentAtEpochMs = now,
        nonce = "nonce-$id",
        payloadSha256 = payloadSha256,
    )

    private fun event(sequence: Long, id: String, occurredAtEpochMs: Long = now) = DeliveryEvent(
        sequence = sequence,
        messageId = MessageId(id),
        peerId = PeerId("peer-a"),
        topic = Topic("clock.command.v1"),
        state = DeliveryState.Pending,
        attempt = 0,
        occurredAtEpochMs = occurredAtEpochMs,
        error = null,
    )

    private fun trustedPeerResolver(active: Boolean = true) = TrustedPeerResolver { peerId ->
        trustedPeer(peerId = peerId, active = active)
    }

    private fun trustedPeer(peerId: PeerId = PeerId("peer-a"), active: Boolean = true) = TrustedPeer(
        peerId = peerId,
        deviceId = "device-${peerId.value}",
        displayName = "Peer ${peerId.value}",
        signingPublicKeyBase64 = "test-key",
        role = PeerRole.Full,
        endpoint = null,
        active = active,
    )
}

private class RecordingSyncStore(
    private var snapshot: SyncStoreSnapshot = SyncStoreSnapshot(),
    private val loadError: SyncError? = null,
    private val saveError: SyncError? = null,
    private var saveFailures: Int = Int.MAX_VALUE,
    private val failOnSaveCalls: Set<Int> = emptySet(),
) : SyncStore {
    private var saveCalls = 0

    override suspend fun load(): SyncStoreLoadResult = loadError?.let { SyncStoreLoadResult.Failed(it) }
        ?: SyncStoreLoadResult.Loaded(snapshot)

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult {
        saveCalls += 1
        if (saveCalls in failOnSaveCalls) {
            return SyncStoreSaveResult.Failed(saveError ?: SyncError(SyncErrorCode.StoreWriteFailed, "configured save failure"))
        }
        saveError?.let {
            if (saveFailures > 0) {
                saveFailures -= 1
                return SyncStoreSaveResult.Failed(it)
            }
        }
        this.snapshot = snapshot
        return SyncStoreSaveResult.Saved
    }

    override suspend fun healthCheck(): StoreHealth = StoreHealth.Healthy

    fun currentSnapshot(): SyncStoreSnapshot = snapshot

    fun saveCallCount(): Int = saveCalls
}






