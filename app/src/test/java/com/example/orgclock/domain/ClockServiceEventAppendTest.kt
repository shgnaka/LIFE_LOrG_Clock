package com.example.orgclock.domain

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.OrgDocument
import com.example.orgclock.sync.ClockEventRecorder
import com.example.orgclock.sync.ClockEventType
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockServiceEventAppendTest {
    @Test
    fun startClockInFile_success_records_started_event() = runBlocking {
        val recorder = RecordingClockEventRecorder()
        val service = ClockService(
            repository = fileRepo(
                lines = listOf("* Work", "** Project A", ":LOGBOOK:", ":END:"),
            ),
            clockEventRecorder = recorder,
        )

        val result = service.startClockInFile(
            fileId = "f1",
            headingPath = HeadingPath.parse("Work/Project A"),
            now = Instant.parse("2026-03-18T09:00:00Z"),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(ClockEventType.Started), recorder.events.map { it.type })
        assertEquals("f1", recorder.events.single().fileName)
    }

    @Test
    fun stopClockInFile_success_records_stopped_event() = runBlocking {
        val recorder = RecordingClockEventRecorder()
        val service = ClockService(
            repository = fileRepo(
                lines = listOf("* Work", "** Project A", ":LOGBOOK:", "CLOCK: [2026-03-18 Tue 09:00:00]", ":END:"),
            ),
            clockEventRecorder = recorder,
        )

        val result = service.stopClockInFile(
            fileId = "f1",
            headingPath = HeadingPath.parse("Work/Project A"),
            now = Instant.parse("2026-03-18T10:00:00Z"),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(ClockEventType.Stopped), recorder.events.map { it.type })
    }

    @Test
    fun cancelClockInFile_success_records_cancelled_event() = runBlocking {
        val recorder = RecordingClockEventRecorder()
        val service = ClockService(
            repository = fileRepo(
                lines = listOf("* Work", "** Project A", ":LOGBOOK:", "CLOCK: [2026-03-18 Tue 09:00:00]", ":END:"),
            ),
            clockEventRecorder = recorder,
        )

        val result = service.cancelClockInFile(
            fileId = "f1",
            headingPath = HeadingPath.parse("Work/Project A"),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(ClockEventType.Cancelled), recorder.events.map { it.type })
    }

    @Test
    fun startClockInFile_failure_records_started_event_before_save_failure() = runBlocking {
        val recorder = RecordingClockEventRecorder()
        val service = ClockService(
            repository = failingFileRepo(
                lines = listOf("* Work", "** Project A", ":LOGBOOK:", ":END:"),
            ),
            clockEventRecorder = recorder,
        )

        val result = service.startClockInFile(
            fileId = "f1",
            headingPath = HeadingPath.parse("Work/Project A"),
            now = Instant.parse("2026-03-18T09:00:00Z"),
        )

        assertTrue(result.isFailure)
        assertEquals(listOf(ClockEventType.Started), recorder.events.map { it.type })
    }

    @Test
    fun stopClockInFile_failure_records_stopped_event_before_save_failure() = runBlocking {
        val recorder = RecordingClockEventRecorder()
        val service = ClockService(
            repository = failingFileRepo(
                lines = listOf("* Work", "** Project A", ":LOGBOOK:", "CLOCK: [2026-03-18 Tue 09:00:00]", ":END:"),
            ),
            clockEventRecorder = recorder,
        )

        val result = service.stopClockInFile(
            fileId = "f1",
            headingPath = HeadingPath.parse("Work/Project A"),
            now = Instant.parse("2026-03-18T10:00:00Z"),
        )

        assertTrue(result.isFailure)
        assertEquals(listOf(ClockEventType.Stopped), recorder.events.map { it.type })
    }

    @Test
    fun cancelClockInFile_failure_records_cancelled_event_before_save_failure() = runBlocking {
        val recorder = RecordingClockEventRecorder()
        val service = ClockService(
            repository = failingFileRepo(
                lines = listOf("* Work", "** Project A", ":LOGBOOK:", "CLOCK: [2026-03-18 Tue 09:00:00]", ":END:"),
            ),
            clockEventRecorder = recorder,
        )

        val result = service.cancelClockInFile(
            fileId = "f1",
            headingPath = HeadingPath.parse("Work/Project A"),
        )

        assertTrue(result.isFailure)
        assertEquals(listOf(ClockEventType.Cancelled), recorder.events.map { it.type })
    }

    private fun fileRepo(lines: List<String>): ClockRepository = object : ClockRepository {
        private var currentLines = lines

        override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> = Result.success(listOf(OrgFileEntry("f1", "f1.org", null)))

        override suspend fun loadFile(fileId: String): Result<OrgDocument> =
            Result.success(OrgDocument(LocalDate(2026, 3, 18), currentLines, "hash"))

        override suspend fun saveFile(
            fileId: String,
            lines: List<String>,
            expectedHash: String,
            writeIntent: FileWriteIntent,
        ): SaveResult {
            currentLines = lines
            return SaveResult.Success
        }

        override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> = Result.failure(UnsupportedOperationException())

        override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult =
            SaveResult.ValidationError("unsupported")
    }

    private fun failingFileRepo(lines: List<String>): ClockRepository = object : ClockRepository {
        override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> = Result.failure(UnsupportedOperationException())

        override suspend fun loadFile(fileId: String): Result<OrgDocument> =
            Result.success(OrgDocument(LocalDate(2026, 3, 18), lines, "hash"))

        override suspend fun saveFile(
            fileId: String,
            lines: List<String>,
            expectedHash: String,
            writeIntent: FileWriteIntent,
        ): SaveResult = SaveResult.IoError("boom")

        override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> = Result.failure(UnsupportedOperationException())

        override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult =
            SaveResult.ValidationError("unsupported")
    }
}

private class RecordingClockEventRecorder : ClockEventRecorder {
    data class RecordedEvent(
        val type: ClockEventType,
        val fileName: String,
        val logicalDay: LocalDate,
        val headingPath: HeadingPath,
        val createdAt: Instant,
    )

    val events = mutableListOf<RecordedEvent>()

    override suspend fun recordStarted(fileName: String, logicalDay: LocalDate, headingPath: HeadingPath, createdAt: Instant) {
        events += RecordedEvent(ClockEventType.Started, fileName, logicalDay, headingPath, createdAt)
    }

    override suspend fun recordStopped(fileName: String, logicalDay: LocalDate, headingPath: HeadingPath, createdAt: Instant) {
        events += RecordedEvent(ClockEventType.Stopped, fileName, logicalDay, headingPath, createdAt)
    }

    override suspend fun recordCancelled(fileName: String, logicalDay: LocalDate, headingPath: HeadingPath, createdAt: Instant) {
        events += RecordedEvent(ClockEventType.Cancelled, fileName, logicalDay, headingPath, createdAt)
    }
}
