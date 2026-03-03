package com.example.orgclock.sync

import java.net.HttpURLConnection
import java.net.URI

data class PeerProbeResult(
    val peerId: String,
    val reachable: Boolean,
    val checkedAtEpochMs: Long,
    val reason: String? = null,
)

interface PeerHealthChecker {
    suspend fun probe(peerId: String): PeerProbeResult
}

class HttpPeerHealthChecker(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 3_000,
) : PeerHealthChecker {
    override suspend fun probe(peerId: String): PeerProbeResult {
        val now = System.currentTimeMillis()
        val normalized = peerId.trim()
        if (normalized.isBlank()) {
            return PeerProbeResult(
                peerId = normalized,
                reachable = false,
                checkedAtEpochMs = now,
                reason = "peer id is empty",
            )
        }
        val endpoint = if (normalized.contains("://")) {
            normalized
        } else {
            "https://$normalized"
        }.trimEnd('/')
        return runCatching {
            val connection = (URI.create("$endpoint/v1/health").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }
            val code = connection.responseCode
            PeerProbeResult(
                peerId = normalized,
                reachable = code in 200..299,
                checkedAtEpochMs = now,
                reason = if (code in 200..299) null else "http_status=$code",
            )
        }.getOrElse { error ->
            PeerProbeResult(
                peerId = normalized,
                reachable = false,
                checkedAtEpochMs = now,
                reason = error.message ?: "probe failed",
            )
        }
    }
}
