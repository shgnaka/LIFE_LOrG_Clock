package com.example.orgclock.sync

import fi.iki.elonen.NanoHTTPD
import io.github.shgnaka.synccore.api.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.ArrayDeque
import java.util.Base64
import java.util.logging.Logger

class HttpIncomingCommandSource(
    private val host: String = DEFAULT_BIND_HOST,
    private val port: Int = DEFAULT_BIND_PORT,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val maxRequestsPerMinute: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
    private val allowedSkewSeconds: Long = DEFAULT_ALLOWED_SKEW_SECONDS,
    private val incomingEnvelopeVerifier: IncomingEnvelopeVerifierPort = RejectingIncomingEnvelopeVerifier(),
    private val replayRegistry: ReplayRegistryPort = InMemoryReplayRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IncomingCommandSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var server: IncomingCommandHttpServer? = null

    override suspend fun start(onCommand: suspend (VerifiedIncomingCommand) -> Unit) {
        synchronized(lock) {
            if (server != null) return
            val created = IncomingCommandHttpServer(
                host = host,
                port = port,
                maxBodyBytes = maxBodyBytes,
                maxRequestsPerMinute = maxRequestsPerMinute,
                validationEngine = IncomingCommandValidationEngine(
                    allowedSkewSeconds = allowedSkewSeconds,
                    replayRegistry = replayRegistry,
                    json = json,
                ),
                incomingEnvelopeVerifier = incomingEnvelopeVerifier,
                json = json,
            ) { command ->
                scope.launch {
                    runCatching { onCommand(command) }
                        .onFailure { error ->
                            logger.fine("sync.incoming.http.dispatch_failed reason=${error.message ?: "unknown"}")
                        }
                }
            }
            created.start(SOCKET_READ_TIMEOUT_MS, false)
            server = created
            logger.fine("sync.incoming.http.started host=$host port=$port")
        }
    }

    override suspend fun stop() {
        synchronized(lock) {
            val current = server ?: return
            current.stop()
            server = null
            logger.fine("sync.incoming.http.stopped")
        }
    }

    companion object {
        private val logger: Logger = Logger.getLogger(HttpIncomingCommandSource::class.java.name)
        const val DEFAULT_BIND_HOST = "0.0.0.0"
        const val DEFAULT_BIND_PORT = 39091
        const val DEFAULT_MAX_BODY_BYTES = 128 * 1024
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 120
        const val DEFAULT_ALLOWED_SKEW_SECONDS = 300L
        const val SOCKET_READ_TIMEOUT_MS = 5_000
    }
}

private class IncomingCommandHttpServer(
    host: String,
    port: Int,
    private val maxBodyBytes: Int,
    maxRequestsPerMinute: Int,
    private val validationEngine: IncomingCommandValidationEngine,
    private val incomingEnvelopeVerifier: IncomingEnvelopeVerifierPort,
    private val json: Json,
    private val onCommand: (VerifiedIncomingCommand) -> Unit,
) : NanoHTTPD(host, port) {
    private val rateLimiter = SlidingWindowRateLimiter(maxRequestsPerMinute)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/v1/health") {
            return plain(Response.Status.OK, "ok")
        }
        if (session.method != Method.POST) {
            return plain(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        if (session.uri != "/v1/messages" && session.uri != "/v1/incoming-command") {
            return plain(Response.Status.NOT_FOUND, "not found")
        }
        if (!rateLimiter.allow(session.remoteIpAddress)) {
            return plain(Response.Status.TOO_MANY_REQUESTS, "rate limit exceeded")
        }

        val contentLength = session.headers["content-length"]?.toIntOrNull()
        if (contentLength != null && contentLength > maxBodyBytes) {
            return plain(Response.Status.PAYLOAD_TOO_LARGE, "payload too large")
        }

        val body = runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }.getOrElse {
            return plain(Response.Status.BAD_REQUEST, "invalid request body")
        }

        val command = kotlinx.coroutines.runBlocking {
            validationEngine.extractClockCommandPayload(
                uri = session.uri,
                body = body,
                incomingEnvelopeVerifier = incomingEnvelopeVerifier,
            )
        }.getOrElse { error ->
            return plain(Response.Status.BAD_REQUEST, error.message ?: "invalid payload")
        }

        onCommand(command)
        return plain(Response.Status.ACCEPTED, "accepted")
    }

    private fun plain(status: Response.IStatus, body: String): Response {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, body)
    }
}

internal class RejectingIncomingEnvelopeVerifier : IncomingEnvelopeVerifierPort {
    override fun verifyCommandEnvelope(rawEnvelopeJson: String): Result<IncomingCommandPayloadContext> {
        return Result.failure(IllegalArgumentException("incoming envelope verifier is not configured"))
    }
}

