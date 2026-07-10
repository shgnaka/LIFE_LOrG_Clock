package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.AckIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.CancelOutcome
import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.FlushSummary
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingRoute
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingPage
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRevokeOutcome
import io.github.shgnaka.orgclock.synccore.api.PeerRole
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
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IngressHttpMappingTest {
    private var now = 1_000_000L
    private val clock = SyncClock { now }

    @Test
    fun allOutcomes() = runTest {
        assertEquals(IngressOutcome.Accepted, receiverReturning(IngressOutcome.Accepted).receive(envelopeJson()))
        assertEquals(IngressOutcome.AlreadyAccepted, receiverReturning(IngressOutcome.AlreadyAccepted).receive(envelopeJson()))

        listOf(
            IngressOutcome.Rejected(400, SyncError(SyncErrorCode.InvalidMessage, "raw invalid")) to 400,
            IngressOutcome.Rejected(401, SyncError(SyncErrorCode.PeerNotTrusted, "secret peer")) to 401,
            IngressOutcome.Rejected(403, SyncError(SyncErrorCode.PeerNotAuthorized, "secret role")) to 403,
            IngressOutcome.Rejected(409, SyncError(SyncErrorCode.ReplayConflict, "secret replay")) to 409,
            IngressOutcome.Rejected(413, SyncError(SyncErrorCode.PayloadTooLarge, "secret payload")) to 413,
        ).forEach { (outcome, status) ->
            val rejected = assertIs<IngressOutcome.Rejected>(receiverReturning(outcome).receive(envelopeJson()))
            assertEquals(status, rejected.httpStatus)
            assertEquals(outcome.error.code, rejected.error.code)
            assertEquals(false, rejected.error.detail?.contains("secret") == true)
        }

        listOf(
            IngressOutcome.RetryLater(429, retryAfterSeconds = 60, SyncError(SyncErrorCode.RateLimited, "secret rate")) to 429,
            IngressOutcome.RetryLater(503, retryAfterSeconds = 30, SyncError(SyncErrorCode.InboxFull, "secret inbox")) to 503,
        ).forEach { (outcome, status) ->
            val retryLater = assertIs<IngressOutcome.RetryLater>(receiverReturning(outcome).receive(envelopeJson()))
            assertEquals(status, retryLater.httpStatus)
            assertEquals(outcome.retryAfterSeconds, retryLater.retryAfterSeconds)
            assertEquals(outcome.error.code, retryLater.error.code)
            assertEquals(false, retryLater.error.detail?.contains("secret") == true)
        }
    }

    private fun receiverReturning(outcome: IngressOutcome): RawIngressReceiver {
        val peer = TrustedPeer(
            peerId = PeerId("peer-a"),
            deviceId = "device-a",
            displayName = "Peer A",
            signingPublicKeyBase64 = "test-key",
            role = PeerRole.Full,
            endpoint = "https://peer-a.example",
            active = true,
        )
        return RawIngressReceiver(
            syncCore = StubSyncCore(outcome),
            trustedPeerResolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } },
            envelopeCodec = EnvelopeCodec(
                clock = clock,
                signatureVerifier = EnvelopeSignatureVerifier { _, _, _, _ -> true },
            ),
            clock = clock,
        )
    }

    private fun envelopeJson(): String {
        val payload = """{"op":"start"}"""
        return Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", 1)
                put("envelopeId", "env-1")
                put("messageId", "cmd-1")
                put("senderPeerId", "peer-a")
                put("targetPeerId", "peer-local")
                put("topic", "clock.command.v1")
                put("payloadJson", payload)
                put("sentAtEpochMs", now)
                put("nonce", "nonce-1")
                put("payloadSha256", sha256Hex(payload))
                put("signatureBase64", "test-signature")
            },
        )
    }
}

private class StubSyncCore(private val ingressOutcome: IngressOutcome) : SyncCore {
    override suspend fun start(): StartOutcome = StartOutcome.Started
    override suspend fun stop() = Unit
    override suspend fun submit(message: SyncMessage): SubmitOutcome = SubmitOutcome.Submitted
    override suspend fun cancel(messageId: MessageId): CancelOutcome = CancelOutcome.NotFound
    override suspend fun retry(messageId: MessageId): RetryOutcome = RetryOutcome.NotFound
    override suspend fun flushDue(): FlushSummary = FlushSummary(0, 0, 0, 0, 0, 0, 0)
    override suspend fun listOutgoing(query: OutgoingQuery): OutgoingPage = OutgoingPage(emptyList(), null)
    override suspend fun receiveVerified(envelope: VerifiedEnvelope): IngressOutcome = ingressOutcome
    override suspend fun claimIncoming(limit: Int): ClaimIncomingOutcome = ClaimIncomingOutcome.Claimed(emptyList())
    override suspend fun resolveIncomingRoute(messageId: MessageId): IncomingRoute? = null
    override suspend fun ackIncoming(receiptId: String, outcome: IncomingProcessingOutcome): AckIncomingOutcome = AckIncomingOutcome.NotFound
    override suspend fun revokePeer(peerId: PeerId): PeerRevokeOutcome = PeerRevokeOutcome.Revoked(0, 0)
    override fun observeDeliveryEvents(afterSequence: Long?): Flow<DeliveryEvent> = emptyFlow()
    override suspend fun metricsSnapshot(): SyncMetrics = SyncMetrics(0, 0, 0, 0, 0, null, 0, 0, 0, 0, emptyMap())
    override suspend fun healthCheck(): SyncHealth = SyncHealth.Healthy
}
