package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.claimedIncoming
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncRawIngressReceiver
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

class SyncRawIngressReceiverPublicApiTest {
    private val clock = SyncClock { 1_000_000L }

    @Test
    fun publicReceiverVerifiesRawEnvelopeAndStoresIncomingMessage() = runTest {
        val peer = TrustedPeer(
            peerId = PeerId("peer-a"),
            deviceId = "device-a",
            displayName = "Peer A",
            signingPublicKeyBase64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k=",
            role = PeerRole.Full,
            endpoint = "https://peer-a.example",
            active = true,
            signingAlg = "Ed25519",
        )
        val resolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } }
        val core = InMemorySyncCore(
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
            trustedPeerResolver = resolver,
        )
        val receiver = SyncRawIngressReceiver(
            syncCore = core,
            trustedPeerResolver = resolver,
            clock = clock,
        )

        assertEquals(IngressOutcome.Accepted, receiver.receive(signedEnvelopeJson()))

        val receipt = core.claimedIncoming().single()
        assertEquals("cmd-1", receipt.messageId.value)
        assertEquals("peer-a", receipt.senderPeerId.value)
        assertEquals("""{"op":"start"}""", receipt.payloadJson)
    }

    private fun signedEnvelopeJson(): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", 1)
            put("envelopeId", "env-1")
            put("messageId", "cmd-1")
            put("senderPeerId", "peer-a")
            put("targetPeerId", "peer-local")
            put("topic", "clock.command.v1")
            put("payloadJson", """{"op":"start"}""")
            put("sentAtEpochMs", 1_000_000L)
            put("nonce", "nonce-1")
            put("payloadSha256", sha256Hex("""{"op":"start"}"""))
            put("signatureBase64", "HUE6yB6tYG/lr7yxXIEy/9tx9IN0rDwb7tDXehohOPZQpOSxTSwr6BZSDAelOdtny0P8VjbitAzdTQswPYfACA==")
        },
    )
}
