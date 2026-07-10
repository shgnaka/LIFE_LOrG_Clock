package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EnvelopeCodecTest {
    private var now = 1_000_000L
    private val codec = EnvelopeCodec(clock = SyncClock { now })

    @Test
    fun timestampBoundaryIsInclusive() {
        assertAccepted(envelopeJson(sentAtEpochMs = now - 300_000L))
        assertAccepted(envelopeJson(sentAtEpochMs = now + 300_000L))

        assertRejected(SyncErrorCode.TimestampOutOfRange, envelopeJson(sentAtEpochMs = now - 300_001L))
        assertRejected(SyncErrorCode.TimestampOutOfRange, envelopeJson(sentAtEpochMs = now + 300_001L))
    }

    @Test
    fun payloadHashMismatchIsRejectedBeforeVerification() {
        assertRejected(
            SyncErrorCode.SignatureInvalid,
            envelopeJson(payloadJson = """{"op":"start"}""", payloadSha256 = "not-the-real-hash"),
        )
    }

    @Test
    fun canonicalInputIsStableAcrossJsonFieldOrderAndWhitespace() {
        val payload = """{"op":"start","requested_at":"1900-01-01T00:00:00Z"}"""
        val hash = sha256Hex(payload)
        val first = envelopeJson(payloadJson = payload, payloadSha256 = hash)
        val second = """
            {
              "payloadSha256": "$hash",
              "nonce": "nonce-1",
              "sentAtEpochMs": $now,
              "payloadJson": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), payload)},
              "topic": "clock.command.v1",
              "targetPeerId": "peer-local",
              "senderPeerId": "peer-a",
              "messageId": "cmd-1",
              "envelopeId": "env-1",
              "schemaVersion": 1
            }
        """.trimIndent()

        val acceptedFirst = assertAccepted(first)
        val acceptedSecond = assertAccepted(second)

        assertEquals(acceptedFirst.canonicalInput, acceptedSecond.canonicalInput)
        assertEquals(MessageId("cmd-1"), acceptedSecond.envelope.messageId)
    }

    @Test
    fun unknownOptionalFieldIsIgnoredButUnsupportedVersionIsRejected() {
        val optional = assertAccepted(envelopeJson(extraField = "future-optional"))
        val unsupported = assertIs<EnvelopeDecodeResult.Rejected>(codec.decodeCurrent(envelopeJson(schemaVersion = 2)))

        assertEquals(MessageId("cmd-1"), optional.envelope.messageId)
        assertEquals(SyncErrorCode.InvalidMessage, unsupported.error.code)
        assertEquals("unsupported envelope schemaVersion", unsupported.error.detail)
    }

    @Test
    fun validEd25519SignatureIsAccepted() {
        val accepted = assertVerified(legacySignedEnvelopeJson(), trustedPeer())

        assertEquals(MessageId("cmd-1"), accepted.envelope.messageId)
        assertEquals("peer-a", accepted.envelope.senderPeerId.value)
    }

    @Test
    fun tamperedSignedFieldIsRejected() {
        val tampered = legacySignedEnvelopeJson(nonce = "nonce-tampered")

        assertVerifyRejected(SyncErrorCode.SignatureInvalid, tampered, trustedPeer())
    }

    @Test
    fun inactiveTrustedPeerIsRejectedBeforePersistence() {
        assertVerifyRejected(
            SyncErrorCode.PeerNotTrusted,
            legacySignedEnvelopeJson(),
            trustedPeer(active = false),
        )
    }

    @Test
    fun mismatchedTrustedPeerIsRejected() {
        assertVerifyRejected(
            SyncErrorCode.PeerNotTrusted,
            legacySignedEnvelopeJson(),
            trustedPeer(peerId = "peer-other"),
        )
    }

    private fun assertAccepted(rawJson: String): EnvelopeDecodeResult.Accepted =
        assertIs<EnvelopeDecodeResult.Accepted>(codec.decodeCurrent(rawJson))

    private fun assertVerified(rawJson: String, trustedPeer: TrustedPeer): EnvelopeDecodeResult.Accepted =
        assertIs<EnvelopeDecodeResult.Accepted>(codec.decodeAndVerifyCurrent(rawJson, trustedPeer))

    private fun assertRejected(code: SyncErrorCode, rawJson: String) {
        val rejected = assertIs<EnvelopeDecodeResult.Rejected>(codec.decodeCurrent(rawJson))
        assertEquals(code, rejected.error.code)
    }

    private fun assertVerifyRejected(code: SyncErrorCode, rawJson: String, trustedPeer: TrustedPeer) {
        val rejected = assertIs<EnvelopeDecodeResult.Rejected>(codec.decodeAndVerifyCurrent(rawJson, trustedPeer))
        assertEquals(code, rejected.error.code)
    }

    private fun envelopeJson(
        payloadJson: String = """{"op":"start"}""",
        payloadSha256: String = sha256Hex(payloadJson),
        sentAtEpochMs: Long = now,
        schemaVersion: Int = 1,
        extraField: String? = null,
    ): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("envelopeId", "env-1")
            put("messageId", "cmd-1")
            put("senderPeerId", "peer-a")
            put("targetPeerId", "peer-local")
            put("topic", "clock.command.v1")
            put("payloadJson", payloadJson)
            put("sentAtEpochMs", sentAtEpochMs)
            put("nonce", "nonce-1")
            put("payloadSha256", payloadSha256)
            if (extraField != null) put("futureOptionalField", extraField)
        },
    )

    private fun legacySignedEnvelopeJson(nonce: String = "nonce-1"): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", 1)
            put("envelopeId", "env-1")
            put("messageId", "cmd-1")
            put("senderPeerId", "peer-a")
            put("targetPeerId", "peer-local")
            put("topic", "clock.command.v1")
            put("payloadJson", """{"op":"start"}""")
            put("sentAtEpochMs", now)
            put("nonce", nonce)
            put("payloadSha256", "2347fa81430925fd3dc894626c3a042414144615e9cba40055b07a75e95771ad")
            put("signatureBase64", "HUE6yB6tYG/lr7yxXIEy/9tx9IN0rDwb7tDXehohOPZQpOSxTSwr6BZSDAelOdtny0P8VjbitAzdTQswPYfACA==")
        },
    )

    private fun trustedPeer(peerId: String = "peer-a", active: Boolean = true): TrustedPeer = TrustedPeer(
        peerId = PeerId(peerId),
        deviceId = "device-a",
        displayName = "Peer A",
        signingPublicKeyBase64 = "MCowBQYDK2VwAyEACNuzzJtZpQ4vRpulbVwiR+3a1mrKgn5cR/8BHs4Mp/k=",
        role = PeerRole.Full,
        endpoint = "https://peer-a.example",
        active = active,
        signingAlg = "Ed25519",
    )
}
