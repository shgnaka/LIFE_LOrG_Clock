package com.example.orgclock.sync

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.ArrayDeque
import java.util.logging.Logger

class HttpIncomingCommandSource(
    private val host: String = DEFAULT_BIND_HOST,
    private val port: Int = DEFAULT_BIND_PORT,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val maxRequestsPerMinute: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
    private val allowedSkewSeconds: Long = DEFAULT_ALLOWED_SKEW_SECONDS,
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
                allowedSkewSeconds = allowedSkewSeconds,
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
    private val allowedSkewSeconds: Long,
    private val json: Json,
    private val onCommand: (VerifiedIncomingCommand) -> Unit,
) : NanoHTTPD(host, port) {
    private val rateLimiter = SlidingWindowRateLimiter(maxRequestsPerMinute)
    private val replayGuard = ReplayGuard()

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

        val command = extractClockCommandPayload(
            uri = session.uri,
            body = body,
            allowedSkewSeconds = allowedSkewSeconds,
            replayGuard = replayGuard,
            json = json,
        ).getOrElse { error ->
            return plain(Response.Status.BAD_REQUEST, error.message ?: "invalid payload")
        }
        onCommand(command)
        return plain(Response.Status.ACCEPTED, "accepted")
    }

    private fun plain(status: Response.IStatus, body: String): Response {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, body)
    }
}

internal fun extractClockCommandPayload(
    uri: String,
    body: String,
    allowedSkewSeconds: Long = HttpIncomingCommandSource.DEFAULT_ALLOWED_SKEW_SECONDS,
    replayGuard: ReplayGuard = ReplayGuard(),
    json: Json = Json { ignoreUnknownKeys = true },
): Result<VerifiedIncomingCommand> {
    val commandPayload = when (uri) {
        "/v1/incoming-command" -> body
        "/v1/messages" -> extractFromMessageEnvelope(body, json)
            .getOrElse { return Result.failure(it) }
        else -> return Result.failure(IllegalArgumentException("Unsupported endpoint: $uri"))
    }
    return validateClockCommandPayload(
        payload = commandPayload,
        allowedSkewSeconds = allowedSkewSeconds,
        replayGuard = replayGuard,
        json = json,
    )
}

private fun extractFromMessageEnvelope(body: String, json: Json): Result<String> {
    val outer = runCatching { json.parseToJsonElement(body).jsonObject }
        .getOrElse { return Result.failure(IllegalArgumentException("Envelope must be valid JSON object")) }

    val outerPayloadJson = outer.optionalString("payloadJson")
        ?: return Result.success(body)

    val inner = runCatching { json.parseToJsonElement(outerPayloadJson).jsonObject }.getOrNull()
    val nestedPayload = inner?.optionalString("payloadJson")
    return Result.success(nestedPayload ?: outerPayloadJson)
}

private fun validateClockCommandPayload(
    payload: String,
    allowedSkewSeconds: Long,
    replayGuard: ReplayGuard,
    json: Json,
): Result<VerifiedIncomingCommand> {
    val root = runCatching { json.parseToJsonElement(payload).jsonObject }
        .getOrElse { return Result.failure(IllegalArgumentException("Clock command payload must be valid JSON object")) }

    val schema = root.optionalString("schema")
        ?: return Result.failure(IllegalArgumentException("Missing schema"))
    if (schema != CLOCK_COMMAND_SCHEMA_V1) {
        return Result.failure(IllegalArgumentException("Unsupported schema: $schema"))
    }
    val commandId = root.optionalString("command_id")
        ?: return Result.failure(IllegalArgumentException("Missing command_id"))
    val senderDeviceId = root.optionalString("from_device_id")
        ?: return Result.failure(IllegalArgumentException("Missing from_device_id"))
    val requestedAtRaw = root.optionalString("requested_at")
        ?: return Result.failure(IllegalArgumentException("Missing requested_at"))
    val requestedAt = runCatching { Instant.parse(requestedAtRaw) }
        .getOrElse { return Result.failure(IllegalArgumentException("Invalid requested_at")) }
    val now = Clock.System.now()
    val skew = kotlin.math.abs(now.epochSeconds - requestedAt.epochSeconds)
    if (skew > allowedSkewSeconds) {
        return Result.failure(IllegalArgumentException("requested_at outside allowed skew"))
    }
    val replayPassed = replayGuard.register(senderDeviceId, commandId)
    if (!replayPassed) {
        return Result.failure(IllegalArgumentException("duplicate command_id replay"))
    }
    val peerId = root.optionalString("peer_id")
    return Result.success(
        VerifiedIncomingCommand(
            payloadJson = payload,
            commandId = commandId,
            senderDeviceId = senderDeviceId,
            peerId = peerId ?: senderDeviceId,
            verifiedPeerId = peerId ?: senderDeviceId,
            verificationState = IncomingVerificationState.Verified,
            verificationMethod = "schema+sender+timestamp+replay",
            signatureKeyId = root.optionalString("signature_key_id"),
            replayCheckPassed = replayPassed,
            receivedAtEpochMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        ),
    )
}

private fun JsonObject.optionalString(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal class ReplayGuard(
    private val maxSize: Int = 2_048,
) {
    private val lock = Any()
    private val seen = linkedMapOf<String, Long>()

    fun register(senderDeviceId: String, commandId: String): Boolean {
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
