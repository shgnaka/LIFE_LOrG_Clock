package com.example.orgclock.presentation

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

data class RootReference(
    val rawValue: String,
)

enum class StatusMessageKey {
    SelectOrgDirectory,
    LoadedFile,
    FailedLoadingHeadings,
    FailedListingFiles,
    TodayFileNotFound,
    FailedOpenRoot,
    RootSet,
    FailedLoadingHistory,
    ClockStarted,
    StartFailed,
    ClockStopped,
    StopFailed,
    ClockCancelled,
    CancelFailed,
    EndTimeMustBeAfterStart,
    ClockHistoryUpdated,
    ClockHistoryDeleted,
    UpdateFailed,
    DeleteFailed,
    HeadingTitleEmpty,
    ParentL1Missing,
    HeadingCreated,
    CreateHeadingFailed,
    NotificationEnabled,
    NotificationPermissionRequired,
    NotificationDisplayModeUpdated,
    NotificationPermissionGranted,
    NotificationPermissionDenied,
    InvalidAutoGenerationTime,
    AutoGenerationScheduleSaved,
    SyncPeerAddFailed,
    SyncPeerAdded,
    SyncPeerRemoved,
    SyncPeerProbeOk,
    SyncPeerProbeFailed,
}

data class StatusText(
    val key: StatusMessageKey,
    val args: List<String> = emptyList(),
)

data class UiStatus(
    val text: StatusText,
    val tone: StatusTone,
)

data class OrgClockPresentationState(
    val screen: Screen = Screen.RootSetup,
    val rootReference: RootReference? = null,
    val status: UiStatus = UiStatus(
        text = StatusText(StatusMessageKey.SelectOrgDirectory),
        tone = StatusTone.Info,
    ),
)
