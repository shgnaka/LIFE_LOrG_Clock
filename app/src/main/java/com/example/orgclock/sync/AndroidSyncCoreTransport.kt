package com.example.orgclock.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AndroidSyncCoreTransport(
    private val peerTrustStore: PeerTrustStore,
    private val credentialStore: SyncCoreTransportCredentialStore,
    private val signer: SyncCoreEnvelopeSigner,
    private val clockEpochMs: () -> Long,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val poster: SyncCoreHttpPoster = HttpsSyncCoreHttpPoster(),
    private val json: Json = Json,
) : SyncTransport {
    override suspend fun dispatch(message: SyncMessage, _attempt: Int): DispatchOutcome {
        val record = peerTrustStore.getTrustRecord(message.targetPeerId.value)
            ?: return DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "target peer is not trusted"))
        if (!record.isActive) {
            return DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "target peer is revoked"))
        }
        val endpoint = record.endpoint?.trim()?.takeIf { it.isNotBlank() }
            ?: return DispatchOutcome.Rejected(SyncError(SyncErrorCode.ProtocolError, "target peer endpoint is missing"))
        if (!endpoint.lowercase().startsWith("https://")) {
            return DispatchOutcome.Rejected(SyncError(SyncErrorCode.ProtocolError, "cleartext endpoint is not allowed"))
        }
        val credential = credentialStore.get(record.peerId) ?: legacyCredential(record)
            ?: return DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "target peer transport credential is missing"))
        val envelope = buildEnvelope(message).getOrElse { error ->
            return DispatchOutcome.Rejected(
                SyncError(SyncErrorCode.ProtocolError, error.message ?: "sync-core envelope signing failed"),
            )
        }

        return when (val result = poster.post(resolveMessagesEndpoint(endpoint), credential, envelope)) {
            is SyncCoreHttpPostResult.Accepted -> DispatchOutcome.Accepted
            is SyncCoreHttpPostResult.Rejected -> DispatchOutcome.Rejected(result.error)
            is SyncCoreHttpPostResult.Retryable -> DispatchOutcome.Retryable(result.error)
        }
    }

    private fun legacyCredential(record: PeerTrustRecord): SyncTransportCredential? =
        SyncTransportCredentialCodec.decode(record.publicKeyBase64).getOrNull()

    private fun buildEnvelope(message: SyncMessage): Result<String> = runCatching {
        val envelopeId = "env-${UUID.randomUUID()}"
        val sentAt = clockEpochMs()
        val payloadSha256 = sha256Hex(message.payloadJson)
        val nonceValue = nonce()
        val senderPeerId = signer.senderPeerId
        val alg = signer.signingAlg
        val canonicalInput = canonicalInput(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            alg = alg,
            envelopeId = envelopeId,
            messageId = message.messageId.value,
            senderPeerId = senderPeerId,
            targetPeerId = message.targetPeerId.value,
            topic = message.topic.value,
            sentAtEpochMs = sentAt,
            nonce = nonceValue,
            payloadSha256 = payloadSha256,
        )
        val signatureBase64 = signer.signCanonical(canonicalInput).getOrElse { throw it }
        json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("schemaVersion", CURRENT_SCHEMA_VERSION)
                put("alg", alg)
                put("envelopeId", envelopeId)
                put("messageId", message.messageId.value)
                put("senderPeerId", senderPeerId)
                put("targetPeerId", message.targetPeerId.value)
                put("topic", message.topic.value)
                put("payloadJson", message.payloadJson)
                put("sentAtEpochMs", sentAt)
                put("nonce", nonceValue)
                put("payloadSha256", payloadSha256)
                put("signatureBase64", signatureBase64)
            },
        )
    }

    private fun resolveMessagesEndpoint(endpoint: String): String {
        val trimmed = endpoint.trimEnd('/')
        return if (trimmed.endsWith("/v1/messages")) trimmed else "$trimmed/v1/messages"
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

internal interface SyncCoreEnvelopeSigner {
    val senderPeerId: String
    val signingAlg: String
        get() = ES256_SIGNING_ALG
    fun signCanonical(canonicalInput: String): Result<String>
}

internal class AndroidKeystoreSyncCoreEnvelopeSigner(
    private val deviceIdProvider: DeviceIdProvider,
    private val keyAliasProvider: () -> String = { DEFAULT_KEY_ALIAS },
) : SyncCoreEnvelopeSigner {
    override val senderPeerId: String
        get() = deviceIdProvider.getOrCreate()

    override fun signCanonical(canonicalInput: String): Result<String> = runCatching {
        val alias = keyAliasProvider().trim().ifBlank { DEFAULT_KEY_ALIAS }
        val privateKey = loadOrCreateSigningKeyEntry(alias).privateKey
        val signature = Signature.getInstance(ES256_SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(canonicalInput.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(ecdsaDerToJoseRaw(signature.sign()))
    }

    fun publicKeyBase64(): Result<String> = runCatching {
        val alias = keyAliasProvider().trim().ifBlank { DEFAULT_KEY_ALIAS }
        Base64.getEncoder().encodeToString(loadOrCreateSigningKeyEntry(alias).certificate.publicKey.encoded)
    }

    private fun loadOrCreateSigningKeyEntry(alias: String): KeyStore.PrivateKeyEntry {
        val keyStore = loadKeyStore()
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()

        val created = loadKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        requireNotNull(created?.privateKey) { "sync-core envelope signing key is unavailable after generation" }
        return created
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "orgclock.sync_core.envelope_signing.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ES256_SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val P256_CURVE = "secp256r1"
    }
}

internal interface SyncCoreHttpPoster {
    suspend fun post(endpoint: String, credential: SyncTransportCredential, body: String): SyncCoreHttpPostResult
}

internal sealed interface SyncCoreHttpPostResult {
    data object Accepted : SyncCoreHttpPostResult
    data class Rejected(val error: SyncError) : SyncCoreHttpPostResult
    data class Retryable(val error: SyncError) : SyncCoreHttpPostResult
}

internal class HttpsSyncCoreHttpPoster(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : SyncCoreHttpPoster {
    override suspend fun post(endpoint: String, credential: SyncTransportCredential, body: String): SyncCoreHttpPostResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val sslContext = syncCorePinnedSslContext(credential.certificateSha256)
                val connection = (URI.create(endpoint).toURL().openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    sslSocketFactory = sslContext.socketFactory
                    hostnameVerifier = HostnameVerifier { _, session ->
                        runCatching {
                            syncCoreSecureFingerprintEquals(
                                session.peerCertificates.first() as X509Certificate,
                                credential.certificateSha256,
                            )
                        }.getOrDefault(false)
                    }
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty(AndroidLanSyncTransport.PAIRING_HEADER, credential.pairingSecret)
                }
                try {
                    connection.outputStream.use { it.write(body.encodeToByteArray()) }
                    val status = connection.responseCode
                    syncCoreHttpPostResultForStatus(status)
                } finally {
                    connection.disconnect()
                }
            }.getOrElse(::syncCoreHttpPostResultForFailure)
        }
}

internal fun syncCoreHttpPostResultForStatus(status: Int): SyncCoreHttpPostResult = when (status) {
    in 200..299 -> SyncCoreHttpPostResult.Accepted
    400, 404, 409, 422 -> SyncCoreHttpPostResult.Rejected(
        SyncError(SyncErrorCode.ProtocolError, "http_status=$status"),
    )
    401, 403 -> SyncCoreHttpPostResult.Rejected(
        SyncError(SyncErrorCode.PeerNotAuthorized, "http_status=$status"),
    )
    413 -> SyncCoreHttpPostResult.Rejected(
        SyncError(SyncErrorCode.PayloadTooLarge, "http_status=$status"),
    )
    408 -> SyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.Timeout, "http_status=$status"),
    )
    425, 500, 502, 503, 504 -> SyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.RemoteBusy, "http_status=$status"),
    )
    429 -> SyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.RateLimited, "http_status=$status"),
    )
    else -> SyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.NetworkUnreachable, "http_status=$status"),
    )
}

