package com.example.orgclock.ui.store

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.domain.ClockOperationCode
import com.example.orgclock.domain.ClockOperationException
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusText
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import com.example.orgclock.sync.PeerProbeResult
import com.example.orgclock.sync.SyncIntegrationSnapshot
import com.example.orgclock.ui.state.ClockEditDraft
import com.example.orgclock.ui.state.CreateHeadingDialogState
import com.example.orgclock.ui.state.CreateHeadingMode
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.PeerUiItem
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.time.normalizeMinuteToStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toInstant

class OrgClockStore(
    private val scope: CoroutineScope,
    private val loadSavedRootReference: () -> RootReference?,
    private val saveRootReference: (RootReference) -> Unit,
    private val openRoot: suspend (RootReference) -> Result<Unit>,
    private val listFiles: suspend () -> Result<List<OrgFileEntry>>,
    private val listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    private val listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    private val startClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    private val stopClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    private val cancelClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    private val listClosedClocks: suspend (String, HeadingPath) -> Result<List<ClosedClockEntry>>,
    private val editClosedClock: suspend (String, HeadingPath, Int, Instant, Instant) -> Result<Unit>,
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
    private val nowProvider: () -> Instant,
    private val todayProvider: () -> LocalDate,
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
    showPerfOverlay: Boolean,
) {
    private fun status(messageKey: StatusMessageKey, tone: StatusTone, vararg messageArgs: String): UiStatus {
        return UiStatus(text = StatusText(messageKey, messageArgs.toList()), tone = tone)
    }

    private val _uiState = MutableStateFlow(OrgClockUiState(showPerfOverlay = showPerfOverlay))
    val uiState: StateFlow<OrgClockUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var headingsSyncJob: Job? = null
    private val inFlightClockOps = mutableSetOf<HeadingPath>()
    private var editSaveInFlight = false
    private var createHeadingSubmitInFlight = false
    private var deleteInFlight = false

    init {
        if (syncFeatureEnabled) {
            scope.launch {
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

    private fun handleNavigationAction(action: OrgClockUiAction): Boolean = when (action) {
        OrgClockUiAction.Initialize -> {
            if (!initialized) {
                initialized = true
                scope.launch { initialize() }
            }
            true
        }
        is OrgClockUiAction.PickRoot -> {
            scope.launch { applyRoot(action.rootReference) }
            true
        }
        is OrgClockUiAction.SelectFile -> {
            scope.launch { loadHeadingsFor(action.file) }
            true
        }
        is OrgClockUiAction.ToggleL1 -> {
            _uiState.update { state ->
                val collapsed = if (action.title in state.collapsedL1) state.collapsedL1 - action.title else state.collapsedL1 + action.title
                state.copy(collapsedL1 = collapsed)
            }
            true
        }
        OrgClockUiAction.CollapseAll -> {
            _uiState.update { state ->
                val l1Titles = state.headings.asSequence().filter { it.node.level == 1 }.map { it.node.title }.distinct().toSet()
                state.copy(collapsedL1 = l1Titles)
            }
            true
        }
        OrgClockUiAction.ExpandAll -> {
            _uiState.update { it.copy(collapsedL1 = emptySet()) }
            true
        }
        OrgClockUiAction.RefreshHeadings -> {
            uiState.value.selectedFile?.let { file -> scope.launch { loadHeadingsFor(file, updateStatus = false) } }
            true
        }
        OrgClockUiAction.RefreshFiles -> {
            scope.launch { refreshFilesAndRoute() }
            true
        }
        is OrgClockUiAction.SelectHeading -> {
            _uiState.update { it.copy(selectedHeadingPath = action.path) }
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

    private fun handleClockMutationAction(action: OrgClockUiAction): Boolean = when (action) {
        is OrgClockUiAction.StartClock -> {
            if (beginClockMutation(action.path)) scope.launch { startClock(action.path) }
            true
        }
        is OrgClockUiAction.StopClock -> {
            if (beginClockMutation(action.path)) scope.launch { stopClock(action.path) }
            true
        }
        is OrgClockUiAction.CancelClock -> {
            if (beginClockMutation(action.path)) scope.launch { cancelClock(action.path) }
            true
        }
        else -> false
    }

    private fun handleHistoryEditDeleteAction(action: OrgClockUiAction): Boolean = when (action) {
        is OrgClockUiAction.OpenHistory -> {
            scope.launch { openHistory(action.item) }
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
            val timeZone = timeZoneProvider()
            _uiState.update {
                it.copy(
                    editingEntry = action.entry,
                    editingDraft = ClockEditDraft(
                        startHour = action.entry.start.toLocalDateTime(timeZone).hour,
                        startMinute = normalizeMinuteToStep(action.entry.start.toLocalDateTime(timeZone).minute),
                        endHour = action.entry.end.toLocalDateTime(timeZone).hour,
                        endMinute = normalizeMinuteToStep(action.entry.end.toLocalDateTime(timeZone).minute),
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
            _uiState.update { it.copy(deletingEntry = action.entry, deletingInProgress = false) }
            true
        }
        OrgClockUiAction.CancelDelete -> {
            finishDelete()
            _uiState.update { it.copy(deletingEntry = null, deletingInProgress = false) }
            true
        }
        OrgClockUiAction.ConfirmDelete -> {
            if (beginDelete()) scope.launch { confirmDelete() }
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
            if (beginEditSave()) scope.launch { saveEdit() }
            true
        }
        else -> false
    }

    private fun handleCreateHeadingAction(action: OrgClockUiAction): Boolean = when (action) {
        OrgClockUiAction.OpenCreateL1Dialog -> {
            finishCreateHeadingSubmit()
            _uiState.update {
                it.copy(createHeadingDialog = CreateHeadingDialogState(mode = CreateHeadingMode.L1, canAttachTplTag = isDailyOrgFile(it.selectedFile)))
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
                if (!dialog.canAttachTplTag) return@update state
                state.copy(createHeadingDialog = dialog.copy(attachTplTag = action.enabled))
            }
            true
        }
        OrgClockUiAction.SubmitCreateHeading -> {
            if (beginCreateHeadingSubmit()) scope.launch { submitCreateHeading() }
            true
        }
        OrgClockUiAction.DismissCreateHeadingDialog -> {
            finishCreateHeadingSubmit()
            _uiState.update { it.copy(createHeadingDialog = null) }
            true
        }
        else -> false
    }

    private fun handleNotificationAction(action: OrgClockUiAction): Boolean = when (action) {
        is OrgClockUiAction.ToggleNotificationEnabled -> { toggleNotificationEnabled(action.enabled); true }
        is OrgClockUiAction.ChangeNotificationDisplayMode -> { changeNotificationDisplayMode(action.mode); true }
        OrgClockUiAction.RequestNotificationPermissionHandled -> { _uiState.update { it.copy(notificationPermissionRequestPending = false) }; true }
        is OrgClockUiAction.NotificationPermissionResult -> { onNotificationPermissionResult(action.granted); true }
        OrgClockUiAction.RefreshNotificationPermissionState -> { refreshNotificationPermissionState(); true }
        OrgClockUiAction.OpenAppNotificationSettings -> { _uiState.update { it.copy(openAppNotificationSettingsPending = true) }; true }
        OrgClockUiAction.AppNotificationSettingsOpened -> { _uiState.update { it.copy(openAppNotificationSettingsPending = false) }; true }
        OrgClockUiAction.RefreshSyncDebug -> { applySyncSnapshot(syncSnapshotFlow.value); true }
        OrgClockUiAction.SyncFlushNow -> { scope.launch { syncFlushNow() }; true }
        OrgClockUiAction.SyncEnableStandard -> { scope.launch { syncEnableStandardMode() }; true }
        OrgClockUiAction.SyncEnableActive -> { scope.launch { syncEnableActiveMode() }; true }
        OrgClockUiAction.SyncStopRuntime -> { scope.launch { syncStopRuntime() }; true }
        is OrgClockUiAction.SyncSetEnabled -> { scope.launch { syncSetEnabled(action.enabled) }; true }
        is OrgClockUiAction.SyncSetDefaultPeerId -> { scope.launch { syncSetDefaultPeerId(action.peerId) }; true }
        is OrgClockUiAction.SyncUpdatePeerInput -> { _uiState.update { it.copy(syncPeerInput = action.value, syncPeerInputError = null) }; true }
        OrgClockUiAction.SyncAddPeer -> { scope.launch { addPeer() }; true }
        is OrgClockUiAction.SyncRevokePeer -> { scope.launch { revokePeer(action.peerId) }; true }
        is OrgClockUiAction.SyncProbePeer -> { scope.launch { probePeer(action.peerId) }; true }
        else -> false
    }

    private suspend fun initialize() {
        _uiState.update {
            it.copy(
                notificationEnabled = loadNotificationEnabled(),
                notificationDisplayMode = loadNotificationDisplayMode(),
                notificationPermissionGranted = notificationPermissionGrantedProvider(),
                syncFeatureVisible = syncFeatureEnabled,
                syncDebugVisible = syncDebugEnabled,
            )
        }
        applySyncSnapshot(syncSnapshotFlow.value)
        val saved = loadSavedRootReference()
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
                it.copy(
                    selectedFile = file,
                    headings = headings,
                    selectedHeadingPath = if (sameFile) it.selectedHeadingPath?.takeIf { path -> headings.any { heading -> heading.node.path == path } } else null,
                    pendingClockOps = if (sameFile) it.pendingClockOps.filterTo(mutableSetOf()) { path -> headings.any { heading -> heading.node.path == path } } else emptySet(),
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
                    status = if (updateStatus) status(StatusMessageKey.LoadedFile, StatusTone.Success, file.displayName) else it.status,
                )
            }
        } else {
            val reason = loaded.exceptionOrNull()?.message ?: ""
            _uiState.update { it.copy(status = status(StatusMessageKey.FailedLoadingHeadings, StatusTone.Error, reason)) }
        }
    }

    private suspend fun refreshFilesAndRoute() {
        val result = listFiles()
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update { it.copy(status = status(StatusMessageKey.FailedListingFiles, StatusTone.Error, reason), screen = Screen.FilePicker) }
            return
        }
        val listed = result.getOrThrow()
        _uiState.update { it.copy(files = listed) }
        refreshFilesWithOpenClock()
        val today = listed.firstOrNull { it.displayName == "${todayProvider()}.org" }
        if (today != null) {
            loadHeadingsFor(today)
        } else {
            _uiState.update {
                it.copy(selectedFile = null, headings = emptyList(), selectedHeadingPath = null, screen = Screen.FilePicker, status = status(StatusMessageKey.TodayFileNotFound, StatusTone.Warning))
            }
        }
    }

    private suspend fun refreshFilesWithOpenClock() {
        listFilesWithOpenClock().getOrNull()?.let { fileIds -> _uiState.update { it.copy(filesWithOpenClock = fileIds) } }
    }

    private suspend fun applyRoot(rootReference: RootReference) {
        val opened = openRoot(rootReference)
        if (opened.isFailure) {
            val reason = opened.exceptionOrNull()?.message ?: ""
            _uiState.update { it.copy(status = status(StatusMessageKey.FailedOpenRoot, StatusTone.Error, reason), screen = Screen.RootSetup) }
            return
        }
        saveRootReference(rootReference)
        _uiState.update { it.copy(rootReference = rootReference, status = status(StatusMessageKey.RootSet, StatusTone.Success)) }
        refreshFilesAndRoute()
    }

    private suspend fun refreshSelectedFileHeadings() {
        uiState.value.selectedFile?.let { synchronizeHeadings(it) }
    }

    private suspend fun openHistory(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        _uiState.update { it.copy(selectedHeadingPath = item.node.path, historyTarget = item, historyLoading = true, historyEntries = emptyList()) }
        val result = listClosedClocks(file.fileId, item.node.path)
        if (result.isSuccess) {
            _uiState.update { it.copy(historyEntries = result.getOrThrow(), historyLoading = false) }
        } else {
            val reason = result.exceptionOrNull()?.message ?: ""
            _uiState.update { it.copy(historyEntries = emptyList(), historyLoading = false, status = status(StatusMessageKey.FailedLoadingHistory, StatusTone.Error, reason)) }
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
            _uiState.update { it.copy(historyEntries = emptyList(), status = status(StatusMessageKey.FailedLoadingHistory, StatusTone.Error, reason)) }
        }
    }

    private suspend fun startClock(path: HeadingPath) {
        val file = uiState.value.selectedFile ?: run { finishClockMutation(path); return }
        val item = uiState.value.headings.firstOrNull { it.node.path == path } ?: run { finishClockMutation(path); return }
        val optimisticStartedAt = nowProvider()
        updateHeadingOpenClock(path, OpenClockState(optimisticStartedAt))
        val result = try { startClock(file.fileId, path) } finally { finishClockMutation(path) }
        val nextStatus = if (result.isSuccess) {
            status(StatusMessageKey.ClockStarted, StatusTone.Success)
        } else {
            val error = result.exceptionOrNull()
            val tone = when ((error as? ClockOperationException)?.code) {
                ClockOperationCode.AlreadyRunning -> StatusTone.Warning
                else -> StatusTone.Error
            }
            status(StatusMessageKey.StartFailed, tone, error?.message ?: "")
        }
        if (result.isSuccess) {
            val startedAt = result.getOrThrow().startedAt ?: optimisticStartedAt
            updateHeadingOpenClock(path, OpenClockState(startedAt))
            _uiState.update { it.copy(status = nextStatus) }
            refreshFilesWithOpenClock()
        } else {
            updateHeadingOpenClock(path, item.openClock)
            _uiState.update { it.copy(status = nextStatus) }
        }
    }

    private suspend fun stopClock(path: HeadingPath) {
        val file = uiState.value.selectedFile ?: run { finishClockMutation(path); return }
        val item = uiState.value.headings.firstOrNull { it.node.path == path } ?: run { finishClockMutation(path); return }
        updateHeadingOpenClock(path, null)
        val result = try { stopClock(file.fileId, path) } finally { finishClockMutation(path) }
        val nextStatus = if (result.isSuccess) status(StatusMessageKey.ClockStopped, StatusTone.Success) else status(StatusMessageKey.StopFailed, StatusTone.Error, result.exceptionOrNull()?.message ?: "")
        if (result.isSuccess) {
            _uiState.update { it.copy(status = nextStatus) }
            refreshFilesWithOpenClock()
        } else {
            updateHeadingOpenClock(path, item.openClock)
            _uiState.update { it.copy(status = nextStatus) }
        }
    }

    private suspend fun cancelClock(path: HeadingPath) {
        val file = uiState.value.selectedFile ?: run { finishClockMutation(path); return }
        val item = uiState.value.headings.firstOrNull { it.node.path == path } ?: run { finishClockMutation(path); return }
        updateHeadingOpenClock(path, null)
        val result = try { cancelClock(file.fileId, path) } finally { finishClockMutation(path) }
        val nextStatus = if (result.isSuccess) status(StatusMessageKey.ClockCancelled, StatusTone.Warning) else status(StatusMessageKey.CancelFailed, StatusTone.Error, result.exceptionOrNull()?.message ?: "")
        if (result.isSuccess) {
            _uiState.update { it.copy(status = nextStatus) }
            refreshFilesWithOpenClock()
        } else {
            updateHeadingOpenClock(path, item.openClock)
            _uiState.update { it.copy(status = nextStatus) }
        }
    }

    private suspend fun saveEdit() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val entry = state.editingEntry ?: return
        val draft = state.editingDraft ?: return
        val timeZone = timeZoneProvider()
        val startDate = entry.start.toLocalDateTime(timeZone).date
        val endDate = entry.end.toLocalDateTime(timeZone).date
        val updatedStart = LocalDateTime(startDate, LocalTime(draft.startHour, draft.startMinute)).toInstant(timeZone)
        val updatedEnd = LocalDateTime(endDate, LocalTime(draft.endHour, draft.endMinute)).toInstant(timeZone)
        if (updatedEnd < updatedStart) {
            _uiState.update { it.copy(status = status(StatusMessageKey.EndTimeMustBeAfterStart, StatusTone.Warning)) }
            finishEditSave()
            return
        }
        val result = try { editClosedClock(file.fileId, entry.headingPath, entry.clockLineIndex, updatedStart, updatedEnd) } finally { finishEditSave() }
        if (result.isSuccess) {
            _uiState.update { it.copy(status = status(StatusMessageKey.ClockHistoryDeleted, StatusTone.Success), editingEntry = null, editingDraft = null) }
            reloadHistoryIfNeeded()
            refreshSelectedFileHeadings()
        } else {
            _uiState.update { it.copy(status = status(StatusMessageKey.DeleteFailed, StatusTone.Error, result.exceptionOrNull()?.message ?: "")) }
        }
    }

    private suspend fun submitCreateHeading() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val dialog = state.createHeadingDialog ?: return
        val title = dialog.titleInput.trim()
        if (title.isEmpty()) {
            _uiState.update { it.copy(status = status(StatusMessageKey.HeadingTitleEmpty, StatusTone.Warning)) }
            finishCreateHeadingSubmit()
            return
        }
        val result = try {
            when (dialog.mode) {
                CreateHeadingMode.L1 -> createL1Heading(file.fileId, title, dialog.attachTplTag)
                CreateHeadingMode.L2 -> {
                    val parentPath = dialog.parentL1Path
                    if (parentPath == null) {
                        _uiState.update { it.copy(status = status(StatusMessageKey.ParentL1Missing, StatusTone.Error)) }
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
            _uiState.update { it.copy(createHeadingDialog = null, status = status(StatusMessageKey.HeadingCreated, StatusTone.Success)) }
            synchronizeHeadings(file)
            return
        }
        val error = result.exceptionOrNull()
        val tone = if (error is IllegalArgumentException) StatusTone.Warning else StatusTone.Error
        _uiState.update { current ->
            current.copy(createHeadingDialog = current.createHeadingDialog?.copy(submitting = false), status = status(StatusMessageKey.CreateHeadingFailed, tone, error?.message ?: ""))
        }
    }

    private suspend fun confirmDelete() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val entry = state.deletingEntry ?: return
        val result = try { deleteClosedClock(file.fileId, entry.headingPath, entry.clockLineIndex) } finally { finishDelete() }
        if (result.isSuccess) {
            _uiState.update { it.copy(deletingEntry = null, deletingInProgress = false, status = status(StatusMessageKey.ClockHistoryUpdated, StatusTone.Success)) }
            reloadHistoryIfNeeded()
            refreshSelectedFileHeadings()
        } else {
            _uiState.update { it.copy(deletingInProgress = false, status = status(StatusMessageKey.UpdateFailed, StatusTone.Error, result.exceptionOrNull()?.message ?: "")) }
        }
    }

    private fun toggleNotificationEnabled(enabled: Boolean) {
        if (!enabled) {
            saveNotificationEnabled(false)
            _uiState.update { it.copy(notificationEnabled = false, pendingEnableNotificationAfterPermission = false, notificationPermissionRequestPending = false, openAppNotificationSettingsPending = false) }
            return
        }
        val granted = notificationPermissionGrantedProvider()
        if (granted) {
            saveNotificationEnabled(true)
            _uiState.update {
                it.copy(notificationEnabled = true, notificationPermissionGranted = true, pendingEnableNotificationAfterPermission = false, notificationPermissionRequestPending = false, status = status(StatusMessageKey.NotificationEnabled, StatusTone.Success))
            }
        } else {
            _uiState.update {
                it.copy(notificationPermissionGranted = false, pendingEnableNotificationAfterPermission = true, notificationPermissionRequestPending = true, status = status(StatusMessageKey.NotificationPermissionRequired, StatusTone.Warning))
            }
        }
    }

    private fun changeNotificationDisplayMode(mode: NotificationDisplayMode) {
        saveNotificationDisplayMode(mode)
        _uiState.update { it.copy(notificationDisplayMode = mode, status = status(StatusMessageKey.NotificationDisplayModeUpdated, StatusTone.Success)) }
    }

    private fun onNotificationPermissionResult(granted: Boolean) {
        _uiState.update { state ->
            val shouldEnable = state.pendingEnableNotificationAfterPermission && granted
            if (shouldEnable) {
                saveNotificationEnabled(true)
                state.copy(notificationEnabled = true, notificationPermissionGranted = true, notificationPermissionRequestPending = false, pendingEnableNotificationAfterPermission = false, status = status(StatusMessageKey.NotificationPermissionGranted, StatusTone.Success))
            } else {
                if (state.pendingEnableNotificationAfterPermission) saveNotificationEnabled(false)
                state.copy(
                    notificationEnabled = if (state.pendingEnableNotificationAfterPermission) false else state.notificationEnabled,
                    notificationPermissionGranted = granted,
                    notificationPermissionRequestPending = false,
                    pendingEnableNotificationAfterPermission = false,
                    status = if (!granted && state.pendingEnableNotificationAfterPermission) status(StatusMessageKey.NotificationPermissionDenied, StatusTone.Warning) else state.status,
                )
            }
        }
    }

    private fun refreshNotificationPermissionState() {
        val granted = notificationPermissionGrantedProvider()
        _uiState.update { state -> if (state.notificationPermissionGranted == granted) state else state.copy(notificationPermissionGranted = granted) }
    }

    private fun isDailyOrgFile(file: OrgFileEntry?): Boolean = file?.displayName?.let(DAILY_ORG_FILE_REGEX::matches) ?: false

    private fun updatePendingClock(path: HeadingPath, pending: Boolean) {
        _uiState.update { state -> state.copy(pendingClockOps = if (pending) state.pendingClockOps + path else state.pendingClockOps - path) }
    }

    private fun updateHeadingOpenClock(path: HeadingPath, openClock: OpenClockState?) {
        _uiState.update { state -> state.copy(headings = state.headings.map { if (it.node.path == path) it.copy(openClock = openClock) else it }) }
    }

    private fun beginClockMutation(path: HeadingPath): Boolean {
        synchronized(inFlightClockOps) {
            if (!inFlightClockOps.add(path)) return false
        }
        updatePendingClock(path, true)
        return true
    }

    private fun finishClockMutation(path: HeadingPath) {
        synchronized(inFlightClockOps) { inFlightClockOps.remove(path) }
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
        synchronized(this) { editSaveInFlight = false }
        _uiState.update { it.copy(editingInProgress = false) }
    }

    private fun beginCreateHeadingSubmit(): Boolean {
        if (uiState.value.createHeadingDialog == null) return false
        synchronized(this) {
            if (createHeadingSubmitInFlight) return false
            createHeadingSubmitInFlight = true
        }
        _uiState.update { current ->
            val dialog = current.createHeadingDialog ?: return@update current
            current.copy(createHeadingDialog = dialog.copy(submitting = true))
        }
        return true
    }

    private fun finishCreateHeadingSubmit(success: Boolean = false) {
        synchronized(this) { createHeadingSubmitInFlight = false }
        if (success) return
        _uiState.update { current ->
            val dialog = current.createHeadingDialog ?: return@update current
            current.copy(createHeadingDialog = dialog.copy(submitting = false))
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
        synchronized(this) { deleteInFlight = false }
        _uiState.update { it.copy(deletingInProgress = false) }
    }

    private suspend fun synchronizeHeadings(file: OrgFileEntry) {
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            val headings = loaded.getOrThrow()
            _uiState.update { state ->
                if (state.selectedFile?.fileId != file.fileId) state else state.copy(
                    headings = headings,
                    selectedHeadingPath = state.selectedHeadingPath?.takeIf { path -> headings.any { it.node.path == path } },
                    pendingClockOps = state.pendingClockOps.filterTo(mutableSetOf()) { path -> headings.any { it.node.path == path } },
                    historyTarget = state.historyTarget?.let { target -> headings.firstOrNull { it.node.path == target.node.path } },
                )
            }
        } else {
            _uiState.update { it.copy(status = status(StatusMessageKey.FailedLoadingHeadings, StatusTone.Error, loaded.exceptionOrNull()?.message ?: "")) }
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
            _uiState.update { it.copy(syncPeerInputError = probe.reason ?: "failed to reach peer", status = status(StatusMessageKey.SyncPeerAddFailed, StatusTone.Warning, probe.reason ?: "unknown")) }
            return
        }
        _uiState.update { it.copy(syncPeerInput = "", syncPeerInputError = null, status = status(StatusMessageKey.SyncPeerAdded, StatusTone.Success, input)) }
    }

    private suspend fun revokePeer(peerId: String) {
        _uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        syncRevokePeer(peerId)
        _uiState.update { it.copy(syncPeerBusy = false, status = status(StatusMessageKey.SyncPeerRemoved, StatusTone.Success, peerId)) }
    }

    private suspend fun probePeer(peerId: String) {
        _uiState.update { it.copy(syncPeerBusy = true, syncPeerInputError = null) }
        val probe = syncProbePeer(peerId)
        _uiState.update {
            it.copy(
                syncPeerBusy = false,
                status = if (probe.reachable) status(StatusMessageKey.SyncPeerProbeOk, StatusTone.Success, peerId)
                else status(StatusMessageKey.SyncPeerProbeFailed, StatusTone.Warning, peerId, probe.reason ?: "unknown"),
            )
        }
    }

    private fun validatePeerId(raw: String): String? {
        if (raw.isBlank()) return "peer id is empty"
        if (raw.contains("://")) return "peer id must be host[:port]"
        if (!Regex("^[a-zA-Z0-9.-]+(:\\d{1,5})?$").matches(raw)) return "peer id format is invalid"
        return null
    }

    private fun applySyncSnapshot(snapshot: SyncIntegrationSnapshot) {
        val peersById = snapshot.peerStates.associateBy { it.peerId }
        val trustedPeers = syncListTrustedPeers()
        _uiState.update {
            it.copy(
                syncRuntimeEnabled = snapshot.runtimeEnabled,
                syncDefaultPeerId = snapshot.defaultPeerId.orEmpty(),
                syncPeers = trustedPeers.map { peerId ->
                    val peer = peersById[peerId]
                    PeerUiItem(peerId = peerId, reachable = peer?.reachable, lastCheckedAtEpochMs = peer?.lastCheckedAtEpochMs, lastSyncedAtEpochMs = peer?.lastSyncedAtEpochMs)
                },
                syncRuntimeMode = snapshot.runtimeMode,
                syncLastResultSummary = snapshot.lastResult?.let { result -> "${result.status.wireValue} (${result.commandId})" },
                syncLastError = snapshot.lastError,
                syncMetrics = snapshot.metrics,
                syncDeliveryStates = snapshot.lastDeliveryStates,
            )
        }
    }

    private companion object {
        val DAILY_ORG_FILE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}\\.org$")
    }
}
