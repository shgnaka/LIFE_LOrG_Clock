package com.example.orgclock.desktop

import com.example.orgclock.sync.CLOCK_RESULT_SCHEMA_V1
import com.example.orgclock.sync.ClockResultPayload
import com.example.orgclock.sync.ClockResultStatus
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TopicAuthorization
import io.github.shgnaka.orgclock.synccore.api.TopicPolicy
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.SyncRawIngressReceiver
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSyncCoreRuntimeTest {
    private val clock = SyncClock { NOW_EPOCH_MS }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun receiveAcceptedEnvelopeSchedulesCommandDrainAndAcksInbox() = runTest {
        val peer = TrustedPeer(
            peerId = PeerId("peer-a"),
            deviceId = "device-a",
            displayName = "Peer A",
            signingPublicKeyBase64 = PUBLIC_KEY_BASE64,
            role = PeerRole.Full,
            endpoint = "https://peer-a.example",
            active = true,
            signingAlg = "Ed25519",
        )
        val resolver = TrustedPeerResolver { peerId -> peer.takeIf { it.peerId == peerId } }
        val core = SyncCoreFactory.createInMemory(
            clock = clock,
            trustedPeerResolver = resolver,
            topicPolicy = TopicPolicy { _, _, _ -> TopicAuthorization.Allowed },
        )
        val executor = RecordingDesktopClockCommandExecutor()
        val runtime = DesktopSyncCoreRuntime(
            core = core,
            rawIngressReceiver = SyncRawIngressReceiver(core, resolver, clock),
            commandExecutor = executor,
            drainScope = this,
            nowEpochMs = { NOW_EPOCH_MS },
            localDeviceId = { "desktop-local" },
        )
        runtime.start()

        assertEquals(IngressOutcome.Accepted, runtime.receive(signedEnvelopeJson(), "127.0.0.1"))
        advanceUntilIdle()

        assertEquals(listOf(PAYLOAD_JSON), executor.payloads)
        assertEquals(0L, core.metricsSnapshot().inboxUnprocessedDepth)
        val outgoing = core.listOutgoing(OutgoingQuery()).items.single()
        assertEquals("result-cmd-1", outgoing.messageId.value)
        assertEquals(Topic(CLOCK_RESULT_SCHEMA_V1), outgoing.topic)
        assertEquals(PeerId("peer-a"), outgoing.peerId)
        assertEquals(NOW_EPOCH_MS + RESULT_EXPIRATION_MS, outgoing.expiresAtEpochMs)
    }

    private class RecordingDesktopClockCommandExecutor : DesktopClockCommandExecutor {
        val payloads = mutableListOf<String>()

        override suspend fun execute(rawPayload: String): ClockResultPayload {
            payloads += rawPayload
            return ClockResultPayload(
                commandId = "cmd-1",
                status = ClockResultStatus.Applied,
                appliedAt = Instant.fromEpochMilliseconds(NOW_EPOCH_MS),
                byDeviceId = "desktop-local",
            )
        }
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
            put("payloadJson", PAYLOAD_JSON)
            put("sentAtEpochMs", NOW_EPOCH_MS)
            put("nonce", "nonce-1")
            put("payloadSha256", sha256Hex(PAYLOAD_JSON))
            put("signatureBase64", SIGNATURE_BASE64)
        },
    )

    private companion object {
        const val NOW_EPOCH_MS = 1_000_000L
        const val RESULT_EXPIRATION_MS = 7L * 24L * 60L * 60L * 1_000L
        const val PAYLOAD_JSON = """{"op":"start"}"""
        const val PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k="
        const val SIGNATURE_BASE64 = "HUE6yB6tYG/lr7yxXIEy/9tx9IN0rDwb7tDXehohOPZQpOSxTSwr6BZSDAelOdtny0P8VjbitAzdTQswPYfACA=="
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