internal fun syncCoreHttpPostResultForFailure(error: Throwable): SyncCoreHttpPostResult =
    when (error) {
        is SSLPeerUnverifiedException -> SyncCoreHttpPostResult.Rejected(
            SyncError(SyncErrorCode.CertificatePinMismatch, error.message),
        )
        is SSLHandshakeException -> SyncCoreHttpPostResult.Rejected(
            SyncError(SyncErrorCode.CertificatePinMismatch, error.message),
        )
        is SSLException -> SyncCoreHttpPostResult.Rejected(
            SyncError(SyncErrorCode.CertificatePinMismatch, error.message),
        )
        is SocketTimeoutException -> SyncCoreHttpPostResult.Retryable(
            SyncError(SyncErrorCode.Timeout, error.message),
        )
        is IOException -> SyncCoreHttpPostResult.Retryable(
            SyncError(SyncErrorCode.NetworkUnreachable, error.message),
        )
        else -> SyncCoreHttpPostResult.Retryable(
            SyncError(SyncErrorCode.NetworkUnreachable, error.message ?: "network request failed"),
        )
    }

private fun canonicalInput(
    schemaVersion: Int,
    alg: String,
    envelopeId: String,
    messageId: String,
    senderPeerId: String,
    targetPeerId: String,
    topic: String,
    sentAtEpochMs: Long,
    nonce: String,
    payloadSha256: String,
): String = buildString {
    append("schemaVersion=").append(schemaVersion).append('\n')
    append("alg=").append(alg).append('\n')
    append("envelopeId=").append(envelopeId).append('\n')
    append("messageId=").append(messageId).append('\n')
    append("senderPeerId=").append(senderPeerId).append('\n')
    append("targetPeerId=").append(targetPeerId).append('\n')
    append("topic=").append(topic).append('\n')
    append("sentAtEpochMs=").append(sentAtEpochMs).append('\n')
    append("nonce=").append(nonce).append('\n')
    append("payloadSha256=").append(payloadSha256)
}

internal const val ES256_SIGNING_ALG = "ES256"

internal fun ecdsaDerToJoseRaw(der: ByteArray, partSize: Int = 32): ByteArray {
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

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private fun syncCorePinnedSslContext(expectedFingerprint: String): SSLContext {
    val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            require(chain.isNotEmpty()) { "Server certificate is missing." }
            require(syncCoreSecureFingerprintEquals(chain.first(), expectedFingerprint)) {
                "Server certificate fingerprint mismatch. Scan the desktop QR code again."
            }
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
}

private fun syncCoreSecureFingerprintEquals(certificate: X509Certificate, expected: String): Boolean {
    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
    return MessageDigest.isEqual(actual.encodeToByteArray(), expected.lowercase().encodeToByteArray())
}
