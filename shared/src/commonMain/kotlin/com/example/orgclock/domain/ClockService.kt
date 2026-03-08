package com.example.orgclock.domain

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClock
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.model.OrgDocument
import com.example.orgclock.parser.OrgParser
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class ClockMutationResult(
    val headingLineIndex: Int,
    val startedAt: Instant? = null,
)

enum class ClockOperationCode {
    InvalidHeadingLevel,
    AlreadyRunning,
    ValidationFailed,
    IoFailed,
    Conflict,
}

class ClockOperationException(
    val code: ClockOperationCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ClockService(
    private val repository: ClockRepository,
    private val parser: OrgParser = OrgParser(),
) {

    data class ClockSession(
        val date: LocalDate,
        val headingPath: HeadingPath,
        val startedAt: Instant,
    )

    sealed interface ClockStopResult {
        data class Success(val updatedDates: Set<LocalDate>) : ClockStopResult
        data class Failed(val reason: String) : ClockStopResult
    }

    suspend fun startClock(
        dateTime: Instant,
        headingPath: HeadingPath,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<ClockSession> {
        val date = dateTime.toLocalDateTime(timeZone).date
        val firstDoc = repository.loadDaily(date).getOrElse { return Result.failure(it) }
        val firstLines = runCatching { parser.appendOpenClock(firstDoc.lines, headingPath, dateTime, timeZone) }
            .getOrElse { return Result.failure(it) }

        val firstSave = repository.saveDaily(date, firstLines, firstDoc.hash)
        if (firstSave is SaveResult.Success) {
            return Result.success(ClockSession(date, headingPath, dateTime))
        }
        if (firstSave !is SaveResult.Conflict) {
            return mapSaveFailure(firstSave)
        }

        val secondDoc = repository.loadDaily(date).getOrElse { return Result.failure(it) }
        val secondLines = runCatching { parser.appendOpenClock(secondDoc.lines, headingPath, dateTime, timeZone) }
            .getOrElse { return Result.failure(it) }
        return when (val secondSave = repository.saveDaily(date, secondLines, secondDoc.hash)) {
            SaveResult.Success -> Result.success(ClockSession(date, headingPath, dateTime))
            else -> mapSaveFailure(secondSave)
        }
    }

    suspend fun listHeadings(
        fileId: String,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<List<HeadingViewItem>> {
        return repository.loadFile(fileId).mapCatching { doc ->
            parser.parseHeadingsWithOpenClock(doc.lines, timeZone).map { parsed ->
                HeadingViewItem(
                    node = parsed.node,
                    canStart = parsed.node.level == 2,
                    openClock = parsed.openClock?.let { OpenClockState(it) },
                )
            }
        }
    }

    suspend fun startClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        now: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<ClockMutationResult> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val headingLineIndex = resolveLevel2HeadingLineIndex(doc.lines, headingPath)
            ?: return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        if (parser.findOpenClock(doc.lines, headingPath, timeZone) != null) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.AlreadyRunning,
                    message = "Clock already running for this heading",
                ),
            )
        }

        val firstLines = runCatching { parser.appendOpenClock(doc.lines, headingPath, now, timeZone) }
            .getOrElse { return Result.failure(it) }
        val firstSave = repository.saveFile(fileId, firstLines, doc.hash, FileWriteIntent.ClockMutation)
        if (firstSave is SaveResult.Success) {
            return Result.success(ClockMutationResult(headingLineIndex = headingLineIndex, startedAt = now))
        }
        if (firstSave !is SaveResult.Conflict) {
            return Result.failure(firstSave.toClockOperationException())
        }

        val latestDoc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val latestHeadingLineIndex = resolveLevel2HeadingLineIndex(latestDoc.lines, headingPath)
            ?: return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        if (parser.findOpenClock(latestDoc.lines, headingPath, timeZone) != null) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.AlreadyRunning,
                    message = "Clock already running for this heading",
                ),
            )
        }
        val secondLines = runCatching { parser.appendOpenClock(latestDoc.lines, headingPath, now, timeZone) }
            .getOrElse { return Result.failure(it) }
        val secondSave = repository.saveFile(fileId, secondLines, latestDoc.hash, FileWriteIntent.ClockMutation)
        return if (secondSave is SaveResult.Success) {
            Result.success(ClockMutationResult(headingLineIndex = latestHeadingLineIndex, startedAt = now))
        } else {
            Result.failure(secondSave.toClockOperationException())
        }
    }

    suspend fun startClockInFile(
        fileId: String,
        headingLineIndex: Int,
        now: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<ClockMutationResult> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        }
        if (parser.findOpenClockAtLine(doc.lines, headingLineIndex, timeZone) != null) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.AlreadyRunning,
                    message = "Clock already running for this heading",
                ),
            )
        }

        val firstLines = runCatching { parser.appendOpenClockAtLine(doc.lines, headingLineIndex, now, timeZone) }
            .getOrElse { return Result.failure(it) }
        val firstSave = repository.saveFile(fileId, firstLines, doc.hash, FileWriteIntent.ClockMutation)
        if (firstSave is SaveResult.Success) {
            return Result.success(ClockMutationResult(headingLineIndex = headingLineIndex, startedAt = now))
        }
        if (firstSave !is SaveResult.Conflict) {
            return Result.failure(firstSave.toClockOperationException())
        }

        val latestDoc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.findOpenClockAtLine(latestDoc.lines, headingLineIndex, timeZone) != null) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.AlreadyRunning,
                    message = "Clock already running for this heading",
                ),
            )
        }
        val secondLines = runCatching { parser.appendOpenClockAtLine(latestDoc.lines, headingLineIndex, now, timeZone) }
            .getOrElse { return Result.failure(it) }
        val secondSave = repository.saveFile(fileId, secondLines, latestDoc.hash, FileWriteIntent.ClockMutation)
        return if (secondSave is SaveResult.Success) {
            Result.success(ClockMutationResult(headingLineIndex = headingLineIndex, startedAt = now))
        } else {
            Result.failure(secondSave.toClockOperationException())
        }
    }

    suspend fun stopClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        now: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<ClockMutationResult> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val headingLineIndex = resolveLevel2HeadingLineIndex(doc.lines, headingPath)
            ?: return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        val closeResult = runCatching { parser.closeLatestOpenClock(doc.lines, headingPath, now, timeZone) }
            .getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, closeResult.lines, FileWriteIntent.ClockMutation) {
            parser.closeLatestOpenClock(it, headingPath, now, timeZone).lines
        }
        return if (save is SaveResult.Success) {
            Result.success(ClockMutationResult(headingLineIndex = headingLineIndex))
        } else {
            Result.failure(save.toClockOperationException())
        }
    }

    suspend fun stopClockInFile(
        fileId: String,
        headingLineIndex: Int,
        now: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<ClockMutationResult> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        }
        val closeResult = runCatching { parser.closeLatestOpenClockAtLine(doc.lines, headingLineIndex, now, timeZone) }
            .getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, closeResult.lines, FileWriteIntent.ClockMutation) {
            parser.closeLatestOpenClockAtLine(it, headingLineIndex, now, timeZone).lines
        }
        return if (save is SaveResult.Success) {
            Result.success(ClockMutationResult(headingLineIndex = headingLineIndex))
        } else {
            Result.failure(save.toClockOperationException())
        }
    }

    suspend fun cancelClockInFile(fileId: String, headingPath: HeadingPath): Result<ClockMutationResult> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val headingLineIndex = resolveLevel2HeadingLineIndex(doc.lines, headingPath)
            ?: return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        val cancelled = runCatching { parser.cancelLatestOpenClock(doc.lines, headingPath) }
            .getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, cancelled, FileWriteIntent.ClockMutation) {
            parser.cancelLatestOpenClock(it, headingPath)
        }
        return if (save is SaveResult.Success) {
            Result.success(ClockMutationResult(headingLineIndex = headingLineIndex))
        } else {
            Result.failure(save.toClockOperationException())
        }
    }

    suspend fun cancelClockInFile(fileId: String, headingLineIndex: Int): Result<ClockMutationResult> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(
                ClockOperationException(
                    code = ClockOperationCode.InvalidHeadingLevel,
                    message = "Clock operation is only allowed on level-2 headings",
                ),
            )
        }
        val cancelled = runCatching { parser.cancelLatestOpenClockAtLine(doc.lines, headingLineIndex) }
            .getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, cancelled, FileWriteIntent.ClockMutation) {
            parser.cancelLatestOpenClockAtLine(it, headingLineIndex)
        }
        return if (save is SaveResult.Success) {
            Result.success(ClockMutationResult(headingLineIndex = headingLineIndex))
        } else {
            Result.failure(save.toClockOperationException())
        }
    }

    suspend fun listClosedClocksInFile(
        fileId: String,
        headingPath: HeadingPath,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<List<ClosedClockEntry>> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (resolveLevel2HeadingLineIndex(doc.lines, headingPath) == null) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        return runCatching { parser.listClosedClocks(doc.lines, headingPath, timeZone) }
    }

    suspend fun listClosedClocksInFile(
        fileId: String,
        headingLineIndex: Int,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<List<ClosedClockEntry>> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        return runCatching { parser.listClosedClocksAtLine(doc.lines, headingLineIndex, timeZone) }
    }

    suspend fun editClosedClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        clockLineIndex: Int,
        newStart: Instant,
        newEnd: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<Unit> {
        if (newEnd < newStart) {
            return Result.failure(IllegalArgumentException("End time must be after start time"))
        }
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (resolveLevel2HeadingLineIndex(doc.lines, headingPath) == null) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val firstLines = runCatching {
            parser.replaceClosedClock(doc.lines, headingPath, clockLineIndex, newStart, newEnd, timeZone)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.replaceClosedClock(it, headingPath, clockLineIndex, newStart, newEnd, timeZone)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun editClosedClockInFile(
        fileId: String,
        headingLineIndex: Int,
        clockLineIndex: Int,
        newStart: Instant,
        newEnd: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<Unit> {
        if (newEnd < newStart) {
            return Result.failure(IllegalArgumentException("End time must be after start time"))
        }
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val firstLines = runCatching {
            parser.replaceClosedClockAtLine(doc.lines, headingLineIndex, clockLineIndex, newStart, newEnd, timeZone)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.replaceClosedClockAtLine(it, headingLineIndex, clockLineIndex, newStart, newEnd, timeZone)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun deleteClosedClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        clockLineIndex: Int,
    ): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (resolveLevel2HeadingLineIndex(doc.lines, headingPath) == null) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val firstLines = runCatching {
            parser.deleteClosedClock(doc.lines, headingPath, clockLineIndex)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.deleteClosedClock(it, headingPath, clockLineIndex)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun deleteClosedClockInFile(
        fileId: String,
        headingLineIndex: Int,
        clockLineIndex: Int,
    ): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (parser.headingLevelAtLine(doc.lines, headingLineIndex) != 2) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        val firstLines = runCatching {
            parser.deleteClosedClockAtLine(doc.lines, headingLineIndex, clockLineIndex)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.deleteClosedClockAtLine(it, headingLineIndex, clockLineIndex)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun createL1HeadingInFile(fileId: String, title: String, attachTplTag: Boolean = false): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val firstLines = runCatching {
            parser.appendL1Heading(doc.lines, title, attachTplTag)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.appendL1Heading(it, title, attachTplTag)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun createL2HeadingInFile(
        fileId: String,
        parentPath: HeadingPath,
        title: String,
        attachTplTag: Boolean = false,
    ): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val firstLines = runCatching {
            parser.appendL2HeadingUnderL1(doc.lines, parentPath, title, attachTplTag)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.appendL2HeadingUnderL1(it, parentPath, title, attachTplTag)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun createL2HeadingInFile(
        fileId: String,
        parentL1LineIndex: Int,
        title: String,
        attachTplTag: Boolean = false,
    ): Result<Unit> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        val firstLines = runCatching {
            parser.appendL2HeadingUnderL1(doc.lines, parentL1LineIndex, title, attachTplTag)
        }.getOrElse { return Result.failure(it) }
        val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
            parser.appendL2HeadingUnderL1(it, parentL1LineIndex, title, attachTplTag)
        }
        return if (save is SaveResult.Success) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(save.asMessage()))
        }
    }

    suspend fun stopClock(
        dateTime: Instant,
        headingPath: HeadingPath,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): ClockStopResult {
        val today = dateTime.toLocalDateTime(timeZone).date
        val todayDoc = repository.loadDaily(today).getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to load today's file")
        }

        val openToday = parser.findOpenClock(todayDoc.lines, headingPath, timeZone)
        if (openToday != null) {
            return stopInSingleDocument(todayDoc, headingPath, dateTime, timeZone)
        }

        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val yesterdayDoc = repository.loadDaily(yesterday).getOrElse {
            return ClockStopResult.Failed("No open clock found for $headingPath")
        }
        val openYesterday = parser.findOpenClock(yesterdayDoc.lines, headingPath, timeZone)
            ?: return ClockStopResult.Failed("No open clock found for $headingPath")

        return stopAcrossMidnight(yesterdayDoc, todayDoc, headingPath, openYesterday, dateTime, timeZone)
    }

    suspend fun recoverOpenClocks(
        now: Instant = Clock.System.now(),
        candidates: List<HeadingPath>,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<List<OpenClock>> {
        val today = now.toLocalDateTime(timeZone).date
        val yesterday = today.minus(1, DateTimeUnit.DAY)
        val docs = listOf(today, yesterday).mapNotNull { date -> repository.loadDaily(date).getOrNull() }

        val openClocks = buildList {
            for (doc in docs) {
                for (path in candidates) {
                    val open = parser.findOpenClock(doc.lines, path, timeZone) ?: continue
                    add(OpenClock(doc.date, path, open))
                }
            }
        }

        return Result.success(openClocks)
    }

    private suspend fun stopInSingleDocument(
        doc: OrgDocument,
        headingPath: HeadingPath,
        end: Instant,
        timeZone: TimeZone,
    ): ClockStopResult {
        val closeResult = runCatching { parser.closeLatestOpenClock(doc.lines, headingPath, end, timeZone) }
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
        val retriedLines = runCatching {
            parser.closeLatestOpenClock(latestDoc.lines, headingPath, end, timeZone).lines
        }.getOrElse { return ClockStopResult.Failed(it.message ?: "Failed to reapply stop after conflict") }
        val secondSave = repository.saveDaily(doc.date, retriedLines, latestDoc.hash)
        return if (secondSave is SaveResult.Success) {
            ClockStopResult.Success(setOf(doc.date))
        } else {
            ClockStopResult.Failed(secondSave.asMessage())
        }
    }

    private suspend fun stopAcrossMidnight(
        previousDoc: OrgDocument,
        todayDoc: OrgDocument,
        headingPath: HeadingPath,
        start: Instant,
        end: Instant,
        timeZone: TimeZone,
    ): ClockStopResult {
        val startDate = start.toLocalDateTime(timeZone).date
        val endDate = end.toLocalDateTime(timeZone).date
        if (startDate == endDate) {
            return ClockStopResult.Failed("Unexpected same-day state")
        }

        val endOfPrevious = LocalDateTime(startDate, LocalTime(23, 59, 59)).toInstant(timeZone)
        val startOfToday = endDate.atStartOfDayIn(timeZone)

        val closedPrevious = runCatching {
            parser.closeLatestOpenClock(previousDoc.lines, headingPath, endOfPrevious, timeZone).lines
        }.getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to close previous-day clock")
        }

        val previousSave = saveWithConflictRetry(previousDoc.date, previousDoc.hash, closedPrevious) {
            parser.closeLatestOpenClock(it, headingPath, endOfPrevious, timeZone).lines
        }
        if (previousSave !is SaveResult.Success) {
            return ClockStopResult.Failed("Failed saving previous-day file: ${previousSave.asMessage()}")
        }

        val closedToday = runCatching {
            parser.appendClosedClock(todayDoc.lines, headingPath, startOfToday, end, timeZone)
        }.getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to append today clock")
        }

        val todaySave = saveWithConflictRetry(todayDoc.date, todayDoc.hash, closedToday) {
            parser.appendClosedClock(it, headingPath, startOfToday, end, timeZone)
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
        writeIntent: FileWriteIntent,
        reapply: (List<String>) -> List<String>,
    ): SaveResult {
        val firstSave = repository.saveFile(fileId, firstLines, expectedHash, writeIntent)
        if (firstSave !is SaveResult.Conflict) {
            return firstSave
        }
        val latestDoc = repository.loadFile(fileId).getOrElse {
            return SaveResult.IoError(it.message ?: "Failed to reload file after conflict")
        }
        val secondLines = runCatching { reapply(latestDoc.lines) }
            .getOrElse { return SaveResult.ValidationError(it.message ?: "Failed to reapply patch") }
        return repository.saveFile(fileId, secondLines, latestDoc.hash, writeIntent)
    }

    private fun mapSaveFailure(saveResult: SaveResult): Result<ClockSession> {
        if (saveResult is SaveResult.Success) {
            return Result.failure(IllegalStateException("Unexpected SaveResult.Success in failure mapper"))
        }
        return Result.failure(saveResult.toClockOperationException())
    }

    private fun SaveResult.asMessage(): String {
        return when (this) {
            SaveResult.Success -> "Success"
            is SaveResult.Conflict -> reason
            is SaveResult.ValidationError -> reason
            is SaveResult.IoError -> reason
        }
    }

    private fun SaveResult.toClockOperationException(): ClockOperationException {
        return when (this) {
            is SaveResult.ValidationError -> ClockOperationException(ClockOperationCode.ValidationFailed, reason)
            is SaveResult.IoError -> ClockOperationException(ClockOperationCode.IoFailed, reason)
            is SaveResult.Conflict -> ClockOperationException(ClockOperationCode.Conflict, reason)
            SaveResult.Success -> ClockOperationException(ClockOperationCode.IoFailed, "Unexpected save success mapping")
        }
    }

    private fun resolveLevel2HeadingLineIndex(lines: List<String>, headingPath: HeadingPath): Int? {
        val heading = parser.findHeading(lines, headingPath) ?: return null
        return if (heading.level == 2) heading.start else null
    }
}
