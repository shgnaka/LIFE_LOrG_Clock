package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ObservationContractTest {
    private var now = 41_000L
    private val clock = SyncClock { now }

    @Test
    fun typedModels() = runTest {
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )
        val message = SyncMessage(
            messageId = MessageId("cmd-observation"),
            topic = Topic("clock.command.v1"),
            payloadJson = """{"op":"start","id":"cmd-observation"}""",
            targetPeerId = PeerId("peer-ui"),
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 10_000L,
        )

        assertEquals(SubmitOutcome.Submitted, core.submit(message))
        core.flushDue()

        val events: List<DeliveryEvent> = core.observeDeliveryEvents().take(3).toList()
        assertEquals(listOf(DeliveryState.Pending, DeliveryState.Dispatching, DeliveryState.Acked), events.map { it.state })
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        events.forEach { event ->
            assertEquals(message.messageId, event.messageId)
            assertEquals(message.targetPeerId, event.peerId)
            assertEquals(message.topic, event.topic)
            assertEquals(now, event.occurredAtEpochMs)
            assertNull(event.error)
        }
        assertEquals(listOf(0, 1, 1), events.map { it.attempt })
        assertIs<DeliveryEvent>(events.single { it.state == DeliveryState.Acked })

        val metrics: SyncMetrics = core.metricsSnapshot()
        assertEquals(1L, metrics.submittedTotal)
        assertEquals(1L, metrics.acceptedTotal)
        assertEquals(0L, metrics.rejectedTotal)
        assertEquals(0L, metrics.retryAttemptsTotal)
        assertEquals(0L, metrics.queueDepth)
        assertEquals(0L, metrics.incomingRejectedTotal)
        assertEquals(0L, metrics.persistenceErrorTotal)
        assertEquals(now, metrics.lastSuccessfulDispatchByPeer[message.targetPeerId])
        assertIs<SyncMetrics>(metrics)
    }
}
