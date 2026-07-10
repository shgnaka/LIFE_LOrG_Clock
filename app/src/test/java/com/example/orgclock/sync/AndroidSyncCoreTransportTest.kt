package com.example.orgclock.sync

import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.Topic
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidSyncCoreTransportTest {
    @Test
    fun dispatchPostsSignedEnvelopeToMessagesEndpoint() = runTest {
        val poster = RecordingSyncCoreHttpPoster(SyncCoreHttpPostResult.Accepted)
        val signer = RecordingSyncCoreEnvelopeSigner(senderPeerId = "peer-local", signature = "sig-base64")
        val transport = transport(
            poster = poster,
            signer = signer,
            nonce = { "nonce-1" },
            clockEpochMs = { 1_700_000_000_000L },
        )

        val outcome = transport.dispatch(sampleMessage(), 1)

        assertEquals(DispatchOutcome.Accepted, outcome)
        assertEquals("https://desktop.local:39091/v1/messages", poster.endpoint)
        assertEquals(sampleCredential(), poster.credential)
        val envelope = Json.parseToJsonElement(requireNotNull(poster.body)).jsonObject
        assertEquals("1", envelope["schemaVersion"]?.jsonPrimitive?.content)
        assertEquals("ES256", envelope["alg"]?.jsonPrimitive?.content)
        assertEquals("cmd-1", envelope["messageId"]?.jsonPrimitive?.content)
        assertEquals("peer-local", envelope["senderPeerId"]?.jsonPrimitive?.content)
        assertEquals("peer-a", envelope["targetPeerId"]?.jsonPrimitive?.content)
        assertEquals(CLOCK_COMMAND_SCHEMA_V1, envelope["topic"]?.jsonPrimitive?.content)
        assertEquals(sampleMessage().payloadJson, envelope["payloadJson"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", envelope["sentAtEpochMs"]?.jsonPrimitive?.content)
        assertEquals("nonce-1", envelope["nonce"]?.jsonPrimitive?.content)
        assertEquals(sha256Hex(sampleMessage().payloadJson), envelope["payloadSha256"]?.jsonPrimitive?.content)
        assertEquals("sig-base64", envelope["signatureBase64"]?.jsonPrimitive?.content)
        assertEquals(
            listOf(
                "schemaVersion=1",
                "alg=ES256",
                "envelopeId=${envelope["envelopeId"]?.jsonPrimitive?.content}",
                "messageId=cmd-1",
                "senderPeerId=peer-local",
                "targetPeerId=peer-a",
                "topic=$CLOCK_COMMAND_SCHEMA_V1",
                "sentAtEpochMs=1700000000000",
                "nonce=nonce-1",
                "payloadSha256=${sha256Hex(sampleMessage().payloadJson)}",
            ).joinToString("\n"),
            signer.canonicalInput,
        )
    }

    @Test
    fun credentialStoreTakesPrecedenceOverLegacyTrustField() = runTest {
        val poster = RecordingSyncCoreHttpPoster(SyncCoreHttpPostResult.Accepted)
        val storedCredential = SyncTransportCredential("stored-secret", "cd".repeat(32))
        val credentialStore = InMemorySyncCoreTransportCredentialStore().apply {
            put("peer-a", storedCredential)
        }
        val transport = transport(
            credentialStore = credentialStore,
            poster = poster,
        )

        transport.dispatch(sampleMessage(), 1)

        assertEquals(storedCredential, poster.credential)
    }

    @Test
    fun cleartextEndpointIsRejectedBeforeNetworkCall() = runTest {
        val poster = RecordingSyncCoreHttpPoster(SyncCoreHttpPostResult.Accepted)
        val transport = transport(
            trustStore = StaticSyncCorePeerTrustStore(peerRecord(endpoint = "http://desktop.local:39091")),
            poster = poster,
        )

        val outcome = transport.dispatch(sampleMessage(), 1)

        val rejected = assertIs<DispatchOutcome.Rejected>(outcome)
        assertEquals(SyncErrorCode.ProtocolError, rejected.error.code)
        assertNull(poster.endpoint)
    }

    @Test
    fun missingTrustRecordIsRejected() = runTest {
        val transport = transport(trustStore = StaticSyncCorePeerTrustStore())

        val outcome = transport.dispatch(sampleMessage(), 1)

        val rejected = assertIs<DispatchOutcome.Rejected>(outcome)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)
    }

    @Test
    fun posterRetryableAndRejectedResultsAreMapped() = runTest {
        val retryable = transport(
            poster = RecordingSyncCoreHttpPoster(
                SyncCoreHttpPostResult.Retryable(SyncError(SyncErrorCode.RemoteBusy, "busy")),
            ),
        ).dispatch(sampleMessage(), 1)
        assertEquals(SyncErrorCode.RemoteBusy, assertIs<DispatchOutcome.Retryable>(retryable).error.code)

        val rejected = transport(
            poster = RecordingSyncCoreHttpPoster(
                SyncCoreHttpPostResult.Rejected(SyncError(SyncErrorCode.PeerNotAuthorized, "denied")),
            ),
        ).dispatch(sampleMessage(), 1)
        assertEquals(SyncErrorCode.PeerNotAuthorized, assertIs<DispatchOutcome.Rejected>(rejected).error.code)
    }

    @Test
    fun httpStatusClassificationMatchesRetryPolicy() {
        listOf(200, 202, 204, 299).forEach { status ->
            assertEquals(SyncCoreHttpPostResult.Accepted, syncCoreHttpPostResultForStatus(status))
        }

        mapOf(
            408 to SyncErrorCode.Timeout,
            425 to SyncErrorCode.RemoteBusy,
            429 to SyncErrorCode.RateLimited,
            500 to SyncErrorCode.RemoteBusy,
            502 to SyncErrorCode.RemoteBusy,
            503 to SyncErrorCode.RemoteBusy,
            504 to SyncErrorCode.RemoteBusy,
        ).forEach { (status, code) ->
            val result = assertIs<SyncCoreHttpPostResult.Retryable>(syncCoreHttpPostResultForStatus(status))
            assertEquals(code, result.error.code)
            assertEquals("http_status=$status", result.error.detail)
        }

        mapOf(
            400 to SyncErrorCode.ProtocolError,
            401 to SyncErrorCode.PeerNotAuthorized,
            403 to SyncErrorCode.PeerNotAuthorized,
            404 to SyncErrorCode.ProtocolError,
            409 to SyncErrorCode.ProtocolError,
            413 to SyncErrorCode.PayloadTooLarge,
            422 to SyncErrorCode.ProtocolError,
        ).forEach { (status, code) ->
            val result = assertIs<SyncCoreHttpPostResult.Rejected>(syncCoreHttpPostResultForStatus(status))
            assertEquals(code, result.error.code)
            assertEquals("http_status=$status", result.error.detail)
        }
    }

    @Test
    fun networkFailureClassificationMatchesRetryPolicy() {
        val timeout = assertIs<SyncCoreHttpPostResult.Retryable>(
            syncCoreHttpPostResultForFailure(SocketTimeoutException("read timed out")),
        )
        assertEquals(SyncErrorCode.Timeout, timeout.error.code)

        val connectionFailure = assertIs<SyncCoreHttpPostResult.Retryable>(
            syncCoreHttpPostResultForFailure(IOException("connection refused")),
        )
        assertEquals(SyncErrorCode.NetworkUnreachable, connectionFailure.error.code)

        val tlsMismatch = assertIs<SyncCoreHttpPostResult.Rejected>(
            syncCoreHttpPostResultForFailure(SSLHandshakeException("certificate pin mismatch")),
        )
        assertEquals(SyncErrorCode.CertificatePinMismatch, tlsMismatch.error.code)

        val tlsFailure = assertIs<SyncCoreHttpPostResult.Rejected>(
            syncCoreHttpPostResultForFailure(SSLException("tls handshake failed")),
        )
        assertEquals(SyncErrorCode.CertificatePinMismatch, tlsFailure.error.code)
    }

    private fun transport(
        trustStore: PeerTrustStore = StaticSyncCorePeerTrustStore(peerRecord()),
        credentialStore: SyncCoreTransportCredentialStore = InMemorySyncCoreTransportCredentialStore(),
        signer: SyncCoreEnvelopeSigner = RecordingSyncCoreEnvelopeSigner(),
        clockEpochMs: () -> Long = { 1_700_000_000_000L },
        nonce: () -> String = { "nonce-1" },
        poster: SyncCoreHttpPoster = RecordingSyncCoreHttpPoster(SyncCoreHttpPostResult.Accepted),
    ): AndroidSyncCoreTransport = AndroidSyncCoreTransport(
        peerTrustStore = trustStore,
        credentialStore = credentialStore,
        signer = signer,
        clockEpochMs = clockEpochMs,
        nonce = nonce,
        poster = poster,
    )

    private fun peerRecord(
        peerId: String = "peer-a",
        endpoint: String = "https://desktop.local:39091",
        encodedCredential: String = SyncTransportCredentialCodec.encode(sampleCredential()),
    ): PeerTrustRecord = PeerTrustRecord(
        peerId = peerId,
        deviceId = "device-a",
        displayName = "Desktop A",
        publicKeyBase64 = encodedCredential,
        endpoint = endpoint,
        registeredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
    )

    private fun sampleMessage(): SyncMessage = SyncMessage(
        messageId = MessageId("cmd-1"),
        topic = Topic(CLOCK_COMMAND_SCHEMA_V1),
        payloadJson = """{"schema":"clock.command.v1","command_id":"cmd-1"}""",
        targetPeerId = io.github.shgnaka.orgclock.synccore.api.PeerId("peer-a"),
        createdAtEpochMs = 1_700_000_000_000L,
        expiresAtEpochMs = 1_700_086_400_000L,
    )

    private companion object {
        fun sampleCredential(): SyncTransportCredential = SyncTransportCredential("pairing-secret", "ab".repeat(32))
    }
}

private class RecordingSyncCoreEnvelopeSigner(
    override val senderPeerId: String = "peer-local",
    private val signature: String = "sig-base64",
) : SyncCoreEnvelopeSigner {
    var canonicalInput: String? = null
        private set

    override fun signCanonical(canonicalInput: String): Result<String> {
        this.canonicalInput = canonicalInput
        return Result.success(signature)
    }
}

private class RecordingSyncCoreHttpPoster(
    private val result: SyncCoreHttpPostResult,
) : SyncCoreHttpPoster {
    var endpoint: String? = null
        private set
    var credential: SyncTransportCredential? = null
        private set
    var body: String? = null
        private set

    override suspend fun post(endpoint: String, credential: SyncTransportCredential, body: String): SyncCoreHttpPostResult {
        this.endpoint = endpoint
        this.credential = credential
        this.body = body
        return result
    }
}

private class StaticSyncCorePeerTrustStore(
    private vararg val records: PeerTrustRecord,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean = records.any { it.peerId == peerId && it.isActive }
    override fun listTrusted(): List<String> = records.filter { it.isActive }.map { it.peerId }
    override fun trust(peerId: String) {}
    override fun trust(peerId: String, publicKeyBase64: String) {}
    override fun trust(record: PeerTrustRecord) {}
    override fun getTrustRecord(peerId: String): PeerTrustRecord? = records.firstOrNull { it.peerId == peerId }
    override fun listTrustRecords(): List<PeerTrustRecord> = records.toList()
    override fun revoke(peerId: String) {}
    override fun repair(peerId: String) {}
    override fun getTrustedPublicKey(peerId: String): String? = getTrustRecord(peerId)?.publicKeyBase64
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
