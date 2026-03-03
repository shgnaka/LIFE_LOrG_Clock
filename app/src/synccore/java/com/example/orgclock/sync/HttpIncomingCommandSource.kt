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
import java.util.logging.Logger

class HttpIncomingCommandSource(
    private val host: String = DEFAULT_BIND_HOST,
    private val port: Int = DEFAULT_BIND_PORT,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IncomingCommandSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var server: IncomingCommandHttpServer? = null

    override suspend fun start(onCommand: suspend (String) -> Unit) {
        synchronized(lock) {
            if (server != null) return
            val created = IncomingCommandHttpServer(
                host = host,
                port = port,
                maxBodyBytes = maxBodyBytes,
                json = json,
            ) { payload ->
                scope.launch {
                    runCatching { onCommand(payload) }
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

    private companion object {
        private val logger: Logger = Logger.getLogger(HttpIncomingCommandSource::class.java.name)
        const val DEFAULT_BIND_HOST = "0.0.0.0"
        const val DEFAULT_BIND_PORT = 39091
        const val DEFAULT_MAX_BODY_BYTES = 128 * 1024
        const val SOCKET_READ_TIMEOUT_MS = 5_000
    }
}

private class IncomingCommandHttpServer(
    host: String,
    port: Int,
    private val maxBodyBytes: Int,
    private val json: Json,
    private val onCommand: (String) -> Unit,
) : NanoHTTPD(host, port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/v1/health") {
            return plain(Status.OK, "ok")
        }
        if (session.method != Method.POST) {
            return plain(Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        if (session.uri != "/v1/messages" && session.uri != "/v1/incoming-command") {
            return plain(Status.NOT_FOUND, "not found")
        }

        val contentLength = session.headers["content-length"]?.toIntOrNull()
        if (contentLength != null && contentLength > maxBodyBytes) {
            return plain(Status.REQUEST_ENTITY_TOO_LARGE, "payload too large")
        }

        val body = runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }.getOrElse {
            return plain(Status.BAD_REQUEST, "invalid request body")
        }

        val payload = extractClockCommandPayload(
            uri = session.uri,
            body = body,
            json = json,
        ).getOrElse { error ->
            return plain(Status.BAD_REQUEST, error.message ?: "invalid payload")
        }
        onCommand(payload)
        return plain(Status.ACCEPTED, "accepted")
    }

    private fun plain(status: Status, body: String): Response {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, body)
    }
}

internal fun extractClockCommandPayload(
    uri: String,
    body: String,
    json: Json = Json { ignoreUnknownKeys = true },
): Result<String> {
    val commandPayload = when (uri) {
        "/v1/incoming-command" -> body
        "/v1/messages" -> extractFromMessageEnvelope(body, json)
            .getOrElse { return Result.failure(it) }
        else -> return Result.failure(IllegalArgumentException("Unsupported endpoint: $uri"))
    }
    return validateClockCommandPayload(commandPayload, json)
}

private fun extractFromMessageEnvelope(body: String, json: Json): Result<String> {
    val outer = runCatching { json.parseToJsonElement(body).jsonObject }
        .getOrElse { return Result.failure(IllegalArgumentException("Envelope must be valid JSON object")) }

    val outerPayloadJson = outer.optionalString("payloadJson")
        ?: return validateClockCommandPayload(body, json)

    val inner = runCatching { json.parseToJsonElement(outerPayloadJson).jsonObject }.getOrNull()
    val nestedPayload = inner?.optionalString("payloadJson")
    return Result.success(nestedPayload ?: outerPayloadJson)
}

private fun validateClockCommandPayload(payload: String, json: Json): Result<String> {
    val root = runCatching { json.parseToJsonElement(payload).jsonObject }
        .getOrElse { return Result.failure(IllegalArgumentException("Clock command payload must be valid JSON object")) }

    val schema = root.optionalString("schema")
        ?: return Result.failure(IllegalArgumentException("Missing schema"))
    if (schema != CLOCK_COMMAND_SCHEMA_V1) {
        return Result.failure(IllegalArgumentException("Unsupported schema: $schema"))
    }
    return Result.success(payload)
}

private fun JsonObject.optionalString(name: String): String? {
    return (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
