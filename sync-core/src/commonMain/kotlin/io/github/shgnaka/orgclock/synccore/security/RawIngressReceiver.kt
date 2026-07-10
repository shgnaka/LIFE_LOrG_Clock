package io.github.shgnaka.orgclock.synccore.security

import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import kotlin.math.ceil

internal class RawIngressReceiver(
    private val syncCore: SyncCore,
    private val trustedPeerResolver: TrustedPeerResolver,
    private val envelopeCodec: EnvelopeCodec,
    private val clock: SyncClock,
    private val limitPolicy: IngressLimitPolicy = IngressLimitPolicy(),
    private val rateLimiter: IngressRateLimiter = IngressRateLimiter(clock, limitPolicy),
    private val redactionPolicy: IngressRedactionPolicy = IngressRedactionPolicy(),
) {
    suspend fun receive(rawJson: String, sourceKey: String = DEFAULT_SOURCE_KEY): IngressOutcome {
        if (rawJson.encodeToByteArray().size > limitPolicy.maxEncodedBodyBytes) {
            return reject(413, SyncError(SyncErrorCode.PayloadTooLarge, "encoded envelope body exceeds limit"))
        }
        val rate = rateLimiter.tryAcquire(sourceKey)
        if (rate is RateLimitDecision.Rejected) {
            return retryLater(
                httpStatus = 429,
                retryAfterSeconds = rate.retryAfterSeconds,
                error = SyncError(SyncErrorCode.RateLimited, "source rate limit exceeded", retryAtEpochMs = rate.retryAtEpochMs),
            )
        }
        val senderPeerId = envelopeCodec.senderPeerId(rawJson)
            ?: return reject(400, SyncError(SyncErrorCode.InvalidMessage, "missing senderPeerId"))
        val trustedPeer = trustedPeerResolver.resolve(senderPeerId)
            ?: return reject(401, SyncError(SyncErrorCode.PeerNotTrusted, "peer is not trusted"))
        return when (val decoded = envelopeCodec.decodeAndVerifyCurrent(rawJson, trustedPeer)) {
            is EnvelopeDecodeResult.Accepted -> {
                if (decoded.envelope.payloadJson.encodeToByteArray().size > limitPolicy.maxDecodedPayloadBytes) {
                    reject(413, SyncError(SyncErrorCode.PayloadTooLarge, "decoded payload exceeds limit"))
                } else {
                    sanitizeOutcome(syncCore.receiveVerified(decoded.envelope))
                }
            }
            is EnvelopeDecodeResult.Rejected -> reject(httpStatusFor(decoded.error), decoded.error)
        }
    }

    private fun sanitizeOutcome(outcome: IngressOutcome): IngressOutcome = when (outcome) {
        IngressOutcome.Accepted, IngressOutcome.AlreadyAccepted -> outcome
        is IngressOutcome.Rejected -> reject(outcome.httpStatus, outcome.error)
        is IngressOutcome.RetryLater -> retryLater(outcome.httpStatus, outcome.retryAfterSeconds, outcome.error)
    }

    private fun reject(httpStatus: Int, error: SyncError): IngressOutcome.Rejected =
        IngressOutcome.Rejected(httpStatus = httpStatus, error = redactionPolicy.sanitize(error))

    private fun retryLater(httpStatus: Int, retryAfterSeconds: Int, error: SyncError): IngressOutcome.RetryLater =
        IngressOutcome.RetryLater(
            httpStatus = httpStatus,
            retryAfterSeconds = retryAfterSeconds,
            error = redactionPolicy.sanitize(error),
        )

    private fun httpStatusFor(error: SyncError): Int = when (error.code) {
        SyncErrorCode.InvalidMessage,
        SyncErrorCode.TimestampOutOfRange,
        SyncErrorCode.ProtocolError -> 400
        SyncErrorCode.PeerNotTrusted,
        SyncErrorCode.SignatureInvalid -> 401
        SyncErrorCode.PeerNotAuthorized -> 403
        SyncErrorCode.ReplayConflict -> 409
        SyncErrorCode.PayloadTooLarge -> 413
        SyncErrorCode.RateLimited -> 429
        SyncErrorCode.InboxFull,
        SyncErrorCode.StoreUnavailable,
        SyncErrorCode.StoreWriteFailed,
        SyncErrorCode.StoreReadFailed -> 503
        else -> 400
    }

    private companion object {
        const val DEFAULT_SOURCE_KEY = "unknown"
    }
}

