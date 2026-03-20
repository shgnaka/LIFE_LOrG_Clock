package com.example.orgclock.ui.store

import com.example.orgclock.sync.PeerPairingDraft
import com.example.orgclock.sync.PeerProbeResult
import com.example.orgclock.sync.PeerRegistrationRequest
import com.example.orgclock.sync.toRegistrationRequest
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class OrgClockPeerManagementCoordinator(
    private val uiState: MutableStateFlow<OrgClockUiState>,
    private val status: (StatusMessageKey, StatusTone, Array<out String>) -> UiStatus,
    private val nowProvider: () -> kotlinx.datetime.Instant,
    private val syncPairTrustedPeer: suspend (PeerRegistrationRequest) -> PeerProbeResult,
    private val syncRevokePeer: suspend (String) -> Unit,
    private val syncProbePeer: suspend (String) -> PeerProbeResult,
) {
    suspend fun addPeer() {
        val input = uiState.value.syncPeerInput.trim()
        val deviceId = uiState.value.syncPeerDeviceId.trim()
        val displayName = uiState.value.syncPeerDisplayName.trim().ifBlank { input }
        val publicKey = uiState.value.syncPeerPublicKey.trim()
        val validation = validatePeerId(input)
        if (validation != null) {
            uiState.update { it.copy(syncPeerInputError = validation) }
            return
        }
        if (deviceId.isBlank()) {
            uiState.update { it.copy(syncPeerInputError = "device id is empty") }
            return
        }
        if (displayName.isBlank()) {
            uiState.update { it.copy(syncPeerInputError = "display name is empty") }
            return
        }
        if (publicKey.isBlank()) {
            uiState.update { it.copy(syncPeerInputError = "public key is empty") }
            return
        }
        uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        val request = PeerPairingDraft(
            peerId = input,
            deviceId = deviceId,
            displayName = displayName,
            publicKeyBase64 = publicKey,
            viewerModeEnabled = uiState.value.syncPeerViewerModeEnabled,
        ).toRegistrationRequest(nowProvider())
        val probe = syncPairTrustedPeer(request)
        uiState.update { it.copy(syncPeerBusy = false) }
        if (!probe.reachable) {
            uiState.update { it.copy(syncPeerInputError = probe.reason ?: "failed to reach peer", status = status(StatusMessageKey.SyncPeerAddFailed, StatusTone.Warning, arrayOf(probe.reason ?: "unknown"))) }
            return
        }
        uiState.update {
            it.copy(
                syncPeerInput = "",
                syncPeerDeviceId = "",
                syncPeerDisplayName = "",
                syncPeerPublicKey = "",
                syncPeerViewerModeEnabled = false,
                syncPeerInputError = null,
                status = status(StatusMessageKey.SyncPeerAdded, StatusTone.Success, arrayOf(input)),
            )
        }
    }

    suspend fun revokePeer(peerId: String) {
        uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        syncRevokePeer(peerId)
        uiState.update { it.copy(syncPeerBusy = false, status = status(StatusMessageKey.SyncPeerRemoved, StatusTone.Success, arrayOf(peerId))) }
    }

    suspend fun probePeer(peerId: String) {
        uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        val probe = syncProbePeer(peerId)
        uiState.update {
            it.copy(
                syncPeerBusy = false,
                status = if (probe.reachable) status(StatusMessageKey.SyncPeerProbeOk, StatusTone.Success, arrayOf(peerId))
                else status(StatusMessageKey.SyncPeerProbeFailed, StatusTone.Warning, arrayOf(peerId, probe.reason ?: "unknown")),
            )
        }
    }

    private fun validatePeerId(raw: String): String? {
        if (raw.isBlank()) return "peer id is empty"
        if (raw.contains("://")) return "peer id must be host[:port]"
        if (!Regex("^[a-zA-Z0-9.-]+(:\\d{1,5})?$").matches(raw)) return "peer id format is invalid"
        return null
    }
}
