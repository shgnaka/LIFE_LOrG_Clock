package com.example.orgclock.ui.viewmodel

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.orgclock.R
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockOperationCode
import com.example.orgclock.domain.ClockOperationException
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.sync.PeerProbeResult
import com.example.orgclock.sync.SyncIntegrationSnapshot
import com.example.orgclock.sync.SyncRuntimeMode
import com.example.orgclock.time.toJavaZonedDateTime
import com.example.orgclock.time.toKotlinInstantCompat
import com.example.orgclock.ui.state.ClockEditDraft
import com.example.orgclock.ui.state.CreateHeadingDialogState
import com.example.orgclock.ui.state.CreateHeadingMode
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.PeerUiItem
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import com.example.orgclock.ui.time.normalizeMinuteToStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class OrgClockViewModel(
    private val loadSavedUri: () -> Uri?,
    private val saveUri: (Uri) -> Unit,
    private val openRoot: suspend (Uri) -> Result<Unit>,
    private val listFiles: suspend () -> Result<List<OrgFileEntry>>,
    private val listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    private val listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    private val startClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    private val stopClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    private val cancelClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    private val listClosedClocks: suspend (String, HeadingPath) -> Result<List<ClosedClockEntry>>,
    private val editClosedClock: suspend (String, HeadingPath, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    private val deleteClosedClock: suspend (String, HeadingPath, Int) -> Result<Unit>,
    private val createL1Heading: suspend (String, String, Boolean) -> Result<Unit>,
    private val createL2Heading: suspend (String, HeadingPath, String, Boolean) -> Result<Unit>,
    private val loadNotificationEnabled: () -> Boolean,
    private val saveNotificationEnabled: (Boolean) -> Unit,
    private val loadNotificationDisplayMode: () -> NotificationDisplayMode,
    private val saveNotificationDisplayMode: (NotificationDisplayMode) -> Unit,
    private val notificationPermissionGrantedProvider: () -> Boolean,
    private val syncSnapshotFlow: StateFlow<SyncIntegrationSnapshot> = MutableStateFlow(SyncIntegrationSnapshot()),
    private val syncEnableStandardMode: suspend () -> Unit = {},
    private val syncEnableActiveMode: suspend () -> Unit = {},
    private val syncStopRuntime: suspend () -> Unit = {},
    private val syncFlushNow: suspend () -> Unit = {},
    private val syncSetEnabled: suspend (Boolean) -> Unit = {},
    private val syncSetDefaultPeerId: suspend (String) -> Unit = {},
    private val syncListTrustedPeers: () -> List<String> = { emptyList() },
    private val syncAddTrustedPeer: suspend (String) -> PeerProbeResult = { peerId ->
        PeerProbeResult(peerId = peerId, reachable = false, checkedAtEpochMs = 0L, reason = "sync unavailable")
    },
    private val syncRevokePeer: suspend (String) -> Unit = {},
    private val syncProbePeer: suspend (String) -> PeerProbeResult = { peerId ->
        PeerProbeResult(peerId = peerId, reachable = false, checkedAtEpochMs = 0L, reason = "sync unavailable")
    },
    private val syncFeatureEnabled: Boolean = false,
    private val syncDebugEnabled: Boolean = false,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    showPerfOverlay: Boolean,
) : ViewModel() {
    private fun status(@StringRes messageResId: Int, tone: StatusTone, vararg messageArgs: Any): UiStatus {
        return UiStatus(messageResId = messageResId, messageArgs = messageArgs.toList(), tone = tone)
    }

    private val _uiState = MutableStateFlow(
        OrgClockUiState(showPerfOverlay = showPerfOverlay),
    )
    val uiState: StateFlow<OrgClockUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var headingsSyncJob: Job? = null
    private val inFlightClockOps = mutableSetOf<HeadingPath>()
    private var editSaveInFlight = false
    private var createHeadingSubmitInFlight = false
    private var deleteInFlight = false

    init {
        if (syncFeatureEnabled) {
            viewModelScope.launch {
                syncSnapshotFlow.collectLatest { snapshot ->
                    applySyncSnapshot(snapshot)
                }
            }
        }
    }

    fun onAction(action: OrgClockUiAction) {
        if (handleNavigationAction(action)) return
        if (handleClockMutationAction(action)) return
        if (handleHistoryEditDeleteAction(action)) return
        if (handleCreateHeadingAction(action)) return
        if (handleNotificationAction(action)) return
        error("Unhandled OrgClockUiAction: $action")
    }

    private fun handleNavigationAction(action: OrgClockUiAction): Boolean {
        return when (action) {
            OrgClockUiAction.Initialize -> {
                if (initialized) {
                    true
                } else {
                    initialized = true
                    viewModelScope.launch { initialize() }
                    true
                }
            }

            is OrgClockUiAction.PickRoot -> {
                viewModelScope.launch { applyRoot(action.uri) }
                true
            }

            is OrgClockUiAction.SelectFile -> {
                viewModelScope.launch { loadHeadingsFor(action.file) }
                true
            }

            is OrgClockUiAction.ToggleL1 -> {
                _uiState.update { state ->
                    val collapsed = if (action.title in state.collapsedL1) {
                        state.collapsedL1 - action.title
                    } else {
                        state.collapsedL1 + action.title
                    }
                    state.copy(collapsedL1 = collapsed)
                }
                true
            }

            OrgClockUiAction.CollapseAll -> {
                _uiState.update { state ->
                    val l1Titles = state.headings.asSequence()
                        .filter { it.node.level == 1 }
                        .map { it.node.title }
                        .distinct()
                        .toSet()
                    state.copy(collapsedL1 = l1Titles)
                }
                true
            }

            OrgClockUiAction.ExpandAll -> {
                _uiState.update { it.copy(collapsedL1 = emptySet()) }
                true
            }

            OrgClockUiAction.RefreshHeadings -> {
                val file = uiState.value.selectedFile
                if (file != null) {
                    viewModelScope.launch { loadHeadingsFor(file, updateStatus = false) }
                }
                true
            }

            OrgClockUiAction.RefreshFiles -> {
                viewModelScope.launch { refreshFilesAndRoute() }
                true
            }

            is OrgClockUiAction.SelectHeading -> {
                _uiState.update { state -> state.copy(selectedHeadingPath = action.path) }
                true
            }

            OrgClockUiAction.OpenFilePicker -> {
                _uiState.update { it.copy(screen = Screen.FilePicker) }
                true
            }

            OrgClockUiAction.OpenSettings -> {
                _uiState.update { it.copy(screen = Screen.Settings) }
                true
            }

            OrgClockUiAction.BackFromSettings -> {
                _uiState.update { state ->
                    state.copy(screen = if (state.selectedFile != null) Screen.HeadingList else Screen.FilePicker)
                }
                true
            }

            else -> false
        }
    }

    private fun handleClockMutationAction(action: OrgClockUiAction): Boolean {
        return when (action) {
            is OrgClockUiAction.StartClock -> {
                if (beginClockMutation(action.path)) {
                    viewModelScope.launch { startClock(action.path) }
                }
                true
            }

            is OrgClockUiAction.StopClock -> {
                if (beginClockMutation(action.path)) {
                    viewModelScope.launch { stopClock(action.path) }
                }
                true
            }

            is OrgClockUiAction.CancelClock -> {
                if (beginClockMutation(action.path)) {
                    viewModelScope.launch { cancelClock(action.path) }
                }
                true
            }

            else -> false
        }
    }

    private fun handleHistoryEditDeleteAction(action: OrgClockUiAction): Boolean {
        return when (action) {
            is OrgClockUiAction.OpenHistory -> {
                viewModelScope.launch { openHistory(action.item) }
                true
            }

            OrgClockUiAction.DismissHistory -> {
                finishEditSave()
                finishDelete()
                _uiState.update {
                    it.copy(
                        historyTarget = null,
                        historyEntries = emptyList(),
                        historyLoading = false,
                        editingEntry = null,
                        editingDraft = null,
                        editingInProgress = false,
                        deletingEntry = null,
                        deletingInProgress = false,
                    )
                }
                true
            }

            is OrgClockUiAction.BeginEdit -> {
                finishEditSave()
                _uiState.update {
                    it.copy(
                        editingEntry = action.entry,
                        editingDraft = ClockEditDraft(
                            startHour = action.entry.start.toJavaZonedDateTime(ZoneId.systemDefault()).hour,
                            startMinute = normalizeMinuteToStep(action.entry.start.toJavaZonedDateTime(ZoneId.systemDefault()).minute),
                            endHour = action.entry.end.toJavaZonedDateTime(ZoneId.systemDefault()).hour,
                            endMinute = normalizeMinuteToStep(action.entry.end.toJavaZonedDateTime(ZoneId.systemDefault()).minute),
                        ),
                    )
                }
                true
            }

            OrgClockUiAction.CancelEdit -> {
                finishEditSave()
                _uiState.update { it.copy(editingEntry = null, editingDraft = null, editingInProgress = false) }
                true
            }

            is OrgClockUiAction.BeginDelete -> {
                finishDelete()
                _uiState.update {
                    it.copy(
                        deletingEntry = action.entry,
                        deletingInProgress = false,
                    )
                }
                true
            }

            OrgClockUiAction.CancelDelete -> {
                finishDelete()
                _uiState.update { it.copy(deletingEntry = null, deletingInProgress = false) }
                true
            }

            OrgClockUiAction.ConfirmDelete -> {
                if (beginDelete()) {
                    viewModelScope.launch { confirmDelete() }
                }
                true
            }

            is OrgClockUiAction.SelectStartHour -> {
                _uiState.update { state ->
                    val draft = state.editingDraft ?: return@update state
                    state.copy(editingDraft = draft.copy(startHour = action.hour))
                }
                true
            }

            is OrgClockUiAction.SelectStartMinute -> {
                _uiState.update { state ->
                    val draft = state.editingDraft ?: return@update state
                    state.copy(editingDraft = draft.copy(startMinute = action.minute))
                }
                true
            }

            is OrgClockUiAction.SelectEndHour -> {
                _uiState.update { state ->
                    val draft = state.editingDraft ?: return@update state
                    state.copy(editingDraft = draft.copy(endHour = action.hour))
                }
                true
            }

            is OrgClockUiAction.SelectEndMinute -> {
                _uiState.update { state ->
                    val draft = state.editingDraft ?: return@update state
                    state.copy(editingDraft = draft.copy(endMinute = action.minute))
                }
                true
            }

            OrgClockUiAction.SaveEdit -> {
                if (beginEditSave()) {
                    viewModelScope.launch { saveEdit() }
                }
                true
            }

            else -> false
        }
    }

    private fun handleCreateHeadingAction(action: OrgClockUiAction): Boolean {
        return when (action) {
            OrgClockUiAction.OpenCreateL1Dialog -> {
                finishCreateHeadingSubmit()
                _uiState.update {
                    it.copy(
                        createHeadingDialog = CreateHeadingDialogState(
                            mode = CreateHeadingMode.L1,
                            canAttachTplTag = isDailyOrgFile(it.selectedFile),
                        ),
                    )
                }
                true
            }

            is OrgClockUiAction.OpenCreateL2Dialog -> {
                if (action.parent.node.level == 1) {
                    finishCreateHeadingSubmit()
                    _uiState.update {
                        it.copy(
                            createHeadingDialog = CreateHeadingDialogState(
                                mode = CreateHeadingMode.L2,
                                parentL1Path = action.parent.node.path,
                                parentL1Title = action.parent.node.title,
                                canAttachTplTag = isDailyOrgFile(it.selectedFile),
                            ),
                        )
                    }
                }
                true
            }

            is OrgClockUiAction.UpdateCreateHeadingTitle -> {
                _uiState.update { state ->
                    val dialog = state.createHeadingDialog ?: return@update state
                    state.copy(createHeadingDialog = dialog.copy(titleInput = action.title))
                }
                true
            }

            is OrgClockUiAction.SetCreateHeadingTplTag -> {
                _uiState.update { state ->
                    val dialog = state.createHeadingDialog ?: return@update state
                    if (!dialog.canAttachTplTag) {
                        return@update state
                    }
                    state.copy(createHeadingDialog = dialog.copy(attachTplTag = action.enabled))
                }
                true
            }

            OrgClockUiAction.SubmitCreateHeading -> {
                if (beginCreateHeadingSubmit()) {
                    viewModelScope.launch { submitCreateHeading() }
                }
                true
            }

            OrgClockUiAction.DismissCreateHeadingDialog -> {
                finishCreateHeadingSubmit()
                _uiState.update { it.copy(createHeadingDialog = null) }
                true
            }

            else -> false
        }
    }

    private fun handleNotificationAction(action: OrgClockUiAction): Boolean {
        return when (action) {
            is OrgClockUiAction.ToggleNotificationEnabled -> {
                toggleNotificationEnabled(action.enabled)
                true
            }

            is OrgClockUiAction.ChangeNotificationDisplayMode -> {
                changeNotificationDisplayMode(action.mode)
                true
            }

            OrgClockUiAction.RequestNotificationPermissionHandled -> {
                _uiState.update {
                    it.copy(notificationPermissionRequestPending = false)
                }
                true
            }

            is OrgClockUiAction.NotificationPermissionResult -> {
                onNotificationPermissionResult(action.granted)
                true
            }

            OrgClockUiAction.RefreshNotificationPermissionState -> {
                refreshNotificationPermissionState()
                true
            }

            OrgClockUiAction.OpenAppNotificationSettings -> {
                _uiState.update {
                    it.copy(openAppNotificationSettingsPending = true)
                }
                true
            }

            OrgClockUiAction.AppNotificationSettingsOpened -> {
                _uiState.update {
                    it.copy(openAppNotificationSettingsPending = false)
                }
                true
            }

            OrgClockUiAction.RefreshSyncDebug -> {
                applySyncSnapshot(syncSnapshotFlow.value)
                true
            }

            OrgClockUiAction.SyncFlushNow -> {
                viewModelScope.launch { syncFlushNow() }
                true
            }

            OrgClockUiAction.SyncEnableStandard -> {
                viewModelScope.launch { syncEnableStandardMode() }
                true
            }

            OrgClockUiAction.SyncEnableActive -> {
                viewModelScope.launch { syncEnableActiveMode() }
                true
            }

            OrgClockUiAction.SyncStopRuntime -> {
                viewModelScope.launch { syncStopRuntime() }
                true
            }

            is OrgClockUiAction.SyncSetEnabled -> {
                viewModelScope.launch { syncSetEnabled(action.enabled) }
                true
            }

            is OrgClockUiAction.SyncSetDefaultPeerId -> {
                viewModelScope.launch { syncSetDefaultPeerId(action.peerId) }
                true
            }

            is OrgClockUiAction.SyncUpdatePeerInput -> {
                _uiState.update {
                    it.copy(
                        syncPeerInput = action.value,
                        syncPeerInputError = null,
                    )
                }
                true
            }

            OrgClockUiAction.SyncAddPeer -> {
                viewModelScope.launch { addPeer() }
                true
            }

            is OrgClockUiAction.SyncRevokePeer -> {
                viewModelScope.launch { revokePeer(action.peerId) }
                true
            }

            is OrgClockUiAction.SyncProbePeer -> {
                viewModelScope.launch { probePeer(action.peerId) }
                true
            }

            else -> false
        }
    }

    private suspend fun initialize() {
        val notificationEnabled = loadNotificationEnabled()
        val notificationDisplayMode = loadNotificationDisplayMode()
        val notificationPermissionGranted = notificationPermissionGrantedProvider()
        _uiState.update {
            it.copy(
                notificationEnabled = notificationEnabled,
                notificationDisplayMode = notificationDisplayMode,
                notificationPermissionGranted = notificationPermissionGranted,
                syncFeatureVisible = syncFeatureEnabled,
                syncDebugVisible = syncDebugEnabled,
            )
        }
        applySyncSnapshot(syncSnapshotFlow.value)
        val saved = loadSavedUri()
        if (saved == null) {
            _uiState.update { it.copy(screen = Screen.RootSetup) }
            return
        }
        applyRoot(saved)
    }

    private suspend fun loadHeadingsFor(file: OrgFileEntry, updateStatus: Boolean = true) {
        headingsSyncJob?.cancel()
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            val headings = loaded.getOrThrow()
            _uiState.update {
                val sameFile = it.selectedFile?.fileId == file.fileId
                val selectedHeadingPath = if (sameFile) {
                    it.selectedHeadingPath?.takeIf { path -> headings.any { heading -> heading.node.path == path } }
                } else {
                    null
                }
                val pendingClockOps = if (sameFile) {
                    it.pendingClockOps.filterTo(mutableSetOf()) { path -> headings.any { heading -> heading.node.path == path } }
                } else {
                    emptySet()
                }
                it.copy(
                    selectedFile = file,
                    headings = headings,
                    selectedHeadingPath = selectedHeadingPath,
                    pendingClockOps = pendingClockOps,
                    collapsedL1 = emptySet(),
                    historyTarget = null,
                    historyEntries = emptyList(),
                    historyLoading = false,
                    editingEntry = null,
                    editingDraft = null,
                    editingInProgress = false,
                    deletingEntry = null,
                    deletingInProgress = false,
                    createHeadingDialog = null,
                    screen = Screen.HeadingList,
                    status = if (updateStatus) {
                        status(R.string.status_loaded_file, StatusTone.Success, file.displayName)
                    } else {
                        it.status
                    },
                )
            }
        } else {
            val reason = loaded.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(status = status(R.string.status_failed_loading_headings, StatusTone.Error, reason))
            }
        }
    }

    private suspend fun refreshFilesAndRoute() {
        val result = listFiles()
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(
                    status = status(R.string.status_failed_listing_files, StatusTone.Error, reason),
                    screen = Screen.FilePicker,
                )
            }
            return
        }

        val listed = result.getOrThrow()
        _uiState.update { it.copy(files = listed) }
        refreshFilesWithOpenClock()
        val todayFileName = "${todayProvider()}.org"
        val today = listed.firstOrNull { it.displayName == todayFileName }
        if (today != null) {
            loadHeadingsFor(today)
        } else {
            _uiState.update {
                it.copy(
                    selectedFile = null,
                    headings = emptyList(),
                    selectedHeadingPath = null,
                    screen = Screen.FilePicker,
                    status = status(R.string.status_today_file_not_found, StatusTone.Warning),
                )
            }
        }
    }

    private suspend fun refreshFilesWithOpenClock() {
        val result = listFilesWithOpenClock()
        if (result.isSuccess) {
            val fileIds = result.getOrThrow()
            _uiState.update { it.copy(filesWithOpenClock = fileIds) }
        }
    }

    private suspend fun applyRoot(uri: Uri) {
        val opened = openRoot(uri)
        if (opened.isFailure) {
            val reason = opened.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(
                    status = status(R.string.status_failed_open_root, StatusTone.Error, reason),
                    screen = Screen.RootSetup,
                )
            }
            return
        }
        saveUri(uri)
        _uiState.update {
            it.copy(
                rootUri = uri,
                status = status(R.string.status_root_set, StatusTone.Success),
            )
        }
        refreshFilesAndRoute()
    }

    private suspend fun refreshSelectedFileHeadings() {
        val file = uiState.value.selectedFile ?: return
        synchronizeHeadings(file)
    }

    private suspend fun openHistory(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        _uiState.update {
            it.copy(
                selectedHeadingPath = item.node.path,
                historyTarget = item,
                historyLoading = true,
                historyEntries = emptyList(),
            )
        }
        val result = listClosedClocks(file.fileId, item.node.path)
        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    historyEntries = result.getOrThrow(),
                    historyLoading = false,
                )
            }
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(
                    historyEntries = emptyList(),
                    historyLoading = false,
                    status = status(R.string.status_failed_loading_history, StatusTone.Error, reason),
                )
            }
        }
    }

    private suspend fun reloadHistoryIfNeeded() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val target = state.historyTarget ?: return
        val result = listClosedClocks(file.fileId, target.node.path)
        if (result.isSuccess) {
            _uiState.update { it.copy(historyEntries = result.getOrThrow()) }
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(
                    historyEntries = emptyList(),
                    status = status(R.string.status_failed_loading_history, StatusTone.Error, reason),
                )
            }
        }
    }

    private suspend fun startClock(path: HeadingPath) {
        val file = uiState.value.selectedFile ?: run {
            finishClockMutation(path)
            return
        }
        val item = uiState.value.headings.firstOrNull { it.node.path == path } ?: run {
            finishClockMutation(path)
            return
        }
        val optimisticStartedAt = nowProvider()
        updateHeadingOpenClock(path, OpenClockState(optimisticStartedAt.toKotlinInstantCompat()))

        val result = try {
            startClock(file.fileId, path)
        } finally {
            finishClockMutation(path)
        }
        val status = if (result.isSuccess) {
            status(R.string.status_clock_started, StatusTone.Success)
        } else {
            val error = result.exceptionOrNull()
            val msg = error?.message ?: ""
            val tone = when ((error as? ClockOperationException)?.code) {
                ClockOperationCode.AlreadyRunning -> StatusTone.Warning
                else -> StatusTone.Error
            }
            status(R.string.status_start_failed, tone, msg)
        }
        if (result.isSuccess) {
            val startedAt = result.getOrThrow().startedAt ?: optimisticStartedAt.toKotlinInstantCompat()
            updateHeadingOpenClock(path, OpenClockState(startedAt))
            _uiState.update { it.copy(status = status) }
            refreshFilesWithOpenClock()
        } else {
            updateHeadingOpenClock(path, item.openClock)
            _uiState.update { it.copy(status = status) }
        }
    }

    private suspend fun stopClock(path: HeadingPath) {
        val file = uiState.value.selectedFile ?: run {
            finishClockMutation(path)
            return
        }
        val item = uiState.value.headings.firstOrNull { it.node.path == path } ?: run {
            finishClockMutation(path)
            return
        }
        updateHeadingOpenClock(path, null)
        val result = try {
            stopClock(file.fileId, path)
        } finally {
            finishClockMutation(path)
        }
        val status = if (result.isSuccess) {
            status(R.string.status_clock_stopped, StatusTone.Success)
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            status(R.string.status_stop_failed, StatusTone.Error, reason)
        }
        if (result.isSuccess) {
            _uiState.update { it.copy(status = status) }
            refreshFilesWithOpenClock()
        } else {
            updateHeadingOpenClock(path, item.openClock)
            _uiState.update { it.copy(status = status) }
        }
    }

    private suspend fun cancelClock(path: HeadingPath) {
        val file = uiState.value.selectedFile ?: run {
            finishClockMutation(path)
            return
        }
        val item = uiState.value.headings.firstOrNull { it.node.path == path } ?: run {
            finishClockMutation(path)
            return
        }
        updateHeadingOpenClock(path, null)
        val result = try {
            cancelClock(file.fileId, path)
        } finally {
            finishClockMutation(path)
        }
        val status = if (result.isSuccess) {
            status(R.string.status_clock_cancelled, StatusTone.Warning)
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            status(R.string.status_cancel_failed, StatusTone.Error, reason)
        }
        if (result.isSuccess) {
            _uiState.update { it.copy(status = status) }
            refreshFilesWithOpenClock()
        } else {
            updateHeadingOpenClock(path, item.openClock)
            _uiState.update { it.copy(status = status) }
        }
    }

    private suspend fun saveEdit() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val entry = state.editingEntry ?: return
        val draft = state.editingDraft ?: return

        val updatedStart = entry.start.toJavaZonedDateTime(ZoneId.systemDefault())
            .withHour(draft.startHour)
            .withMinute(draft.startMinute)
            .withSecond(0)
            .withNano(0)
        val updatedEnd = entry.end.toJavaZonedDateTime(ZoneId.systemDefault())
            .withHour(draft.endHour)
            .withMinute(draft.endMinute)
            .withSecond(0)
            .withNano(0)

        if (updatedEnd.isBefore(updatedStart)) {
            _uiState.update {
                it.copy(
                    status = status(R.string.status_end_time_must_be_after_start, StatusTone.Warning),
                )
            }
            finishEditSave()
            return
        }

        val result = try {
            editClosedClock(
                file.fileId,
                entry.headingPath,
                entry.clockLineIndex,
                updatedStart,
                updatedEnd,
            )
        } finally {
            finishEditSave()
        }

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    status = status(R.string.status_clock_history_updated, StatusTone.Success),
                    editingEntry = null,
                    editingDraft = null,
                )
            }
            reloadHistoryIfNeeded()
            refreshSelectedFileHeadings()
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(
                    status = status(R.string.status_update_failed, StatusTone.Error, reason),
                )
            }
        }
    }

    private suspend fun submitCreateHeading() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val dialog = state.createHeadingDialog ?: return

        val title = dialog.titleInput.trim()
        if (title.isEmpty()) {
            _uiState.update {
                it.copy(status = status(R.string.status_heading_title_empty, StatusTone.Warning))
            }
            finishCreateHeadingSubmit()
            return
        }

        val result = try {
            when (dialog.mode) {
                CreateHeadingMode.L1 -> createL1Heading(file.fileId, title, dialog.attachTplTag)
                CreateHeadingMode.L2 -> {
                    val parentPath = dialog.parentL1Path
                    if (parentPath == null) {
                        _uiState.update {
                            it.copy(
                                status = status(R.string.status_parent_l1_missing, StatusTone.Error),
                            )
                        }
                        finishCreateHeadingSubmit()
                        return
                    }
                    createL2Heading(file.fileId, parentPath, title, dialog.attachTplTag)
                }
            }
        } finally {
            finishCreateHeadingSubmit(success = false)
        }

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    createHeadingDialog = null,
                    status = status(R.string.status_heading_created, StatusTone.Success),
                )
            }
            synchronizeHeadings(file)
            return
        }

        val message = result.exceptionOrNull()?.message ?: ""
        val tone = if (result.exceptionOrNull() is IllegalArgumentException) {
            StatusTone.Warning
        } else {
            StatusTone.Error
        }
        _uiState.update { current ->
            val currentDialog = current.createHeadingDialog
            current.copy(
                createHeadingDialog = currentDialog?.copy(submitting = false),
                status = status(R.string.status_create_heading_failed, tone, message),
            )
        }
    }

    private suspend fun confirmDelete() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val entry = state.deletingEntry ?: return

        val result = try {
            deleteClosedClock(file.fileId, entry.headingPath, entry.clockLineIndex)
        } finally {
            finishDelete()
        }
        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    deletingEntry = null,
                    deletingInProgress = false,
                    status = status(R.string.status_clock_history_deleted, StatusTone.Success),
                )
            }
            reloadHistoryIfNeeded()
            refreshSelectedFileHeadings()
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(
                    deletingInProgress = false,
                    status = status(R.string.status_delete_failed, StatusTone.Error, reason),
                )
            }
        }
    }

    private fun toggleNotificationEnabled(enabled: Boolean) {
        if (!enabled) {
            saveNotificationEnabled(false)
            _uiState.update {
                it.copy(
                    notificationEnabled = false,
                    pendingEnableNotificationAfterPermission = false,
                    notificationPermissionRequestPending = false,
                    openAppNotificationSettingsPending = false,
                )
            }
            return
        }

        val granted = notificationPermissionGrantedProvider()
        if (granted) {
            saveNotificationEnabled(true)
            _uiState.update {
                it.copy(
                    notificationEnabled = true,
                    notificationPermissionGranted = true,
                    pendingEnableNotificationAfterPermission = false,
                    notificationPermissionRequestPending = false,
                    status = status(R.string.status_notification_enabled, StatusTone.Success),
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    notificationPermissionGranted = false,
                    pendingEnableNotificationAfterPermission = true,
                    notificationPermissionRequestPending = true,
                    status = status(R.string.status_notification_permission_required, StatusTone.Warning),
                )
            }
        }
    }

    private fun changeNotificationDisplayMode(mode: NotificationDisplayMode) {
        saveNotificationDisplayMode(mode)
        _uiState.update {
            it.copy(
                notificationDisplayMode = mode,
                status = status(R.string.status_notification_display_mode_updated, StatusTone.Success),
            )
        }
    }

    private fun onNotificationPermissionResult(granted: Boolean) {
        _uiState.update { state ->
            val shouldEnable = state.pendingEnableNotificationAfterPermission && granted
            if (shouldEnable) {
                saveNotificationEnabled(true)
                state.copy(
                    notificationEnabled = true,
                    notificationPermissionGranted = true,
                    notificationPermissionRequestPending = false,
                    pendingEnableNotificationAfterPermission = false,
                    status = status(R.string.status_notification_permission_granted, StatusTone.Success),
                )
            } else {
                if (state.pendingEnableNotificationAfterPermission) {
                    saveNotificationEnabled(false)
                }
                state.copy(
                    notificationEnabled = if (state.pendingEnableNotificationAfterPermission) false else state.notificationEnabled,
                    notificationPermissionGranted = granted,
                    notificationPermissionRequestPending = false,
                    pendingEnableNotificationAfterPermission = false,
                    status = if (!granted && state.pendingEnableNotificationAfterPermission) {
                        status(R.string.status_notification_permission_denied, StatusTone.Warning)
                    } else {
                        state.status
                    },
                )
            }
        }
    }

    private fun refreshNotificationPermissionState() {
        val granted = notificationPermissionGrantedProvider()
        _uiState.update { state ->
            if (state.notificationPermissionGranted == granted) {
                state
            } else {
                state.copy(notificationPermissionGranted = granted)
            }
        }
    }

    private fun isDailyOrgFile(file: OrgFileEntry?): Boolean {
        val name = file?.displayName ?: return false
        return DAILY_ORG_FILE_REGEX.matches(name)
    }

    private fun updatePendingClock(path: HeadingPath, pending: Boolean) {
        _uiState.update { state ->
            val next = if (pending) {
                state.pendingClockOps + path
            } else {
                state.pendingClockOps - path
            }
            state.copy(pendingClockOps = next)
        }
    }

    private fun updateHeadingOpenClock(path: HeadingPath, openClock: OpenClockState?) {
        _uiState.update { state ->
            val updated = state.headings.map { heading ->
                if (heading.node.path == path) {
                    heading.copy(openClock = openClock)
                } else {
                    heading
                }
            }
            state.copy(headings = updated)
        }
    }

    private fun beginClockMutation(path: HeadingPath): Boolean {
        synchronized(inFlightClockOps) {
            if (!inFlightClockOps.add(path)) return false
        }
        updatePendingClock(path, true)
        return true
    }

    private fun finishClockMutation(path: HeadingPath) {
        synchronized(inFlightClockOps) {
            inFlightClockOps.remove(path)
        }
        updatePendingClock(path, false)
    }

    private fun beginEditSave(): Boolean {
        if (uiState.value.editingEntry == null || uiState.value.editingDraft == null) return false
        synchronized(this) {
            if (editSaveInFlight) return false
            editSaveInFlight = true
        }
        _uiState.update { it.copy(editingInProgress = true) }
        return true
    }

    private fun finishEditSave() {
        synchronized(this) {
            editSaveInFlight = false
        }
        _uiState.update { it.copy(editingInProgress = false) }
    }

    private fun beginCreateHeadingSubmit(): Boolean {
        if (uiState.value.createHeadingDialog == null) return false
        synchronized(this) {
            if (createHeadingSubmitInFlight) return false
            createHeadingSubmitInFlight = true
        }
        _uiState.update { current ->
            val currentDialog = current.createHeadingDialog ?: return@update current
            current.copy(createHeadingDialog = currentDialog.copy(submitting = true))
        }
        return true
    }

    private fun finishCreateHeadingSubmit(success: Boolean = false) {
        synchronized(this) {
            createHeadingSubmitInFlight = false
        }
        if (success) return
        _uiState.update { current ->
            val currentDialog = current.createHeadingDialog ?: return@update current
            current.copy(createHeadingDialog = currentDialog.copy(submitting = false))
        }
    }

    private fun beginDelete(): Boolean {
        if (uiState.value.deletingEntry == null) return false
        synchronized(this) {
            if (deleteInFlight) return false
            deleteInFlight = true
        }
        _uiState.update { it.copy(deletingInProgress = true) }
        return true
    }

    private fun finishDelete() {
        synchronized(this) {
            deleteInFlight = false
        }
        _uiState.update { it.copy(deletingInProgress = false) }
    }

    private fun requestHeadingsSync(file: OrgFileEntry) {
        headingsSyncJob?.cancel()
        headingsSyncJob = viewModelScope.launch {
            synchronizeHeadings(file)
        }
    }

    private suspend fun synchronizeHeadings(file: OrgFileEntry) {
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            val headings = loaded.getOrThrow()
            _uiState.update { state ->
                if (state.selectedFile?.fileId != file.fileId) {
                    state
                } else {
                    state.copy(
                        headings = headings,
                        selectedHeadingPath = state.selectedHeadingPath?.takeIf { path ->
                            headings.any { heading -> heading.node.path == path }
                        },
                        pendingClockOps = state.pendingClockOps.filterTo(mutableSetOf()) { path ->
                            headings.any { heading -> heading.node.path == path }
                        },
                        historyTarget = state.historyTarget?.let { target ->
                            headings.firstOrNull { heading -> heading.node.path == target.node.path }
                        },
                    )
                }
            }
        } else {
            val reason = loaded.exceptionOrNull()?.message ?: ""
            _uiState.update {
                it.copy(status = status(R.string.status_failed_loading_headings, StatusTone.Error, reason))
            }
        }
    }

    private suspend fun addPeer() {
        val input = uiState.value.syncPeerInput.trim()
        val validation = validatePeerId(input)
        if (validation != null) {
            _uiState.update { it.copy(syncPeerInputError = validation) }
            return
        }
        _uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        val probe = syncAddTrustedPeer(input)
        _uiState.update { it.copy(syncPeerBusy = false) }
        if (!probe.reachable) {
            _uiState.update {
                it.copy(
                    syncPeerInputError = probe.reason ?: "failed to reach peer",
                    status = status(R.string.status_sync_peer_add_failed, StatusTone.Warning, probe.reason ?: "unknown"),
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                syncPeerInput = "",
                syncPeerInputError = null,
                status = status(R.string.status_sync_peer_added, StatusTone.Success, input),
            )
        }
    }

    private suspend fun revokePeer(peerId: String) {
        _uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        syncRevokePeer(peerId)
        _uiState.update {
            it.copy(
                syncPeerBusy = false,
                status = status(R.string.status_sync_peer_removed, StatusTone.Success, peerId),
            )
        }
    }

    private suspend fun probePeer(peerId: String) {
        _uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        val probe = syncProbePeer(peerId)
        _uiState.update {
            it.copy(
                syncPeerBusy = false,
                status = if (probe.reachable) {
                    status(R.string.status_sync_peer_probe_ok, StatusTone.Success, peerId)
                } else {
                    status(
                        R.string.status_sync_peer_probe_failed,
                        StatusTone.Warning,
                        peerId,
                        probe.reason ?: "unknown",
                    )
                },
            )
        }
    }

    private fun validatePeerId(raw: String): String? {
        if (raw.isBlank()) return "peer id is empty"
        if (raw.contains("://")) return "peer id must be host[:port]"
        val regex = Regex("^[a-zA-Z0-9.-]+(:\\d{1,5})?$")
        if (!regex.matches(raw)) return "peer id format is invalid"
        return null
    }

    private companion object {
        val DAILY_ORG_FILE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}\\.org$")
    }

    private fun applySyncSnapshot(snapshot: SyncIntegrationSnapshot) {
        _uiState.update { state ->
            state.copy(
                syncRuntimeEnabled = snapshot.runtimeEnabled,
                syncDefaultPeerId = snapshot.defaultPeerId.orEmpty(),
                syncPeers = buildPeerUiItems(snapshot),
                syncRuntimeMode = snapshot.runtimeMode,
                syncLastResultSummary = snapshot.lastResult?.let { result ->
                    buildString {
                        append(result.status.wireValue)
                        append(" / ")
                        append(result.commandId)
                        if (result.errorCode != null) {
                            append(" / ")
                            append(result.errorCode.name)
                        }
                    }
                },
                syncLastError = snapshot.lastError,
                syncMetrics = snapshot.metrics,
                syncDeliveryStates = snapshot.lastDeliveryStates,
            )
        }
    }

    private fun buildPeerUiItems(snapshot: SyncIntegrationSnapshot): List<PeerUiItem> {
        val stateByPeer = snapshot.peerStates.associateBy { it.peerId }
        return snapshot.trustedPeers
            .ifEmpty { syncListTrustedPeers() }
            .distinct()
            .sorted()
            .map { peerId ->
                val peerState = stateByPeer[peerId]
                PeerUiItem(
                    peerId = peerId,
                    reachable = peerState?.reachable,
                    lastCheckedAtEpochMs = peerState?.lastCheckedAtEpochMs,
                    lastSyncedAtEpochMs = peerState?.lastSyncedAtEpochMs,
                )
            }
    }
}
