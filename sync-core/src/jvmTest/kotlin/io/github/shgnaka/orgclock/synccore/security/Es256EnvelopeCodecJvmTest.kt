package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertIs

class Es256EnvelopeCodecJvmTest {
    @Test
    fun validEs256SignatureIsAccepted() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val codec = EnvelopeCodec(clock = SyncClock { NOW_EPOCH_MS })
        val payloadJson = """{"op":"start"}"""
        val payloadSha256 = sha256Hex(payloadJson)
        val envelope = codec.decodeCurrent(
            envelopeJson(payloadJson = payloadJson, payloadSha256 = payloadSha256, signatureBase64 = ""),
        ) as EnvelopeDecodeResult.Accepted
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(envelope.canonicalInput.toByteArray(Charsets.UTF_8))
        }
        val signedJson = envelopeJson(
            payloadJson = payloadJson,
            payloadSha256 = payloadSha256,
            signatureBase64 = Base64.getEncoder().encodeToString(ecdsaDerToJoseRaw(signer.sign())),
        )

        val result = codec.decodeAndVerifyCurrent(
            signedJson,
            TrustedPeer(
                peerId = PeerId("peer-a"),
                deviceId = "device-a",
                displayName = "Peer A",
                signingPublicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                role = PeerRole.Full,
                endpoint = "https://peer-a.example",
                active = true,
                signingAlg = "ES256",
            ),
        )

        assertIs<EnvelopeDecodeResult.Accepted>(result)
    }

    private fun envelopeJson(payloadJson: String, payloadSha256: String, signatureBase64: String): String =
        Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", 1)
                put("alg", "ES256")
                put("envelopeId", "env-1")
                put("messageId", "cmd-1")
                put("senderPeerId", "peer-a")
                put("targetPeerId", "peer-local")
                put("topic", "clock.command.v1")
                put("payloadJson", payloadJson)
                put("sentAtEpochMs", NOW_EPOCH_MS)
                put("nonce", "nonce-1")
                put("payloadSha256", payloadSha256)
                put("signatureBase64", signatureBase64)
            },
        )

    private companion object {
        const val NOW_EPOCH_MS = 1_000_000L
    }
}

private fun ecdsaDerToJoseRaw(der: ByteArray, partSize: Int = 32): ByteArray {
    require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER signature." }
    var index = 2
    require(der[index] == 0x02.toByte()) { "Invalid ECDSA DER integer." }
    val rLength = der[index + 1].toInt() and 0xff
    val r = der.copyOfRange(index + 2, index + 2 + rLength)
    index += 2 + rLength
    require(der[index] == 0x02.toByte()) { "Invalid ECDSA DER integer." }
    val sLength = der[index + 1].toInt() and 0xff
    val s = der.copyOfRange(index + 2, index + 2 + sLength)
    return unsignedFixed(r, partSize) + unsignedFixed(s, partSize)
}

private fun unsignedFixed(value: ByteArray, size: Int): ByteArray {
    val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
    require(stripped.size <= size) { "ECDSA integer is too large." }
    return ByteArray(size - stripped.size) + stripped
}
