package com.example.orgclock.notification

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.OrgRepository
import com.example.orgclock.data.RootAccess
import com.example.orgclock.data.SaveResult
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
        val entries = result.getOrThrow()
        assertEquals(2, entries.size)
        assertEquals("B", entries[0].headingTitle)
        assertEquals("A", entries[1].headingTitle)
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
) : OrgRepository {
    override suspend fun openRoot(uri: android.net.Uri): Result<RootAccess> {
        return Result.success(RootAccess(uri, "root"))
    }

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> {
        return Result.success(files)
    }

    override suspend fun loadFile(fileId: String): Result<OrgDocument> {
        return docs[fileId]?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("missing file: $fileId"))
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
        return Result.failure(UnsupportedOperationException())
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
        return SaveResult.ValidationError("not used")
    }
}
