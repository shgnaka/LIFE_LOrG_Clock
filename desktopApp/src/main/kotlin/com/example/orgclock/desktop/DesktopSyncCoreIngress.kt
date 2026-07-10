package com.example.orgclock.desktop

import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface DesktopSyncCoreIngressReceiver {
    suspend fun receive(rawJson: String, sourceKey: String): IngressOutcome
}

data class DesktopSyncCoreHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

class DesktopSyncCoreIngressHttpMapper(
    private val json: Json = Json,
) {
    fun responseFor(outcome: IngressOutcome): DesktopSyncCoreHttpResponse = when (outcome) {
        IngressOutcome.Accepted -> DesktopSyncCoreHttpResponse(202, statusBody("accepted"))
        IngressOutcome.AlreadyAccepted -> DesktopSyncCoreHttpResponse(202, statusBody("already_accepted"))
        is IngressOutcome.Rejected -> DesktopSyncCoreHttpResponse(
            status = outcome.httpStatus,
            body = errorBody(outcome.error),
        )
        is IngressOutcome.RetryLater -> DesktopSyncCoreHttpResponse(
            status = outcome.httpStatus,
            body = errorBody(outcome.error),
            headers = mapOf("Retry-After" to outcome.retryAfterSeconds.toString()),
        )
    }

    fun payloadTooLarge(): DesktopSyncCoreHttpResponse = DesktopSyncCoreHttpResponse(
        status = 413,
        body = errorBody(SyncError(SyncErrorCode.PayloadTooLarge, "payload too large")),
    )

    fun invalidRequestBody(): DesktopSyncCoreHttpResponse = DesktopSyncCoreHttpResponse(
        status = 400,
        body = errorBody(SyncError(SyncErrorCode.InvalidMessage, "invalid request body")),
    )

    fun serverUnavailable(): DesktopSyncCoreHttpResponse = DesktopSyncCoreHttpResponse(
        status = 503,
        body = errorBody(SyncError(SyncErrorCode.StoreUnavailable, "store unavailable")),
    )

    private fun statusBody(status: String): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject { put("status", status) },
    )

    private fun errorBody(error: SyncError): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("status", "rejected")
            put("errorCode", error.code.name)
            put("detail", safeDetail(error.code))
        },
    )

    private fun safeDetail(code: SyncErrorCode): String = when (code) {
        SyncErrorCode.InvalidMessage -> "invalid message"
        SyncErrorCode.PayloadTooLarge -> "payload too large"
        SyncErrorCode.MessageIdConflict -> "message id conflict"
        SyncErrorCode.PeerNotTrusted -> "peer not trusted"
        SyncErrorCode.SignatureInvalid -> "signature invalid"
        SyncErrorCode.TimestampOutOfRange -> "timestamp out of range"
        SyncErrorCode.ReplayConflict -> "replay conflict"
        SyncErrorCode.CertificatePinMismatch -> "certificate pin mismatch"
        SyncErrorCode.PeerNotAuthorized -> "peer not authorized"
        SyncErrorCode.QueueFull -> "queue full"
        SyncErrorCode.InboxFull -> "inbox full"
        SyncErrorCode.RateLimited -> "rate limited"
        SyncErrorCode.NetworkUnreachable -> "network unreachable"
        SyncErrorCode.Timeout -> "timeout"
        SyncErrorCode.RemoteBusy -> "remote busy"
        SyncErrorCode.ProtocolError -> "protocol error"
        SyncErrorCode.Expired -> "expired"
        SyncErrorCode.Cancelled -> "cancelled"
        SyncErrorCode.RetryExhausted -> "retry exhausted"
        SyncErrorCode.ResultRouteNotFound -> "result route not found"
        SyncErrorCode.StoreUnavailable -> "store unavailable"
        SyncErrorCode.StoreWriteFailed -> "store write failed"
        SyncErrorCode.StoreReadFailed -> "store read failed"
        SyncErrorCode.MigrationFailed -> "migration failed"
        SyncErrorCode.InvalidStateTransition -> "invalid state transition"
    }
}

