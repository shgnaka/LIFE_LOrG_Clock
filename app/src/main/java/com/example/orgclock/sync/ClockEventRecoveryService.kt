package com.example.orgclock.sync

import com.example.orgclock.ui.state.OrgDivergenceCategory
import com.example.orgclock.ui.state.OrgDivergenceRecommendedAction
import com.example.orgclock.ui.state.OrgDivergenceSeverity
import com.example.orgclock.ui.state.OrgDivergenceSnapshot
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class ClockEventRecoveryResult(
    val projection: ClockEventProjection? = null,
    val divergenceSnapshot: OrgDivergenceSnapshot? = null,
    val failureReason: String? = null,
)

class ClockEventRecoveryService(
    private val projectEvents: (List<StoredClockEvent>) -> ClockEventProjection = ClockEventProjector()::project,
    private val nowProvider: () -> Instant = { Clock.System.now() },
) {
    suspend fun rebuildFromEventLog(
        readEvents: suspend () -> List<StoredClockEvent>,
    ): ClockEventRecoveryResult {
        val events = runCatching { readEvents() }
            .getOrElse { error ->
                return failureResult(error.message ?: "failed to read event log")
            }
        return rebuildFromEvents(events)
    }

    fun rebuildFromEvents(events: List<StoredClockEvent>): ClockEventRecoveryResult {
        val projection = runCatching { projectEvents(events) }
            .getOrElse { error ->
                return failureResult(error.message ?: "failed to project event log")
            }
        val divergenceSnapshot = if (projection.issues.isNotEmpty()) {
            OrgDivergenceSnapshot(
                severity = OrgDivergenceSeverity.RecoveryRequired,
                category = OrgDivergenceCategory.ProjectionReplayFailure,
                reason = "Projection replay produced ${projection.issues.size} issue(s)",
                detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
                recommendedAction = OrgDivergenceRecommendedAction.RebuildFromEventLog,
            )
        } else {
            null
        }
        return ClockEventRecoveryResult(
            projection = projection,
            divergenceSnapshot = divergenceSnapshot,
        )
    }

    private fun failureResult(reason: String): ClockEventRecoveryResult {
        return ClockEventRecoveryResult(
            divergenceSnapshot = OrgDivergenceSnapshot(
                severity = OrgDivergenceSeverity.RecoveryRequired,
                category = OrgDivergenceCategory.ProjectionReplayFailure,
                reason = reason,
                detectedAtEpochMs = nowProvider().toEpochMilliseconds(),
                recommendedAction = OrgDivergenceRecommendedAction.RebuildFromEventLog,
            ),
            failureReason = reason,
        )
    }
}
