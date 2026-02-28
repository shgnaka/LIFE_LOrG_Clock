package com.example.orgclock.notification

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.OrgRepository
import com.example.orgclock.data.RootAccess
import com.example.orgclock.data.SaveResult
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.model.OrgDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ClockInScannerTest {
    @Test
    fun scan_collectsOpenClocksFromAllFiles_sortedByLatestStart() = runTest {
        val repo = FakeOrgRepository(
            files = listOf(
                OrgFileEntry("f1", "2026-02-22.org", Instant.now()),
                OrgFileEntry("f2", "projects.org", Instant.now()),
            ),
            docs = mapOf(
                "f1" to doc(
                    "* Work",
                    "** A",
                    "CLOCK: [2026-02-22 Sun 09:00:00]",
                ),
                "f2" to doc(
                    "* Work",
                    "** B",
                    "CLOCK: [2026-02-22 Sun 10:00:00]",
                ),
            ),
        )

        val scanner = ClockInScanner(repo)
        val result = scanner.scan(ZoneId.of("Asia/Tokyo"))

        assertTrue(result.isSuccess)
        val scanResult = result.getOrThrow()
        val entries = scanResult.entries
        assertEquals(2, entries.size)
        assertEquals("B", entries[0].headingTitle)
        assertEquals("A", entries[1].headingTitle)
        assertTrue(scanResult.failedFiles.isEmpty())
    }

    @Test
    fun scan_collectsFailuresAndContinuesWhenSomeFilesFail() = runTest {
        val repo = FakeOrgRepository(
            files = listOf(
                OrgFileEntry("ok", "ok.org", Instant.now()),
                OrgFileEntry("broken", "broken.org", Instant.now()),
            ),
            docs = mapOf(
                "ok" to doc(
                    "* Work",
                    "** A",
                    "CLOCK: [2026-02-22 Sun 09:00:00]",
                ),
            ),
            failures = mapOf(
                "broken" to IllegalStateException("read failed"),
            ),
        )

        val scanner = ClockInScanner(repo)
        val result = scanner.scan(ZoneId.of("Asia/Tokyo"))

        assertTrue(result.isSuccess)
        val scanResult = result.getOrThrow()
        assertEquals(1, scanResult.entries.size)
        assertEquals("A", scanResult.entries.first().headingTitle)
        assertEquals(1, scanResult.failedFiles.size)
        assertEquals("broken", scanResult.failedFiles.first().fileId)
        assertEquals("read failed", scanResult.failedFiles.first().reason)
    }

    @Test
    fun scan_failsWhenListingFilesFails() = runTest {
        val repo = FakeOrgRepository(
            files = emptyList(),
            docs = emptyMap(),
            listFailure = IllegalStateException("list failed"),
        )

        val scanner = ClockInScanner(repo)
        val result = scanner.scan(ZoneId.of("Asia/Tokyo"))

        assertTrue(result.isFailure)
        assertEquals("list failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun scan_returnsOnlyFailedFiles_whenAllLoadsFail() = runTest {
        val repo = FakeOrgRepository(
            files = listOf(
                OrgFileEntry("f1", "a.org", Instant.now()),
                OrgFileEntry("f2", "b.org", Instant.now()),
            ),
            docs = emptyMap(),
            failures = mapOf(
                "f1" to IllegalStateException("read a failed"),
                "f2" to IllegalStateException("read b failed"),
            ),
        )

        val scanner = ClockInScanner(repo)
        val result = scanner.scan(ZoneId.of("Asia/Tokyo"))

        assertTrue(result.isSuccess)
        val scanResult = result.getOrThrow()
        assertTrue(scanResult.entries.isEmpty())
        assertEquals(2, scanResult.failedFiles.size)
        assertEquals("f1", scanResult.failedFiles[0].fileId)
        assertEquals("f2", scanResult.failedFiles[1].fileId)
    }

    private fun doc(vararg lines: String): OrgDocument {
        return OrgDocument(
            date = LocalDate.of(2026, 2, 22),
            lines = lines.toList(),
            hash = "dummy",
        )
    }
}

private class FakeOrgRepository(
    private val files: List<OrgFileEntry>,
    private val docs: Map<String, OrgDocument>,
    private val failures: Map<String, Throwable> = emptyMap(),
    private val listFailure: Throwable? = null,
) : OrgRepository {
    override suspend fun openRoot(uri: android.net.Uri): Result<RootAccess> {
        return Result.success(RootAccess(uri, "root"))
    }

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> {
        listFailure?.let { return Result.failure(it) }
        return Result.success(files)
    }

    override suspend fun loadFile(fileId: String): Result<OrgDocument> {
        failures[fileId]?.let { return Result.failure(it) }
        return docs[fileId]?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("missing file: $fileId"))
    }

    override suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent,
    ): SaveResult {
        return SaveResult.ValidationError("not used")
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
        return Result.failure(UnsupportedOperationException())
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
        return SaveResult.ValidationError("not used")
    }
}
