package io.github.shgnaka.orgclock.synccore.security

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal actual fun verifyEnvelopeSignature(alg: String, publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean =
    when (alg) {
        "ES256" -> verifyEs256(publicKeyBase64, canonicalInput, signatureBase64)
        "Ed25519" -> verifyEd25519(publicKeyBase64, canonicalInput, signatureBase64)
        else -> false
    }

private fun verifyEd25519(publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean =
    runCatching {
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(canonicalInput.toByteArray(Charsets.UTF_8))
        verifier.verify(Base64.getDecoder().decode(signatureBase64))
    }.getOrDefault(false)

private fun verifyEs256(publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean =
    runCatching {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))) as ECPublicKey
        require(publicKey.params.curve.field.fieldSize == 256) { "ES256 requires P-256 public key" }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(canonicalInput.toByteArray(Charsets.UTF_8))
        verifier.verify(es256RawToDer(Base64.getDecoder().decode(signatureBase64)))
    }.getOrDefault(false)

private fun es256RawToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64) { "ES256 signature must be 64 bytes" }
    val r = derInteger(raw.copyOfRange(0, 32))
    val s = derInteger(raw.copyOfRange(32, 64))
    val sequenceLength = r.size + s.size
    return byteArrayOf(0x30, sequenceLength.toByte()) + r + s
}

private fun derInteger(value: ByteArray): ByteArray {
    val strippedBytes = value.dropWhile { it == 0.toByte() }.toByteArray()
    val stripped = if (strippedBytes.isEmpty()) byteArrayOf(0) else strippedBytes
    val positive = if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
    return byteArrayOf(0x02, positive.size.toByte()) + positive
}
