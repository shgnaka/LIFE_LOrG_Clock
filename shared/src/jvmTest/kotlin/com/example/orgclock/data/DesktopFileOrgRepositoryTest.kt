package com.example.orgclock.data

import com.example.orgclock.domain.ClockService
import com.example.orgclock.model.HeadingPath
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFileOrgRepositoryTest {
    private val tempRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempRoots.clear()
    }

    @Test
    fun listOrgFiles_returnsAbsolutePathIdsSortedByModifiedTime() {
        val root = tempRoot()
        val older = write(root.resolve("2026-03-01.org"), "* Older")
        val newer = write(root.resolve("notes.org"), "* Newer")
        write(root.resolve(".orgclock-template.org"), "* Hidden Template")
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000))
        val repository = DesktopFileOrgRepository(root)

        val files = runSuspend { repository.listOrgFiles() }.getOrThrow()

        assertEquals(listOf(newer.absolutePathString(), older.absolutePathString()), files.map { it.fileId })
        assertEquals(listOf("notes.org", "2026-03-01.org"), files.map { it.displayName })
    }

    @Test
    fun saveFile_preservesExistingLineEndingsAndTrailingNewline() {
        val root = tempRoot()
        val path = write(root.resolve("2026-03-01.org"), "* M4\r\n** Task\r\n")
        val repository = DesktopFileOrgRepository(root)
        val before = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()

        val save = runSuspend {
            repository.saveFile(
                fileId = path.absolutePathString(),
                lines = listOf("* M4", "** Task", "CLOCK: [2026-03-01 Sat 09:00]--[2026-03-01 Sat 10:00]"),
                expectedHash = before.hash,
                writeIntent = FileWriteIntent.UserEdit,
            )
        }

        assertEquals(SaveResult.Success, save)
        assertEquals(
            "* M4\r\n** Task\r\nCLOCK: [2026-03-01 Sat 09:00]--[2026-03-01 Sat 10:00]\r\n",
            path.readText(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun saveFile_withStaleHash_returnsConflict() {
        val root = tempRoot()
        val path = write(root.resolve("2026-03-01.org"), "* M4\n")
        val repository = DesktopFileOrgRepository(root)
        val before = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()

        write(path, "* M4\n** Changed elsewhere\n")

        val save = runSuspend {
            repository.saveFile(
                fileId = path.absolutePathString(),
                lines = listOf("* M4", "** Local edit"),
                expectedHash = before.hash,
                writeIntent = FileWriteIntent.UserEdit,
            )
        }

        assertIs<SaveResult.Conflict>(save)
    }

    @Test
    fun saveFile_outsideRepositoryRoot_returnsValidationError() {
        val root = tempRoot()
        val outsideRoot = tempRoot()
        val path = write(outsideRoot.resolve("2026-03-01.org"), "* External\n")
        val repository = DesktopFileOrgRepository(root)

        val save = runSuspend {
            repository.saveFile(
                fileId = path.absolutePathString(),
                lines = listOf("* External", "** Updated"),
                expectedHash = "irrelevant",
                writeIntent = FileWriteIntent.UserEdit,
            )
        }

        val error = assertIs<SaveResult.ValidationError>(save)
        assertEquals("File is outside repository root", error.reason)
    }

    @Test
    fun saveDaily_createsNewFileAndLoadDailyReturnsSavedDocument() {
        val root = tempRoot()
        val repository = DesktopFileOrgRepository(root)
        val date = LocalDate(2026, 3, 2)

        val before = runSuspend { repository.loadDaily(date) }.getOrThrow()
        assertEquals(emptyList(), before.lines)

        val save = runSuspend {
            repository.saveDaily(
                date = date,
                lines = listOf("* Work", "** Project A"),
                expectedHash = before.hash,
            )
        }

        assertEquals(SaveResult.Success, save)
        val loaded = runSuspend { repository.loadDaily(date) }.getOrThrow()
        assertEquals(date, loaded.date)
        assertEquals(listOf("* Work", "** Project A"), loaded.lines)
        assertTrue(Files.exists(root.resolve("2026-03-02.org")))
    }

    @Test
    fun saveFile_createsAndPrunesBackups() {
        val root = tempRoot()
        val path = write(root.resolve("2026-03-01.org"), "* M4\n")
        var nowMs = 1_000L
        val repository = DesktopFileOrgRepository(
            rootDirectory = root,
            backupPolicy = BackupPolicyConfig(backupGenerations = 2, clockBackupIntervalMs = 10_000L),
            nowMsProvider = { nowMs },
        )

        var current = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()
        assertEquals(
            SaveResult.Success,
            runSuspend {
                repository.saveFile(path.absolutePathString(), listOf("* M4", "** One"), current.hash, FileWriteIntent.UserEdit)
            },
        )

        nowMs = 2_000L
        current = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()
        assertEquals(
            SaveResult.Success,
            runSuspend {
                repository.saveFile(path.absolutePathString(), listOf("* M4", "** Two"), current.hash, FileWriteIntent.UserEdit)
            },
        )

        nowMs = 3_000L
        current = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()
        assertEquals(
            SaveResult.Success,
            runSuspend {
                repository.saveFile(path.absolutePathString(), listOf("* M4", "** Three"), current.hash, FileWriteIntent.UserEdit)
            },
        )

        val backups = root.listDirectoryEntries(".2026-03-01.org.bak.*").sortedBy { it.name }
        assertEquals(2, backups.size)
        assertEquals(
            listOf(
                ".2026-03-01.org.bak.${backupStamp(2_000L)}",
                ".2026-03-01.org.bak.${backupStamp(3_000L)}",
            ),
            backups.map { it.name },
        )
        assertEquals("* M4\n** One\n", backups[0].readText(StandardCharsets.UTF_8))
        assertEquals("* M4\n** Two\n", backups[1].readText(StandardCharsets.UTF_8))
    }

    @Test
    fun saveFile_clockMutationRateLimitsBackups() {
        val root = tempRoot()
        val path = write(root.resolve("2026-03-01.org"), "* M4\n")
        var nowMs = 1_000L
        val repository = DesktopFileOrgRepository(
            rootDirectory = root,
            backupPolicy = BackupPolicyConfig(backupGenerations = 10, clockBackupIntervalMs = 1_000L),
            nowMsProvider = { nowMs },
        )

        var current = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()
        assertEquals(
            SaveResult.Success,
            runSuspend {
                repository.saveFile(path.absolutePathString(), listOf("* M4", "** One"), current.hash, FileWriteIntent.ClockMutation)
            },
        )

        nowMs = 1_500L
        current = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()
        assertEquals(
            SaveResult.Success,
            runSuspend {
                repository.saveFile(path.absolutePathString(), listOf("* M4", "** Two"), current.hash, FileWriteIntent.ClockMutation)
            },
        )

        nowMs = 2_500L
        current = runSuspend { repository.loadFile(path.absolutePathString()) }.getOrThrow()
        assertEquals(
            SaveResult.Success,
            runSuspend {
                repository.saveFile(path.absolutePathString(), listOf("* M4", "** Three"), current.hash, FileWriteIntent.ClockMutation)
            },
        )

        val backups = root.listDirectoryEntries(".2026-03-01.org.bak.*")
        assertEquals(2, backups.size)
    }

    @Test
    fun clockService_runsAgainstDesktopRepository() {
        val root = tempRoot()
        val repository = DesktopFileOrgRepository(root)
        val service = ClockService(repository)
        val date = LocalDate(2026, 3, 10)
        val initial = listOf("* M4", "** Task")

        val before = runSuspend { repository.loadDaily(date) }.getOrThrow()
        assertEquals(SaveResult.Success, runSuspend { repository.saveDaily(date, initial, before.hash) })

        val result = runSuspend {
            service.startClock(
                dateTime = Instant.parse("2026-03-10T09:00:00Z"),
                headingPath = HeadingPath(listOf("M4", "Task")),
                timeZone = TimeZone.UTC,
            )
        }

        assertTrue(result.isSuccess)
        val updated = runSuspend { repository.loadDaily(date) }.getOrThrow()
        val clockLine = updated.lines.firstOrNull { it.contains("CLOCK:") }
        assertTrue(clockLine != null)
        assertTrue(clockLine.contains("2026-03-10"))
        assertTrue(clockLine.contains("09:00"))
    }

    private fun tempRoot(): Path = createTempDirectory("desktop-repo-test").also(tempRoots::add)

    private fun write(path: Path, text: String): Path {
        Files.writeString(path, text, StandardCharsets.UTF_8)
        return path
    }

    private fun backupStamp(nowMs: Long): String =
        java.time.Instant.ofEpochMilli(nowMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completion: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    completion = result
                }
            },
        )
        return completion?.getOrThrow() ?: error("Suspend function did not complete synchronously")
    }
}
