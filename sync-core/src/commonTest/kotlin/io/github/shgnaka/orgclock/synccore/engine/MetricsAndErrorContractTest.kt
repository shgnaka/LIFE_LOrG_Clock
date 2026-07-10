package io.github.shgnaka.orgclock.synccore.engine

import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageDirection
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TopicAuthorization
import io.github.shgnaka.orgclock.synccore.api.TopicPolicy
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MetricsTest {
    private var now = 31_000L
    private val clock = SyncClock { now }

    @Test
    fun mixedOutcomes() = runTest {
        val store = InMemorySyncStore().apply {
            outgoing[MessageId("cmd-recovered")] = OutgoingRecord(
                sequence = 1,
                message = message("cmd-recovered", targetPeerId = "peer-recovered"),
                state = DeliveryState.Dispatching,
                attempt = 1,
                nextAttemptAtEpochMs = null,
                lastError = null,
                leaseUntilEpochMs = 30_999L,
            )
            nextRecordSequence = 2L
        }
        val core = InMemorySyncCore(
            clock = clock,
            store = store,
            transport = SyncTransport { message, _ ->
                when (message.messageId.value) {
                    "cmd-rejected" -> DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "revoked"))
                    "cmd-retry" -> DispatchOutcome.Retryable(SyncError(SyncErrorCode.Timeout, "timeout"))
                    else -> DispatchOutcome.Accepted
                }
            },
            topicPolicy = TopicPolicy { _: TrustedPeer, _: Topic, _: MessageDirection -> TopicAuthorization.Denied },
            trustedPeerResolver = trustedPeerResolver(),
        )

        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-accepted", targetPeerId = "peer-accepted")))
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-rejected", targetPeerId = "peer-rejected")))
        assertEquals(SubmitOutcome.Submitted, core.submit(message("cmd-retry", targetPeerId = "peer-retry")))
        val rejectedIngress = assertIs<IngressOutcome.Rejected>(core.receiveVerified(envelope("cmd-in-denied")))

        assertEquals(SyncErrorCode.PeerNotAuthorized, rejectedIngress.error.code)
        core.flushDue()

        val metrics = core.metricsSnapshot()
        assertEquals(3L, metrics.submittedTotal)
        assertEquals(2L, metrics.acceptedTotal)
        assertEquals(1L, metrics.rejectedTotal)
        assertEquals(1L, metrics.retryAttemptsTotal)
        assertEquals(1L, metrics.incomingRejectedTotal)
        assertEquals(1L, metrics.expiredLeaseRecoveryTotal)
        assertEquals(1L, metrics.queueDepth)
        assertEquals(now, metrics.lastSuccessfulDispatchByPeer[PeerId("peer-recovered")])
        assertEquals(now, metrics.lastSuccessfulDispatchByPeer[PeerId("peer-accepted")])

        val states = core.listOutgoing(OutgoingQuery(limit = 10)).items.associate { it.messageId.value to it.state }
        assertEquals(DeliveryState.Acked, states["cmd-recovered"])
        assertEquals(DeliveryState.Acked, states["cmd-accepted"])
        assertEquals(DeliveryState.Rejected, states["cmd-rejected"])
        assertEquals(DeliveryState.RetryWait, states["cmd-retry"])

        val failingCore = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            durableStore = FailingSaveStore(),
        )
        val failedSubmit = assertIs<SubmitOutcome.Failed>(
            failingCore.submit(message("cmd-store-error", targetPeerId = "peer-store")),
        )
        assertEquals(SyncErrorCode.StoreWriteFailed, failedSubmit.error.code)
        assertEquals(1L, failingCore.metricsSnapshot().persistenceErrorTotal)
        assertEquals(0L, failingCore.metricsSnapshot().queueDepth)
    }

    private fun message(id: String, targetPeerId: String): SyncMessage = SyncMessage(
        messageId = MessageId(id),
        topic = Topic("clock.command.v1"),
        payloadJson = """{"op":"start","id":"$id"}""",
        targetPeerId = PeerId(targetPeerId),
        createdAtEpochMs = now,
        expiresAtEpochMs = now + 10_000L,
    )

    private fun envelope(id: String): VerifiedEnvelope = VerifiedEnvelope(
        schemaVersion = 1,
        envelopeId = "env-$id",
        messageId = MessageId(id),
        senderPeerId = PeerId("peer-a"),
        targetPeerId = PeerId("peer-local"),
        topic = Topic("clock.command.v1"),
        payloadJson = """{"op":"start","id":"$id"}""",
        sentAtEpochMs = now,
        nonce = "nonce-$id",
        payloadSha256 = "sha-$id",
    )

    private fun trustedPeerResolver() = TrustedPeerResolver { peerId ->
        TrustedPeer(
            peerId = peerId,
            deviceId = "device-${peerId.value}",
            displayName = "Peer ${peerId.value}",
            signingPublicKeyBase64 = "test-key",
            role = PeerRole.Full,
            endpoint = null,
            active = true,
        )
    }
}

private class FailingSaveStore : SyncStore {
    override suspend fun load(): SyncStoreLoadResult = SyncStoreLoadResult.Loaded(SyncStoreSnapshot())

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult =
        SyncStoreSaveResult.Failed(SyncError(SyncErrorCode.StoreWriteFailed, "store unavailable"))

    override suspend fun healthCheck(): StoreHealth = StoreHealth.Healthy
}

class ErrorContractTest {
    @Test
    fun stableCodes() {
        assertEquals(
            listOf(
                "InvalidMessage",
                "PayloadTooLarge",
                "MessageIdConflict",
                "PeerNotTrusted",
                "SignatureInvalid",
                "TimestampOutOfRange",
                "ReplayConflict",
                "CertificatePinMismatch",
                "PeerNotAuthorized",
                "QueueFull",
                "InboxFull",
                "RateLimited",
                "NetworkUnreachable",
                "Timeout",
                "RemoteBusy",
                "ProtocolError",
                "Expired",
                "Cancelled",
                "RetryExhausted",
                "ResultRouteNotFound",
                "StoreUnavailable",
                "StoreWriteFailed",
                "StoreReadFailed",
                "MigrationFailed",
                "InvalidStateTransition",
            ),
            SyncErrorCode.values().map { it.name },
        )
        SyncErrorCode.values().forEach { code ->
            assertEquals(code, SyncError(code, "x".repeat(512)).code)
            assertFailsWith<IllegalArgumentException> {
                SyncError(code, "x".repeat(513))
            }
        }
    }
}
