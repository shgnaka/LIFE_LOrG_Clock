package com.example.orgclock.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.sync.PeerProbeResult
import com.example.orgclock.sync.SyncIntegrationSnapshot
import com.example.orgclock.template.RootScheduleConfig
import com.example.orgclock.template.TemplateFileStatus
import com.example.orgclock.time.toKotlinInstantCompat
import com.example.orgclock.time.toKotlinLocalDateCompat
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.store.OrgClockStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.toJavaInstant
import java.time.LocalDate
import java.time.ZonedDateTime

class OrgClockViewModel(
    loadSavedRootReference: () -> RootReference?,
    saveRootReference: (RootReference) -> Unit,
    openRoot: suspend (RootReference) -> Result<Unit>,
    listFiles: suspend () -> Result<List<OrgFileEntry>>,
    listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    startClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    stopClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    cancelClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    listClosedClocks: suspend (String, HeadingPath) -> Result<List<ClosedClockEntry>>,
    editClosedClock: suspend (String, HeadingPath, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    deleteClosedClock: suspend (String, HeadingPath, Int) -> Result<Unit>,
    createL1Heading: suspend (String, String, Boolean) -> Result<Unit>,
    createL2Heading: suspend (String, HeadingPath, String, Boolean) -> Result<Unit>,
    loadNotificationEnabled: () -> Boolean,
    saveNotificationEnabled: (Boolean) -> Unit,
    loadNotificationDisplayMode: () -> NotificationDisplayMode,
    saveNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    notificationPermissionGrantedProvider: () -> Boolean,
    loadRootScheduleConfig: (RootReference) -> RootScheduleConfig,
    loadTemplateFileStatus: suspend (RootScheduleConfig) -> TemplateFileStatus,
    loadTemplateAutoGenerationFailure: (RootReference) -> String?,
    saveRootScheduleConfig: suspend (RootScheduleConfig) -> Unit,
    syncRootScheduleConfig: suspend (RootScheduleConfig) -> Unit,
    syncTemplateTaggedHeading: suspend (String) -> Result<Boolean> = { Result.success(false) },
    syncSnapshotFlow: StateFlow<SyncIntegrationSnapshot> = MutableStateFlow(SyncIntegrationSnapshot()),
    syncEnableStandardMode: suspend () -> Unit = {},
    syncEnableActiveMode: suspend () -> Unit = {},
    syncStopRuntime: suspend () -> Unit = {},
    syncFlushNow: suspend () -> Unit = {},
    syncSetEnabled: suspend (Boolean) -> Unit = {},
    syncSetDefaultPeerId: suspend (String) -> Unit = {},
    syncListTrustedPeers: () -> List<String> = { emptyList() },
    syncAddTrustedPeer: suspend (String) -> PeerProbeResult = { peerId ->
        PeerProbeResult(peerId = peerId, reachable = false, checkedAtEpochMs = 0L, reason = "sync unavailable")
    },
    syncRevokePeer: suspend (String) -> Unit = {},
    syncProbePeer: suspend (String) -> PeerProbeResult = { peerId ->
        PeerProbeResult(peerId = peerId, reachable = false, checkedAtEpochMs = 0L, reason = "sync unavailable")
    },
    syncFeatureEnabled: Boolean = false,
    syncDebugEnabled: Boolean = false,
    nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    todayProvider: () -> LocalDate = { LocalDate.now() },
    showPerfOverlay: Boolean,
) : ViewModel() {
    private val store = OrgClockStore(
        scope = viewModelScope,
        loadSavedRootReference = loadSavedRootReference,
        saveRootReference = saveRootReference,
        openRoot = openRoot,
        listFiles = listFiles,
        listFilesWithOpenClock = listFilesWithOpenClock,
        listHeadings = listHeadings,
        startClock = startClock,
        stopClock = stopClock,
        cancelClock = cancelClock,
        listClosedClocks = listClosedClocks,
        editClosedClock = { fileId, headingPath, lineIndex, start, end ->
            editClosedClock(
                fileId,
                headingPath,
                lineIndex,
                start.toJavaInstant().atZone(java.time.ZoneId.systemDefault()),
                end.toJavaInstant().atZone(java.time.ZoneId.systemDefault()),
            )
        },
        deleteClosedClock = deleteClosedClock,
        createL1Heading = createL1Heading,
        createL2Heading = createL2Heading,
        loadNotificationEnabled = loadNotificationEnabled,
        saveNotificationEnabled = saveNotificationEnabled,
        loadNotificationDisplayMode = loadNotificationDisplayMode,
        saveNotificationDisplayMode = saveNotificationDisplayMode,
        notificationPermissionGrantedProvider = notificationPermissionGrantedProvider,
        loadRootScheduleConfig = loadRootScheduleConfig,
        loadTemplateFileStatus = loadTemplateFileStatus,
        loadTemplateAutoGenerationFailure = loadTemplateAutoGenerationFailure,
        saveRootScheduleConfig = saveRootScheduleConfig,
        syncRootScheduleConfig = syncRootScheduleConfig,
        syncTemplateTaggedHeading = syncTemplateTaggedHeading,
        syncSnapshotFlow = syncSnapshotFlow,
        syncEnableStandardMode = syncEnableStandardMode,
        syncEnableActiveMode = syncEnableActiveMode,
        syncStopRuntime = syncStopRuntime,
        syncFlushNow = syncFlushNow,
        syncSetEnabled = syncSetEnabled,
        syncSetDefaultPeerId = syncSetDefaultPeerId,
        syncListTrustedPeers = syncListTrustedPeers,
        syncAddTrustedPeer = syncAddTrustedPeer,
        syncRevokePeer = syncRevokePeer,
        syncProbePeer = syncProbePeer,
        syncFeatureEnabled = syncFeatureEnabled,
        syncDebugEnabled = syncDebugEnabled,
        nowProvider = { nowProvider().toKotlinInstantCompat() },
        todayProvider = { todayProvider().toKotlinLocalDateCompat() },
        showPerfOverlay = showPerfOverlay,
    )

    val uiState: StateFlow<OrgClockUiState> = store.uiState

    fun onAction(action: OrgClockUiAction) {
        store.onAction(action)
    }
}
