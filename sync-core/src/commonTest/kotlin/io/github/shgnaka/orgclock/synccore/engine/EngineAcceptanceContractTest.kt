package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.api.CancelOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.FlushSummary
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.RetryOutcome
import io.github.shgnaka.orgclock.synccore.api.StartOutcome
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncRandom
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StateMachineTest {
    private var now = 1_000L
    private val clock = SyncClock { now }

    @Test
    fun validTransitions() = runTest {
        val accepted = InMemorySyncCore(clock, SyncTransport { _, _ -> DispatchOutcome.Accepted })
        accepted.submit(message("accepted"))
        accepted.flushDue()
        assertStateTrace(
            accepted.observeDeliveryEvents().take(3).toList(),
            listOf(DeliveryState.Pending, DeliveryState.Dispatching, DeliveryState.Acked),
            occurredAt = listOf(1_000L, 1_000L, 1_000L),
            terminalError = null,
        )

        val rejected = InMemorySyncCore(
            clock,
            SyncTransport { _, _ -> DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "denied")) },
        )
        rejected.submit(message("rejected"))
        rejected.flushDue()
        assertStateTrace(
            rejected.observeDeliveryEvents().take(3).toList(),
            listOf(DeliveryState.Pending, DeliveryState.Dispatching, DeliveryState.Rejected),
            occurredAt = listOf(1_000L, 1_000L, 1_000L),
            terminalError = SyncErrorCode.PeerNotTrusted,
        )

        val retryThenFailed = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout, "timeout")) },
            retryPolicy = RetryPolicy(initialDelayMs = 1_000L, jitterRatio = 0.0, maxAttempts = 2),
            random = SyncRandom { 0.0 },
        )
        retryThenFailed.submit(message("retry"))
        retryThenFailed.flushDue()
        now = 2_000L
        retryThenFailed.flushDue()
        assertStateTrace(
            retryThenFailed.observeDeliveryEvents().take(5).toList(),
            listOf(
                DeliveryState.Pending,
                DeliveryState.Dispatching,
                DeliveryState.RetryWait,
                DeliveryState.Dispatching,
                DeliveryState.Failed,
            ),
            occurredAt = listOf(1_000L, 1_000L, 1_000L, 2_000L, 2_000L),
            terminalError = SyncErrorCode.RetryExhausted,
        )

        val cancelled = InMemorySyncCore(clock, SyncTransport { _, _ -> DispatchOutcome.Accepted })
        cancelled.submit(message("cancelled"))
        assertEquals(CancelOutcome.Cancelled, cancelled.cancel(MessageId("cancelled")))
        assertStateTrace(
            cancelled.observeDeliveryEvents().take(2).toList(),
            listOf(DeliveryState.Pending, DeliveryState.Cancelled),
            occurredAt = listOf(2_000L, 2_000L),
            terminalError = SyncErrorCode.Cancelled,
        )

        val expired = InMemorySyncCore(clock, SyncTransport { _, _ -> DispatchOutcome.Accepted })
        expired.submit(message("expired", expiresAt = 500L))
        expired.flushDue()
        assertStateTrace(
            expired.observeDeliveryEvents().take(2).toList(),
            listOf(DeliveryState.Pending, DeliveryState.Expired),
            occurredAt = listOf(2_000L, 2_000L),
            terminalError = SyncErrorCode.Expired,
        )
    }

    @Test
    fun invalidTransitions() = runTest {
        val core = InMemorySyncCore(clock, SyncTransport { _, _ -> DispatchOutcome.Accepted })
        core.submit(message("acked"))
        core.flushDue()

        assertEquals(CancelOutcome.AlreadyTerminal, core.cancel(MessageId("acked")))
        assertEquals(RetryOutcome.NotRetryable, core.retry(MessageId("acked")))

        val acked = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Acked, acked.state)
        assertEquals(1, acked.attempt)
        assertNull(acked.lastError)

        val rejectedCore = InMemorySyncCore(
            clock,
            SyncTransport { _, _ -> DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted)) },
        )
        rejectedCore.submit(message("rejected"))
        rejectedCore.flushDue()

        assertEquals(RetryOutcome.NotRetryable, rejectedCore.retry(MessageId("rejected")))
        val rejected = rejectedCore.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Rejected, rejected.state)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.lastError?.code)
    }

    private fun assertStateTrace(
        events: List<DeliveryEvent>,
        states: List<DeliveryState>,
        occurredAt: List<Long>,
        terminalError: SyncErrorCode?,
    ) {
        assertEquals(states, events.map { it.state })
        assertEquals(occurredAt, events.map { it.occurredAtEpochMs })
        assertEquals((1..events.size).map { it.toLong() }, events.map { it.sequence })
        events.forEach { event ->
            assertEquals(Topic("clock.command.v1"), event.topic)
            assertEquals(PeerId("peer-a"), event.peerId)
            assertIs<Int>(event.attempt)
        }
        assertEquals(terminalError, events.last().error?.code)
    }
}

