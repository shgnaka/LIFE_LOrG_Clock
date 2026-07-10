package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.claimedIncoming
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageDirection
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TopicAuthorization
import io.github.shgnaka.orgclock.synccore.api.TopicPolicy
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.engine.InMemorySyncCore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RawIngressReceiverTest {
    private var now = 1_000_000L
    private val clock = SyncClock { now }

    @Test
    fun trustedSignedEnvelopeIsAcceptedIntoInbox() = runTest {
        val peer = trustedPeer()
        val core = coreWith(peer)
        val receiver = receiver(core, peer)

        assertEquals(IngressOutcome.Accepted, receiver.receive(signedEnvelopeJson()))

        val receipt = core.claimedIncoming().single()
        assertEquals(MessageId("cmd-1"), receipt.messageId)
        assertEquals(PeerId("peer-a"), receipt.senderPeerId)
        assertEquals("""{"op":"start"}""", receipt.payloadJson)
    }

    @Test
    fun unknownPeerIsRejectedWithoutPersistence() = runTest {
        val core = coreWith(null)
        val receiver = receiver(core, null)

        val rejected = assertIs<IngressOutcome.Rejected>(receiver.receive(signedEnvelopeJson()))

        assertEquals(401, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
    }

    @Test
    fun revokedPeerIsRejectedWithoutPersistence() = runTest {
        val peer = trustedPeer(active = false)
        val core = coreWith(peer)
        val receiver = receiver(core, peer)

        val rejected = assertIs<IngressOutcome.Rejected>(receiver.receive(signedEnvelopeJson()))

        assertEquals(401, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotTrusted, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
    }

    @Test
    fun invalidSignatureIsRejectedWithoutPersistence() = runTest {
        val peer = trustedPeer()
        val core = coreWith(peer)
        val receiver = receiver(core, peer)

        val rejected = assertIs<IngressOutcome.Rejected>(receiver.receive(signedEnvelopeJson(nonce = "tampered")))

        assertEquals(401, rejected.httpStatus)
        assertEquals(SyncErrorCode.SignatureInvalid, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
    }

    @Test
    fun viewerMutationTopicIsRejectedBeforePersistence() = runTest {
        val peer = trustedPeer(role = PeerRole.Viewer)
        val core = coreWith(
            peer = peer,
            topicPolicy = TopicPolicy { trustedPeer, topic, direction ->
                assertEquals(MessageDirection.Incoming, direction)
                if (trustedPeer.role == PeerRole.Viewer && topic == Topic("clock.command.v1")) {
                    TopicAuthorization.Denied
                } else {
                    TopicAuthorization.Allowed
                }
            },
        )
        val receiver = receiver(core, peer)

        val rejected = assertIs<IngressOutcome.Rejected>(receiver.receive(signedEnvelopeJson()))

        assertEquals(403, rejected.httpStatus)
        assertEquals(SyncErrorCode.PeerNotAuthorized, rejected.error.code)
        assertEquals(emptyList(), core.claimedIncoming())
    }

    @Test
    fun unsupportedTopicAndEnvelopeVersionAreRejectedWithoutStoppingReceiver() = runTest {
        val peer = trustedPeer()
        val core = coreWith(
            peer = peer,
            topicPolicy = TopicPolicy { _, topic, _ ->
                if (topic == Topic("clock.command.v1")) TopicAuthorization.Allowed else TopicAuthorization.Denied
            },
        )
        val receiver = receiver(core, peer, acceptAnySignature = true)

        val unsupportedTopic = assertIs<IngressOutcome.Rejected>(
            receiver.receive(signedEnvelopeJson(topic = "clock.future.v99")),
        )
        val unsupportedVersion = assertIs<IngressOutcome.Rejected>(
            receiver.receive(signedEnvelopeJson(schemaVersion = 2, nonce = "nonce-v2")),
        )
        val accepted = receiver.receive(signedEnvelopeJson(nonce = "nonce-after-reject", messageId = "cmd-after-reject"))

        assertEquals(403, unsupportedTopic.httpStatus)
        assertEquals(SyncErrorCode.PeerNotAuthorized, unsupportedTopic.error.code)
        assertEquals(400, unsupportedVersion.httpStatus)
        assertEquals(SyncErrorCode.InvalidMessage, unsupportedVersion.error.code)
        assertEquals(IngressOutcome.Accepted, accepted)
        assertEquals(listOf(MessageId("cmd-after-reject")), core.claimedIncoming().map { it.messageId })
        assertEquals(emptyList(), core.claimedIncoming())
    }

    @Test
    fun encodedBodySizeBoundaryIsEnforcedBeforePersistence() = runTest {
        val peer = trustedPeer()
        val acceptedCore = coreWith(peer)
        val rejectedCore = coreWith(peer)
        val acceptedReceiver = receiver(acceptedCore, peer, acceptAnySignature = true)
        val rejectedReceiver = receiver(rejectedCore, peer, acceptAnySignature = true)
        val atLimit = envelopeWithEncodedSize(128 * 1024)
        val overLimit = envelopeWithEncodedSize(128 * 1024 + 1)

        assertEquals(128 * 1024, atLimit.encodeToByteArray().size)
        assertEquals(IngressOutcome.Accepted, acceptedReceiver.receive(atLimit))
        val rejected = assertIs<IngressOutcome.Rejected>(rejectedReceiver.receive(overLimit))

        assertEquals(413, rejected.httpStatus)
        assertEquals(SyncErrorCode.PayloadTooLarge, rejected.error.code)
        assertEquals(emptyList(), rejectedCore.claimedIncoming())
    }

    @Test
    fun decodedPayloadSizeBoundaryIsEnforcedBeforePersistence() = runTest {
        val peer = trustedPeer()
        val acceptedCore = coreWith(peer)
        val rejectedCore = coreWith(peer)
        val acceptedReceiver = receiver(acceptedCore, peer, acceptAnySignature = true)
        val rejectedReceiver = receiver(rejectedCore, peer, acceptAnySignature = true)
        val atLimitPayload = payloadWithUtf8Size(96 * 1024)
        val overLimitPayload = payloadWithUtf8Size(96 * 1024 + 1)

        assertEquals(96 * 1024, atLimitPayload.encodeToByteArray().size)
        assertEquals(IngressOutcome.Accepted, acceptedReceiver.receive(signedEnvelopeJson(payloadJson = atLimitPayload)))
        val rejected = assertIs<IngressOutcome.Rejected>(rejectedReceiver.receive(signedEnvelopeJson(payloadJson = overLimitPayload)))

        assertEquals(413, rejected.httpStatus)
        assertEquals(SyncErrorCode.PayloadTooLarge, rejected.error.code)
        assertTrue(rejected.error.detail?.contains("x".repeat(32)) != true)
        assertEquals(emptyList(), rejectedCore.claimedIncoming())
    }

    @Test
    fun rateLimitRejectsOneHundredTwentyFirstRequestAndResetsNextWindow() = runTest {
        val peer = trustedPeer()
        val core = coreWith(peer)
        val receiver = receiver(core, peer, acceptAnySignature = true)
        val sourceKey = "192.0.2.10"

        repeat(120) { index ->
            val outcome = receiver.receive(signedEnvelopeJson(), sourceKey = sourceKey)
            if (index == 0) {
                assertEquals(IngressOutcome.Accepted, outcome)
            } else {
                assertEquals(IngressOutcome.AlreadyAccepted, outcome)
            }
        }

        val limited = assertIs<IngressOutcome.RetryLater>(receiver.receive(signedEnvelopeJson(), sourceKey = sourceKey))
        assertEquals(429, limited.httpStatus)
        assertEquals(60, limited.retryAfterSeconds)
        assertEquals(SyncErrorCode.RateLimited, limited.error.code)
        assertEquals(1, core.claimedIncoming().size)
        assertEquals(emptyList(), core.claimedIncoming())

        now += 60_000L
        assertEquals(IngressOutcome.AlreadyAccepted, receiver.receive(signedEnvelopeJson(), sourceKey = sourceKey))
    }

    @Test
    fun rateLimitIsScopedBySourceKey() = runTest {
        val peer = trustedPeer()
        val core = coreWith(peer)
        val receiver = receiver(core, peer, acceptAnySignature = true)

        repeat(120) {
            receiver.receive(signedEnvelopeJson(), sourceKey = "192.0.2.10")
        }

        assertIs<IngressOutcome.RetryLater>(receiver.receive(signedEnvelopeJson(), sourceKey = "192.0.2.10"))
        assertEquals(IngressOutcome.AlreadyAccepted, receiver.receive(signedEnvelopeJson(), sourceKey = "192.0.2.11"))
    }

    @Test
    fun malformedEnvelopeErrorDetailIsRedacted() = runTest {
        val peer = trustedPeer()
        val core = coreWith(peer)
        val receiver = receiver(core, peer, acceptAnySignature = true)
        val secret = "SECRET-MARKER-1234567890"
        val raw = """
            {
              "senderPeerId": "peer-a",
              "payloadJson": "$secret",
              "signatureBase64": "$secret"
            }
        """.trimIndent()

        val rejected = assertIs<IngressOutcome.Rejected>(receiver.receive(raw))

        assertEquals(400, rejected.httpStatus)
        assertEquals(SyncErrorCode.InvalidMessage, rejected.error.code)
        assertEquals("invalid message", rejected.error.detail)
        assertTrue(rejected.error.detail?.contains(secret) != true)
        assertTrue((rejected.error.detail?.length ?: 0) <= 512)
        assertEquals(emptyList(), core.claimedIncoming())
    }

    @Test
    fun coreRejectedErrorDetailIsRedacted() = runTest {
        val peer = trustedPeer()
        val core = coreWith(peer)
        val receiver = receiver(core, peer, acceptAnySignature = true)
        val secret = "SECRET-REPLAY-PAYLOAD-1234567890"

        assertEquals(IngressOutcome.Accepted, receiver.receive(signedEnvelopeJson(payloadJson = """{"op":"start"}""")))
        val rejected = assertIs<IngressOutcome.Rejected>(
            receiver.receive(signedEnvelopeJson(payloadJson = """{"secret":"$secret"}""")),
        )

        assertEquals(409, rejected.httpStatus)
        assertEquals(SyncErrorCode.ReplayConflict, rejected.error.code)
        assertEquals("replay conflict", rejected.error.detail)
        assertTrue(rejected.error.detail?.contains(secret) != true)
        assertTrue((rejected.error.detail?.length ?: 0) <= 512)
    }

    private fun receiver(
        core: InMemorySyncCore,
        peer: TrustedPeer?,
        acceptAnySignature: Boolean = false,
    ): RawIngressReceiver = RawIngressReceiver(
        syncCore = core,
        trustedPeerResolver = resolver(peer),
        envelopeCodec = EnvelopeCodec(
            clock = clock,
            signatureVerifier = if (acceptAnySignature) EnvelopeSignatureVerifier { _, _, _, _ -> true } else PlatformEnvelopeSignatureVerifier,
        ),
        clock = clock,
    )

    private fun coreWith(
        peer: TrustedPeer?,
        topicPolicy: TopicPolicy = TopicPolicy { _, _, _ -> TopicAuthorization.Allowed },
    ): InMemorySyncCore = InMemorySyncCore(
        clock = clock,
        transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        topicPolicy = topicPolicy,
        trustedPeerResolver = resolver(peer),
    )

    private fun resolver(peer: TrustedPeer?): TrustedPeerResolver = TrustedPeerResolver { peerId ->
        peer?.takeIf { it.peerId == peerId }
    }

    private fun envelopeWithEncodedSize(targetBytes: Int): String {
        val base = signedEnvelopeJson(padding = "")
        val baseBytes = base.encodeToByteArray().size
        require(baseBytes <= targetBytes) { "base envelope is larger than requested size" }
        return signedEnvelopeJson(padding = "x".repeat(targetBytes - baseBytes))
    }

    private fun payloadWithUtf8Size(targetBytes: Int): String {
        val prefix = "{\"data\":\""
        val suffix = "\"}"
        val fillerBytes = targetBytes - prefix.encodeToByteArray().size - suffix.encodeToByteArray().size
        require(fillerBytes >= 0) { "target payload size is too small" }
        return prefix + "x".repeat(fillerBytes) + suffix
    }

    private fun signedEnvelopeJson(
        nonce: String = "nonce-1",
        messageId: String = "cmd-1",
        envelopeId: String = "env-1",
        schemaVersion: Int = 1,
        topic: String = "clock.command.v1",
        payloadJson: String = """{"op":"start"}""",
        padding: String? = null,
    ): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("envelopeId", envelopeId)
            put("messageId", messageId)
            put("senderPeerId", "peer-a")
            put("targetPeerId", "peer-local")
            put("topic", topic)
            put("payloadJson", payloadJson)
            put("sentAtEpochMs", now)
            put("nonce", nonce)
            put("payloadSha256", sha256Hex(payloadJson))
            put("signatureBase64", "HUE6yB6tYG/lr7yxXIEy/9tx9IN0rDwb7tDXehohOPZQpOSxTSwr6BZSDAelOdtny0P8VjbitAzdTQswPYfACA==")
            if (padding != null) put("padding", padding)
        },
    )

    private fun trustedPeer(
        role: PeerRole = PeerRole.Full,
        active: Boolean = true,
    ): TrustedPeer = TrustedPeer(
        peerId = PeerId("peer-a"),
        deviceId = "device-a",
        displayName = "Peer A",
        signingPublicKeyBase64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k=",
        role = role,
        endpoint = "https://peer-a.example",
        active = active,
        signingAlg = "Ed25519",
    )
}





