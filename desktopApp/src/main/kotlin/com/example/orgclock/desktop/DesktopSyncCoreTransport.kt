package com.example.orgclock.desktop

import com.example.orgclock.sync.SyncTransportCredential
import com.example.orgclock.sync.SyncTransportCredentialCodec
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
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

internal class DesktopSyncCoreTransport(
    private val peerTrustStore: PeerTrustStore,
    private val credentialStore: DesktopSyncCoreTransportCredentialStore? = null,
    private val signer: DesktopSyncCoreEnvelopeSigner,
    private val clockEpochMs: () -> Long,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val poster: DesktopSyncCoreHttpPoster = DesktopSyncCoreHttpsPoster(),
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
        val credential = credentialStore?.get(record.peerId)
            ?: SyncTransportCredentialCodec.decode(record.publicKeyBase64).getOrNull()
            ?: return DispatchOutcome.Rejected(SyncError(SyncErrorCode.PeerNotTrusted, "target peer transport credential is missing"))
        val envelope = buildEnvelope(message).getOrElse { error ->
            return DispatchOutcome.Rejected(
                SyncError(SyncErrorCode.ProtocolError, error.message ?: "sync-core envelope signing failed"),
            )
        }

        return when (val result = poster.post(resolveMessagesEndpoint(endpoint), credential, envelope)) {
            DesktopSyncCoreHttpPostResult.Accepted -> DispatchOutcome.Accepted
            is DesktopSyncCoreHttpPostResult.Rejected -> DispatchOutcome.Rejected(result.error)
            is DesktopSyncCoreHttpPostResult.Retryable -> DispatchOutcome.Retryable(result.error)
        }
    }

    private fun buildEnvelope(message: SyncMessage): Result<String> = runCatching {
        val envelopeId = "env-${UUID.randomUUID()}"
        val sentAt = clockEpochMs()
        val payloadSha256 = desktopSyncCoreSha256Hex(message.payloadJson)
        val nonceValue = nonce()
        val canonicalInput = desktopSyncCoreCanonicalInput(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            alg = signer.signingAlg,
            envelopeId = envelopeId,
            messageId = message.messageId.value,
            senderPeerId = signer.senderPeerId,
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
                put("alg", signer.signingAlg)
                put("envelopeId", envelopeId)
                put("messageId", message.messageId.value)
                put("senderPeerId", signer.senderPeerId)
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

internal interface DesktopSyncCoreEnvelopeSigner {
    val senderPeerId: String
    val signingAlg: String
        get() = "ES256"
    fun signCanonical(canonicalInput: String): Result<String>
}

internal class DesktopSyncCoreIdentityEnvelopeSigner(
    private val rootPath: Path,
    private val syncIdentity: DesktopSyncIdentity,
    override val senderPeerId: String,
) : DesktopSyncCoreEnvelopeSigner {
    override fun signCanonical(canonicalInput: String): Result<String> =
        syncIdentity.signSyncCoreEnvelope(rootPath, canonicalInput)
}

internal interface DesktopSyncCoreHttpPoster {
    suspend fun post(endpoint: String, credential: SyncTransportCredential, body: String): DesktopSyncCoreHttpPostResult
}

internal sealed interface DesktopSyncCoreHttpPostResult {
    data object Accepted : DesktopSyncCoreHttpPostResult
    data class Rejected(val error: SyncError) : DesktopSyncCoreHttpPostResult
    data class Retryable(val error: SyncError) : DesktopSyncCoreHttpPostResult
}

internal class DesktopSyncCoreHttpsPoster(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : DesktopSyncCoreHttpPoster {
    override suspend fun post(
        endpoint: String,
        credential: SyncTransportCredential,
        body: String,
    ): DesktopSyncCoreHttpPostResult = withContext(Dispatchers.IO) {
        runCatching {
            val sslContext = desktopSyncCorePinnedSslContext(credential.certificateSha256)
            val connection = (URI.create(endpoint).toURL().openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = HostnameVerifier { _, session ->
                    runCatching {
                        desktopSyncCoreSecureFingerprintEquals(
                            session.peerCertificates.first() as X509Certificate,
                            credential.certificateSha256,
                        )
                    }.getOrDefault(false)
                }
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty(DesktopLanSyncTransport.PAIRING_HEADER, credential.pairingSecret)
            }
            try {
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
                desktopSyncCoreHttpPostResultForStatus(connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }.getOrElse(::desktopSyncCoreHttpPostResultForFailure)
    }
}

internal fun desktopSyncCoreHttpPostResultForStatus(status: Int): DesktopSyncCoreHttpPostResult = when (status) {
    in 200..299 -> DesktopSyncCoreHttpPostResult.Accepted
    400, 404, 409, 422 -> DesktopSyncCoreHttpPostResult.Rejected(
        SyncError(SyncErrorCode.ProtocolError, "http_status=$status"),
    )
    401, 403 -> DesktopSyncCoreHttpPostResult.Rejected(
        SyncError(SyncErrorCode.PeerNotAuthorized, "http_status=$status"),
    )
    413 -> DesktopSyncCoreHttpPostResult.Rejected(
        SyncError(SyncErrorCode.PayloadTooLarge, "http_status=$status"),
    )
    408 -> DesktopSyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.Timeout, "http_status=$status"),
    )
    425, 500, 502, 503, 504 -> DesktopSyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.RemoteBusy, "http_status=$status"),
    )
    429 -> DesktopSyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.RateLimited, "http_status=$status"),
    )
    else -> DesktopSyncCoreHttpPostResult.Retryable(
        SyncError(SyncErrorCode.NetworkUnreachable, "http_status=$status"),
    )
}

internal fun desktopSyncCoreHttpPostResultForFailure(error: Throwable): DesktopSyncCoreHttpPostResult =
    when (error) {
        is SSLPeerUnverifiedException -> DesktopSyncCoreHttpPostResult.Rejected(
            SyncError(SyncErrorCode.CertificatePinMismatch, error.message),
        )
        is SSLHandshakeException -> DesktopSyncCoreHttpPostResult.Rejected(
            SyncError(SyncErrorCode.CertificatePinMismatch, error.message),
        )
        is SSLException -> DesktopSyncCoreHttpPostResult.Rejected(
            SyncError(SyncErrorCode.CertificatePinMismatch, error.message),
        )
        is SocketTimeoutException -> DesktopSyncCoreHttpPostResult.Retryable(
            SyncError(SyncErrorCode.Timeout, error.message),
        )
        is IOException -> DesktopSyncCoreHttpPostResult.Retryable(
            SyncError(SyncErrorCode.NetworkUnreachable, error.message),
        )
        else -> DesktopSyncCoreHttpPostResult.Retryable(
            SyncError(SyncErrorCode.NetworkUnreachable, error.message ?: "network request failed"),
        )
    }

private fun desktopSyncCoreCanonicalInput(
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

private fun desktopSyncCoreSha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private fun desktopSyncCorePinnedSslContext(expectedFingerprint: String): SSLContext {
    val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            require(chain.isNotEmpty()) { "Server certificate is missing." }
            require(desktopSyncCoreSecureFingerprintEquals(chain.first(), expectedFingerprint)) {
                "Server certificate fingerprint mismatch."
            }
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
}

private fun desktopSyncCoreSecureFingerprintEquals(certificate: X509Certificate, expected: String): Boolean {
    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
    return MessageDigest.isEqual(actual.encodeToByteArray(), expected.lowercase().encodeToByteArray())
}
