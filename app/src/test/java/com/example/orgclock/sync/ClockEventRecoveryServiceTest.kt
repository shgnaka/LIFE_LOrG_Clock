package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import com.example.orgclock.ui.state.OrgDivergenceCategory
import com.example.orgclock.ui.state.OrgDivergenceRecommendedAction
import com.example.orgclock.ui.state.OrgDivergenceSeverity
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockEventRecoveryServiceTest {
    @Test
    fun rebuildFromEventLog_projectsEventsAndLeavesCleanSnapshotForValidHistory() = runTest {
        val service = ClockEventRecoveryService(
            nowProvider = { Instant.parse("2026-03-18T12:00:00Z") },
        )

        val result = service.rebuildFromEvents(
            listOf(
                storedEvent(
                    cursor = 1L,
                    eventId = "evt-1",
                    type = ClockEventType.Started,
                    createdAt = "2026-03-18T09:00:00Z",
                ),
                storedEvent(
                    cursor = 2L,
                    eventId = "evt-2",
                    type = ClockEventType.Stopped,
                    createdAt = "2026-03-18T10:30:00Z",
                ),
            ),
        )

        assertNotNull(result.projection)
        assertTrue(result.projection!!.issues.isEmpty())
        assertEquals(1, result.projection!!.historyEntries.size)
        assertNull(result.divergenceSnapshot)
        assertNull(result.failureReason)
    }

    @Test
    fun rebuildFromEventLog_handlesEmptyLogsWithoutDivergence() = runTest {
        val service = ClockEventRecoveryService(
            nowProvider = { Instant.parse("2026-03-18T12:00:00Z") },
        )

        val result = service.rebuildFromEventLog { emptyList() }

        assertNotNull(result.projection)
        assertTrue(result.projection!!.activeClocks.isEmpty())
        assertTrue(result.projection!!.historyEntries.isEmpty())
        assertTrue(result.projection!!.issues.isEmpty())
        assertNull(result.divergenceSnapshot)
        assertNull(result.failureReason)
    }

    @Test
    fun rebuildFromEventLog_reportsProjectionFailureAsRecoveryRequiredDivergence() = runTest {
        val service = ClockEventRecoveryService(
            projectEvents = { error("projection blew up") },
            nowProvider = { Instant.parse("2026-03-18T12:00:00Z") },
        )

        val result = service.rebuildFromEventLog {
            listOf(
                storedEvent(
                    cursor = 1L,
                    eventId = "evt-1",
                    type = ClockEventType.Started,
                    createdAt = "2026-03-18T09:00:00Z",
                ),
            )
        }

        assertNull(result.projection)
        assertNotNull(result.divergenceSnapshot)
        assertEquals(OrgDivergenceCategory.ProjectionReplayFailure, result.divergenceSnapshot?.category)
        assertEquals(OrgDivergenceSeverity.RecoveryRequired, result.divergenceSnapshot?.severity)
        assertEquals(OrgDivergenceRecommendedAction.RebuildFromEventLog, result.divergenceSnapshot?.recommendedAction)
        assertTrue(result.failureReason?.contains("projection blew up") == true)
    }

    private fun storedEvent(
        cursor: Long,
        eventId: String,
        type: ClockEventType,
        createdAt: String,
    ): StoredClockEvent = StoredClockEvent(
        cursor = ClockEventCursor(cursor),
        event = ClockEvent(
            eventId = eventId,
            eventType = type,
            deviceId = "device-local",
            createdAt = Instant.parse(createdAt),
            logicalDay = LocalDate.parse("2026-03-18"),
            fileName = "2026-03-18.org",
            headingPath = HeadingPath.parse("Work/Project A"),
            causalOrder = ClockEventCausalOrder(counter = cursor),
        ),
    )
}
