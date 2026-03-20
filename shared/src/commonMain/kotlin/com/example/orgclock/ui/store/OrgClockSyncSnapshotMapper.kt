package com.example.orgclock.ui.store

import com.example.orgclock.sync.PeerTrustRole
import com.example.orgclock.sync.SyncIntegrationSnapshot
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.PeerUiItem

class OrgClockSyncSnapshotMapper {
    fun map(
        current: OrgClockUiState,
        snapshot: SyncIntegrationSnapshot,
        trustedPeers: List<String>,
        peersById: Map<String, com.example.orgclock.sync.SyncPeerState>,
    ): OrgClockUiState {
        val viewerProjectionSummary = snapshot.viewerProjection?.let { projection ->
            "active=${projection.activeClocks.size}, history=${projection.historyEntries.size}, issues=${projection.issues.size}"
        }
        return current.copy(
            syncRuntimeEnabled = snapshot.runtimeEnabled,
            syncDefaultPeerId = snapshot.defaultPeerId.orEmpty(),
            syncPeers = trustedPeers.map { peerId ->
                val peer = peersById[peerId]
                PeerUiItem(
                    peerId = peerId,
                    displayName = peer?.displayName,
                    role = peer?.role ?: PeerTrustRole.Full,
                    publicKeyRegistered = peer?.publicKeyRegistered ?: false,
                    reachable = peer?.reachable,
                    lastCheckedAtEpochMs = peer?.lastCheckedAtEpochMs,
                    lastSyncedAtEpochMs = peer?.lastSyncedAtEpochMs,
                    lastSeenCursor = peer?.lastSeenCursor,
                    lastSentCursor = peer?.lastSentCursor,
                )
            },
            syncRuntimeMode = snapshot.runtimeMode,
            syncLastResultSummary = snapshot.lastResult?.let { result -> "${result.status.wireValue} (${result.commandId})" },
            syncLastError = snapshot.lastError,
            syncViewerPeerCount = snapshot.viewerPeerCount,
            syncViewerProjectionSummary = viewerProjectionSummary,
            syncMetrics = snapshot.metrics,
            syncDeliveryStates = snapshot.lastDeliveryStates,
            divergenceSnapshot = snapshot.orgDivergenceSnapshot,
        )
    }
}
