package com.example.orgclock.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.orgclock.R
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.UiStatus

@Composable
internal fun statusText(status: UiStatus): String {
    val resId = when (status.text.key) {
        StatusMessageKey.SelectOrgDirectory -> R.string.status_select_org_directory
        StatusMessageKey.LoadedFile -> R.string.status_loaded_file
        StatusMessageKey.FailedLoadingHeadings -> R.string.status_failed_loading_headings
        StatusMessageKey.FailedListingFiles -> R.string.status_failed_listing_files
        StatusMessageKey.TodayFileNotFound -> R.string.status_today_file_not_found
        StatusMessageKey.FailedOpenRoot -> R.string.status_failed_open_root
        StatusMessageKey.RootSet -> R.string.status_root_set
        StatusMessageKey.FailedLoadingHistory -> R.string.status_failed_loading_history
        StatusMessageKey.ClockStarted -> R.string.status_clock_started
        StatusMessageKey.StartFailed -> R.string.status_start_failed
        StatusMessageKey.ClockStopped -> R.string.status_clock_stopped
        StatusMessageKey.StopFailed -> R.string.status_stop_failed
        StatusMessageKey.ClockCancelled -> R.string.status_clock_cancelled
        StatusMessageKey.CancelFailed -> R.string.status_cancel_failed
        StatusMessageKey.EndTimeMustBeAfterStart -> R.string.status_end_time_must_be_after_start
        StatusMessageKey.ClockHistoryUpdated -> R.string.status_clock_history_updated
        StatusMessageKey.ClockHistoryDeleted -> R.string.status_clock_history_deleted
        StatusMessageKey.UpdateFailed -> R.string.status_update_failed
        StatusMessageKey.DeleteFailed -> R.string.status_delete_failed
        StatusMessageKey.HeadingTitleEmpty -> R.string.status_heading_title_empty
        StatusMessageKey.ParentL1Missing -> R.string.status_parent_l1_missing
        StatusMessageKey.HeadingCreated -> R.string.status_heading_created
        StatusMessageKey.CreateHeadingFailed -> R.string.status_create_heading_failed
        StatusMessageKey.NotificationEnabled -> R.string.status_notification_enabled
        StatusMessageKey.NotificationPermissionRequired -> R.string.status_notification_permission_required
        StatusMessageKey.NotificationDisplayModeUpdated -> R.string.status_notification_display_mode_updated
        StatusMessageKey.NotificationPermissionGranted -> R.string.status_notification_permission_granted
        StatusMessageKey.NotificationPermissionDenied -> R.string.status_notification_permission_denied
        StatusMessageKey.InvalidAutoGenerationTime -> R.string.status_invalid_auto_generation_time
        StatusMessageKey.AutoGenerationScheduleSaved -> R.string.status_auto_generation_schedule_saved
        StatusMessageKey.SyncPeerAddFailed -> R.string.status_sync_peer_add_failed
        StatusMessageKey.SyncPeerAdded -> R.string.status_sync_peer_added
        StatusMessageKey.SyncPeerRemoved -> R.string.status_sync_peer_removed
        StatusMessageKey.SyncPeerProbeOk -> R.string.status_sync_peer_probe_ok
        StatusMessageKey.SyncPeerProbeFailed -> R.string.status_sync_peer_probe_failed
    }
    return stringResource(resId, *status.text.args.toTypedArray())
}
