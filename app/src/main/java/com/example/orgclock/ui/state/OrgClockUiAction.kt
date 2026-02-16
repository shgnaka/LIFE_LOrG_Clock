package com.example.orgclock.ui.state

import android.net.Uri
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingViewItem

sealed interface OrgClockUiAction {
    data object Initialize : OrgClockUiAction
    data class PickRoot(val uri: Uri) : OrgClockUiAction
    data class SelectFile(val file: OrgFileEntry) : OrgClockUiAction
    data class ToggleL1(val title: String) : OrgClockUiAction
    data object CollapseAll : OrgClockUiAction
    data object ExpandAll : OrgClockUiAction
    data class OpenHistory(val item: HeadingViewItem) : OrgClockUiAction
    data object DismissHistory : OrgClockUiAction
    data class StartClock(val item: HeadingViewItem) : OrgClockUiAction
    data class StopClock(val item: HeadingViewItem) : OrgClockUiAction
    data class CancelClock(val item: HeadingViewItem) : OrgClockUiAction
    data class BeginEdit(val entry: ClosedClockEntry) : OrgClockUiAction
    data object CancelEdit : OrgClockUiAction
    data class SelectStartHour(val hour: Int) : OrgClockUiAction
    data class SelectStartMinute(val minute: Int) : OrgClockUiAction
    data class SelectEndHour(val hour: Int) : OrgClockUiAction
    data class SelectEndMinute(val minute: Int) : OrgClockUiAction
    data object SaveEdit : OrgClockUiAction
    data object OpenFilePicker : OrgClockUiAction
    data object OpenSettings : OrgClockUiAction
    data object BackFromSettings : OrgClockUiAction
}
