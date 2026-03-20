package com.example.orgclock.ui.state

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.template.ScheduleRuleType
import com.example.orgclock.template.ScheduleWeekday

sealed interface OrgClockUiAction {
    data object Initialize : OrgClockUiAction
    data class PickRoot(val rootReference: RootReference) : OrgClockUiAction
    data class SelectFile(val file: OrgFileEntry) : OrgClockUiAction
    data class ToggleL1(val title: String) : OrgClockUiAction
    data object CollapseAll : OrgClockUiAction
    data object ExpandAll : OrgClockUiAction
    data object RefreshHeadings : OrgClockUiAction
    data object RefreshFiles : OrgClockUiAction
    data object ReloadFromDisk : OrgClockUiAction
    data class SelectHeading(val path: HeadingPath) : OrgClockUiAction
    data class OpenHistory(val item: HeadingViewItem) : OrgClockUiAction
    data object DismissHistory : OrgClockUiAction
    data class StartClock(val path: HeadingPath) : OrgClockUiAction
    data class StopClock(val path: HeadingPath) : OrgClockUiAction
    data class CancelClock(val path: HeadingPath) : OrgClockUiAction
    data class BeginEdit(val entry: ClosedClockEntry) : OrgClockUiAction
    data object CancelEdit : OrgClockUiAction
    data class BeginDelete(val entry: ClosedClockEntry) : OrgClockUiAction
    data object CancelDelete : OrgClockUiAction
    data object ConfirmDelete : OrgClockUiAction
    data class SelectStartHour(val hour: Int) : OrgClockUiAction
    data class SelectStartMinute(val minute: Int) : OrgClockUiAction
    data class SelectEndHour(val hour: Int) : OrgClockUiAction
    data class SelectEndMinute(val minute: Int) : OrgClockUiAction
    data object SaveEdit : OrgClockUiAction
    data object OpenCreateL1Dialog : OrgClockUiAction
    data class OpenCreateL2Dialog(val parent: HeadingViewItem) : OrgClockUiAction
    data class UpdateCreateHeadingTitle(val title: String) : OrgClockUiAction
    data class SetCreateHeadingTplTag(val enabled: Boolean) : OrgClockUiAction
    data object SubmitCreateHeading : OrgClockUiAction
    data object DismissCreateHeadingDialog : OrgClockUiAction
    data object OpenFilePicker : OrgClockUiAction
    data object OpenTemplateFilePicker : OrgClockUiAction
    data object OpenSettings : OrgClockUiAction
    data object BackFromSettings : OrgClockUiAction
    data object RefreshTemplateStatus : OrgClockUiAction
    data object RefreshTemplateCandidates : OrgClockUiAction
    data object ClearExplicitTemplateFile : OrgClockUiAction
    data class SelectTemplateFile(val file: OrgFileEntry) : OrgClockUiAction
    data object CreateDefaultTemplateFile : OrgClockUiAction
    data class ToggleNotificationEnabled(val enabled: Boolean) : OrgClockUiAction
    data class ChangeNotificationDisplayMode(val mode: NotificationDisplayMode) : OrgClockUiAction
    data object RequestNotificationPermissionHandled : OrgClockUiAction
    data class NotificationPermissionResult(val granted: Boolean) : OrgClockUiAction
    data object RefreshNotificationPermissionState : OrgClockUiAction
    data object OpenAppNotificationSettings : OrgClockUiAction
    data object AppNotificationSettingsOpened : OrgClockUiAction
    data class ToggleAutoGenerationEnabled(val enabled: Boolean) : OrgClockUiAction
    data class SetAutoGenerationRule(val ruleType: ScheduleRuleType) : OrgClockUiAction
    data class UpdateAutoGenerationHour(val value: String) : OrgClockUiAction
    data class UpdateAutoGenerationMinute(val value: String) : OrgClockUiAction
    data class ToggleAutoGenerationDay(val dayOfWeek: ScheduleWeekday) : OrgClockUiAction
    data object SaveAutoGenerationSchedule : OrgClockUiAction
    data object RefreshAutoGenerationStatus : OrgClockUiAction
    data object EvaluateAutoGenerationCatchUp : OrgClockUiAction
    data object RefreshSyncDebug : OrgClockUiAction
    data object SyncFlushNow : OrgClockUiAction
    data object SyncEnableStandard : OrgClockUiAction
    data object SyncEnableActive : OrgClockUiAction
    data object SyncStopRuntime : OrgClockUiAction
    data class SyncSetEnabled(val enabled: Boolean) : OrgClockUiAction
    data class SyncSetDefaultPeerId(val peerId: String) : OrgClockUiAction
    data class SyncUpdatePeerInput(val value: String) : OrgClockUiAction
    data class SyncUpdatePeerDeviceId(val value: String) : OrgClockUiAction
    data class SyncUpdatePeerDisplayName(val value: String) : OrgClockUiAction
    data class SyncUpdatePeerPublicKey(val value: String) : OrgClockUiAction
    data class SyncSetPeerViewerMode(val enabled: Boolean) : OrgClockUiAction
    data object SyncAddPeer : OrgClockUiAction
    data class SyncRevokePeer(val peerId: String) : OrgClockUiAction
    data class SyncProbePeer(val peerId: String) : OrgClockUiAction
}
