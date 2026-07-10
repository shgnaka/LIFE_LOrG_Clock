package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.DEFAULT_ENVELOPE_SIGNING_ALG
import io.github.shgnaka.orgclock.synccore.api.OPTIONAL_ED25519_ENVELOPE_SIGNING_ALG
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.VerifiedEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class EnvelopeCodec(
    private val clock: SyncClock,
    private val maxClockSkewMs: Long = 300_000L,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val signatureVerifier: EnvelopeSignatureVerifier = PlatformEnvelopeSignatureVerifier,
) {
    init {
        require(maxClockSkewMs in 30_000L..900_000L) { "maxClockSkewMs must be between 30 and 900 seconds." }
    }

    fun senderPeerId(rawJson: String): PeerId? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val value = root.requiredString("senderPeerId") ?: return null
        return runCatching { PeerId(value) }.getOrNull()
    }
    fun decodeCurrent(rawJson: String, fallbackAlg: String = DEFAULT_ENVELOPE_SIGNING_ALG): EnvelopeDecodeResult {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.InvalidMessage, "invalid envelope JSON")) }
        val schemaVersion = root.requiredInt("schemaVersion") ?: return invalid("missing schemaVersion")
        if (schemaVersion != CURRENT_SCHEMA_VERSION) return invalid("unsupported envelope schemaVersion")
        val explicitAlg = root.requiredString("alg")?.trim()?.takeIf { it.isNotBlank() }
        val alg = explicitAlg ?: fallbackAlg
        if (alg !in SUPPORTED_SIGNATURE_ALGS) return invalid("unsupported envelope alg")
        val payloadJson = root.requiredString("payloadJson") ?: return invalid("missing payloadJson")
        val payloadSha256 = root.requiredString("payloadSha256") ?: return invalid("missing payloadSha256")
        if (sha256Hex(payloadJson) != payloadSha256) {
            return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.SignatureInvalid, "payload hash mismatch"))
        }
        val sentAtEpochMs = root.requiredLong("sentAtEpochMs") ?: return invalid("missing sentAtEpochMs")
        val now = clock.nowEpochMs()
        if (sentAtEpochMs < now - maxClockSkewMs || sentAtEpochMs > now + maxClockSkewMs) {
            return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.TimestampOutOfRange, "envelope timestamp outside allowed skew"))
        }

        val envelope = runCatching {
            VerifiedEnvelope(
                schemaVersion = schemaVersion,
                alg = alg,
                envelopeId = root.requiredString("envelopeId") ?: throw IllegalArgumentException("missing envelopeId"),
                messageId = MessageId(root.requiredString("messageId") ?: throw IllegalArgumentException("missing messageId")),
                senderPeerId = PeerId(root.requiredString("senderPeerId") ?: throw IllegalArgumentException("missing senderPeerId")),
                targetPeerId = PeerId(root.requiredString("targetPeerId") ?: throw IllegalArgumentException("missing targetPeerId")),
                topic = Topic(root.requiredString("topic") ?: throw IllegalArgumentException("missing topic")),
                payloadJson = payloadJson,
                sentAtEpochMs = sentAtEpochMs,
                nonce = root.requiredString("nonce") ?: throw IllegalArgumentException("missing nonce"),
                payloadSha256 = payloadSha256,
            )
        }.getOrElse { return invalid(it.message ?: "invalid envelope") }
        return EnvelopeDecodeResult.Accepted(envelope, canonicalInput(envelope, includeAlg = explicitAlg != null))
    }

    fun decodeAndVerifyCurrent(rawJson: String, trustedPeer: TrustedPeer): EnvelopeDecodeResult {
        if (!trustedPeer.active) {
            return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "peer is not active"))
        }
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.InvalidMessage, "invalid envelope JSON")) }
        val signatureBase64 = root.requiredString("signatureBase64")
            ?: return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.SignatureInvalid, "missing signature"))
        val accepted = when (val decoded = decodeCurrent(rawJson, fallbackAlg = trustedPeer.signingAlg)) {
            is EnvelopeDecodeResult.Accepted -> decoded
            is EnvelopeDecodeResult.Rejected -> return decoded
        }
        if (accepted.envelope.senderPeerId != trustedPeer.peerId) {
            return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "sender peer does not match trusted peer"))
        }
        if (accepted.envelope.alg != trustedPeer.signingAlg) {
            return EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.SignatureInvalid, "signature alg mismatch"))
        }
        val valid = signatureVerifier.verify(
            alg = accepted.envelope.alg,
            publicKeyBase64 = trustedPeer.signingPublicKeyBase64,
            canonicalInput = accepted.canonicalInput,
            signatureBase64 = signatureBase64,
        )
        return if (valid) {
            accepted
        } else {
            EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.SignatureInvalid, "signature invalid"))
        }
    }

    fun canonicalInput(envelope: VerifiedEnvelope): String = canonicalInput(envelope, includeAlg = true)

    private fun canonicalInput(envelope: VerifiedEnvelope, includeAlg: Boolean): String = buildString {
        append("schemaVersion=").append(envelope.schemaVersion).append('\n')
        if (includeAlg) append("alg=").append(envelope.alg).append('\n')
        append("envelopeId=").append(envelope.envelopeId).append('\n')
        append("messageId=").append(envelope.messageId.value).append('\n')
        append("senderPeerId=").append(envelope.senderPeerId.value).append('\n')
        append("targetPeerId=").append(envelope.targetPeerId.value).append('\n')
        append("topic=").append(envelope.topic.value).append('\n')
        append("sentAtEpochMs=").append(envelope.sentAtEpochMs).append('\n')
        append("nonce=").append(envelope.nonce).append('\n')
        append("payloadSha256=").append(envelope.payloadSha256)
    }

    private fun invalid(detail: String): EnvelopeDecodeResult.Rejected =
        EnvelopeDecodeResult.Rejected(SyncError(SyncErrorCode.InvalidMessage, detail))

    private fun JsonObject.requiredString(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.requiredLong(name: String): Long? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        val SUPPORTED_SIGNATURE_ALGS = setOf(DEFAULT_ENVELOPE_SIGNING_ALG, OPTIONAL_ED25519_ENVELOPE_SIGNING_ALG)
    }
}

internal sealed interface EnvelopeDecodeResult {
    data class Accepted(val envelope: VerifiedEnvelope, val canonicalInput: String) : EnvelopeDecodeResult
    data class Rejected(val error: SyncError) : EnvelopeDecodeResult
}

internal fun interface EnvelopeSignatureVerifier {
    fun verify(alg: String, publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean
}

internal object PlatformEnvelopeSignatureVerifier : EnvelopeSignatureVerifier {
    override fun verify(alg: String, publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean =
        verifyEnvelopeSignature(alg, publicKeyBase64, canonicalInput, signatureBase64)
}

internal expect fun sha256Hex(value: String): String
internal expect fun sha256Hex(bytes: ByteArray): String
internal expect fun verifyEnvelopeSignature(alg: String, publicKeyBase64: String, canonicalInput: String, signatureBase64: String): Boolean