internal class Ed25519IncomingEnvelopeVerifier(
    private val peerTrustStore: PeerTrustStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IncomingEnvelopeVerifierPort {
    override fun verifyCommandEnvelope(rawEnvelopeJson: String): Result<IncomingCommandPayloadContext> = runCatching {
        val envelope = parseEnvelope(rawEnvelopeJson)
        if (!envelope.messageType.name.equals("COMMAND", ignoreCase = true)) {
            throw IllegalArgumentException("envelope messageType must be COMMAND")
        }
        val senderDeviceId = envelope.senderDeviceId.trim()
        if (senderDeviceId.isBlank()) {
            throw IllegalArgumentException("envelope senderDeviceId is empty")
        }
        val trustedPublicKeyBase64 = peerTrustStore.getTrustedPublicKey(senderDeviceId)
            ?: throw IllegalArgumentException("sender device is not trusted: $senderDeviceId")

        val signatureRaw = envelope.signatureBase64.trim()
        if (signatureRaw.isBlank()) {
            throw IllegalArgumentException("envelope signature is empty")
        }

        val unsigned = envelope.copy(signatureBase64 = "")
        val verified = verifySignature(
            canonicalPayload = canonicalizeEnvelope(unsigned),
            signatureBase64 = signatureRaw,
            trustedPublicKeyBase64 = trustedPublicKeyBase64,
        )
        if (!verified) {
            throw IllegalArgumentException("envelope signature verification failed")
        }

        val payloadObject = runCatching { json.parseToJsonElement(envelope.payloadJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("envelope payloadJson is not valid JSON object") }
        IncomingCommandPayloadContext(
            payloadJson = envelope.payloadJson,
            verifiedPeerId = payloadObject.optionalString("peer_id") ?: senderDeviceId,
            signatureKeyId = payloadObject.optionalString("signature_key_id"),
            expectedSenderDeviceId = senderDeviceId,
            verificationMethod = "envelope-signature+schema+sender+timestamp+replay",
        )
    }

    private fun parseEnvelope(rawEnvelopeJson: String): Envelope {
        val candidates = mutableListOf(rawEnvelopeJson)
        runCatching { json.parseToJsonElement(rawEnvelopeJson).jsonObject }
            .getOrNull()
            ?.let { root ->
                root.optionalString("payloadJson")?.let { candidates.add(it) }
                val nested = root.optionalString("payloadJson")
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                nested?.optionalString("payloadJson")?.let { candidates.add(it) }
            }

        for (candidate in candidates) {
            val envelope = runCatching { json.decodeFromString(Envelope.serializer(), candidate) }.getOrNull()
            if (envelope != null) return envelope
        }
        throw IllegalArgumentException("command envelope is not valid")
    }

    private fun verifySignature(
        canonicalPayload: String,
        signatureBase64: String,
        trustedPublicKeyBase64: String,
    ): Boolean {
        return runCatching {
            val keyFactory = KeyFactory.getInstance(ED25519_ALGORITHM)
            val publicKeyBytes = Base64.getDecoder().decode(trustedPublicKeyBase64)
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val signature = Signature.getInstance(ED25519_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(canonicalPayload.toByteArray(Charsets.UTF_8))
            signature.verify(Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)
    }

    private fun canonicalizeEnvelope(envelope: Envelope): String {
        return listOf(
            envelope.schemaVersion.toString(),
            envelope.messageType.name,
            envelope.messageId,
            envelope.senderDeviceId,
            envelope.sentAtEpochMs.toString(),
            envelope.nonce,
            envelope.payloadJson,
        ).joinToString("\n")
    }

    private companion object {
        const val ED25519_ALGORITHM = "Ed25519"
    }
}

internal class InMemoryReplayRegistry(
    private val maxSize: Int = 2_048,
) : ReplayRegistryPort {
    private val lock = Any()
    private val seen = linkedMapOf<String, Long>()

    override suspend fun register(senderDeviceId: String, commandId: String): Boolean {
        val key = "$senderDeviceId::$commandId"
        synchronized(lock) {
            if (seen.containsKey(key)) return false
            seen[key] = System.currentTimeMillis()
            if (seen.size > maxSize) {
                val oldest = seen.keys.firstOrNull()
                if (oldest != null) {
                    seen.remove(oldest)
                }
            }
            return true
        }
    }
}

private fun JsonObject.optionalString(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

private class SlidingWindowRateLimiter(
    private val maxRequestsPerMinute: Int,
) {
    private val lock = Any()
    private val windows = linkedMapOf<String, ArrayDeque<Long>>()

    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        synchronized(lock) {
            val deque = windows.getOrPut(key) { ArrayDeque() }
            while (deque.isNotEmpty() && deque.first() < cutoff) {
                deque.removeFirst()
            }
            if (deque.size >= maxRequestsPerMinute) {
                return false
            }
            deque.addLast(now)
            if (windows.size > MAX_KEYS) {
                val oldest = windows.keys.firstOrNull()
                if (oldest != null && oldest != key) {
                    windows.remove(oldest)
                }
            }
            return true
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_KEYS = 256
    }
}
