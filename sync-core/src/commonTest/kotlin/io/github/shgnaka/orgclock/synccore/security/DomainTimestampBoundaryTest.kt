package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.claimedIncoming
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.engine.InMemorySyncCore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainTimestampBoundaryTest {
    private var now = 1_000_000L
    private val clock = SyncClock { now }

    @Test
    fun oldPayloadFreshEnvelope() = runTest {
        val peer = trustedPeer()
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } },
        )
        val receiver = RawIngressReceiver(
            syncCore = core,
            trustedPeerResolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } },
            envelopeCodec = EnvelopeCodec(
                clock = clock,
                signatureVerifier = EnvelopeSignatureVerifier { _, _, _, _ -> true },
            ),
            clock = clock,
        )
        val oldPayload = """
            {
              "op": "start",
              "requested_at": "1900-01-01T00:00:00Z"
            }
        """.trimIndent()

        assertEquals(IngressOutcome.Accepted, receiver.receive(signedEnvelopeJson(payloadJson = oldPayload)))

        val receipt = core.claimedIncoming().single()
        assertEquals(MessageId("cmd-domain-time"), receipt.messageId)
        assertEquals(oldPayload, receipt.payloadJson)
    }

    private fun signedEnvelopeJson(payloadJson: String): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", 1)
            put("envelopeId", "env-domain-time")
            put("messageId", "cmd-domain-time")
            put("senderPeerId", "peer-a")
            put("targetPeerId", "peer-local")
            put("topic", "clock.command.v1")
            put("payloadJson", payloadJson)
            put("sentAtEpochMs", now)
            put("nonce", "nonce-domain-time")
            put("payloadSha256", sha256Hex(payloadJson))
            put("signatureBase64", "test-signature")
        },
    )

    private fun trustedPeer(): TrustedPeer = TrustedPeer(
        peerId = PeerId("peer-a"),
        deviceId = "device-a",
        displayName = "Peer A",
        signingPublicKeyBase64 = "test-key",
        role = PeerRole.Full,
        endpoint = "https://peer-a.example",
        active = true,
    )
}
