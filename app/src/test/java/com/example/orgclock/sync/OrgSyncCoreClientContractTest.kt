package com.example.orgclock.sync

import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OrgSyncCoreClientContractTest {
    private var nowEpochMs = 1_700_000_000_000L

    @Test
    fun internalFactorySatisfiesOrgSyncCoreClientContract() = runTest {
        val fixture = internalFixture()
        val client = fixture.client

        client.start()
        assertIs<SubmitResult.Submitted>(
            client.submitOutgoing(
                OutgoingClockCommand(
                    commandId = "cmd-1",
                    payloadJson = """{"schema":"clock.command.v1","command_id":"cmd-1"}""",
                    targetPeerId = "peer-a",
                ),
            ),
        )
        assertIs<SubmitResult.Submitted>(
            client.submitOutgoing(
                OutgoingClockCommand(
                    commandId = "cmd-1",
                    payloadJson = """{"schema":"clock.command.v1","command_id":"cmd-1"}""",
                    targetPeerId = "peer-a",
                ),
            ),
        )
        assertIs<SubmitResult.Rejected>(
            client.submitOutgoing(
                OutgoingClockCommand(
                    commandId = "cmd-invalid",
                    payloadJson = "not json",
                    targetPeerId = "peer-a",
                ),
            ),
        )

        assertEquals(
            listOf(SyncDeliveryState(commandId = "cmd-1", state = "pending")),
            client.observeDeliveryState(),
        )
        assertEquals(SyncMetricsSnapshot(commandsSubmittedTotal = 1, queueDepth = 1), client.metricsSnapshot())

        client.flushNow()

        assertEquals(
            listOf(SyncDeliveryState(commandId = "cmd-1", state = "acked")),
            client.observeDeliveryState(),
        )
        assertEquals(
            SyncMetricsSnapshot(commandsSubmittedTotal = 1, commandsAppliedTotal = 1, queueDepth = 0),
            client.metricsSnapshot(),
        )

        fixture.seedIncoming("cmd-in")
        val incoming = client.observeIncomingCommands()
        assertEquals(1, incoming.size)
        assertEquals("cmd-in", incoming.single().commandId)
        assertEquals("peer-remote", incoming.single().peerId)
        assertEquals("peer-remote", incoming.single().verifiedPeerId)
        assertEquals(IncomingVerificationState.Verified, incoming.single().verificationState)
        assertEquals("sync-core-verified-envelope", incoming.single().verificationMethod)

        client.reportResult(
            ClockResultPayload(
                commandId = "cmd-in",
                status = ClockResultStatus.Applied,
                appliedAt = Instant.fromEpochMilliseconds(nowEpochMs),
                byDeviceId = "device-local",
            ),
        )
        assertEquals(emptyList(), client.observeIncomingCommands())

        client.stop()
    }

    private fun internalFixture(): ContractFixture {
        lateinit var core: SyncCore
        core = SyncCoreFactory.createInMemory(
            clock = SyncClock { nowEpochMs },
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = trustedPeerResolver(),
        )
        return ContractFixture(
            client = InRepositoryOrgSyncCoreClient(core = core, nowEpochMs = { nowEpochMs }),
            seedIncoming = { commandId ->
                assertEquals(IngressOutcome.Accepted, core.receiveVerified(sampleEnvelope(commandId)))
            },
        )
    }

    private fun sampleEnvelope(commandId: String): VerifiedEnvelope = VerifiedEnvelope(
        schemaVersion = 1,
        envelopeId = "env-$commandId",
        messageId = MessageId(commandId),
        senderPeerId = PeerId("peer-remote"),
        targetPeerId = PeerId("peer-local"),
        topic = Topic(CLOCK_COMMAND_SCHEMA_V1),
        payloadJson = """{"schema":"clock.command.v1","command_id":"$commandId"}""",
        sentAtEpochMs = nowEpochMs,
        nonce = "nonce-$commandId",
        payloadSha256 = "sha-$commandId",
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

    private data class ContractFixture(
        val client: OrgSyncCoreClient,
        val seedIncoming: suspend (String) -> Unit,
    )
}
