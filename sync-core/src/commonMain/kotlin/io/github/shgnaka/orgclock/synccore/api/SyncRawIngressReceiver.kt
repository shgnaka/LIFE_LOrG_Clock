package io.github.shgnaka.orgclock.synccore.api

import io.github.shgnaka.orgclock.synccore.security.EnvelopeCodec
import io.github.shgnaka.orgclock.synccore.security.IngressLimitPolicy
import io.github.shgnaka.orgclock.synccore.security.RawIngressReceiver

class SyncRawIngressReceiver(
    syncCore: SyncCore,
    trustedPeerResolver: TrustedPeerResolver,
    clock: SyncClock,
    limitPolicy: SyncRawIngressLimitPolicy = SyncRawIngressLimitPolicy(),
) {
    private val delegate = RawIngressReceiver(
        syncCore = syncCore,
        trustedPeerResolver = trustedPeerResolver,
        envelopeCodec = EnvelopeCodec(clock = clock),
        clock = clock,
        limitPolicy = limitPolicy.toInternal(),
    )

    suspend fun receive(rawJson: String, sourceKey: String = "unknown"): IngressOutcome =
        delegate.receive(rawJson, sourceKey)
}

data class SyncRawIngressLimitPolicy(
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

private fun SyncRawIngressLimitPolicy.toInternal(): IngressLimitPolicy = IngressLimitPolicy(
    maxEncodedBodyBytes = maxEncodedBodyBytes,
    maxDecodedPayloadBytes = maxDecodedPayloadBytes,
    maxRequestsPerWindow = maxRequestsPerWindow,
    rateLimitWindowMs = rateLimitWindowMs,
    maxTrackedRateLimitSourceKeys = maxTrackedRateLimitSourceKeys,
)
