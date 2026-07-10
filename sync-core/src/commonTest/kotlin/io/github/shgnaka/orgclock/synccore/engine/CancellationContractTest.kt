package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CancellationContractTest {
    private var now = 1_000L
    private val clock = SyncClock { now }

    @Test
    fun storeSaveCancellationRollsBackMutationAndPropagates() = runTest {
        val store = CancellingSaveStore()
        val core = SyncCoreFactory.createPersistent(store = store, clock = clock)

        assertFailsWith<CancellationException> {
            core.submit(message("cmd-store-cancel"))
        }

        assertEquals(1, store.saveCalls)
        assertEquals(emptyList(), core.listOutgoing(OutgoingQuery()).items)
        assertEquals(0L, core.metricsSnapshot().queueDepth)
        assertEquals(0L, core.metricsSnapshot().persistenceErrorTotal)
    }

    @Test
    fun transportCancellationPropagatesWithoutConvertingToFailure() = runTest {
        val enteredTransport = CompletableDeferred<Unit>()
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            transport = SyncTransport { _, _ ->
                enteredTransport.complete(Unit)
                awaitCancellation()
            },
        )
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-transport-cancel")))

        val flushing = async { core.flushDue() }
        enteredTransport.await()
        flushing.cancel()

        assertFailsWith<CancellationException> {
            flushing.await()
        }
        val item = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(DeliveryState.Dispatching, item.state)
        assertEquals(null, item.lastError)
        assertEquals(0L, core.metricsSnapshot().retryAttemptsTotal)
        assertEquals(0L, core.metricsSnapshot().acceptedTotal)
        assertEquals(1L, core.metricsSnapshot().queueDepth)
    }

    private fun message(id: String) = SyncMessage(
        messageId = MessageId(id),
        topic = Topic("clock.command.v1"),
        payloadJson = """{"op":"start","id":"$id"}""",
        targetPeerId = PeerId("peer-a"),
        createdAtEpochMs = now,
        expiresAtEpochMs = now + 10_000L,
    )
}

class ConcurrencyStressTest {
    @Test
    fun seededTenThousandOperationConcurrencyStress() = runTest {
        repeat(SEED_COUNT) { seed ->
            var now = 1_000L + seed
            val clock = SyncClock { now }
            val core = SyncCoreFactory.createInMemory(
                clock = clock,
                transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            )
            val schedule = DeterministicSchedule(seed + 1)

            repeat(WAVES_PER_SEED) { wave ->
                val release = CompletableDeferred<Unit>()
                val operations = (0 until OPERATIONS_PER_WAVE).map { lane ->
                    val op = schedule.next(4)
                    val idIndex = schedule.next(MESSAGE_ID_SPACE)
                    val id = "stress-$seed-$idIndex"
                    async {
                        release.await()
                        when (op) {
                            0 -> core.submit(message(id, now, idIndex))
                            1 -> core.cancel(MessageId(id))
                            2 -> core.retry(MessageId(id))
                            else -> core.flushDue()
                        }
                    }
                }

                release.complete(Unit)
                operations.awaitAll()
                now += 1

                val items = core.listOutgoing(OutgoingQuery(limit = 500)).items
                assertEquals(items.size, items.map { it.messageId }.distinct().size, "seed=$seed wave=$wave")
                assertEquals(
                    items.count { !it.state.isTerminalForStress() }.toLong(),
                    core.metricsSnapshot().queueDepth,
                    "seed=$seed wave=$wave",
                )
                assertTrue(items.all { it.attempt >= 0 }, "seed=$seed wave=$wave")
            }
        }
    }

    private fun message(id: String, now: Long, idIndex: Int) = SyncMessage(
        messageId = MessageId(id),
        topic = Topic("clock.command.v1"),
        payloadJson = """{"op":"start","id":"$id"}""",
        targetPeerId = PeerId("peer-${idIndex % 5}"),
        createdAtEpochMs = now,
        expiresAtEpochMs = now + 10_000L,
    )

    private fun DeliveryState.isTerminalForStress(): Boolean = when (this) {
        DeliveryState.Acked,
        DeliveryState.Rejected,
        DeliveryState.Failed,
        DeliveryState.Expired,
        DeliveryState.Cancelled -> true
        DeliveryState.Pending,
        DeliveryState.Dispatching,
        DeliveryState.RetryWait -> false
    }

    private class DeterministicSchedule(seed: Int) {
        private var state = seed.toLong()

        fun next(bound: Int): Int {
            state = ((state * 1_103_515_245L + 12_345L) % 2_147_483_647L).let { value ->
                if (value < 0) value + 2_147_483_647L else value
            }
            return (state % bound).toInt()
        }
    }

    private companion object {
        const val SEED_COUNT = 100
        const val WAVES_PER_SEED = 10
        const val OPERATIONS_PER_WAVE = 10
        const val MESSAGE_ID_SPACE = 30
    }
}

private class CancellingSaveStore : SyncStore {
    var saveCalls = 0
        private set

    override suspend fun load(): SyncStoreLoadResult = SyncStoreLoadResult.Loaded(SyncStoreSnapshot())

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult {
        saveCalls += 1
        throw CancellationException("save cancelled")
    }

    override suspend fun healthCheck(): StoreHealth = StoreHealth.Healthy
}
