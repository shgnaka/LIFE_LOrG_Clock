package com.example.orgclock.sync

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.orgclock.template.TemplateFetchRequest
import com.example.orgclock.template.TemplateFetchResponse
import com.example.orgclock.template.TemplatePushRequest
import com.example.orgclock.template.TemplatePushResult
import com.example.orgclock.template.TemplateSharingJsonCodec
import com.example.orgclock.template.TemplateSyncTransport

class AndroidLanSyncTransport(
    endpoint: String,
    encodedCredential: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : ClockEventSyncTransport, TemplateSyncTransport {
    private val endpoint = endpoint.trimEnd('/')
    private val credential = SyncTransportCredentialCodec.decode(encodedCredential).getOrThrow()
    private val sslContext = pinnedSslContext(credential.certificateSha256)

    override suspend fun fetch(request: ClockEventFetchRequest): ClockEventFetchResponse =
        ClockEventTransportJsonCodec.decodeFetchResponse(post("/v1/events/fetch", ClockEventTransportJsonCodec.encodeFetchRequest(request)))

    override suspend fun push(request: ClockEventPushRequest): ClockEventPushResponse =
        ClockEventTransportJsonCodec.decodePushResponse(post("/v1/events/push", ClockEventTransportJsonCodec.encodePushRequest(request)))

    override suspend fun acknowledge(ack: ClockEventTransportAck): ClockEventTransportAckResult =
        ClockEventTransportJsonCodec.decodeAckResult(post("/v1/events/ack", ClockEventTransportJsonCodec.encodeAck(ack)))

    override suspend fun fetchTemplate(request: TemplateFetchRequest): TemplateFetchResponse =
        TemplateSharingJsonCodec.decodeFetchResponse(
            post("/v1/template/fetch", TemplateSharingJsonCodec.encodeFetchRequest(request)),
        )

    override suspend fun pushTemplate(request: TemplatePushRequest): TemplatePushResult =
        TemplateSharingJsonCodec.decodePushResult(
            post("/v1/template/push", TemplateSharingJsonCodec.encodePushRequest(request)),
        )

    suspend fun probe(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URI.create(endpoint + "/v1/health").toURL().openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = HostnameVerifier { _, session ->
                    runCatching {
                        secureFingerprintEquals(session.peerCertificates.first() as X509Certificate, credential.certificateSha256)
                    }.getOrDefault(false)
                }
            }
            try {
                check(connection.responseCode in 200..299) { "health status=${connection.responseCode}" }
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val connection = (URI.create(endpoint + path).toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = HostnameVerifier { _, session ->
                runCatching {
                    secureFingerprintEquals(session.peerCertificates.first() as X509Certificate, credential.certificateSha256)
                }.getOrDefault(false)
            }
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty(PAIRING_HEADER, credential.pairingSecret)
        }
        try {
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "sync https status=$status: ${response.take(300)}" }
            response
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PAIRING_HEADER = "X-OrgClock-Pairing-Key"
    }
}

private fun pinnedSslContext(expectedFingerprint: String): SSLContext {
    val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            require(chain.isNotEmpty()) { "Server certificate is missing." }
            require(secureFingerprintEquals(chain.first(), expectedFingerprint)) {
                "Server certificate fingerprint mismatch. Scan the desktop QR code again."
            }
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
}

private fun secureFingerprintEquals(certificate: X509Certificate, expected: String): Boolean {
    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02x".format(it) }
    return MessageDigest.isEqual(actual.encodeToByteArray(), expected.lowercase().encodeToByteArray())
}

data class SyncPairingExchangeOutcome(
    val encodedTransportCredential: String,
    val hostPeerId: String? = null,
    val hostDeviceId: String? = null,
    val hostDisplayName: String? = null,
    val hostSigningPublicKeyBase64: String? = null,
    val hostSigningAlg: String? = null,
    val grantedRole: PeerTrustRole? = null,
    val certificateSha256: String? = null,
)

suspend fun exchangeDesktopPairingInvitation(
    endpoint: String,
    encodedInvitation: String,
    localDeviceId: String,
    localSigningPublicKeyBase64: String? = null,
    requestedRole: PeerTrustRole = PeerTrustRole.Full,
): Result<SyncPairingExchangeOutcome> = withContext(Dispatchers.IO) {
    runCatching {
        val v2Invitation = SyncPairingInvitationV2Codec.decode(encodedInvitation).getOrNull()
        val v1Invitation = if (v2Invitation == null) SyncPairingInvitationCodec.decode(encodedInvitation).getOrThrow() else null
        val certificateSha256 = v2Invitation?.certificateSha256 ?: requireNotNull(v1Invitation).certificateSha256
        val expiresAtEpochMs = v2Invitation?.expiresAtEpochMs ?: requireNotNull(v1Invitation).expiresAtEpochMs
        require(expiresAtEpochMs > System.currentTimeMillis()) { "Pairing QR code expired. Refresh it on desktop." }
        val sslContext = pinnedSslContext(certificateSha256)
        val connection = (URI.create(endpoint.trimEnd('/') + "/v1/pair").toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = HostnameVerifier { _, session ->
                runCatching { secureFingerprintEquals(session.peerCertificates.first() as X509Certificate, certificateSha256) }.getOrDefault(false)
            }
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = if (v2Invitation != null) {
                val localSigningKey = requireNotNull(localSigningPublicKeyBase64?.takeIf { it.isNotBlank() }) {
                    "local signing public key is required for pairing v2"
                }
                SyncPairingExchangeV2JsonCodec.encodeRequest(
                    SyncPairingExchangeRequestV2(
                        invitationToken = v2Invitation.token,
                        requesterPeerId = localDeviceId,
                        requesterDeviceId = localDeviceId,
                        requesterDisplayName = "Android $localDeviceId",
                        requesterSigningPublicKeyBase64 = localSigningKey,
                        requesterSigningAlg = DEFAULT_SYNC_SIGNING_ALG,
                        requestedRole = requestedRole,
                    ),
                )
            } else {
                SyncPairingExchangeJsonCodec.encodeRequest(
                    SyncPairingExchangeRequest(requireNotNull(v1Invitation).token, localDeviceId, "Android $localDeviceId"),
                )
            }
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "pairing https status=$status: ${response.take(300)}" }
            if (v2Invitation != null) {
                val decoded = SyncPairingExchangeV2JsonCodec.decodeResponse(response)
                SyncPairingExchangeOutcome(
                    encodedTransportCredential = decoded.encodedTransportCredential,
                    hostPeerId = decoded.hostPeerId,
                    hostDeviceId = decoded.hostDeviceId,
                    hostDisplayName = decoded.hostDisplayName,
                    hostSigningPublicKeyBase64 = decoded.hostSigningPublicKeyBase64,
                    hostSigningAlg = decoded.hostSigningAlg,
                    grantedRole = decoded.grantedRole,
                    certificateSha256 = decoded.certificateSha256,
                )
            } else {
                SyncPairingExchangeOutcome(
                    encodedTransportCredential = SyncPairingExchangeJsonCodec.decodeResponse(response).encodedTransportCredential,
                    certificateSha256 = certificateSha256,
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
