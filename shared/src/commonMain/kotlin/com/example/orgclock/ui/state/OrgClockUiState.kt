package com.example.orgclock.ui.state

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusText
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import com.example.orgclock.sync.SyncDeliveryState
import com.example.orgclock.sync.SyncMetricsSnapshot
import com.example.orgclock.sync.SyncRuntimeMode

data class ClockEditDraft(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
)

enum class CreateHeadingMode {
    L1,
    L2,
}

data class PeerUiItem(
    val peerId: String,
    val reachable: Boolean? = null,
    val lastCheckedAtEpochMs: Long? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

data class CreateHeadingDialogState(
    val mode: CreateHeadingMode,
    val parentL1Path: HeadingPath? = null,
    val parentL1Title: String? = null,
    val canAttachTplTag: Boolean = false,
    val attachTplTag: Boolean = false,
    val titleInput: String = "",
    val submitting: Boolean = false,
)

data class OrgClockUiState(
    val screen: Screen = Screen.RootSetup,
    val rootReference: RootReference? = null,
    val status: UiStatus = UiStatus(
        text = StatusText(StatusMessageKey.SelectOrgDirectory),
        tone = StatusTone.Info,
    ),
    val files: List<OrgFileEntry> = emptyList(),
    val filesWithOpenClock: Set<String> = emptySet(),
    val selectedFile: OrgFileEntry? = null,
    val headings: List<HeadingViewItem> = emptyList(),
    val selectedHeadingPath: HeadingPath? = null,
    val pendingClockOps: Set<HeadingPath> = emptySet(),
    val collapsedL1: Set<String> = emptySet(),
    val historyTarget: HeadingViewItem? = null,
    val historyEntries: List<ClosedClockEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val editingEntry: ClosedClockEntry? = null,
    val editingDraft: ClockEditDraft? = null,
    val editingInProgress: Boolean = false,
    val deletingEntry: ClosedClockEntry? = null,
    val deletingInProgress: Boolean = false,
    val createHeadingDialog: CreateHeadingDialogState? = null,
    val notificationEnabled: Boolean = true,
    val notificationDisplayMode: NotificationDisplayMode = NotificationDisplayMode.ActiveOnly,
    val notificationPermissionGranted: Boolean = false,
    val notificationPermissionRequestPending: Boolean = false,
    val pendingEnableNotificationAfterPermission: Boolean = false,
    val openAppNotificationSettingsPending: Boolean = false,
    val showPerfOverlay: Boolean = false,
    val syncFeatureVisible: Boolean = false,
    val syncDebugVisible: Boolean = false,
    val syncRuntimeEnabled: Boolean = false,
    val syncDefaultPeerId: String = "",
    val syncPeers: List<PeerUiItem> = emptyList(),
    val syncPeerInput: String = "",
    val syncPeerInputError: String? = null,
    val syncPeerBusy: Boolean = false,
    val syncRuntimeMode: SyncRuntimeMode = SyncRuntimeMode.Off,
    val syncLastResultSummary: String? = null,
    val syncLastError: String? = null,
    val syncMetrics: SyncMetricsSnapshot = SyncMetricsSnapshot(),
    val syncDeliveryStates: List<SyncDeliveryState> = emptyList(),
)
