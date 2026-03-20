package com.example.orgclock.domain

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClock
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.model.OrgDocument
import com.example.orgclock.parser.OrgParser
import com.example.orgclock.sync.ClockEventRecorder
import com.example.orgclock.sync.NoOpClockEventRecorder
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
    val startedAt: Instant? = null,
)

enum class ClockOperationCode {
    InvalidHeadingLevel,
    AlreadyRunning,
    ValidationFailed,
    IoFailed,
    Conflict,
    SaveRoundTripMismatch,
}

class ClockOperationException(
    val code: ClockOperationCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ClockService(
    private val repository: ClockRepository,
    private val parser: OrgParser = OrgParser(),
    private val fileOperationCoordinator: FileOperationCoordinator = NoOpFileOperationCoordinator,
    private val clockEventRecorder: ClockEventRecorder = NoOpClockEventRecorder,
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
        val startedEvent = recordStartedEvent(date.toString() + ".org", date, headingPath, dateTime)
        if (startedEvent.isFailure) {
            return Result.failure(startedEvent.exceptionOrNull()!!)
        }

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
        return fileOperationCoordinator.runExclusive(fileId) {
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            if (resolveLevel2HeadingNode(doc.lines, headingPath) == null) {
                return@runExclusive Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.InvalidHeadingLevel,
                        message = "Clock operation is only allowed on level-2 headings",
                    ),
                )
            }
            if (parser.findOpenClock(doc.lines, headingPath, timeZone) != null) {
                return@runExclusive Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.AlreadyRunning,
                        message = "Clock already running for this heading",
                    ),
                )
            }

            val firstLines = runCatching { parser.appendOpenClock(doc.lines, headingPath, now, timeZone) }
                .getOrElse { return@runExclusive Result.failure(it) }
            val startedEvent = recordStartedEvent(fileId, doc.date, headingPath, now)
            if (startedEvent.isFailure) {
                return@runExclusive Result.failure(startedEvent.exceptionOrNull()!!)
            }
            val firstSave = repository.saveFile(fileId, firstLines, doc.hash, FileWriteIntent.ClockMutation)
            if (firstSave is SaveResult.Success) {
                return@runExclusive Result.success(ClockMutationResult(startedAt = now))
            }
            if (firstSave !is SaveResult.Conflict) {
                return@runExclusive Result.failure(firstSave.toClockOperationException())
            }

            val latestDoc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            if (resolveLevel2HeadingNode(latestDoc.lines, headingPath) == null) {
                return@runExclusive Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.InvalidHeadingLevel,
                        message = "Clock operation is only allowed on level-2 headings",
                    ),
                )
            }
            if (parser.findOpenClock(latestDoc.lines, headingPath, timeZone) != null) {
                return@runExclusive Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.AlreadyRunning,
                        message = "Clock already running for this heading",
                    ),
                )
            }
            val secondLines = runCatching { parser.appendOpenClock(latestDoc.lines, headingPath, now, timeZone) }
                .getOrElse { return@runExclusive Result.failure(it) }
            val secondSave = repository.saveFile(fileId, secondLines, latestDoc.hash, FileWriteIntent.ClockMutation)
            if (secondSave is SaveResult.Success) {
                Result.success(ClockMutationResult(startedAt = now))
            } else {
                Result.failure(secondSave.toClockOperationException())
            }
        }
    }

    suspend fun stopClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        now: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<ClockMutationResult> {
        return fileOperationCoordinator.runExclusive(fileId) {
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            if (resolveLevel2HeadingNode(doc.lines, headingPath) == null) {
                return@runExclusive Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.InvalidHeadingLevel,
                        message = "Clock operation is only allowed on level-2 headings",
                    ),
                )
            }
            val closeResult = runCatching { parser.closeLatestOpenClock(doc.lines, headingPath, now, timeZone) }
                .getOrElse { return@runExclusive Result.failure(it) }
            val stoppedEvent = recordStoppedEvent(fileId, doc.date, headingPath, now)
            if (stoppedEvent.isFailure) {
                return@runExclusive Result.failure(stoppedEvent.exceptionOrNull()!!)
            }
            val save = saveFileWithRetry(fileId, doc.hash, closeResult.lines, FileWriteIntent.ClockMutation) {
                parser.closeLatestOpenClock(it, headingPath, now, timeZone).lines
            }
            if (save is SaveResult.Success) {
                Result.success(ClockMutationResult())
            } else {
                Result.failure(save.toClockOperationException())
            }
        }
    }

    suspend fun cancelClockInFile(fileId: String, headingPath: HeadingPath): Result<ClockMutationResult> {
        return fileOperationCoordinator.runExclusive(fileId) {
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            if (resolveLevel2HeadingNode(doc.lines, headingPath) == null) {
                return@runExclusive Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.InvalidHeadingLevel,
                        message = "Clock operation is only allowed on level-2 headings",
                    ),
                )
            }
            val cancelled = runCatching { parser.cancelLatestOpenClock(doc.lines, headingPath) }
                .getOrElse { return@runExclusive Result.failure(it) }
            val cancelledEvent = recordCancelledEvent(fileId, doc.date, headingPath, Clock.System.now())
            if (cancelledEvent.isFailure) {
                return@runExclusive Result.failure(cancelledEvent.exceptionOrNull()!!)
            }
            val save = saveFileWithRetry(fileId, doc.hash, cancelled, FileWriteIntent.ClockMutation) {
                parser.cancelLatestOpenClock(it, headingPath)
            }
            if (save is SaveResult.Success) {
                Result.success(ClockMutationResult())
            } else {
                Result.failure(save.toClockOperationException())
            }
        }
    }

    suspend fun listClosedClocksInFile(
        fileId: String,
        headingPath: HeadingPath,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<List<ClosedClockEntry>> {
        val doc = repository.loadFile(fileId).getOrElse { return Result.failure(it) }
        if (resolveLevel2HeadingNode(doc.lines, headingPath) == null) {
            return Result.failure(IllegalArgumentException("Clock operation is only allowed on level-2 headings"))
        }
        return runCatching { parser.listClosedClocks(doc.lines, headingPath, timeZone) }
    }

    suspend fun editClosedClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        clockLineIndex: Int,
        newStart: Instant,
        newEnd: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Result<Unit> {
        return fileOperationCoordinator.runExclusive(fileId) {
            if (newEnd < newStart) {
                return@runExclusive Result.failure(IllegalArgumentException("End time must be after start time"))
            }
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            if (resolveLevel2HeadingNode(doc.lines, headingPath) == null) {
                return@runExclusive Result.failure(
                    IllegalArgumentException("Clock operation is only allowed on level-2 headings"),
                )
            }
            val firstLines = runCatching {
                parser.replaceClosedClock(doc.lines, headingPath, clockLineIndex, newStart, newEnd, timeZone)
            }.getOrElse { return@runExclusive Result.failure(it) }
            val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
                parser.replaceClosedClock(it, headingPath, clockLineIndex, newStart, newEnd, timeZone)
            }
            if (save is SaveResult.Success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(save.asMessage()))
            }
        }
    }

    suspend fun deleteClosedClockInFile(
        fileId: String,
        headingPath: HeadingPath,
        clockLineIndex: Int,
    ): Result<Unit> {
        return fileOperationCoordinator.runExclusive(fileId) {
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            if (resolveLevel2HeadingNode(doc.lines, headingPath) == null) {
                return@runExclusive Result.failure(
                    IllegalArgumentException("Clock operation is only allowed on level-2 headings"),
                )
            }
            val firstLines = runCatching {
                parser.deleteClosedClock(doc.lines, headingPath, clockLineIndex)
            }.getOrElse { return@runExclusive Result.failure(it) }
            val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
                parser.deleteClosedClock(it, headingPath, clockLineIndex)
            }
            if (save is SaveResult.Success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(save.asMessage()))
            }
        }
    }

    suspend fun createL1HeadingInFile(fileId: String, title: String, attachTplTag: Boolean = false): Result<Unit> {
        return fileOperationCoordinator.runExclusive(fileId) {
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            val firstLines = runCatching {
                parser.appendL1Heading(doc.lines, title, attachTplTag)
            }.getOrElse { return@runExclusive Result.failure(it) }
            val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
                parser.appendL1Heading(it, title, attachTplTag)
            }
            if (save is SaveResult.Success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(save.asMessage()))
            }
        }
    }

    suspend fun createL2HeadingInFile(
        fileId: String,
        parentPath: HeadingPath,
        title: String,
        attachTplTag: Boolean = false,
    ): Result<Unit> {
        return fileOperationCoordinator.runExclusive(fileId) {
            val doc = repository.loadFile(fileId).getOrElse { return@runExclusive Result.failure(it) }
            val firstLines = runCatching {
                parser.appendL2HeadingUnderL1(doc.lines, parentPath, title, attachTplTag)
            }.getOrElse { return@runExclusive Result.failure(it) }
            val save = saveFileWithRetry(fileId, doc.hash, firstLines, FileWriteIntent.UserEdit) {
                parser.appendL2HeadingUnderL1(it, parentPath, title, attachTplTag)
            }
            if (save is SaveResult.Success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(save.asMessage()))
            }
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

        val stoppedEvent = recordStoppedEvent(doc.date.toString() + ".org", doc.date, headingPath, end)
        if (stoppedEvent.isFailure) {
            return ClockStopResult.Failed("Failed to append local event")
        }

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

        val closedToday = runCatching {
            parser.appendClosedClock(todayDoc.lines, headingPath, startOfToday, end, timeZone)
        }.getOrElse {
            return ClockStopResult.Failed(it.message ?: "Failed to append today clock")
        }

        val stoppedEvent = recordStoppedEvent(previousDoc.date.toString() + ".org", previousDoc.date, headingPath, end)
        if (stoppedEvent.isFailure) {
            return ClockStopResult.Failed("Failed to append local event")
        }

        val previousSave = saveWithConflictRetry(previousDoc.date, previousDoc.hash, closedPrevious) {
            parser.closeLatestOpenClock(it, headingPath, endOfPrevious, timeZone).lines
        }
        if (previousSave !is SaveResult.Success) {
            return ClockStopResult.Failed("Failed saving previous-day file: ${previousSave.asMessage()}")
        }

        val todaySave = saveWithConflictRetry(todayDoc.date, todayDoc.hash, closedToday) {
            parser.appendClosedClock(it, headingPath, startOfToday, end, timeZone)
        }
        if (todaySave !is SaveResult.Success) {
            return ClockStopResult.Failed("Failed saving current-day file: ${todaySave.asMessage()}")
        }

        return ClockStopResult.Success(setOf(previousDoc.date, todayDoc.date))
    }

    private suspend fun recordStartedEvent(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    ): Result<Unit> = runCatching {
        clockEventRecorder.recordStarted(fileName, logicalDay, headingPath, createdAt)
    }

    private suspend fun recordStoppedEvent(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    ): Result<Unit> = runCatching {
        clockEventRecorder.recordStopped(fileName, logicalDay, headingPath, createdAt)
    }

    private suspend fun recordCancelledEvent(
        fileName: String,
        logicalDay: LocalDate,
        headingPath: HeadingPath,
        createdAt: Instant,
    ): Result<Unit> = runCatching {
        clockEventRecorder.recordCancelled(fileName, logicalDay, headingPath, createdAt)
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
            is SaveResult.RoundTripMismatch -> reason
        }
    }

    private fun SaveResult.toClockOperationException(): ClockOperationException {
        return when (this) {
            is SaveResult.ValidationError -> ClockOperationException(ClockOperationCode.ValidationFailed, reason)
            is SaveResult.IoError -> ClockOperationException(ClockOperationCode.IoFailed, reason)
            is SaveResult.Conflict -> ClockOperationException(ClockOperationCode.Conflict, reason)
            is SaveResult.RoundTripMismatch -> ClockOperationException(ClockOperationCode.SaveRoundTripMismatch, reason)
            SaveResult.Success -> ClockOperationException(ClockOperationCode.IoFailed, "Unexpected save success mapping")
        }
    }

    private fun resolveLevel2HeadingNode(lines: List<String>, headingPath: HeadingPath): HeadingNode? {
        return parser.findLevel2HeadingNode(lines, headingPath)
    }
}
