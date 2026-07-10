package com.example.orgclock.sync

import io.github.shgnaka.orgclock.synccore.api.CancelOutcome
import io.github.shgnaka.orgclock.synccore.api.AckIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.FlushSummary
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingReceipt
import io.github.shgnaka.orgclock.synccore.api.IncomingRoute
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.OutgoingPage
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
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
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import io.github.shgnaka.orgclock.synccore.api.SyncRawIngressReceiver
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSyncCoreIngressServerTest {
    private val clock = SyncClock { NOW_EPOCH_MS }

    @Test
    fun duplicateSignedEnvelopeReturnsTransportAcceptedStatus() {
        val peer = trustedPeer()
        val resolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } }
        val core = RecordingSyncCore()
        val port = availablePort()
        val server = AndroidSyncCoreIngressServer(
            receiver = SyncRawIngressReceiver(core, resolver, clock),
            host = "127.0.0.1",
            port = port,
        )

        try {
            server.start()

            val first = post(port, signedEnvelopeJson())
            val duplicate = post(port, signedEnvelopeJson())

            assertEquals(202, first.status)
            assertEquals("accepted", first.statusValue())
            assertEquals(202, duplicate.status)
            assertEquals("already_accepted", duplicate.statusValue())
            assertEquals(2, core.received.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectedResponseDoesNotExposeUnsanitizedCoreDetail() {
        val peer = trustedPeer()
        val resolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } }
        val secret = "SECRET-CORE-DETAIL-SHOULD-NOT-LEAK"
        val core = RecordingSyncCore(
            forcedOutcome = IngressOutcome.Rejected(
                httpStatus = 409,
                error = SyncError(SyncErrorCode.ReplayConflict, "replay conflict for $secret"),
            ),
        )
        val port = availablePort()
        val server = AndroidSyncCoreIngressServer(
            receiver = SyncRawIngressReceiver(core, resolver, clock),
            host = "127.0.0.1",
            port = port,
        )

        try {
            server.start()

            val response = post(port, signedEnvelopeJson())

            assertEquals(409, response.status)
            assertEquals("replay conflict", response.detail())
            kotlin.test.assertFalse(response.body.contains(secret))
        } finally {
            server.stop()
        }
    }

    @Test
    fun retryLaterResponseDoesNotExposeUnsanitizedCoreDetail() {
        val peer = trustedPeer()
        val resolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } }
        val secret = "SECRET-RETRY-LATER-SHOULD-NOT-LEAK"
        val core = RecordingSyncCore(
            forcedOutcome = IngressOutcome.RetryLater(
                httpStatus = 503,
                retryAfterSeconds = 30,
                error = SyncError(SyncErrorCode.StoreUnavailable, "store unavailable: $secret"),
            ),
        )
        val port = availablePort()
        val server = AndroidSyncCoreIngressServer(
            receiver = SyncRawIngressReceiver(core, resolver, clock),
            host = "127.0.0.1",
            port = port,
        )

        try {
            server.start()

            val response = post(port, signedEnvelopeJson())

            assertEquals(503, response.status)
            assertEquals("30", response.retryAfter)
            assertEquals("store unavailable", response.detail())
            kotlin.test.assertFalse(response.body.contains(secret))
        } finally {
            server.stop()
        }
    }

    @Test
    fun legacyUnsignedIncomingCommandEndpointIsNotExposed() {
        val peer = trustedPeer()
        val resolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } }
        val core = RecordingSyncCore()
        val port = availablePort()
        val server = AndroidSyncCoreIngressServer(
            receiver = SyncRawIngressReceiver(core, resolver, clock),
            host = "127.0.0.1",
            port = port,
        )

        try {
            server.start()

            val response = post(port, "/v1/incoming-command", PAYLOAD_JSON)

            assertEquals(404, response.status)
            assertEquals(0, core.received.size)
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, body: String): HttpResult = post(port, "/v1/messages", body)

    private fun post(port: Int, path: String, body: String): HttpResult {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val responseBody = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val retryAfter = connection.getHeaderField("Retry-After")
        connection.disconnect()
        return HttpResult(status, responseBody, retryAfter)
    }

    private fun HttpResult.statusValue(): String = Json.parseToJsonElement(body)
        .jsonObject
        .getValue("status")
        .jsonPrimitive
        .content

    private fun HttpResult.detail(): String = Json.parseToJsonElement(body)
        .jsonObject
        .getValue("detail")
        .jsonPrimitive
        .content

    private data class HttpResult(val status: Int, val body: String, val retryAfter: String? = null)

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    private fun trustedPeer(): TrustedPeer = TrustedPeer(
        peerId = PeerId("peer-a"),
        deviceId = "device-a",
        displayName = "Peer A",
        signingPublicKeyBase64 = PUBLIC_KEY_BASE64,
        role = PeerRole.Full,
        endpoint = "https://peer-a.example",
        active = true,
        signingAlg = "Ed25519",
    )

    private fun signedEnvelopeJson(): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", 1)
            put("envelopeId", "env-1")
            put("messageId", "cmd-1")
            put("senderPeerId", "peer-a")
            put("targetPeerId", "peer-local")
            put("topic", "clock.command.v1")
            put("payloadJson", PAYLOAD_JSON)
            put("sentAtEpochMs", NOW_EPOCH_MS)
            put("nonce", "nonce-1")
            put("payloadSha256", sha256Hex(PAYLOAD_JSON))
            put("signatureBase64", SIGNATURE_BASE64)
        },
    )

    private companion object {
        const val NOW_EPOCH_MS = 1_000_000L
        const val PAYLOAD_JSON = """{"op":"start"}"""
        const val PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
        const val SIGNATURE_BASE64 = "HUE6yB6tYG/lr7yxXIEy/9tx9IN0rDwb7tDXehohOPZQpOSxTSwr6BZSDAelOdtny0P8VjbitAzdTQswPYfACA=="
    }
}

