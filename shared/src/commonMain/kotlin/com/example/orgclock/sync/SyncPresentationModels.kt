package com.example.orgclock.sync

data class PeerProbeResult(
    val peerId: String,
    val reachable: Boolean,
    val checkedAtEpochMs: Long,
    val reason: String? = null,
)

data class SyncDeliveryState(
    val commandId: String,
    val state: String,
    val detail: String? = null,
)

data class SyncMetricsSnapshot(
    val commandsSubmittedTotal: Long = 0L,
    val commandsAppliedTotal: Long = 0L,
    val retryAttemptsTotal: Long = 0L,
    val queueDepth: Long = 0L,
)

enum class SyncRuntimeMode {
    Off,
    Standard,
    Active,
}

data class SyncPeerState(
    val peerId: String,
    val reachable: Boolean? = null,
    val lastCheckedAtEpochMs: Long? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

data class SyncIntegrationSnapshot(
    val lastResult: ClockResultPayload? = null,
    val lastError: String? = null,
    val lastDeliveryStates: List<SyncDeliveryState> = emptyList(),
    val metrics: SyncMetricsSnapshot = SyncMetricsSnapshot(),
    val runtimeMode: SyncRuntimeMode = SyncRuntimeMode.Off,
    val runtimeEnabled: Boolean = false,
    val defaultPeerId: String? = null,
    val trustedPeers: List<String> = emptyList(),
    val peerStates: List<SyncPeerState> = emptyList(),
)
