package com.example.orgclock.sync

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.SaveResult
import com.example.orgclock.domain.ClockService
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.OrgDocument
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class RemoteClockEventApplierTest {
    @Test
    fun appliesStartedEventToFileResolvedByPortableDisplayName() = runTest {
        val repository = MemoryRepository()
        val applier = RepositoryRemoteClockEventApplier(
            repository = repository,
            clockService = ClockService(repository),
            timeZoneProvider = { TimeZone.UTC },
        )

        val result = applier.apply(
            ClockEvent(
                eventId = "evt-remote",
                eventType = ClockEventType.Started,
                deviceId = "phone",
                createdAt = Instant.parse("2026-06-11T09:00:00Z"),
                logicalDay = LocalDate(2026, 6, 11),
                fileName = "2026-06-11.org",
                headingPath = HeadingPath.parse("Work/Project"),
                causalOrder = ClockEventCausalOrder(counter = 1),
            ),
        )

        assertTrue(result.isSuccess)
        assertContains(repository.lines, "CLOCK: [2026-06-11 Thu 09:00:00]")
    }
}

private class MemoryRepository : ClockRepository {
    var lines = listOf("* Work", "** Project", ":LOGBOOK:", ":END:")
    private val date = LocalDate(2026, 6, 11)

    override suspend fun listOrgFiles() = Result.success(
        listOf(OrgFileEntry(fileId = "platform-specific-id", displayName = "2026-06-11.org", modifiedAt = null)),
    )

    override suspend fun loadFile(fileId: String) = Result.success(OrgDocument(date, lines, "hash"))

    override suspend fun saveFile(fileId: String, lines: List<String>, expectedHash: String, writeIntent: FileWriteIntent): SaveResult {
        this.lines = lines
        return SaveResult.Success
    }

    override suspend fun loadDaily(date: LocalDate) = loadFile("platform-specific-id")
    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String) =
        saveFile("platform-specific-id", lines, expectedHash)
}
