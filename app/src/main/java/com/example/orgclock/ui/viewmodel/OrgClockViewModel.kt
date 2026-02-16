package com.example.orgclock.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.RootAccess
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.ui.state.ClockEditDraft
import com.example.orgclock.ui.state.CreateHeadingDialogState
import com.example.orgclock.ui.state.CreateHeadingMode
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

class OrgClockViewModel(
    private val loadSavedUri: () -> Uri?,
    private val saveUri: (Uri) -> Unit,
    private val openRoot: suspend (Uri) -> Result<RootAccess>,
    private val listFiles: suspend () -> Result<List<OrgFileEntry>>,
    private val listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    private val startClock: suspend (String, Int) -> Result<ClockMutationResult>,
    private val stopClock: suspend (String, Int) -> Result<ClockMutationResult>,
    private val cancelClock: suspend (String, Int) -> Result<ClockMutationResult>,
    private val listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    private val editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    private val createL1Heading: suspend (String, String) -> Result<Unit>,
    private val createL2Heading: suspend (String, Int, String) -> Result<Unit>,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    showPerfOverlay: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OrgClockUiState(showPerfOverlay = showPerfOverlay),
    )
    val uiState: StateFlow<OrgClockUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var headingsSyncJob: Job? = null

    fun onAction(action: OrgClockUiAction) {
        when (action) {
            OrgClockUiAction.Initialize -> {
                if (initialized) return
                initialized = true
                viewModelScope.launch { initialize() }
            }

            is OrgClockUiAction.PickRoot -> viewModelScope.launch { applyRoot(action.uri) }
            is OrgClockUiAction.SelectFile -> viewModelScope.launch { loadHeadingsFor(action.file) }
            is OrgClockUiAction.ToggleL1 -> {
                _uiState.update { state ->
                    val collapsed = if (action.title in state.collapsedL1) {
                        state.collapsedL1 - action.title
                    } else {
                        state.collapsedL1 + action.title
                    }
                    state.copy(collapsedL1 = collapsed)
                }
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
            }

            OrgClockUiAction.ExpandAll -> _uiState.update { it.copy(collapsedL1 = emptySet()) }
            is OrgClockUiAction.OpenHistory -> viewModelScope.launch { openHistory(action.item) }
            OrgClockUiAction.DismissHistory -> {
                _uiState.update {
                    it.copy(
                        historyTarget = null,
                        historyEntries = emptyList(),
                        historyLoading = false,
                    )
                }
            }

            is OrgClockUiAction.StartClock -> viewModelScope.launch { startClock(action.item) }
            is OrgClockUiAction.StopClock -> viewModelScope.launch { stopClock(action.item) }
            is OrgClockUiAction.CancelClock -> viewModelScope.launch { cancelClock(action.item) }
            is OrgClockUiAction.BeginEdit -> {
                _uiState.update {
                    it.copy(
                        editingEntry = action.entry,
                        editingDraft = ClockEditDraft(
                            startHour = action.entry.start.hour,
                            startMinute = roundToNearest5(action.entry.start.minute),
                            endHour = action.entry.end.hour,
                            endMinute = roundToNearest5(action.entry.end.minute),
                        ),
                    )
                }
            }

            OrgClockUiAction.CancelEdit -> _uiState.update { it.copy(editingEntry = null, editingDraft = null) }
            is OrgClockUiAction.SelectStartHour -> _uiState.update { state ->
                val draft = state.editingDraft ?: return@update state
                state.copy(editingDraft = draft.copy(startHour = action.hour))
            }

            is OrgClockUiAction.SelectStartMinute -> _uiState.update { state ->
                val draft = state.editingDraft ?: return@update state
                state.copy(editingDraft = draft.copy(startMinute = action.minute))
            }

            is OrgClockUiAction.SelectEndHour -> _uiState.update { state ->
                val draft = state.editingDraft ?: return@update state
                state.copy(editingDraft = draft.copy(endHour = action.hour))
            }

            is OrgClockUiAction.SelectEndMinute -> _uiState.update { state ->
                val draft = state.editingDraft ?: return@update state
                state.copy(editingDraft = draft.copy(endMinute = action.minute))
            }

            OrgClockUiAction.SaveEdit -> viewModelScope.launch { saveEdit() }
            OrgClockUiAction.OpenCreateL1Dialog -> _uiState.update {
                it.copy(
                    createHeadingDialog = CreateHeadingDialogState(mode = CreateHeadingMode.L1),
                )
            }

            is OrgClockUiAction.OpenCreateL2Dialog -> {
                if (action.parent.node.level != 1) return
                _uiState.update {
                    it.copy(
                        createHeadingDialog = CreateHeadingDialogState(
                            mode = CreateHeadingMode.L2,
                            parentL1LineIndex = action.parent.node.lineIndex,
                            parentL1Title = action.parent.node.title,
                        ),
                    )
                }
            }

            is OrgClockUiAction.UpdateCreateHeadingTitle -> _uiState.update { state ->
                val dialog = state.createHeadingDialog ?: return@update state
                state.copy(createHeadingDialog = dialog.copy(titleInput = action.title))
            }

            OrgClockUiAction.SubmitCreateHeading -> viewModelScope.launch { submitCreateHeading() }
            OrgClockUiAction.DismissCreateHeadingDialog -> _uiState.update {
                it.copy(createHeadingDialog = null)
            }
            OrgClockUiAction.OpenFilePicker -> _uiState.update { it.copy(screen = Screen.FilePicker) }
            OrgClockUiAction.OpenSettings -> _uiState.update { it.copy(screen = Screen.Settings) }
            OrgClockUiAction.BackFromSettings -> _uiState.update { state ->
                state.copy(screen = if (state.selectedFile != null) Screen.HeadingList else Screen.FilePicker)
            }
        }
    }

    private suspend fun initialize() {
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
            _uiState.update {
                it.copy(
                    selectedFile = file,
                    headings = loaded.getOrThrow(),
                    pendingClockOps = emptySet(),
                    collapsedL1 = emptySet(),
                    historyTarget = null,
                    historyEntries = emptyList(),
                    historyLoading = false,
                    editingEntry = null,
                    editingDraft = null,
                    createHeadingDialog = null,
                    screen = Screen.HeadingList,
                    status = if (updateStatus) UiStatus("Loaded ${file.displayName}", StatusTone.Success) else it.status,
                )
            }
        } else {
            val reason = loaded.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(status = UiStatus("Failed loading headings: $reason", StatusTone.Error))
            }
        }
    }

    private suspend fun refreshFilesAndRoute() {
        val result = listFiles()
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(
                    status = UiStatus("Failed listing files: $reason", StatusTone.Error),
                    screen = Screen.FilePicker,
                )
            }
            return
        }

        val listed = result.getOrThrow()
        _uiState.update { it.copy(files = listed) }
        val todayFileName = "${todayProvider()}.org"
        val today = listed.firstOrNull { it.displayName == todayFileName }
        if (today != null) {
            loadHeadingsFor(today)
        } else {
            _uiState.update {
                it.copy(
                    selectedFile = null,
                    headings = emptyList(),
                    screen = Screen.FilePicker,
                    status = UiStatus("Today's file not found. Please select a file.", StatusTone.Warning),
                )
            }
        }
    }

    private suspend fun applyRoot(uri: Uri) {
        val opened = openRoot(uri)
        if (opened.isFailure) {
            val reason = opened.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(
                    status = UiStatus("Failed to open root: $reason", StatusTone.Error),
                    screen = Screen.RootSetup,
                )
            }
            return
        }
        saveUri(uri)
        _uiState.update {
            it.copy(
                rootUri = uri,
                status = UiStatus("Root set", StatusTone.Success),
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
                historyTarget = item,
                historyLoading = true,
                historyEntries = emptyList(),
            )
        }
        val result = listClosedClocks(file.fileId, item.node.lineIndex)
        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    historyEntries = result.getOrThrow(),
                    historyLoading = false,
                )
            }
        } else {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(
                    historyEntries = emptyList(),
                    historyLoading = false,
                    status = UiStatus("Failed loading history: $reason", StatusTone.Error),
                )
            }
        }
    }

    private suspend fun reloadHistoryIfNeeded() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val target = state.historyTarget ?: return
        val result = listClosedClocks(file.fileId, target.node.lineIndex)
        if (result.isSuccess) {
            _uiState.update { it.copy(historyEntries = result.getOrThrow()) }
        } else {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(
                    historyEntries = emptyList(),
                    status = UiStatus("Failed loading history: $reason", StatusTone.Error),
                )
            }
        }
    }

    private suspend fun startClock(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        val lineIndex = item.node.lineIndex
        val optimisticStartedAt = nowProvider()
        updatePendingClock(lineIndex, true)
        updateHeadingOpenClock(lineIndex, OpenClockState(optimisticStartedAt))

        val result = startClock(file.fileId, lineIndex)
        val status = if (result.isSuccess) {
            UiStatus("Clock started", StatusTone.Success)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "unknown"
            val tone = if (msg.contains("already", ignoreCase = true)) StatusTone.Warning else StatusTone.Error
            UiStatus("Start failed: $msg", tone)
        }
        if (result.isSuccess) {
            val startedAt = result.getOrThrow().startedAt ?: optimisticStartedAt
            updateHeadingOpenClock(lineIndex, OpenClockState(startedAt))
            updatePendingClock(lineIndex, false)
            _uiState.update { it.copy(status = status) }
            requestHeadingsSync(file)
        } else {
            updateHeadingOpenClock(lineIndex, item.openClock)
            updatePendingClock(lineIndex, false)
            _uiState.update { it.copy(status = status) }
        }
    }

    private suspend fun stopClock(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        val lineIndex = item.node.lineIndex
        updatePendingClock(lineIndex, true)
        updateHeadingOpenClock(lineIndex, null)

        val result = stopClock(file.fileId, lineIndex)
        val status = if (result.isSuccess) {
            UiStatus("Clock stopped", StatusTone.Success)
        } else {
            UiStatus("Stop failed: ${result.exceptionOrNull()?.message ?: "unknown"}", StatusTone.Error)
        }
        if (result.isSuccess) {
            updatePendingClock(lineIndex, false)
            _uiState.update { it.copy(status = status) }
            requestHeadingsSync(file)
        } else {
            updateHeadingOpenClock(lineIndex, item.openClock)
            updatePendingClock(lineIndex, false)
            _uiState.update { it.copy(status = status) }
        }
    }

    private suspend fun cancelClock(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        val lineIndex = item.node.lineIndex
        updatePendingClock(lineIndex, true)
        updateHeadingOpenClock(lineIndex, null)

        val result = cancelClock(file.fileId, lineIndex)
        val status = if (result.isSuccess) {
            UiStatus("Clock cancelled", StatusTone.Warning)
        } else {
            UiStatus("Cancel failed: ${result.exceptionOrNull()?.message ?: "unknown"}", StatusTone.Error)
        }
        if (result.isSuccess) {
            updatePendingClock(lineIndex, false)
            _uiState.update { it.copy(status = status) }
            requestHeadingsSync(file)
        } else {
            updateHeadingOpenClock(lineIndex, item.openClock)
            updatePendingClock(lineIndex, false)
            _uiState.update { it.copy(status = status) }
        }
    }

    private suspend fun saveEdit() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val entry = state.editingEntry ?: return
        val draft = state.editingDraft ?: return

        val updatedStart = entry.start
            .withHour(draft.startHour)
            .withMinute(draft.startMinute)
            .withSecond(0)
            .withNano(0)
        val updatedEnd = entry.end
            .withHour(draft.endHour)
            .withMinute(draft.endMinute)
            .withSecond(0)
            .withNano(0)

        if (updatedEnd.isBefore(updatedStart)) {
            _uiState.update {
                it.copy(status = UiStatus("終了時刻は開始時刻以降にしてください", StatusTone.Warning))
            }
            return
        }

        val result = editClosedClock(
            file.fileId,
            entry.headingLineIndex,
            entry.clockLineIndex,
            updatedStart,
            updatedEnd,
        )

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    status = UiStatus("Clock履歴を更新しました", StatusTone.Success),
                    editingEntry = null,
                    editingDraft = null,
                )
            }
            reloadHistoryIfNeeded()
            refreshSelectedFileHeadings()
        } else {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(status = UiStatus("更新に失敗: $reason", StatusTone.Error))
            }
        }
    }

    private suspend fun submitCreateHeading() {
        val state = uiState.value
        val file = state.selectedFile ?: return
        val dialog = state.createHeadingDialog ?: return
        if (dialog.submitting) return

        val title = dialog.titleInput.trim()
        if (title.isEmpty()) {
            _uiState.update {
                it.copy(status = UiStatus("Heading title cannot be empty", StatusTone.Warning))
            }
            return
        }

        _uiState.update { current ->
            val currentDialog = current.createHeadingDialog ?: return@update current
            current.copy(createHeadingDialog = currentDialog.copy(submitting = true))
        }

        val result = when (dialog.mode) {
            CreateHeadingMode.L1 -> createL1Heading(file.fileId, title)
            CreateHeadingMode.L2 -> {
                val parentIndex = dialog.parentL1LineIndex
                if (parentIndex == null) {
                    _uiState.update {
                        it.copy(
                            createHeadingDialog = it.createHeadingDialog?.copy(submitting = false),
                            status = UiStatus("Parent L1 is missing", StatusTone.Error),
                        )
                    }
                    return
                }
                createL2Heading(file.fileId, parentIndex, title)
            }
        }

        if (result.isSuccess) {
            _uiState.update {
                it.copy(
                    createHeadingDialog = null,
                    status = UiStatus("Heading created", StatusTone.Success),
                )
            }
            synchronizeHeadings(file)
            return
        }

        val message = result.exceptionOrNull()?.message ?: "unknown"
        val tone = if (result.exceptionOrNull() is IllegalArgumentException) {
            StatusTone.Warning
        } else {
            StatusTone.Error
        }
        _uiState.update { current ->
            val currentDialog = current.createHeadingDialog
            current.copy(
                createHeadingDialog = currentDialog?.copy(submitting = false),
                status = UiStatus("Create heading failed: $message", tone),
            )
        }
    }

    private fun roundToNearest5(minute: Int): Int {
        val normalized = ((minute + 2) / 5) * 5
        return if (normalized == 60) 55 else normalized
    }

    private fun updatePendingClock(lineIndex: Int, pending: Boolean) {
        _uiState.update { state ->
            val next = if (pending) {
                state.pendingClockOps + lineIndex
            } else {
                state.pendingClockOps - lineIndex
            }
            state.copy(pendingClockOps = next)
        }
    }

    private fun updateHeadingOpenClock(lineIndex: Int, openClock: OpenClockState?) {
        _uiState.update { state ->
            val updated = state.headings.map { heading ->
                if (heading.node.lineIndex == lineIndex) {
                    heading.copy(openClock = openClock)
                } else {
                    heading
                }
            }
            state.copy(headings = updated)
        }
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
            _uiState.update { state ->
                if (state.selectedFile?.fileId != file.fileId) {
                    state
                } else {
                    state.copy(headings = loaded.getOrThrow())
                }
            }
        } else {
            val reason = loaded.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(status = UiStatus("Failed loading headings: $reason", StatusTone.Error))
            }
        }
    }

}
