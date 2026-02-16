package com.example.orgclock.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.RootAccess
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.ui.state.ClockEditDraft
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

class OrgClockViewModel(
    private val loadSavedUri: () -> Uri?,
    private val saveUri: (Uri) -> Unit,
    private val openRoot: suspend (Uri) -> Result<RootAccess>,
    private val listFiles: suspend () -> Result<List<OrgFileEntry>>,
    private val listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    private val startClock: suspend (String, Int) -> Result<Unit>,
    private val stopClock: suspend (String, Int) -> Result<Unit>,
    private val cancelClock: suspend (String, Int) -> Result<Unit>,
    private val listClosedClocks: suspend (String, Int) -> Result<List<ClosedClockEntry>>,
    private val editClosedClock: suspend (String, Int, Int, ZonedDateTime, ZonedDateTime) -> Result<Unit>,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    showPerfOverlay: Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OrgClockUiState(showPerfOverlay = showPerfOverlay),
    )
    val uiState: StateFlow<OrgClockUiState> = _uiState.asStateFlow()

    private var initialized = false

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
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            _uiState.update {
                it.copy(
                    selectedFile = file,
                    headings = loaded.getOrThrow(),
                    collapsedL1 = emptySet(),
                    historyTarget = null,
                    historyEntries = emptyList(),
                    historyLoading = false,
                    editingEntry = null,
                    editingDraft = null,
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
        val loaded = listHeadings(file.fileId)
        if (loaded.isSuccess) {
            _uiState.update { it.copy(headings = loaded.getOrThrow()) }
        } else {
            val reason = loaded.exceptionOrNull()?.message ?: "unknown"
            _uiState.update {
                it.copy(status = UiStatus("Failed loading headings: $reason", StatusTone.Error))
            }
        }
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
        val result = startClock(file.fileId, item.node.lineIndex)
        val status = if (result.isSuccess) {
            UiStatus("Clock started", StatusTone.Success)
        } else {
            val msg = result.exceptionOrNull()?.message ?: "unknown"
            val tone = if (msg.contains("already", ignoreCase = true)) StatusTone.Warning else StatusTone.Error
            UiStatus("Start failed: $msg", tone)
        }
        _uiState.update { it.copy(status = status) }
        loadHeadingsFor(file, updateStatus = false)
    }

    private suspend fun stopClock(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        val result = stopClock(file.fileId, item.node.lineIndex)
        val status = if (result.isSuccess) {
            UiStatus("Clock stopped", StatusTone.Success)
        } else {
            UiStatus("Stop failed: ${result.exceptionOrNull()?.message ?: "unknown"}", StatusTone.Error)
        }
        _uiState.update { it.copy(status = status) }
        loadHeadingsFor(file, updateStatus = false)
    }

    private suspend fun cancelClock(item: HeadingViewItem) {
        val file = uiState.value.selectedFile ?: return
        val result = cancelClock(file.fileId, item.node.lineIndex)
        val status = if (result.isSuccess) {
            UiStatus("Clock cancelled", StatusTone.Warning)
        } else {
            UiStatus("Cancel failed: ${result.exceptionOrNull()?.message ?: "unknown"}", StatusTone.Error)
        }
        _uiState.update { it.copy(status = status) }
        loadHeadingsFor(file, updateStatus = false)
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

    private fun roundToNearest5(minute: Int): Int {
        val normalized = ((minute + 2) / 5) * 5
        return if (normalized == 60) 55 else normalized
    }
}
