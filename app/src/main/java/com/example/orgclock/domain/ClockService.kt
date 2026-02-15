package com.example.orgclock.domain

import com.example.orgclock.data.OrgRepository
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClock
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.parser.OrgParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class ClockService(
    private val repository: OrgRepository,
    private val parser: OrgParser = OrgParser(),
) {

    data class ClockSession(
        val date: LocalDate,
        val headingPath: HeadingPath,
        val startedAt: ZonedDateTime,
    )

    sealed interface ClockStopResult {
        data class Success(val updatedDates: Set<LocalDate>) : ClockStopResult
        data class Failed(val reason: String) : ClockStopResult
    }

    suspend fun startClock(dateTime: ZonedDateTime, headingPath: HeadingPath): Result<ClockSession> {
        val date = dateTime.toLocalDate()
        val firstDoc = repository.loadDaily(date).getOrElse { return Result.failure(it) }
        val firstLines = runCatching { parser.appendOpenClock(firstDoc.lines, headingPath, dateTime) }
            .getOrElse { return Result.failure(it) }

        val firstSave = repository.saveDaily(date, firstLines, firstDoc.hash)
        if (firstSave is SaveResult.Success) {
            return Result.success(ClockSession(date, headingPath, dateTime))
        }
        if (firstSave !is SaveResult.Conflict) {
            return mapSaveFailure(firstSave)
        }

        val secondDoc = repository.loadDaily(date).getOrElse { return Result.failure(it) }
        val secondLines = runCatching { parser.appendOpenClock(secondDoc.lines, headingPath, dateTime) }
            .getOrElse { return Result.failure(it) }
        return when (val secondSave = repository.saveDaily(date, secondLines, secondDoc.hash)) {
            SaveResult.Success -> Result.success(ClockSession(date, headingPath, dateTime))
            else -> mapSaveFailure(secondSave)
        }
    }

    suspend fun listHeadings(fileId: String, now: ZonedDateTime = ZonedDateTime.now()): Result<List<HeadingViewItem>> {
        val zone = now.zone
        return repository.loadFile(fileId).mapCatching { doc ->
            parser.parseHeadings(doc.lines).map { node ->
                val open = parser.findOpenClockAtLine(doc.lines, node.lineIndex, zone)
                HeadingViewItem(
                    node = node,
                    canStart = node.level == 2,
                    openClock = open?.let { OpenClockState(it) },
                )
            }
        }
    }

    suspend fun startClockInFile(fileId: String, headingLineIndex: Int, now: ZonedDateTime): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        if (parser.findOpenClockAtLine(doc.lines, headingLineIndex, now.zone) != null) {
            return Result.failure(IllegalStateException("Clock already running for this heading"))
        }

        val firstLines = runCatching { parser.appendOpenClockAtLine(doc.lines, headingLineIndex, now) }
            .getOrElse { return Result.failure(it) }
        val firstSave = repository.saveFile(fileId, firstLines, doc.hash)
        if (firstSave is SaveResult.Success) {
            return Result.success(Unit)
        }
        if (firstSave !is SaveResult.Conflict) {
            return Result.failure(IllegalStateException(firstSave.asMessage()))
        }

        val latestDoc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.findOpenClockAtLine(latestDoc.lines, headingLineIndex, now.zone) != null) {
            return Result.failure(IllegalStateException("Clock already running for this heading"))
        }
        val secondLines = runCatching { parser.appendOpenClockAtLine(latestDoc.lines, headingLineIndex, now) }
            .getOrElse { return Result.failure(it) }
        val secondSave = repository.saveFile(fileId, secondLines, latestDoc.hash)
        return if (secondSave is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(secondSave.asMessage()))
        }
    }

    suspend fun stopClockInFile(fileId: String, headingLineIndex: Int, now: ZonedDateTime): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val closeResult = runCatching { parser.closeLatestOpenClockAtLine(doc.lines, headingLineIndex, now) }
            .getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, closeResult.lines) {
            parser.closeLatestOpenClockAtLine(it, headingLineIndex, now).lines
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun cancelClockInFile(fileId: String, headingLineIndex: Int): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val cancelled = runCatching { parser.cancelLatestOpenClockAtLine(doc.lines, headingLineIndex) }
            .getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, cancelled) {
            parser.cancelLatestOpenClockAtLine(it, headingLineIndex)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun listClosedClocksInFile(
        fileId: String,
        headingLineIndex: Int,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): Result<List<ClosedClockEntry>> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        return runCatching { parser.listClosedClocksAtLine(doc.lines, headingLineIndex, now.zone) }
    }

    suspend fun editClosedClockInFile(
        fileId: String,
        headingLineIndex: Int,
        clockLineIndex: Int,
        newStart: ZonedDateTime,
        newEnd: ZonedDateTime,
    ): Result<Unit> {
        if (newEnd.isBefore(newStart)) {
            return Result.failure(IllegalArgumentException("End time must be after start time"))
        }
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val firstLines = runCatching {
            parser.replaceClosedClockAtLine(doc.lines, headingLineIndex, clockLineIndex, newStart, newEnd)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines) {
            parser.replaceClosedClockAtLine(it, headingLineIndex, clockLineIndex, newStart, newEnd)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun stopClock(dateTime: ZonedDateTime, headingPath: HeadingPath): ClockStopResult {
        val zone = dateTime.zone
        val today = dateTime.toLocalDate()
        val todayDoc = repository.loadDaily(today).getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to load today's file")
        }

        val openToday = parser.findOpenClock(todayDoc.lines, headingPath, zone)
        if (openToday != null) {
            return stopInSingleDocument(todayDoc, headingPath, dateTime)
        }

        val yesterday = today.minusDays(1)
        val yesterdayDoc = repository.loadDaily(yesterday).getOrElse {
            return ClockStopResult.Failed("No open clock found for $headingPath")
        }
        val openYesterday = parser.findOpenClock(yesterdayDoc.lines, headingPath, zone)
            ?: return ClockStopResult.Failed("No open clock found for $headingPath")

        return stopAcrossMidnight(yesterdayDoc, todayDoc, headingPath, openYesterday, dateTime)
    }

    suspend fun recoverOpenClocks(now: ZonedDateTime, candidates: List<HeadingPath>): Result<List<OpenClock>> {
        val zone = now.zone
        val dates = listOf(now.toLocalDate(), now.toLocalDate().minusDays(1))
        val docs = dates.mapNotNull { date -> repository.loadDaily(date).getOrNull() }

        val openClocks = buildList {
            for (doc in docs) {
                for (path in candidates) {
                    val open = parser.findOpenClock(doc.lines, path, zone) ?: continue
                    add(OpenClock(doc.date, path, open))
                }
            }
        }

        return Result.success(openClocks)
    }

    private suspend fun stopInSingleDocument(
        doc: com.example.orgclock.model.OrgDocument,
        headingPath: HeadingPath,
        end: ZonedDateTime,
    ): ClockStopResult {
        val closeResult = runCatching { parser.closeLatestOpenClock(doc.lines, headingPath, end) }
            .getOrElse { return ClockStopResult.Failed(it.message ?: "Failed to close clock") }

        val firstSave = repository.saveDaily(doc.date, closeResult.lines, doc.hash)
        if (firstSave is SaveResult.Success) {
            return ClockStopResult.Success(setOf(doc.date))
        }
        if (firstSave !is SaveResult.Conflict) {
            return ClockStopResult.Failed(firstSave.asMessage())
        }

        val latestDoc = repository.loadDaily(doc.date).getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to reload document after conflict")
        }
        val retriedLines = runCatching { parser.closeLatestOpenClock(latestDoc.lines, headingPath, end).lines }
            .getOrElse { return ClockStopResult.Failed(it.message ?: "Failed to reapply stop after conflict") }
        val secondSave = repository.saveDaily(doc.date, retriedLines, latestDoc.hash)
        return if (secondSave is SaveResult.Success) {
            ClockStopResult.Success(setOf(doc.date))
        } else {
            ClockStopResult.Failed(secondSave.asMessage())
        }
    }

    private suspend fun stopAcrossMidnight(
        previousDoc: com.example.orgclock.model.OrgDocument,
        todayDoc: com.example.orgclock.model.OrgDocument,
        headingPath: HeadingPath,
        start: ZonedDateTime,
        end: ZonedDateTime,
    ): ClockStopResult {
        if (start.toLocalDate() == end.toLocalDate()) {
            return ClockStopResult.Failed("Unexpected same-day state")
        }

        val endOfPrevious = start.toLocalDate().atTime(LocalTime.of(23, 59, 59)).atZone(end.zone)
        val startOfToday = end.toLocalDate().atStartOfDay(end.zone)

        val closedPrevious = runCatching {
            parser.closeLatestOpenClock(previousDoc.lines, headingPath, endOfPrevious).lines
        }.getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to close previous-day clock")
        }

        val previousSave = saveWithConflictRetry(previousDoc.date, previousDoc.hash, closedPrevious) {
            parser.closeLatestOpenClock(it, headingPath, endOfPrevious).lines
        }
        if (previousSave !is SaveResult.Success) {
            return ClockStopResult.Failed("Failed saving previous-day file: ${previousSave.asMessage()}")
        }

        val closedToday = runCatching {
            parser.appendClosedClock(todayDoc.lines, headingPath, startOfToday, end)
        }.getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to append today clock")
        }

        val todaySave = saveWithConflictRetry(todayDoc.date, todayDoc.hash, closedToday) {
            parser.appendClosedClock(it, headingPath, startOfToday, end)
        }
        if (todaySave !is SaveResult.Success) {
            return ClockStopResult.Failed("Failed saving current-day file: ${todaySave.asMessage()}")
        }

        return ClockStopResult.Success(setOf(previousDoc.date, todayDoc.date))
    }

    private suspend fun saveWithConflictRetry(
        date: LocalDate,
        expectedHash: String,
        firstLines: List<String>,
        reapply: (List<String>) -> List<String>,
    ): SaveResult {
        val firstSave = repository.saveDaily(date, firstLines, expectedHash)
        if (firstSave !is SaveResult.Conflict) {
            return firstSave
        }

        val latestDoc = repository.loadDaily(date).getOrElse {
            return SaveResult.IoError(it.message ?: "Failed to reload document after conflict")
        }
        val secondLines = runCatching { reapply(latestDoc.lines) }
            .getOrElse { return SaveResult.ValidationError(it.message ?: "Failed to reapply patch") }
        return repository.saveDaily(date, secondLines, latestDoc.hash)
    }

    private suspend fun saveFileWithRetry(
        fileId: String,
        expectedHash: String,
        firstLines: List<String>,
        reapply: (List<String>) -> List<String>,
    ): SaveResult {
        val firstSave = repository.saveFile(fileId, firstLines, expectedHash)
        if (firstSave !is SaveResult.Conflict) {
            return firstSave
        }
        val latestDoc = repository.loadFile(fileId).getOrElse {
            return SaveResult.IoError(it.message ?: "Failed to reload file after conflict")
        }
        val secondLines = runCatching { reapply(latestDoc.lines) }
            .getOrElse { return SaveResult.ValidationError(it.message ?: "Failed to reapply patch") }
        return repository.saveFile(fileId, secondLines, latestDoc.hash)
    }

    private fun mapSaveFailure(saveResult: SaveResult): Result<ClockSession> {
        return when (saveResult) {
            is SaveResult.ValidationError -> Result.failure(IllegalArgumentException(saveResult.reason))
            is SaveResult.IoError -> Result.failure(IllegalStateException(saveResult.reason))
            is SaveResult.Conflict -> Result.failure(IllegalStateException(saveResult.reason))
            SaveResult.Success -> error("Success is not a failure")
        }
    }

    private fun SaveResult.asMessage(): String {
        return when (this) {
            SaveResult.Success -> "Success"
            is SaveResult.Conflict -> reason
            is SaveResult.ValidationError -> reason
            is SaveResult.IoError -> reason
        }
    }
}