internal data class IngressLimitPolicy(
    val maxEncodedBodyBytes: Int = 128 * 1024,
    val maxDecodedPayloadBytes: Int = 96 * 1024,
    val maxRequestsPerWindow: Int = 120,
    val rateLimitWindowMs: Long = 60_000L,
    val maxTrackedRateLimitSourceKeys: Int = 256,
) {
    init {
        require(maxEncodedBodyBytes > 0) { "maxEncodedBodyBytes must be > 0." }
        require(maxDecodedPayloadBytes > 0) { "maxDecodedPayloadBytes must be > 0." }
        require(maxRequestsPerWindow > 0) { "maxRequestsPerWindow must be > 0." }
        require(rateLimitWindowMs > 0) { "rateLimitWindowMs must be > 0." }
        require(maxTrackedRateLimitSourceKeys > 0) { "maxTrackedRateLimitSourceKeys must be > 0." }
    }
}

internal class IngressRateLimiter(
    private val clock: SyncClock,
    private val policy: IngressLimitPolicy,
) {
    private val windows = linkedMapOf<String, RateWindow>()

    fun tryAcquire(sourceKey: String): RateLimitDecision {
        val key = sourceKey.takeIf { it.isNotBlank() } ?: "unknown"
        val now = clock.nowEpochMs()
        evictExpired(now)
        val window = windows[key]
        if (window == null || now >= window.windowStartEpochMs + policy.rateLimitWindowMs) {
            ensureCapacity(key)
            windows[key] = RateWindow(windowStartEpochMs = now, count = 1)
            return RateLimitDecision.Allowed
        }
        if (window.count >= policy.maxRequestsPerWindow) {
            val retryAt = window.windowStartEpochMs + policy.rateLimitWindowMs
            val retryAfterMs = (retryAt - now).coerceAtLeast(1L)
            return RateLimitDecision.Rejected(
                retryAfterSeconds = ceil(retryAfterMs / 1000.0).toInt().coerceAtLeast(1),
                retryAtEpochMs = retryAt,
            )
        }
        windows.remove(key)
        windows[key] = window.copy(count = window.count + 1)
        return RateLimitDecision.Allowed
    }

    private fun evictExpired(nowEpochMs: Long) {
        val expiredKeys = windows.entries
            .filter { (_, window) -> nowEpochMs >= window.windowStartEpochMs + policy.rateLimitWindowMs }
            .map { it.key }
        expiredKeys.forEach(windows::remove)
    }

    private fun ensureCapacity(nextKey: String) {
        if (windows.containsKey(nextKey)) return
        while (windows.size >= policy.maxTrackedRateLimitSourceKeys) {
            val oldestKey = windows.entries.firstOrNull()?.key ?: return
            windows.remove(oldestKey)
        }
    }
}

internal class IngressRedactionPolicy {
    fun sanitize(error: SyncError): SyncError = SyncError(
        code = error.code,
        detail = safeDetail(error.code),
        retryAtEpochMs = error.retryAtEpochMs,
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

private data class RateWindow(val windowStartEpochMs: Long, val count: Int)

internal sealed interface RateLimitDecision {
    data object Allowed : RateLimitDecision
    data class Rejected(val retryAfterSeconds: Int, val retryAtEpochMs: Long) : RateLimitDecision
}
