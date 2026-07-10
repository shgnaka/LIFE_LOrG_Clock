package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.claimedIncoming
import io.github.shgnaka.orgclock.synccore.api.CancelOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.RetryOutcome
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncRandom
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class InMemorySyncCoreTest {
    private var now = 1_000L
    private val clock = SyncClock { now }

    @Test
    fun submitStoresPendingMessage() = runTest {
        val core = SyncCoreFactory.createInMemory(clock)
        val message = message("cmd-1")

        assertEquals(SubmitOutcome.Submitted, core.submit(message))

        val page = core.listOutgoing(OutgoingQuery())
        assertEquals(1, page.items.size)
        assertEquals(MessageId("cmd-1"), page.items.single().messageId)
        assertEquals(DeliveryState.Pending, page.items.single().state)
        assertEquals(1L, core.metricsSnapshot().submittedTotal)
        assertEquals(1L, core.metricsSnapshot().queueDepth)
    }

    @Test
    fun submitSameMessageIsIdempotent() = runTest {
        val core = SyncCoreFactory.createInMemory(clock)
        val message = message("cmd-1")

        assertEquals(SubmitOutcome.Submitted, core.submit(message))
        assertEquals(SubmitOutcome.AlreadySubmitted, core.submit(message))

        assertEquals(1, core.listOutgoing(OutgoingQuery()).items.size)
        assertEquals(1L, core.metricsSnapshot().submittedTotal)
    }

    @Test
    fun submitSameIdWithDifferentContentIsConflict() = runTest {
        val core = SyncCoreFactory.createInMemory(clock)

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-1", payload = """{"op":"start"}""")))
        val rejected = core.submit(message("cmd-1", payload = """{"op":"stop"}"""))

        val conflict = assertIs<SubmitOutcome.Rejected>(rejected)
        assertEquals(SyncErrorCode.MessageIdConflict, conflict.error.code)
        assertEquals(1, core.listOutgoing(OutgoingQuery()).items.size)
    }

    @Test
    fun outgoingCapacityRejectsWithoutEvictingActiveRows() = runTest {
        val totalCore = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxPendingOutgoingTotal = 2, maxPendingOutgoingPerPeer = 2),
        )
        assertEquals(SubmitOutcome.Submitted, totalCore.submit(message("cmd-1", targetPeerId = "peer-a")))
        assertEquals(SubmitOutcome.Submitted, totalCore.submit(message("cmd-2", targetPeerId = "peer-b")))
        val totalRejected = assertIs<SubmitOutcome.Rejected>(
            totalCore.submit(message("cmd-3", targetPeerId = "peer-c")),
        )
        assertEquals(SyncErrorCode.QueueFull, totalRejected.error.code)
        assertEquals(listOf(MessageId("cmd-1"), MessageId("cmd-2")), totalCore.listOutgoing(OutgoingQuery()).items.map { it.messageId })

        val peerCore = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxPendingOutgoingTotal = 10, maxPendingOutgoingPerPeer = 1),
        )
        assertEquals(SubmitOutcome.Submitted, peerCore.submit(message("cmd-1", targetPeerId = "peer-a")))
        val peerRejected = assertIs<SubmitOutcome.Rejected>(
            peerCore.submit(message("cmd-2", targetPeerId = "peer-a")),
        )
        assertEquals(SyncErrorCode.QueueFull, peerRejected.error.code)
        assertEquals(1, peerCore.listOutgoing(OutgoingQuery()).items.size)
    }

    @Test
    fun terminalOutgoingRowsDoNotCountAgainstCapacity() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxPendingOutgoingTotal = 1, maxPendingOutgoingPerPeer = 1),
        )
        core.submit(message("cmd-1"))
        core.flushDue()

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-2")))
        assertEquals(
            listOf(DeliveryState.Acked, DeliveryState.Pending),
            core.listOutgoing(OutgoingQuery()).items.map { it.state },
        )
    }

    @Test
    fun incomingCapacityReturnsRetryLaterWithoutEvictingInbox() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxUnprocessedIncoming = 1, capacityRetryAfterSeconds = 30),
            trustedPeerResolver = trustedPeerResolver(),
        )
        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-1")))

        val rejected = assertIs<IngressOutcome.RetryLater>(core.receiveVerified(envelope("cmd-2")))

        assertEquals(503, rejected.httpStatus)
        assertEquals(30, rejected.retryAfterSeconds)
        assertEquals(SyncErrorCode.InboxFull, rejected.error.code)
        assertEquals(1L, core.metricsSnapshot().inboxUnprocessedDepth)
        assertEquals(MessageId("cmd-1"), core.claimedIncoming().single().messageId)
    }

    @Test
    fun defaultOutgoingCapacityBoundariesRejectWithoutEvictingActiveRows() = runTest {
        val totalCore = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )
        repeat(2_000) { index ->
            val peerIndex = index / 500
            assertEquals(
                SubmitOutcome.Submitted,
                totalCore.submit(message("cmd-total-${index + 1}", targetPeerId = "peer-$peerIndex")),
            )
        }

        val totalRejected = assertIs<SubmitOutcome.Rejected>(
            totalCore.submit(message("cmd-total-overflow", targetPeerId = "peer-overflow")),
        )

        assertEquals(SyncErrorCode.QueueFull, totalRejected.error.code)
        assertEquals(2_000L, totalCore.metricsSnapshot().queueDepth)
        assertEquals(MessageId("cmd-total-1"), totalCore.listOutgoing(OutgoingQuery(limit = 500)).items.first().messageId)

        val peerCore = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )
        repeat(500) { index ->
            assertEquals(
                SubmitOutcome.Submitted,
                peerCore.submit(message("cmd-peer-${index + 1}", targetPeerId = "peer-a")),
            )
        }

        val peerRejected = assertIs<SubmitOutcome.Rejected>(
            peerCore.submit(message("cmd-peer-overflow", targetPeerId = "peer-a")),
        )

        assertEquals(SyncErrorCode.QueueFull, peerRejected.error.code)
        assertEquals(500L, peerCore.metricsSnapshot().queueDepth)
        assertEquals(MessageId("cmd-peer-1"), peerCore.listOutgoing(OutgoingQuery(limit = 500)).items.first().messageId)
        assertEquals(
            SubmitOutcome.Submitted,
            peerCore.submit(message("cmd-peer-other", targetPeerId = "peer-b")),
        )
        assertEquals(501L, peerCore.metricsSnapshot().queueDepth)
    }

    @Test
    fun defaultInboxCapacityBoundaryReturnsRetryLaterWithoutEvictingActiveRows() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = trustedPeerResolver(),
        )
        repeat(2_000) { index ->
            assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-in-${index + 1}")))
        }

        val rejected = assertIs<IngressOutcome.RetryLater>(core.receiveVerified(envelope("cmd-in-overflow")))

        assertEquals(503, rejected.httpStatus)
        assertEquals(60, rejected.retryAfterSeconds)
        assertEquals(SyncErrorCode.InboxFull, rejected.error.code)
        assertEquals(2_000L, core.metricsSnapshot().inboxUnprocessedDepth)
        assertEquals(MessageId("cmd-in-1"), core.claimedIncoming(limit = 500).first().messageId)
        assertEquals(2_000L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun concurrentOutgoingWritersCannotExceedTotalCapacity() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxPendingOutgoingTotal = 2, maxPendingOutgoingPerPeer = 10),
        )
        val release = CompletableDeferred<Unit>()
        val writers = (1..4).map { index ->
            async {
                release.await()
                core.submit(message("cmd-$index", targetPeerId = "peer-$index"))
            }
        }

        release.complete(Unit)
        val outcomes = writers.map { it.await() }

        assertEquals(2, outcomes.count { it == SubmitOutcome.Submitted })
        assertEquals(
            2,
            outcomes.count { it is SubmitOutcome.Rejected && it.error.code == SyncErrorCode.QueueFull },
        )
        val committed = core.listOutgoing(OutgoingQuery(limit = 10)).items
        assertEquals(2, committed.size)
        assertEquals(2, committed.map { it.messageId }.distinct().size)
        assertEquals(2L, core.metricsSnapshot().queueDepth)
    }

    @Test
    fun concurrentOutgoingWritersCannotExceedPerPeerCapacity() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxPendingOutgoingTotal = 10, maxPendingOutgoingPerPeer = 2),
        )
        val release = CompletableDeferred<Unit>()
        val writers = (1..4).map { index ->
            async {
                release.await()
                core.submit(message("cmd-$index", targetPeerId = "peer-a"))
            }
        }

        release.complete(Unit)
        val outcomes = writers.map { it.await() }

        assertEquals(2, outcomes.count { it == SubmitOutcome.Submitted })
        assertEquals(
            2,
            outcomes.count { it is SubmitOutcome.Rejected && it.error.code == SyncErrorCode.QueueFull },
        )
        val committed = core.listOutgoing(OutgoingQuery(limit = 10)).items
        assertEquals(2, committed.size)
        assertEquals(setOf(PeerId("peer-a")), committed.map { it.peerId }.toSet())
        assertEquals(2L, core.metricsSnapshot().queueDepth)
    }

    @Test
    fun concurrentIncomingWritersCannotExceedInboxCapacity() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxUnprocessedIncoming = 2, capacityRetryAfterSeconds = 30),
            trustedPeerResolver = trustedPeerResolver(),
        )
        val release = CompletableDeferred<Unit>()
        val writers = (1..4).map { index ->
            async {
                release.await()
                core.receiveVerified(envelope("cmd-$index"))
            }
        }

        release.complete(Unit)
        val outcomes = writers.map { it.await() }

        assertEquals(2, outcomes.count { it == IngressOutcome.Accepted })
        assertEquals(
            2,
            outcomes.count { it is IngressOutcome.RetryLater && it.error.code == SyncErrorCode.InboxFull },
        )
        val committed = core.claimedIncoming(limit = 10)
        assertEquals(2, committed.size)
        assertEquals(2, committed.map { it.messageId }.distinct().size)
        assertEquals(2L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun flushAcceptedMessageTransitionsToAcked() = runTest {
        val core = SyncCoreFactory.createInMemory(clock)
        core.submit(message("cmd-1"))

        val summary = core.flushDue()

        assertEquals(1, summary.accepted)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Acked, item.state)
        assertEquals(1, item.attempt)
        assertEquals(1L, core.metricsSnapshot().acceptedTotal)
        assertEquals(0L, core.metricsSnapshot().queueDepth)
    }

    @Test
    fun flushRejectedMessageTransitionsToRejected() = runTest {
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "no trust")) },
        )
        core.submit(message("cmd-1"))

        val summary = core.flushDue()

        assertEquals(1, summary.rejected)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Rejected, item.state)
        assertEquals(SyncErrorCode.PeerNotTrusted, item.lastError?.code)
        assertEquals(1L, core.metricsSnapshot().rejectedTotal)
    }

    @Test
    fun retryableFailureSchedulesBoundedBackoffWithJitter() = runTest {
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            random = SyncRandom { 0.5 },
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout, "timeout")) },
        )
        core.submit(message("cmd-1"))

        val summary = core.flushDue()

        assertEquals(1, summary.retryScheduled)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.RetryWait, item.state)
        assertEquals(1, item.attempt)
        assertEquals(2_100L, item.nextAttemptAtEpochMs)
        assertEquals(SyncErrorCode.Timeout, item.lastError?.code)
        assertEquals(2_100L, item.lastError?.retryAtEpochMs)
        assertEquals(1L, core.metricsSnapshot().retryAttemptsTotal)
    }

    @Test
    fun retryWaitIsNotDispatchedBeforeNextAttemptBoundary() = runTest {
        var calls = 0
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ ->
                calls += 1
                DispatchOutcome.Retryable(SyncError(SyncErrorCode.NetworkUnreachable))
            },
        )
        core.submit(message("cmd-1"))
        core.flushDue()

        now = 1_999L
        val before = core.flushDue()
        now = 2_000L
        val atBoundary = core.flushDue()

        assertEquals(0, before.dispatched)
        assertEquals(1, atBoundary.dispatched)
        assertEquals(2, calls)
        assertEquals(2, core.listOutgoing(OutgoingQuery()).items.single().attempt)
    }

    @Test
    fun samePeerMessagesDoNotOvertakeRetryWaitHead() = runTest {
        val calls = mutableListOf<String>()
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { message, attempt ->
                calls += "${message.messageId.value}:$attempt"
                if (message.messageId == MessageId("cmd-1") && attempt == 1) {
                    DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout))
                } else {
                    DispatchOutcome.Accepted
                }
            },
        )
        core.submit(message("cmd-1", targetPeerId = "peer-a"))
        core.submit(message("cmd-2", targetPeerId = "peer-a"))
        core.submit(message("cmd-3", targetPeerId = "peer-a"))

        val first = core.flushDue()

        assertEquals(1, first.dispatched)
        assertEquals(listOf("cmd-1:1"), calls)
        assertEquals(
            listOf(DeliveryState.RetryWait, DeliveryState.Pending, DeliveryState.Pending),
            core.listOutgoing(OutgoingQuery()).items.map { it.state },
        )

        now = core.listOutgoing(OutgoingQuery()).items.first().nextAttemptAtEpochMs!!
        val second = core.flushDue()

        assertEquals(3, second.dispatched)
        assertEquals(listOf("cmd-1:1", "cmd-1:2", "cmd-2:1", "cmd-3:1"), calls)
        assertEquals(
            listOf(DeliveryState.Acked, DeliveryState.Acked, DeliveryState.Acked),
            core.listOutgoing(OutgoingQuery()).items.map { it.state },
        )
    }

    @Test
    fun retryWaitHeadDoesNotBlockDifferentPeer() = runTest {
        val calls = mutableListOf<String>()
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { message, _ ->
                calls += message.messageId.value
                if (message.messageId == MessageId("cmd-1")) {
                    DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout))
                } else {
                    DispatchOutcome.Accepted
                }
            },
        )
        core.submit(message("cmd-1", targetPeerId = "peer-a"))
        core.submit(message("cmd-2", targetPeerId = "peer-b"))

        val summary = core.flushDue()

        assertEquals(2, summary.dispatched)
        assertEquals(listOf("cmd-1", "cmd-2"), calls)
        assertEquals(
            listOf(DeliveryState.RetryWait, DeliveryState.Acked),
            core.listOutgoing(OutgoingQuery()).items.map { it.state },
        )
    }
    @Test
    fun differentPeersDispatchWithGlobalInFlightLimitFour() = runTest {
        var active = 0
        var maxActive = 0
        val started = mutableListOf<MessageId>()
        val firstWaveEntered = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { message, _ ->
                started += message.messageId
                active += 1
                maxActive = maxOf(maxActive, active)
                if (active == 4) firstWaveEntered.complete(Unit)
                allowReturn.await()
                active -= 1
                DispatchOutcome.Accepted
            },
        )
        repeat(5) { index ->
            core.submit(message("cmd-${index + 1}", targetPeerId = "peer-${index + 1}"))
        }

        val flushing = async { core.flushDue() }
        firstWaveEntered.await()

        assertEquals(4, maxActive)
        assertEquals(
            listOf(MessageId("cmd-1"), MessageId("cmd-2"), MessageId("cmd-3"), MessageId("cmd-4")),
            started.toList(),
        )

        allowReturn.complete(Unit)
        val summary = flushing.await()

        assertEquals(5, summary.dispatched)
        assertEquals(5, summary.accepted)
        assertEquals(4, maxActive)
        assertEquals(
            listOf(DeliveryState.Acked, DeliveryState.Acked, DeliveryState.Acked, DeliveryState.Acked, DeliveryState.Acked),
            core.listOutgoing(OutgoingQuery()).items.map { it.state },
        )
    }
    @Test
    fun tenthRetryableAttemptFailsWithRetryExhausted() = runTest {
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.RemoteBusy, "busy")) },
        )
        core.submit(message("cmd-1", expiresAt = 1_000_000L))

        repeat(9) { attemptIndex ->
            val summary = core.flushDue()
            assertEquals(1, summary.retryScheduled, "attempt ${attemptIndex + 1}")
            now = core.listOutgoing(OutgoingQuery()).items.single().nextAttemptAtEpochMs!!
        }
        val exhausted = core.flushDue()

        assertEquals(1, exhausted.failed)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Failed, item.state)
        assertEquals(10, item.attempt)
        assertEquals(SyncErrorCode.RetryExhausted, item.lastError?.code)
    }

    @Test
    fun expiredMessageIsNotDispatched() = runTest {
        var calls = 0
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ ->
                calls += 1
                DispatchOutcome.Accepted
            },
        )
        core.submit(message("cmd-1", createdAt = 100L, expiresAt = 1_000L))

        val summary = core.flushDue()

        assertEquals(1, summary.expired)
        assertEquals(0, summary.dispatched)
        assertEquals(0, calls)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Expired, item.state)
        assertEquals(SyncErrorCode.Expired, item.lastError?.code)
    }

    @Test
    fun retryableResponseAfterExpirationBecomesExpiredWithoutRetry() = runTest {
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ ->
                now = 1_500L
                DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout))
            },
        )
        core.submit(message("cmd-1", createdAt = 100L, expiresAt = 1_200L))

        val summary = core.flushDue()

        assertEquals(1, summary.expired)
        assertEquals(0, summary.retryScheduled)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Expired, item.state)
        assertEquals(1, item.attempt)
    }

    @Test
    fun manualRetryResetsFailedMessageAttempt() = runTest {
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.RemoteBusy)) },
        )
        core.submit(message("cmd-1", expiresAt = 1_000_000L))
        repeat(9) {
            core.flushDue()
            now = core.listOutgoing(OutgoingQuery()).items.single().nextAttemptAtEpochMs!!
        }
        core.flushDue()

        assertEquals(RetryOutcome.Scheduled, core.retry(MessageId("cmd-1")))

        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Pending, item.state)
        assertEquals(0, item.attempt)
        assertEquals(now, item.nextAttemptAtEpochMs)
        assertEquals(null, item.lastError)
    }

    @Test
    fun cancelPendingRetryWaitDispatchingAndTerminalStates() = runTest {
        val pendingCore = SyncCoreFactory.createInMemory(clock)
        pendingCore.submit(message("pending"))
        assertEquals(CancelOutcome.Cancelled, pendingCore.cancel(MessageId("pending")))
        assertEquals(DeliveryState.Cancelled, pendingCore.listOutgoing(OutgoingQuery()).items.single().state)

        val retryCore = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout)) },
        )
        retryCore.submit(message("retry"))
        retryCore.flushDue()
        assertEquals(CancelOutcome.Cancelled, retryCore.cancel(MessageId("retry")))
        assertEquals(DeliveryState.Cancelled, retryCore.listOutgoing(OutgoingQuery()).items.single().state)

        val enteredDispatch = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        val dispatchCore = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ ->
                enteredDispatch.complete(Unit)
                allowReturn.await()
                DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout))
            },
        )
        dispatchCore.submit(message("dispatch"))
        val flushing = async { dispatchCore.flushDue() }
        enteredDispatch.await()
        assertEquals(CancelOutcome.Cancelled, dispatchCore.cancel(MessageId("dispatch")))
        allowReturn.complete(Unit)
        val summary = flushing.await()
        assertEquals(0, summary.retryScheduled)
        assertEquals(DeliveryState.Cancelled, dispatchCore.listOutgoing(OutgoingQuery()).items.single().state)

        val terminalCore = SyncCoreFactory.createInMemory(clock)
        terminalCore.submit(message("acked"))
        terminalCore.flushDue()
        assertEquals(CancelOutcome.AlreadyTerminal, terminalCore.cancel(MessageId("acked")))
    }

    @Test
    fun observeDeliveryEventsReplaysHistoryAfterSequence() = runTest {
        val core = SyncCoreFactory.createInMemory(clock)

        core.submit(message("cmd-1"))
        core.submit(message("cmd-2"))

        val allEvents = core.observeDeliveryEvents().take(2).toList()
        assertEquals(listOf(MessageId("cmd-1"), MessageId("cmd-2")), allEvents.map { it.messageId })
        assertEquals(listOf(DeliveryState.Pending, DeliveryState.Pending), allEvents.map { it.state })

        val afterFirst = core.observeDeliveryEvents(afterSequence = allEvents.first().sequence).take(1).toList()
        assertEquals(MessageId("cmd-2"), afterFirst.single().messageId)
    }

    @Test
    fun deliveryEventSnapshotEvictsOldestEventsAtCapacity() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            capacityPolicy = CapacityPolicy(maxDeliveryEventSnapshot = 3),
        )

        repeat(4) { index ->
            core.submit(message("cmd-${index + 1}"))
        }

        val replayed = core.observeDeliveryEvents().take(3).toList()

        assertEquals(
            listOf(MessageId("cmd-2"), MessageId("cmd-3"), MessageId("cmd-4")),
            replayed.map { it.messageId },
        )
        assertEquals(listOf(2L, 3L, 4L), replayed.map { it.sequence })
    }

    @Test
    fun slowAndFailingDeliveryObserversDoNotStopDispatch() = runTest {
        val core = SyncCoreFactory.createInMemory(clock)
        val slowObserverStarted = CompletableDeferred<Unit>()
        val releaseSlowObserver = CompletableDeferred<Unit>()
        val slowObserver = launch {
            core.observeDeliveryEvents().collect {
                slowObserverStarted.complete(Unit)
                releaseSlowObserver.await()
            }
        }

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-slow-observer")))
        slowObserverStarted.await()
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-while-observer-suspended")))
        val firstFlush = core.flushDue()
        assertEquals(2, firstFlush.accepted)
        releaseSlowObserver.complete(Unit)
        slowObserver.cancel()

        val failingObserver = async {
            runCatching {
                core.observeDeliveryEvents(afterSequence = core.observeDeliveryEvents().take(1).toList().single().sequence).collect {
                    throw IllegalStateException("observer failed")
                }
            }.exceptionOrNull()
        }

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-failing-observer")))
        val failure = assertIs<IllegalStateException>(failingObserver.await())
        assertEquals("observer failed", failure.message)
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-after-observer-failure")))
        val secondFlush = core.flushDue()
        assertEquals(2, secondFlush.accepted)

        val replayed = core.observeDeliveryEvents().take(12).toList()
        assertEquals(
            listOf(
                MessageId("cmd-slow-observer"),
                MessageId("cmd-while-observer-suspended"),
                MessageId("cmd-slow-observer"),
                MessageId("cmd-slow-observer"),
                MessageId("cmd-while-observer-suspended"),
                MessageId("cmd-while-observer-suspended"),
                MessageId("cmd-failing-observer"),
                MessageId("cmd-after-observer-failure"),
                MessageId("cmd-failing-observer"),
                MessageId("cmd-failing-observer"),
                MessageId("cmd-after-observer-failure"),
                MessageId("cmd-after-observer-failure"),
            ),
            replayed.map { it.messageId },
        )
        assertEquals(
            listOf(
                DeliveryState.Pending,
                DeliveryState.Pending,
                DeliveryState.Dispatching,
                DeliveryState.Acked,
                DeliveryState.Dispatching,
                DeliveryState.Acked,
                DeliveryState.Pending,
                DeliveryState.Pending,
                DeliveryState.Dispatching,
                DeliveryState.Acked,
                DeliveryState.Dispatching,
                DeliveryState.Acked,
            ),
            replayed.map { it.state },
        )
    }

    @Test
    fun topicPolicyAllowsIncomingBeforePersistence() = runTest {
        val calls = mutableListOf<Pair<PeerId, Topic>>()
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            topicPolicy = TopicPolicy { peer, topic, direction ->
                assertEquals(MessageDirection.Incoming, direction)
                calls += peer.peerId to topic
                TopicAuthorization.Allowed
            },
            trustedPeerResolver = trustedPeerResolver(),
        )

        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-1")))

        assertEquals(listOf(PeerId("peer-a") to Topic("clock.command.v1")), calls)
        assertEquals(MessageId("cmd-1"), core.claimedIncoming().single().messageId)
    }

    @Test
    fun topicPolicyRejectsIncomingBeforePersistence() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            topicPolicy = TopicPolicy { _: TrustedPeer, _: Topic, _: MessageDirection -> TopicAuthorization.Denied },
            trustedPeerResolver = trustedPeerResolver(),
        )

        val rejected = assertIs<IngressOutcome.Rejected>(core.receiveVerified(envelope("cmd-1")))

        assertEquals(403, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotAuthorized, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
        assertEquals(1L, core.metricsSnapshot().incomingRejectedTotal)
    }

    @Test
    fun inactiveTrustedPeerIsRejectedBeforePersistence() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = trustedPeerResolver(active = false),
        )

        val rejected = assertIs<IngressOutcome.Rejected>(core.receiveVerified(envelope("cmd-1")))

        assertEquals(401, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
        assertEquals(1L, core.metricsSnapshot().incomingRejectedTotal)
    }

    @Test
    fun unknownTrustedPeerIsRejectedBeforePersistence() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = TrustedPeerResolver { null },
        )

        val rejected = assertIs<IngressOutcome.Rejected>(core.receiveVerified(envelope("cmd-1")))

        assertEquals(401, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
        assertEquals(1L, core.metricsSnapshot().incomingRejectedTotal)
    }

    @Test
    fun replayFromRevokedPeerIsRejectedBeforeIdempotentAcceptance() = runTest {
        var active = true
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = TrustedPeerResolver { peerId -> trustedPeer(peerId = peerId, active = active) },
        )
        val envelope = envelope("cmd-1")

        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope))
        active = false
        val rejected = assertIs<IngressOutcome.Rejected>(core.receiveVerified(envelope))

        assertEquals(401, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)
        assertEquals(1, core.claimedIncoming().size)
        assertEquals(1L, core.metricsSnapshot().incomingRejectedTotal)
    }

    @Test
    fun receiveVerifiedStoresAndClaimsIncomingMessage() = runTest {
        val core = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())

        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope("cmd-1")))

        val receipts = core.claimedIncoming()
        assertEquals(1, receipts.size)
        assertEquals(MessageId("cmd-1"), receipts.single().messageId)
        assertEquals(PeerId("peer-a"), receipts.single().senderPeerId)
        assertEquals(Topic("clock.command.v1"), receipts.single().topic)
        assertEquals("""{"op":"start"}""", receipts.single().payloadJson)
        assertEquals(1, receipts.single().deliveryCount)
        assertEquals(1L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun duplicateIncomingSameContentIsAcceptedButNotRepublished() = runTest {
        val core = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())
        val envelope = envelope("cmd-1")

        assertEquals(IngressOutcome.Accepted, core.receiveVerified(envelope))
        assertEquals(IngressOutcome.AlreadyAccepted, core.receiveVerified(envelope))

        assertEquals(1, core.claimedIncoming().size)
        assertEquals(emptyList(), core.claimedIncoming())
        assertEquals(1L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun duplicateIncomingDifferentContentIsReplayConflict() = runTest {
        val core = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())
        core.receiveVerified(envelope("cmd-1", payload = """{"op":"start"}""", payloadSha256 = "sha-start"))

        val rejected = core.receiveVerified(envelope("cmd-1", payload = """{"op":"stop"}""", payloadSha256 = "sha-stop"))

        val conflict = assertIs<IngressOutcome.Rejected>(rejected)
        assertEquals(409, conflict.httpStatus)
        assertEquals(SyncErrorCode.ReplayConflict, conflict.error.code)
        assertEquals(1L, core.metricsSnapshot().incomingRejectedTotal)
        assertEquals("""{"op":"start"}""", core.claimedIncoming().single().payloadJson)
    }

    @Test
    fun incomingAckProcessedRejectedAndRetryLaterControlsReclaim() = runTest {
        val processedCore = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())
        processedCore.receiveVerified(envelope("processed"))
        val processed = processedCore.claimedIncoming().single()
        processedCore.ackIncoming(processed.receiptId, IncomingProcessingOutcome.Processed)
        assertEquals(emptyList(), processedCore.claimedIncoming())
        assertEquals(0L, processedCore.metricsSnapshot().inboxUnprocessedDepth)

        val rejectedCore = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())
        rejectedCore.receiveVerified(envelope("rejected"))
        val rejected = rejectedCore.claimedIncoming().single()
        rejectedCore.ackIncoming(
            rejected.receiptId,
            IncomingProcessingOutcome.Rejected(SyncError(SyncErrorCode.InvalidMessage, "domain reject")),
        )
        assertEquals(emptyList(), rejectedCore.claimedIncoming())
        assertEquals(0L, rejectedCore.metricsSnapshot().inboxUnprocessedDepth)

        val retryCore = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())
        retryCore.receiveVerified(envelope("retry"))
        val retry = retryCore.claimedIncoming().single()
        retryCore.ackIncoming(retry.receiptId, IncomingProcessingOutcome.RetryLater(retryAtEpochMs = 2_000L))
        assertEquals(emptyList(), retryCore.claimedIncoming())
        now = 2_000L
        val reclaimed = retryCore.claimedIncoming().single()
        assertEquals(retry.receiptId, reclaimed.receiptId)
        assertEquals(2, reclaimed.deliveryCount)
        assertEquals(1L, retryCore.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun claimedIncomingIsReclaimedAfterProcessingLeaseExpires() = runTest {
        val core = SyncCoreFactory.createInMemory(clock, trustedPeerResolver = trustedPeerResolver())
        core.receiveVerified(envelope("lease"))

        val first = core.claimedIncoming().single()
        assertEquals(1, first.deliveryCount)

        now += 59_999L
        assertEquals(emptyList(), core.claimedIncoming())

        now += 1L
        val reclaimed = core.claimedIncoming().single()
        assertEquals(first.receiptId, reclaimed.receiptId)
        assertEquals(2, reclaimed.deliveryCount)
        assertEquals(1L, core.metricsSnapshot().inboxUnprocessedDepth)
    }
    @Test
    fun expiredDispatchLeaseIsRecoveredAndResentAcrossCoreInstances() = runTest {
        val store = InMemorySyncStore()
        store.outgoing[MessageId("cmd-1")] = OutgoingRecord(
            sequence = 1,
            message = message("cmd-1", expiresAt = 100_000L),
            state = DeliveryState.Dispatching,
            attempt = 1,
            nextAttemptAtEpochMs = null,
            lastError = null,
            leaseUntilEpochMs = 31_000L,
        )
        var calls = 0
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, attempt ->
                calls += 1
                assertEquals(2, attempt)
                DispatchOutcome.Accepted
            },
            store = store,
        )

        now = 31_000L
        val summary = core.flushDue()

        assertEquals(1, calls)
        assertEquals(1, summary.dispatched)
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Acked, item.state)
        assertEquals(2, item.attempt)
        assertEquals(1L, core.metricsSnapshot().expiredLeaseRecoveryTotal)
    }

    @Test
    fun liveDispatchLeaseIsNotRecoveredBeforeBoundary() = runTest {
        val store = InMemorySyncStore()
        store.outgoing[MessageId("cmd-1")] = OutgoingRecord(
            sequence = 1,
            message = message("cmd-1", expiresAt = 100_000L),
            state = DeliveryState.Dispatching,
            attempt = 1,
            nextAttemptAtEpochMs = null,
            lastError = null,
            leaseUntilEpochMs = 31_000L,
        )
        var calls = 0
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ ->
                calls += 1
                DispatchOutcome.Accepted
            },
            store = store,
        )

        now = 30_999L
        val before = core.flushDue()
        now = 31_000L
        val atBoundary = core.flushDue()

        assertEquals(0, before.dispatched)
        assertEquals(1, atBoundary.dispatched)
        assertEquals(1, calls)
        assertEquals(2, core.listOutgoing(OutgoingQuery()).items.single().attempt)
    }
    @Test
    fun sharedStoreRestoresOutgoingRetryWaitAcrossCoreInstances() = runTest {
        val store = InMemorySyncStore()
        val first = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout)) },
            store = store,
        )
        first.submit(message("cmd-1"))
        first.flushDue()
        val nextAttempt = first.listOutgoing(OutgoingQuery()).items.single().nextAttemptAtEpochMs!!

        val second = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            store = store,
        )
        now = nextAttempt
        second.flushDue()

        val item = second.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Acked, item.state)
        assertEquals(2, item.attempt)
        assertEquals(1L, second.metricsSnapshot().acceptedTotal)
    }

    @Test
    fun sharedStoreRestoresUnprocessedIncomingAcrossCoreInstances() = runTest {
        val store = InMemorySyncStore()
        val first = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            store = store,
            trustedPeerResolver = trustedPeerResolver(),
        )
        first.receiveVerified(envelope("cmd-1"))

        val second = InMemorySyncCore(clock = clock, transport = SyncTransport { _, _ -> DispatchOutcome.Accepted }, store = store)
        val receipt = second.claimedIncoming().single()

        assertEquals(MessageId("cmd-1"), receipt.messageId)
        assertEquals(1, receipt.deliveryCount)
        assertEquals(1L, second.metricsSnapshot().inboxUnprocessedDepth)
    }
    @Test
    fun invalidPayloadJsonIsRejectedByMessageContract() {
        assertFailsWith<IllegalArgumentException> { message("cmd-1", payload = "{not-json") }
    }

    private fun envelope(
        id: String,
        payload: String = """{"op":"start"}""",
        payloadSha256: String = "sha-start",
        senderPeerId: String = "peer-a",
        targetPeerId: String = "peer-b",
    ) = VerifiedEnvelope(
        schemaVersion = 1,
        envelopeId = "env-$id-$payloadSha256",
        messageId = MessageId(id),
        senderPeerId = PeerId(senderPeerId),
        targetPeerId = PeerId(targetPeerId),
        topic = Topic("clock.command.v1"),
        payloadJson = payload,
        sentAtEpochMs = now,
        nonce = "nonce-$id-$payloadSha256",
        payloadSha256 = payloadSha256,
    )

    private fun message(
        id: String,
        payload: String = """{"op":"start"}""",
        createdAt: Long = 100L,
        expiresAt: Long? = 10_000L,
        targetPeerId: String = "peer-a",
    ) = SyncMessage(
        messageId = MessageId(id),
        topic = Topic("clock.command.v1"),
        payloadJson = payload,
        targetPeerId = PeerId(targetPeerId),
        createdAtEpochMs = createdAt,
        expiresAtEpochMs = expiresAt,
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