class LifecycleTest {
    @Test
    fun idempotentStartStop() = runTest {
        val core = InMemorySyncCore(SyncClock { 1_000L }, SyncTransport { _, _ -> DispatchOutcome.Accepted })

        assertEquals(StartOutcome.Started, core.start())
        assertEquals(StartOutcome.AlreadyStarted, core.start())
        core.stop()
        core.stop()
        assertEquals(StartOutcome.Started, core.start())

        core.submit(message("after-restart"))
        val summary = core.flushDue()
        assertEquals(1, summary.dispatched)
        assertEquals(DeliveryState.Acked, core.listOutgoing(OutgoingQuery()).items.single().state)
    }
}

class FlushTest {
    @Test
    fun concurrentFlush() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val core = InMemorySyncCore(
            SyncClock { 1_000L },
            SyncTransport { _, _ ->
                calls += 1
                entered.complete(Unit)
                release.await()
                DispatchOutcome.Accepted
            },
        )
        core.submit(message("cmd-1"))

        val first = async { core.flushDue() }
        entered.await()
        val second = async { core.flushDue() }
        release.complete(Unit)
        val summaries = listOf(first.await(), second.await())

        assertEquals(1, calls)
        assertEquals(1, summaries.sumOf(FlushSummary::dispatched))
        assertEquals(
            listOf(DeliveryState.Pending, DeliveryState.Dispatching, DeliveryState.Acked),
            core.observeDeliveryEvents().take(3).toList().map { it.state },
        )
    }
}

class QueueQueryTest {
    @Test
    fun paginationAndFilters() = runTest {
        val core = InMemorySyncCore(
            SyncClock { 1_000L },
            SyncTransport { message, _ ->
                if (message.messageId == MessageId("cmd-2")) {
                    DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "denied"))
                } else {
                    DispatchOutcome.Accepted
                }
            },
        )
        core.submit(message("cmd-1", targetPeerId = "peer-a", topic = "clock.command.v1"))
        core.submit(message("cmd-2", targetPeerId = "peer-b", topic = "clock.command.v1"))
        core.submit(message("cmd-3", targetPeerId = "peer-a", topic = "clock.result.v1"))
        core.submit(message("cmd-4", targetPeerId = "peer-b", topic = "clock.result.v1"))
        core.flushDue()

        val firstPage = core.listOutgoing(OutgoingQuery(limit = 2))
        val secondPage = core.listOutgoing(OutgoingQuery(afterSequence = firstPage.nextSequence, limit = 2))
        assertEquals(listOf(MessageId("cmd-1"), MessageId("cmd-2")), firstPage.items.map { it.messageId })
        assertEquals(listOf(MessageId("cmd-3"), MessageId("cmd-4")), secondPage.items.map { it.messageId })
        assertEquals(2L, firstPage.nextSequence)
        assertNull(secondPage.nextSequence)

        val peerA = core.listOutgoing(OutgoingQuery(peerId = PeerId("peer-a"), limit = 10))
        assertEquals(listOf(MessageId("cmd-1"), MessageId("cmd-3")), peerA.items.map { it.messageId })

        val resultTopic = core.listOutgoing(OutgoingQuery(topic = Topic("clock.result.v1"), limit = 10))
        assertEquals(listOf(MessageId("cmd-3"), MessageId("cmd-4")), resultTopic.items.map { it.messageId })

        val rejected = core.listOutgoing(OutgoingQuery(states = setOf(DeliveryState.Rejected), limit = 10))
        assertEquals(listOf(MessageId("cmd-2")), rejected.items.map { it.messageId })
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.items.single().lastError?.code)
    }
}

private fun message(
    id: String,
    payload: String = """{"op":"start"}""",
    createdAt: Long = 100L,
    expiresAt: Long? = 10_000L,
    targetPeerId: String = "peer-a",
    topic: String = "clock.command.v1",
) = SyncMessage(
    messageId = MessageId(id),
    topic = Topic(topic),
    payloadJson = payload,
    targetPeerId = PeerId(targetPeerId),
    createdAtEpochMs = createdAt,
    expiresAtEpochMs = expiresAt,
)
