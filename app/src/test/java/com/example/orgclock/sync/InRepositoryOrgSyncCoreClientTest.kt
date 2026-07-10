package com.example.orgclock.sync

import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.datetime.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InRepositoryOrgSyncCoreClientTest {
    private var nowEpochMs = 1_700_000_000_000L

    @Test
    fun submitOutgoingEnqueuesPendingCommand() = runTest {
        val (client, core) = acceptingClientWithCore()

        val result = client.submitOutgoing(sampleCommand())

        assertIs<SubmitResult.Submitted>(result)
        val outgoing = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(MessageId("cmd-1"), outgoing.messageId)
        assertEquals(Topic(CLOCK_COMMAND_SCHEMA_V1), outgoing.topic)
        assertEquals(PeerId("peer-a"), outgoing.peerId)
        assertEquals(nowEpochMs + COMMAND_EXPIRATION_MS, outgoing.expiresAtEpochMs)
        assertEquals(
            listOf(SyncDeliveryState(commandId = "cmd-1", state = "pending")),
            client.observeDeliveryState(),
        )
        assertEquals(
            SyncMetricsSnapshot(commandsSubmittedTotal = 1, queueDepth = 1),
            client.metricsSnapshot(),
        )
    }

    @Test
    fun duplicateSubmitWithSameContentIsIdempotent() = runTest {
        val client = acceptingClient()
        val command = sampleCommand()

        assertIs<SubmitResult.Submitted>(client.submitOutgoing(command))
        assertIs<SubmitResult.Submitted>(client.submitOutgoing(command))

        assertEquals(1, client.observeDeliveryState().size)
        assertEquals(
            SyncMetricsSnapshot(commandsSubmittedTotal = 1, queueDepth = 1),
            client.metricsSnapshot(),
        )
    }

    @Test
    fun flushNowDispatchesAndMarksCommandAcked() = runTest {
        val client = acceptingClient()
        client.submitOutgoing(sampleCommand())

        client.flushNow()

        assertEquals(
            listOf(SyncDeliveryState(commandId = "cmd-1", state = "acked")),
            client.observeDeliveryState(),
        )
        assertEquals(
            SyncMetricsSnapshot(commandsSubmittedTotal = 1, commandsAppliedTotal = 1, queueDepth = 0),
            client.metricsSnapshot(),
        )
    }

    @Test
    fun invalidPayloadIsRejectedBeforeQueueing() = runTest {
        val client = acceptingClient()

        val result = client.submitOutgoing(sampleCommand(payloadJson = "not json"))

        assertIs<SubmitResult.Rejected>(result)
        assertEquals(emptyList(), client.observeDeliveryState())
        assertEquals(SyncMetricsSnapshot(), client.metricsSnapshot())
    }

    @Test
    fun metricsSnapshotMapsPersistenceErrorTotal() = runTest {
        val core = SyncCoreFactory.createPersistent(
            store = RecordingSyncStore(
                SyncStoreSnapshot(metrics = SyncStoreMetrics(persistenceErrorTotal = 2)),
            ),
            clock = SyncClock { nowEpochMs },
        )
        val client = InRepositoryOrgSyncCoreClient(core = core, nowEpochMs = { nowEpochMs })

        assertEquals(
            SyncMetricsSnapshot(persistenceErrorTotal = 2),
            client.metricsSnapshot(),
        )
    }

    @Test
    fun observeIncomingCommandsClaimsCoreInboxAndReportResultAcksIt() = runTest {
        val (client, core) = acceptingClientWithCore()

        assertEquals(IngressOutcome.Accepted, core.receiveVerified(sampleEnvelope()))

        val incoming = client.observeIncomingCommands()
        assertEquals(1, incoming.size)
        assertEquals("cmd-in", incoming.single().commandId)
        assertEquals("peer-remote", incoming.single().peerId)
        assertEquals("peer-remote", incoming.single().verifiedPeerId)
        assertEquals(IncomingVerificationState.Verified, incoming.single().verificationState)
        assertEquals("sync-core-verified-envelope", incoming.single().verificationMethod)
        assertEquals(emptyList(), client.observeIncomingCommands())
        assertEquals(1L, core.metricsSnapshot().inboxUnprocessedDepth)

        client.reportResult(
            ClockResultPayload(
                commandId = "cmd-in",
                status = ClockResultStatus.Applied,
                appliedAt = Instant.fromEpochMilliseconds(nowEpochMs),
                byDeviceId = "device-local",
            ),
        )

        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun reportResultQueuesClockResultToOriginalPeerBeforeAckingIncoming() = runTest {
        val (client, core) = acceptingClientWithCore()
        assertEquals(IngressOutcome.Accepted, core.receiveVerified(sampleEnvelope()))
        client.observeIncomingCommands()

        client.reportResult(sampleAppliedResult("cmd-in"))

        val outgoing = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(MessageId("result-cmd-in"), outgoing.messageId)
        assertEquals(Topic(CLOCK_RESULT_SCHEMA_V1), outgoing.topic)
        assertEquals(PeerId("peer-remote"), outgoing.peerId)
        assertEquals(nowEpochMs + RESULT_EXPIRATION_MS, outgoing.expiresAtEpochMs)
        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun reportResultResolvesRouteAfterClientAndCoreRecreation() = runTest {
        val store = RecordingSyncStore()
        val firstCore = SyncCoreFactory.createPersistent(
            store = store,
            clock = SyncClock { nowEpochMs },
            trustedPeerResolver = trustedPeerResolver(),
        )
        assertEquals(IngressOutcome.Accepted, firstCore.receiveVerified(sampleEnvelope()))

        val secondCore = SyncCoreFactory.createPersistent(store = store, clock = SyncClock { nowEpochMs })
        val client = InRepositoryOrgSyncCoreClient(
            core = secondCore,
            nowEpochMs = { nowEpochMs },
        )

        client.reportResult(sampleAppliedResult("cmd-in"))

        val outgoing = secondCore.listOutgoing(OutgoingQuery()).items.single()
        assertEquals(MessageId("result-cmd-in"), outgoing.messageId)
        assertEquals(PeerId("peer-remote"), outgoing.peerId)
        assertEquals(
            sampleAppliedResult("cmd-in").toResultPayloadJson(),
            store.snapshot.outgoing.single().message.payloadJson,
        )
        assertEquals(0L, secondCore.metricsSnapshot().inboxUnprocessedDepth)
    }

    @Test
    fun reportResultWithMissingRouteRecordsObservableFailureWithoutOutgoingRow() = runTest {
        val (client, core) = acceptingClientWithCore()

        client.reportResult(sampleAppliedResult("cmd-missing"))

        assertEquals(emptyList(), core.listOutgoing(OutgoingQuery()).items)
        assertEquals(
            listOf(
                SyncDeliveryState(
                    commandId = "result-cmd-missing",
                    state = "failed",
                    detail = "RESULT_ROUTE_NOT_FOUND: result route not found",
                ),
            ),
            client.observeDeliveryState(),
        )
    }

    @Test
    fun revokePeerRejectsQueuedCommandsAndDisablesPendingResultRoute() = runTest {
        val (client, core) = acceptingClientWithCore()
        assertIs<SubmitResult.Submitted>(client.submitOutgoing(sampleCommand(targetPeerId = "peer-remote")))
        assertEquals(IngressOutcome.Accepted, core.receiveVerified(sampleEnvelope(senderPeerId = "peer-remote")))
        client.observeIncomingCommands()

        client.revokePeer("peer-remote")
        client.reportResult(sampleAppliedResult("cmd-in"))

        assertEquals(
            listOf(
                SyncDeliveryState(
                    commandId = "cmd-1",
                    state = "rejected",
                    detail = "PEER_NOT_TRUSTED: peer revoked",
                ),
                SyncDeliveryState(
                    commandId = "result-cmd-in",
                    state = "failed",
                    detail = "RESULT_ROUTE_NOT_FOUND: result route not found",
                ),
            ),
            client.observeDeliveryState(),
        )
    }

    @Test
    fun startAndStopManageIngressServerLifecycle() = runTest {
        val (_, core) = acceptingClientWithCore()
        val ingressServer = RecordingSyncCoreIngressServer()
        val client = InRepositoryOrgSyncCoreClient(
            core = core,
            ingressServer = ingressServer,
            nowEpochMs = { nowEpochMs },
        )

        client.start()
        client.stop()

        assertEquals(1, ingressServer.startCount)
        assertEquals(1, ingressServer.stopCount)
    }
    private fun acceptingClient(): InRepositoryOrgSyncCoreClient = acceptingClientWithCore().first

    private fun acceptingClientWithCore(): Pair<InRepositoryOrgSyncCoreClient, SyncCore> {
        val core = SyncCoreFactory.createInMemory(
            clock = SyncClock { nowEpochMs },
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = trustedPeerResolver(),
        )
        return InRepositoryOrgSyncCoreClient(
            core = core,
            nowEpochMs = { nowEpochMs },
        ) to core
    }

    private fun sampleEnvelope(
        commandId: String = "cmd-in",
        payloadJson: String = """{"schema":"clock.command.v1","command_id":"cmd-in"}""",
        senderPeerId: String = "peer-remote",
    ): VerifiedEnvelope = VerifiedEnvelope(
        schemaVersion = 1,
        envelopeId = "env-$commandId",
        messageId = MessageId(commandId),
        senderPeerId = PeerId(senderPeerId),
        targetPeerId = PeerId("peer-local"),
        topic = Topic(CLOCK_COMMAND_SCHEMA_V1),
        payloadJson = payloadJson,
        sentAtEpochMs = nowEpochMs,
        nonce = "nonce-$commandId",
        payloadSha256 = "sha-$commandId",
    )
    private fun sampleCommand(
        commandId: String = "cmd-1",
        payloadJson: String = """{"schema":"clock.command.v1","command_id":"cmd-1"}""",
        targetPeerId: String = "peer-a",
    ): OutgoingClockCommand = OutgoingClockCommand(
        commandId = commandId,
        payloadJson = payloadJson,
        targetPeerId = targetPeerId,
    )

    private fun sampleAppliedResult(commandId: String): ClockResultPayload = ClockResultPayload(
        commandId = commandId,
        status = ClockResultStatus.Applied,
        appliedAt = Instant.fromEpochMilliseconds(nowEpochMs),
        byDeviceId = "device-local",
    )

    private fun trustedPeerResolver() = TrustedPeerResolver { peerId ->
        TrustedPeer(
            peerId = peerId,
            deviceId = "device-${peerId.value}",
            displayName = "Peer ${peerId.value}",
            signingPublicKeyBase64 = "test-key",
            role = PeerRole.Full,
            endpoint = "https://${peerId.value}.example",
            active = true,
        )
    }

    private companion object {
        const val COMMAND_EXPIRATION_MS = 24L * 60L * 60L * 1_000L
        const val RESULT_EXPIRATION_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
private class RecordingSyncCoreIngressServer : SyncCoreIngressServer {
    var startCount = 0
        private set
    var stopCount = 0
        private set

    override fun start() {
        startCount += 1
    }

    override fun stop() {
        stopCount += 1
    }
}

private class RecordingSyncStore(
    var snapshot: SyncStoreSnapshot = SyncStoreSnapshot(),
) : SyncStore {
    override suspend fun load(): SyncStoreLoadResult = SyncStoreLoadResult.Loaded(snapshot)

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult {
        this.snapshot = snapshot
        return SyncStoreSaveResult.Saved
    }

    override suspend fun healthCheck(): StoreHealth = StoreHealth.Healthy
}
