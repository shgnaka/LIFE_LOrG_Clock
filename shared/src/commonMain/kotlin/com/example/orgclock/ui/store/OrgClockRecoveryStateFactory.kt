package com.example.orgclock.ui.store

import com.example.orgclock.ui.state.OrgDivergenceCategory
import com.example.orgclock.ui.state.OrgDivergenceRecommendedAction
import com.example.orgclock.ui.state.OrgDivergenceSeverity
import com.example.orgclock.ui.state.OrgDivergenceSnapshot
import kotlinx.datetime.Instant

class OrgClockRecoveryStateFactory(
    private val nowProvider: () -> Instant,
) {
    fun missingSelectedHeading(fileId: String): OrgDivergenceSnapshot {
        return OrgDivergenceSnapshot(
            severity = OrgDivergenceSeverity.RecoveryRequired,
            category = OrgDivergenceCategory.ContentMismatch,
            reason = "Selected heading no longer exists after reload",
            affectedFileIds = setOf(fileId),
            detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
            recommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
        )
    }

    fun failedListingFiles(reason: String): OrgDivergenceSnapshot {
        return OrgDivergenceSnapshot(
            severity = OrgDivergenceSeverity.RecoveryRequired,
            category = OrgDivergenceCategory.ContentMismatch,
            reason = reason.ifBlank { "Failed to list files" },
            detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
            recommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
        )
    }

    fun selectedFileMissing(fileId: String): OrgDivergenceSnapshot {
        return OrgDivergenceSnapshot(
            severity = OrgDivergenceSeverity.Warning,
            category = OrgDivergenceCategory.ExternalChange,
            reason = "Selected file is no longer available on disk",
            affectedFileIds = setOf(fileId),
            detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
            recommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
        )
    }

    fun externalChange(changedFileIds: Set<String>, affectsSelectedFile: Boolean): OrgDivergenceSnapshot {
        return OrgDivergenceSnapshot(
            severity = OrgDivergenceSeverity.Warning,
            category = OrgDivergenceCategory.ExternalChange,
            reason = if (affectsSelectedFile) {
                "Selected file changed on disk"
            } else {
                "Org files changed on disk"
            },
            affectedFileIds = changedFileIds,
            detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
            recommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
        )
    }

    fun saveRoundTripMismatch(fileId: String, reason: String?): OrgDivergenceSnapshot {
        return OrgDivergenceSnapshot(
            severity = OrgDivergenceSeverity.RecoveryRequired,
            category = OrgDivergenceCategory.SaveRoundTripMismatch,
            reason = reason?.takeIf { it.isNotBlank() } ?: "Saved file contents did not round-trip cleanly.",
            affectedFileIds = setOf(fileId),
            detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
            recommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
        )
    }
}
