package com.example.orgclock.ui.state

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem

enum class Screen {
    RootSetup,
    FilePicker,
    HeadingList,
    Settings,
}

enum class StatusTone {
    Info,
    Success,
    Warning,
    Error,
}

enum class CreateHeadingMode {
    L1,
    L2,
}

@Immutable
data class UiStatus(
    val message: String,
    val tone: StatusTone,
)

@Immutable
data class ClockEditDraft(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
)

@Immutable
data class CreateHeadingDialogState(
    val mode: CreateHeadingMode,
    val parentL1LineIndex: Int? = null,
    val parentL1Title: String? = null,
    val titleInput: String = "",
    val submitting: Boolean = false,
)

@Immutable
data class OrgClockUiState(
    val screen: Screen = Screen.RootSetup,
    val rootUri: Uri? = null,
    val status: UiStatus = UiStatus("Select org directory", StatusTone.Info),
    val files: List<OrgFileEntry> = emptyList(),
    val selectedFile: OrgFileEntry? = null,
    val headings: List<HeadingViewItem> = emptyList(),
    val pendingClockOps: Set<Int> = emptySet(),
    val collapsedL1: Set<String> = emptySet(),
    val historyTarget: HeadingViewItem? = null,
    val historyEntries: List<ClosedClockEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val editingEntry: ClosedClockEntry? = null,
    val editingDraft: ClockEditDraft? = null,
    val createHeadingDialog: CreateHeadingDialogState? = null,
    val showPerfOverlay: Boolean = false,
)
