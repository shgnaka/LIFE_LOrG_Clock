package com.example.orgclock.desktop

import com.example.orgclock.sync.ClockEventFetchRequest
import com.example.orgclock.sync.ClockEventFetchResponse
import com.example.orgclock.sync.ClockEventPushRequest
import com.example.orgclock.sync.ClockEventPushResponse
import com.example.orgclock.sync.ClockEventSyncTransport
import com.example.orgclock.sync.ClockEventTransportAck
import com.example.orgclock.sync.ClockEventTransportAckResult
import com.example.orgclock.sync.ClockEventTransportJsonCodec
import com.example.orgclock.sync.SyncTransportCredentialCodec
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class DesktopLanSyncTransport(
    endpoint: String,
    encodedCredential: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : ClockEventSyncTransport {
    private val endpoint = endpoint.trimEnd('/')
    private val credential = SyncTransportCredentialCodec.decode(encodedCredential).getOrThrow()
    private val sslContext = pinnedSslContext(credential.certificateSha256)

    override suspend fun fetch(request: ClockEventFetchRequest): ClockEventFetchResponse =
        ClockEventTransportJsonCodec.decodeFetchResponse(post("/v1/events/fetch", ClockEventTransportJsonCodec.encodeFetchRequest(request)))

    override suspend fun push(request: ClockEventPushRequest): ClockEventPushResponse =
        ClockEventTransportJsonCodec.decodePushResponse(post("/v1/events/push", ClockEventTransportJsonCodec.encodePushRequest(request)))

    override suspend fun acknowledge(ack: ClockEventTransportAck): ClockEventTransportAckResult =
        ClockEventTransportJsonCodec.decodeAckResult(post("/v1/events/ack", ClockEventTransportJsonCodec.encodeAck(ack)))

    private fun post(path: String, body: String): String {
        val connection = (URI.create(endpoint + path).toURL().openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
                PinnedHostnameVerifier(credential.certificateSha256).verify(host, session)
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
            return response
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
            require(secureFingerprintEquals(chain.first(), expectedFingerprint)) { "Server certificate fingerprint mismatch." }
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
}

private class PinnedHostnameVerifier(private val expectedFingerprint: String) {
    fun verify(host: String, session: javax.net.ssl.SSLSession): Boolean = runCatching {
        val certificate = session.peerCertificates.first() as X509Certificate
        secureFingerprintEquals(certificate, expectedFingerprint)
    }.getOrDefault(false)
}

private fun secureFingerprintEquals(certificate: X509Certificate, expected: String): Boolean {
    val actual = certificate.sha256Hex().encodeToByteArray()
    return MessageDigest.isEqual(actual, expected.lowercase().encodeToByteArray())
}