private class RecordingSyncCore(
    private val forcedOutcome: IngressOutcome? = null,
) : SyncCore {
    val received = mutableListOf<VerifiedEnvelope>()

    override suspend fun start(): StartOutcome = StartOutcome.Started
    override suspend fun stop() = Unit
    override suspend fun submit(message: SyncMessage): SubmitOutcome = SubmitOutcome.Submitted
    override suspend fun cancel(messageId: io.github.shgnaka.orgclock.synccore.api.MessageId): CancelOutcome = CancelOutcome.NotFound
    override suspend fun retry(messageId: io.github.shgnaka.orgclock.synccore.api.MessageId): RetryOutcome = RetryOutcome.NotFound
    override suspend fun flushDue(): FlushSummary = FlushSummary(0, 0, 0, 0, 0, 0, 0)
    override suspend fun listOutgoing(query: OutgoingQuery): OutgoingPage = OutgoingPage(emptyList(), null)
    override suspend fun receiveVerified(envelope: VerifiedEnvelope): IngressOutcome {
        received += envelope
        forcedOutcome?.let { return it }
        return if (received.size == 1) IngressOutcome.Accepted else IngressOutcome.AlreadyAccepted
    }
    override suspend fun claimIncoming(limit: Int): ClaimIncomingOutcome = ClaimIncomingOutcome.Claimed(emptyList())
    override suspend fun resolveIncomingRoute(messageId: io.github.shgnaka.orgclock.synccore.api.MessageId): IncomingRoute? = null
    override suspend fun ackIncoming(receiptId: String, outcome: IncomingProcessingOutcome): AckIncomingOutcome = AckIncomingOutcome.Acked
    override suspend fun revokePeer(peerId: io.github.shgnaka.orgclock.synccore.api.PeerId): io.github.shgnaka.orgclock.synccore.api.PeerRevokeOutcome =
        io.github.shgnaka.orgclock.synccore.api.PeerRevokeOutcome.Revoked(outgoingRejected = 0, incomingRejected = 0)
    override fun observeDeliveryEvents(afterSequence: Long?): Flow<DeliveryEvent> = emptyFlow()
    override suspend fun metricsSnapshot(): SyncMetrics = SyncMetrics(
        submittedTotal = 0,
        acceptedTotal = 0,
        rejectedTotal = 0,
        retryAttemptsTotal = 0,
        queueDepth = 0,
        oldestPendingAgeMs = null,
        incomingRejectedTotal = 0,
        inboxUnprocessedDepth = 0,
        expiredLeaseRecoveryTotal = 0,
        persistenceErrorTotal = 0,
        lastSuccessfulDispatchByPeer = emptyMap(),
    )
    override suspend fun healthCheck(): SyncHealth = SyncHealth.Healthy
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
