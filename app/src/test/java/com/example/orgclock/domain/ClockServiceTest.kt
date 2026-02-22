package com.example.orgclock.domain

import android.net.Uri
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.OrgRepository
import com.example.orgclock.data.RootAccess
import com.example.orgclock.data.SaveResult
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.OrgDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ClockServiceTest {

    @Test
    fun stopClock_splitsAcrossMidnight() = runBlocking {
        val repo = FakeRepo(
            mutableMapOf(
                LocalDate.of(2026, 2, 14) to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-14 Sat 23:50:00]",
                    ":END:",
                ),
                LocalDate.of(2026, 2, 15) to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    ":END:",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.stopClock(
            ZonedDateTime.of(2026, 2, 15, 0, 10, 0, 0, ZoneId.of("Asia/Tokyo")),
            HeadingPath.parse("Work/Project A"),
        )

        assertTrue(result is ClockService.ClockStopResult.Success)
        assertTrue(repo.files[LocalDate.of(2026, 2, 14)]!!.any { it.contains("23:59:59") })
        assertTrue(repo.files[LocalDate.of(2026, 2, 15)]!!.any { it.contains("00:00:00") })
    }

    @Test
    fun stopClock_acrossMidnight_retriesOnConflict() = runBlocking {
        val date1 = LocalDate.of(2026, 2, 14)
        val date2 = LocalDate.of(2026, 2, 15)
        val repo = FakeRepo(
            mutableMapOf(
                date1 to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-14 Sat 23:50:00]",
                    ":END:",
                ),
                date2 to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    ":END:",
                ),
            ),
            conflictCountByDate = mutableMapOf(date1 to 1, date2 to 1),
        )
        val service = ClockService(repo)

        val result = service.stopClock(
            ZonedDateTime.of(2026, 2, 15, 0, 10, 0, 0, ZoneId.of("Asia/Tokyo")),
            HeadingPath.parse("Work/Project A"),
        )

        assertTrue(result is ClockService.ClockStopResult.Success)
        assertEquals(2, repo.saveAttempts[date1])
        assertEquals(2, repo.saveAttempts[date2])
        assertTrue(repo.files[date1]!!.any { it.contains("23:59:59") })
        assertTrue(repo.files[date2]!!.any { it.contains("00:00:00") })
    }

    @Test
    fun startClockInFile_rejectsNonLevel2Heading() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.startClockInFile(
            fileId = "f1",
            headingLineIndex = 0,
            now = ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo")),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun cancelClockInFile_removesOpenClockLine() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-15 Sun 09:00:00]",
                    ":END:",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.cancelClockInFile("f1", 1)

        assertTrue(result.isSuccess)
        assertTrue(repo.files["f1"]!!.none { it.startsWith("CLOCK: [2026-02-15 Sun 09:00:00]") })
    }

    @Test
    fun listClosedClocksInFile_returnsEntriesForLevel2() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:25:00] =>  0:25",
                    ":END:",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.listClosedClocksInFile("f1", 1, ZonedDateTime.of(2026, 2, 15, 12, 0, 0, 0, ZoneId.of("Asia/Tokyo")))

        assertTrue(result.isSuccess)
        val entries = result.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals(25L, entries.first().durationMinutes)
    }

    @Test
    fun editClosedClockInFile_updatesClockLine() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:25:00] =>  0:25",
                    ":END:",
                ),
            ),
        )
        val service = ClockService(repo)
        val start = ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
        val end = ZonedDateTime.of(2026, 2, 15, 11, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

        val result = service.editClosedClockInFile("f1", 1, 3, start, end)

        assertTrue(result.isSuccess)
        assertTrue(repo.files["f1"]!![3].contains("10:00:00]--[2026-02-15 Sun 11:00:00]"))
        assertTrue(repo.files["f1"]!![3].endsWith("=>  1:00"))
    }

    @Test
    fun deleteClosedClockInFile_removesSpecifiedClockLine() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:25:00] =>  0:25",
                    "CLOCK: [2026-02-15 Sun 10:00:00]--[2026-02-15 Sun 10:50:00] =>  0:50",
                    ":END:",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.deleteClosedClockInFile("f1", 1, 3)

        assertTrue(result.isSuccess)
        assertTrue(repo.files["f1"]!!.none { it.contains("09:00:00") })
        assertTrue(repo.files["f1"]!!.any { it.contains("10:00:00") })
    }

    @Test
    fun deleteClosedClockInFile_retriesOnConflict() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:25:00] =>  0:25",
                    ":END:",
                ),
            ),
            conflictCountByFileId = mutableMapOf("f1" to 1),
        )
        val service = ClockService(repo)

        val result = service.deleteClosedClockInFile("f1", 1, 3)

        assertTrue(result.isSuccess)
        assertEquals(2, repo.saveAttempts["f1"])
        assertTrue(repo.files["f1"]!!.none { it.contains("09:00:00") })
    }

    @Test
    fun deleteClosedClockInFile_rejectsNonLevel2Heading() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:25:00] =>  0:25",
                    ":END:",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.deleteClosedClockInFile("f1", 0, 3)

        assertTrue(result.isFailure)
    }

    @Test
    fun createL1HeadingInFile_appendsNewHeading() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.createL1HeadingInFile("f1", "Home")

        assertTrue(result.isSuccess)
        assertEquals("* Home", repo.files["f1"]!!.last())
    }

    @Test
    fun createL1HeadingInFile_rejectsDuplicateTitle() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.createL1HeadingInFile("f1", "Work")

        assertTrue(result.isFailure)
    }

    @Test
    fun createL1HeadingInFile_withTplTag_appendsTplSuffix() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.createL1HeadingInFile("f1", "Template Seed", attachTplTag = true)

        assertTrue(result.isSuccess)
        assertEquals("* Template Seed :TPL:", repo.files["f1"]!!.last())
    }

    @Test
    fun createL2HeadingInFile_appendsUnderTargetL1() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    "* Home",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.createL2HeadingInFile("f1", 0, "Project B")

        assertTrue(result.isSuccess)
        assertEquals("** Project B", repo.files["f1"]!![2])
    }

    @Test
    fun createL2HeadingInFile_rejectsDuplicateUnderSameL1() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    "* Home",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.createL2HeadingInFile("f1", 0, "Project A")

        assertTrue(result.isFailure)
    }

    @Test
    fun createL2HeadingInFile_withTplTag_appendsTplSuffix() = runBlocking {
        val repo = FileRepo(
            mutableMapOf(
                "f1" to listOf(
                    "* Work",
                    "** Project A",
                    "* Home",
                ),
            ),
        )
        val service = ClockService(repo)

        val result = service.createL2HeadingInFile("f1", 0, "Project B", attachTplTag = true)

        assertTrue(result.isSuccess)
        assertEquals("** Project B :TPL:", repo.files["f1"]!![2])
    }

    @Test
    fun startClock_mapsValidationSaveFailureToIllegalArgumentException() = runBlocking {
        val repo = object : OrgRepository {
            override suspend fun openRoot(uri: Uri): Result<RootAccess> = Result.success(RootAccess(uri, "test"))

            override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
                val lines = listOf("* Work", "** Project A")
                return Result.success(OrgDocument(date, lines, "hash"))
            }

            override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
                return SaveResult.ValidationError("invalid heading")
            }
        }
        val service = ClockService(repo)

        val result = service.startClock(
            ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo")),
            HeadingPath.parse("Work/Project A"),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        assertEquals("invalid heading", ex?.message)
    }

    @Test
    fun startClock_mapsIoSaveFailureToIllegalStateException() = runBlocking {
        val repo = object : OrgRepository {
            override suspend fun openRoot(uri: Uri): Result<RootAccess> = Result.success(RootAccess(uri, "test"))

            override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
                val lines = listOf("* Work", "** Project A")
                return Result.success(OrgDocument(date, lines, "hash"))
            }

            override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
                return SaveResult.IoError("write failed")
            }
        }
        val service = ClockService(repo)

        val result = service.startClock(
            ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo")),
            HeadingPath.parse("Work/Project A"),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertEquals("write failed", ex?.message)
    }

    private class FakeRepo(
        val files: MutableMap<LocalDate, List<String>>,
        private val conflictCountByDate: MutableMap<LocalDate, Int> = mutableMapOf(),
    ) : OrgRepository {
        val saveAttempts: MutableMap<LocalDate, Int> = mutableMapOf()

        override suspend fun openRoot(uri: Uri): Result<RootAccess> {
            return Result.success(RootAccess(uri, "test"))
        }

        override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
            val lines = files[date] ?: emptyList()
            return Result.success(
                OrgDocument(date, lines, hash(lines.joinToString("\n"))),
            )
        }

        override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
            saveAttempts[date] = (saveAttempts[date] ?: 0) + 1

            val plannedConflictCount = conflictCountByDate[date] ?: 0
            if (plannedConflictCount > 0) {
                conflictCountByDate[date] = plannedConflictCount - 1
                files[date] = currentWithExternalEdit(files[date] ?: emptyList())
                return SaveResult.Conflict("Injected conflict")
            }

            val current = files[date] ?: emptyList()
            val currentHash = hash(current.joinToString("\n"))
            if (currentHash != expectedHash) {
                return SaveResult.Conflict("Hash mismatch")
            }
            files[date] = lines
            return SaveResult.Success
        }

        private fun hash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun currentWithExternalEdit(lines: List<String>): List<String> {
            return lines + "# external edit"
        }
    }

    private class FileRepo(
        val files: MutableMap<String, List<String>>,
        private val conflictCountByFileId: MutableMap<String, Int> = mutableMapOf(),
    ) : OrgRepository {
        val saveAttempts: MutableMap<String, Int> = mutableMapOf()

        override suspend fun openRoot(uri: Uri): Result<RootAccess> {
            return Result.success(RootAccess(uri, "test"))
        }

        override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> {
            return Result.success(files.keys.map { OrgFileEntry(it, "$it.org", null) })
        }

        override suspend fun loadFile(fileId: String): Result<OrgDocument> {
            val lines = files[fileId] ?: return Result.failure(IllegalArgumentException("missing file"))
            return Result.success(OrgDocument(LocalDate.of(2026, 2, 15), lines, hash(lines.joinToString("\n"))))
        }

        override suspend fun saveFile(
            fileId: String,
            lines: List<String>,
            expectedHash: String,
            writeIntent: FileWriteIntent,
        ): SaveResult {
            saveAttempts[fileId] = (saveAttempts[fileId] ?: 0) + 1
            val remainingConflicts = conflictCountByFileId[fileId] ?: 0
            if (remainingConflicts > 0) {
                conflictCountByFileId[fileId] = remainingConflicts - 1
                return SaveResult.Conflict("Injected conflict")
            }
            val current = files[fileId] ?: return SaveResult.ValidationError("missing file")
            val currentHash = hash(current.joinToString("\n"))
            if (currentHash != expectedHash) return SaveResult.Conflict("Hash mismatch")
            files[fileId] = lines
            return SaveResult.Success
        }

        override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
            return Result.failure(UnsupportedOperationException())
        }

        override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
            return SaveResult.ValidationError("unsupported")
        }

        private fun hash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
