package com.example.orgclock.ui.state

enum class OrgDivergenceCategory {
    ExternalChange,
    ContentMismatch,
    ProjectionReplayFailure,
    SaveRoundTripMismatch,
}

enum class OrgDivergenceSeverity {
    Clean,
    Warning,
    RecoveryRequired,
}

enum class OrgDivergenceRecommendedAction {
    ReloadFromDisk,
    RebuildFromEventLog,
    SyncNow,
}

data class OrgDivergenceSnapshot(
    val severity: OrgDivergenceSeverity = OrgDivergenceSeverity.Clean,
    val category: OrgDivergenceCategory? = null,
    val reason: String? = null,
    val affectedFileIds: Set<String> = emptySet(),
    val detectedAtEpochMs: Long? = null,
    val recommendedAction: OrgDivergenceRecommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
) {
    val isClear: Boolean
        get() = severity == OrgDivergenceSeverity.Clean
}

