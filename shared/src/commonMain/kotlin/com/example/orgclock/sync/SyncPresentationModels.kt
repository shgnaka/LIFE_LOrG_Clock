package com.example.orgclock.sync

import com.example.orgclock.ui.state.OrgDivergenceSnapshot

enum class ClockEventSyncStatus {
    Synced,
    Pending,
    Error,
    RecoveryRequired,
}

data class ClockEventSyncState(
    val status: ClockEventSyncStatus = ClockEventSyncStatus.Synced,
    val pendingLocalEventCount: Int = 0,
    val lastCursor: Long? = null,
    val lastSyncedCursor: Long? = null,
    val lastError: String? = null,
    val lastRejectReason: String? = null,
    val quarantinedEventCount: Int = 0,
) {
    val summaryText: String
        get() = when (status) {
            ClockEventSyncStatus.Synced -> "Synced"
            ClockEventSyncStatus.Pending -> "Pending ($pendingLocalEventCount)"
            ClockEventSyncStatus.Error -> "Error"
            ClockEventSyncStatus.RecoveryRequired -> "Recovery required"
        }
}

fun ClockEventStoreSnapshot.toClockEventSyncState(lastError: String? = null): ClockEventSyncState {
    val status = when {
        lastError != null -> ClockEventSyncStatus.Error
        lastRejectReason != null || quarantinedEventCount > 0 -> ClockEventSyncStatus.RecoveryRequired
        pendingSyncCount == 0 && quarantinedEventCount == 0 -> ClockEventSyncStatus.Synced
        else -> ClockEventSyncStatus.Pending
    }
    return ClockEventSyncState(
        status = status,
        pendingLocalEventCount = pendingSyncCount,
        lastCursor = lastCursor?.value,
        lastSyncedCursor = lastSyncedCursor?.value,
        lastError = lastError,
        lastRejectReason = lastRejectReason,
        quarantinedEventCount = quarantinedEventCount,
    )
}

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
    val displayName: String? = null,
    val role: PeerTrustRole = PeerTrustRole.Full,
    val publicKeyRegistered: Boolean = false,
    val reachable: Boolean? = null,
    val lastCheckedAtEpochMs: Long? = null,
    val lastSyncedAtEpochMs: Long? = null,
    val lastSeenCursor: Long? = null,
    val lastSentCursor: Long? = null,
)

data class SyncIntegrationSnapshot(
    val lastResult: ClockResultPayload? = null,
    val lastError: String? = null,
    val lastDeliveryStates: List<SyncDeliveryState> = emptyList(),
    val viewerPeerCount: Int = 0,
    val viewerProjection: ClockEventProjection? = null,
    val viewerProjectionAtEpochMs: Long? = null,
    val orgDivergenceSnapshot: OrgDivergenceSnapshot? = null,
    val metrics: SyncMetricsSnapshot = SyncMetricsSnapshot(),
    val runtimeMode: SyncRuntimeMode = SyncRuntimeMode.Off,
    val runtimeEnabled: Boolean = false,
    val defaultPeerId: String? = null,
    val trustedPeers: List<String> = emptyList(),
    val peerStates: List<SyncPeerState> = emptyList(),
)
