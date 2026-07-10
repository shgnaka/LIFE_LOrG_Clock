package com.example.orgclock.sync

import fi.iki.elonen.NanoHTTPD
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncRawIngressReceiver
import java.util.logging.Logger
import javax.net.ssl.SSLServerSocketFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface SyncCoreIngressServer {
    fun start()
    fun stop()
}

internal class AndroidSyncCoreIngressServer(
    private val receiver: SyncRawIngressReceiver,
    private val host: String = DEFAULT_BIND_HOST,
    private val port: Int = DEFAULT_BIND_PORT,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val sslServerSocketFactory: SSLServerSocketFactory? = null,
    private val json: Json = Json,
) : SyncCoreIngressServer {
    private val lock = Any()
    private var server: NanoHttpSyncCoreIngressServer? = null

    override fun start() {
        synchronized(lock) {
            if (server != null) return
            val created = NanoHttpSyncCoreIngressServer(
                host = host,
                port = port,
                maxBodyBytes = maxBodyBytes,
                receiver = receiver,
                json = json,
            )
            sslServerSocketFactory?.let { created.makeSecure(it, null) }
            created.start(SOCKET_READ_TIMEOUT_MS, false)
            server = created
            logger.fine("sync-core.ingress.started host=$host port=$port tls=${sslServerSocketFactory != null}")
        }
    }

    override fun stop() {
        synchronized(lock) {
            val current = server ?: return
            current.stop()
            server = null
            logger.fine("sync-core.ingress.stopped")
        }
    }

    private companion object {
        private val logger = Logger.getLogger(AndroidSyncCoreIngressServer::class.java.name)
        const val DEFAULT_BIND_HOST = "0.0.0.0"
        const val DEFAULT_BIND_PORT = 39091
        const val DEFAULT_MAX_BODY_BYTES = 128 * 1024
        const val SOCKET_READ_TIMEOUT_MS = 5_000
    }
}

private class NanoHttpSyncCoreIngressServer(
    host: String,
    port: Int,
    private val maxBodyBytes: Int,
    private val receiver: SyncRawIngressReceiver,
    private val json: Json,
) : NanoHTTPD(host, port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == HEALTH_PATH) {
            return text(Response.Status.OK, "ok")
        }
        if (session.method != Method.POST) {
            return text(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        if (session.uri != MESSAGES_PATH) {
            return text(Response.Status.NOT_FOUND, "not found")
        }
        val contentLength = session.headers["content-length"]?.toIntOrNull()
        if (contentLength != null && contentLength > maxBodyBytes) {
            return json(Response.Status.PAYLOAD_TOO_LARGE, errorBody("PayloadTooLarge", "payload too large"))
        }
        val body = runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }.getOrElse {
            return json(Response.Status.BAD_REQUEST, errorBody("InvalidMessage", "invalid request body"))
        }
        val outcome = runBlocking {
            receiver.receive(body, sourceKey = session.remoteIpAddress.orEmpty().ifBlank { "unknown" })
        }
        return responseFor(outcome)
    }

    private fun responseFor(outcome: IngressOutcome): Response = when (outcome) {
        IngressOutcome.Accepted -> json(Response.Status.ACCEPTED, statusBody("accepted"))
        IngressOutcome.AlreadyAccepted -> json(Response.Status.ACCEPTED, statusBody("already_accepted"))
        is IngressOutcome.Rejected -> json(statusFor(outcome.httpStatus), errorBody(outcome.error))
        is IngressOutcome.RetryLater -> json(statusFor(outcome.httpStatus), errorBody(outcome.error)).apply {
            addHeader("Retry-After", outcome.retryAfterSeconds.toString())
        }
    }

    private fun statusFor(status: Int): Response.Status = when (status) {
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        403 -> Response.Status.FORBIDDEN
        404 -> Response.Status.NOT_FOUND
        409 -> Response.Status.CONFLICT
        413 -> Response.Status.PAYLOAD_TOO_LARGE
        429 -> Response.Status.TOO_MANY_REQUESTS
        503 -> Response.Status.SERVICE_UNAVAILABLE
        else -> if (status in 500..599) Response.Status.INTERNAL_ERROR else Response.Status.BAD_REQUEST
    }

    private fun text(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun statusBody(status: String): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject { put("status", status) },
    )

    private fun errorBody(error: SyncError): String = errorBody(error.code.name, error.detail ?: error.code.name)

    private fun errorBody(code: String, detail: String): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("status", "rejected")
            put("errorCode", code)
            put("detail", safeDetail(code, detail))
        },
    )

    private fun safeDetail(code: String, fallback: String): String = when (code) {
        "InvalidMessage" -> "invalid message"
        "PayloadTooLarge" -> "payload too large"
        "MessageIdConflict" -> "message id conflict"
        "PeerNotTrusted" -> "peer not trusted"
        "SignatureInvalid" -> "signature invalid"
        "TimestampOutOfRange" -> "timestamp out of range"
        "ReplayConflict" -> "replay conflict"
        "CertificatePinMismatch" -> "certificate pin mismatch"
        "PeerNotAuthorized" -> "peer not authorized"
        "QueueFull" -> "queue full"
        "InboxFull" -> "inbox full"
        "RateLimited" -> "rate limited"
        "NetworkUnreachable" -> "network unreachable"
        "Timeout" -> "timeout"
        "RemoteBusy" -> "remote busy"
        "ProtocolError" -> "protocol error"
        "Expired" -> "expired"
        "Cancelled" -> "cancelled"
        "RetryExhausted" -> "retry exhausted"
        "ResultRouteNotFound" -> "result route not found"
        "StoreUnavailable" -> "store unavailable"
        "StoreWriteFailed" -> "store write failed"
        "StoreReadFailed" -> "store read failed"
        "MigrationFailed" -> "migration failed"
        "InvalidStateTransition" -> "invalid state transition"
        else -> fallback.take(128)
    }

    private companion object {
        const val HEALTH_PATH = "/v1/health"
        const val MESSAGES_PATH = "/v1/messages"
    }
}
