package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.OpenClock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class ClockProjectionIssueCode {
    StartWhileOpen,
    StopWithoutOpen,
    StopBeforeStart,
    CancelWithoutOpen,
}

data class ClockProjectionIssue(
    val cursor: ClockEventCursor,
    val eventId: String,
    val code: ClockProjectionIssueCode,
    val logicalDay: LocalDate,
    val headingPath: HeadingPath,
)

data class ProjectedClockHistoryEntry(
    val logicalDay: LocalDate,
    val fileName: String,
    val headingPath: HeadingPath,
    val start: Instant,
    val end: Instant,
    val durationMinutes: Long,
)

data class ClockEventProjection(
    val activeClocks: List<OpenClock>,
    val historyEntries: List<ProjectedClockHistoryEntry>,
    val issues: List<ClockProjectionIssue>,
)

class ClockEventProjector {
    fun project(events: List<StoredClockEvent>): ClockEventProjection {
        val activeByKey = linkedMapOf<ClockProjectionKey, OpenClock>()
        val historyEntries = mutableListOf<ProjectedClockHistoryEntry>()
        val issues = mutableListOf<ClockProjectionIssue>()

        for (storedEvent in events.sortedBy { it.cursor.value }) {
            val event = storedEvent.event
            val key = ClockProjectionKey(
                logicalDay = event.logicalDay,
                headingPath = event.headingPath,
            )
            val active = activeByKey[key]
            when (event.eventType) {
                ClockEventType.Started -> {
                    if (active != null) {
                        issues += storedEvent.toIssue(ClockProjectionIssueCode.StartWhileOpen)
                    } else {
                        activeByKey[key] = OpenClock(
                            fileDate = event.logicalDay,
                            headingPath = event.headingPath,
                            start = event.createdAt,
                        )
                    }
                }

                ClockEventType.Stopped -> {
                    when {
                        active == null -> issues += storedEvent.toIssue(ClockProjectionIssueCode.StopWithoutOpen)
                        event.createdAt < active.start -> issues += storedEvent.toIssue(ClockProjectionIssueCode.StopBeforeStart)
                        else -> {
                            historyEntries += ProjectedClockHistoryEntry(
                                logicalDay = event.logicalDay,
                                fileName = event.fileName,
                                headingPath = event.headingPath,
                                start = active.start,
                                end = event.createdAt,
                                durationMinutes = (event.createdAt - active.start).inWholeMinutes,
                            )
                            activeByKey.remove(key)
                        }
                    }
                }

                ClockEventType.Cancelled -> {
                    if (active == null) {
                        issues += storedEvent.toIssue(ClockProjectionIssueCode.CancelWithoutOpen)
                    } else {
                        activeByKey.remove(key)
                    }
                }
            }
        }

        return ClockEventProjection(
            activeClocks = activeByKey.values.sortedWith(compareBy<OpenClock> { it.fileDate }.thenBy { it.start }.thenBy { it.headingPath.toString() }),
            historyEntries = historyEntries.sortedWith(
                compareBy<ProjectedClockHistoryEntry> { it.logicalDay }
                    .thenBy { it.start }
                    .thenBy { it.headingPath.toString() },
            ),
            issues = issues,
        )
    }

    private data class ClockProjectionKey(
        val logicalDay: LocalDate,
        val headingPath: HeadingPath,
    )

    private fun StoredClockEvent.toIssue(code: ClockProjectionIssueCode): ClockProjectionIssue = ClockProjectionIssue(
        cursor = cursor,
        eventId = event.eventId,
        code = code,
        logicalDay = event.logicalDay,
        headingPath = event.headingPath,
    )
}
