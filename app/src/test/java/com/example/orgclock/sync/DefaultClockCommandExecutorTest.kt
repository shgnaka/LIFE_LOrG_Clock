package com.example.orgclock.sync

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.FileWriteIntent
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.SaveResult
import com.example.orgclock.domain.ClockService
import com.example.orgclock.model.OrgDocument
import com.example.orgclock.time.ClockEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class DefaultClockCommandExecutorTest {
    @Test
    fun execute_validStart_returnsApplied() = runBlocking {
        val repo = FakeClockRepository(
            mutableMapOf(
                "2026-03-01.org" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    ":END:",
                ),
            ),
        )
        val result = newExecutor(repo).execute(validCommand(kind = "clock.start"))

        assertEquals(ClockResultStatus.Applied, result.status)
        assertTrue(repo.files["2026-03-01.org"]!!.any { it.startsWith("CLOCK: [") })
    }

    @Test
    fun execute_unknownSchema_returnsRejectedValidationFailed() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val payload = validCommand(schema = "clock.command.v0")

        val result = newExecutor(repo).execute(payload)

        assertEquals(ClockResultStatus.Rejected, result.status)
        assertEquals(ClockErrorCode.VALIDATION_FAILED, result.errorCode)
    }

    @Test
    fun execute_unknownKind_returnsRejectedValidationFailed() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val payload = validCommand(kind = "clock.pause")

        val result = newExecutor(repo).execute(payload)

        assertEquals(ClockResultStatus.Rejected, result.status)
        assertEquals(ClockErrorCode.VALIDATION_FAILED, result.errorCode)
    }

    @Test
    fun execute_missingRequiredField_returnsRejected() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val payload = """
            {"schema":"clock.command.v1","kind":"clock.start"}
        """.trimIndent()

        val result = newExecutor(repo).execute(payload)

        assertEquals(ClockResultStatus.Rejected, result.status)
        assertEquals(ClockErrorCode.VALIDATION_FAILED, result.errorCode)
    }

    @Test
    fun execute_fileNotFound_returnsTargetFileNotFound() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val payload = validCommand(fileName = "2026-03-02.org")

        val result = newExecutor(repo).execute(payload)

        assertEquals(ClockResultStatus.Failed, result.status)
        assertEquals(ClockErrorCode.TARGET_FILE_NOT_FOUND, result.errorCode)
    }

    @Test
    fun execute_headingNotFound_returnsTargetHeadingNotFound() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val payload = validCommand(headingPath = "Work/Not Exists")

        val result = newExecutor(repo).execute(payload)

        assertEquals(ClockResultStatus.Failed, result.status)
        assertEquals(ClockErrorCode.TARGET_HEADING_NOT_FOUND, result.errorCode)
    }

    @Test
    fun execute_startAlreadyRunning_returnsAlreadyRunning() = runBlocking {
        val repo = FakeClockRepository(
            mutableMapOf(
                "2026-03-01.org" to listOf(
                    "* Work",
                    "** Project A",
                    ":LOGBOOK:",
                    "CLOCK: [2026-03-01 Sun 09:00:00]",
                    ":END:",
                ),
            ),
        )

        val result = newExecutor(repo).execute(validCommand(kind = "clock.start"))

        assertEquals(ClockResultStatus.Failed, result.status)
        assertEquals(ClockErrorCode.ALREADY_RUNNING, result.errorCode)
    }

    @Test
    fun execute_stopWithoutOpenClock_returnsNoOpenClock() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))

        val result = newExecutor(repo).execute(validCommand(kind = "clock.stop"))

        assertEquals(ClockResultStatus.Failed, result.status)
        assertEquals(ClockErrorCode.NO_OPEN_CLOCK, result.errorCode)
    }

    @Test
    fun execute_duplicateCommandId_returnsDuplicateWithoutSecondMutation() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val executor = newExecutor(repo)
        val payload = validCommand(commandId = "cmd-1", kind = "clock.start")

        val first = executor.execute(payload)
        val second = executor.execute(payload)

        assertEquals(ClockResultStatus.Applied, first.status)
        assertEquals(ClockResultStatus.Duplicate, second.status)
        assertEquals(ClockErrorCode.DUPLICATE_COMMAND, second.errorCode)
        assertEquals(1, repo.saveFileCallCount)
    }

    @Test
    fun execute_duplicateAfterRestart_returnsDuplicate() = runBlocking {
        val sharedSet = linkedSetOf<String>()
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val first = newExecutor(repo, SharedBackingCommandIdStore(sharedSet))
        val second = newExecutor(repo, SharedBackingCommandIdStore(sharedSet))
        val payload = validCommand(commandId = "cmd-1", kind = "clock.start")

        val firstResult = first.execute(payload)
        val secondResult = second.execute(payload)

        assertEquals(ClockResultStatus.Applied, firstResult.status)
        assertEquals(ClockResultStatus.Duplicate, secondResult.status)
    }

    @Test
    fun execute_conflictRetryExhausted_returnsMappedCode() = runBlocking {
        val repo = FakeClockRepository(
            mutableMapOf("2026-03-01.org" to baseFile()),
            alwaysConflictOnSaveFile = true,
        )

        val result = newExecutor(repo).execute(validCommand(kind = "clock.start"))

        assertEquals(ClockResultStatus.Failed, result.status)
        assertEquals(ClockErrorCode.CONFLICT_RETRY_EXHAUSTED, result.errorCode)
    }

    @Test
    fun execute_listOrgFilesIoFailure_returnsIoFailure() = runBlocking {
        val repo = FakeClockRepository(
            mutableMapOf("2026-03-01.org" to baseFile()),
            listFilesError = IllegalStateException("io down"),
        )

        val result = newExecutor(repo).execute(validCommand())

        assertEquals(ClockResultStatus.Failed, result.status)
        assertEquals(ClockErrorCode.IO_FAILURE, result.errorCode)
    }

    @Test
    fun syncIntegrationService_featureDisabled_rejectsManualCommand() = runBlocking {
        val repo = FakeClockRepository(mutableMapOf("2026-03-01.org" to baseFile()))
        val executor = newExecutor(repo)
        val service = SyncIntegrationService(
            featureFlag = object : SyncIntegrationFeatureFlag {
                override fun isEnabled(): Boolean = false
            },
            syncCoreClient = RecordingSyncCoreClient(),
            commandExecutor = executor,
        )

        val result = service.executeManualCommand(validCommand())

        assertEquals(ClockResultStatus.Rejected, result.status)
        assertEquals(ClockErrorCode.VALIDATION_FAILED, result.errorCode)
        assertNotNull(service.snapshot.value.lastResult)
    }

    private fun newExecutor(
        repository: FakeClockRepository,
        commandIdStore: CommandIdStore = InMemoryCommandIdStore(),
    ): DefaultClockCommandExecutor {
        return DefaultClockCommandExecutor(
            repository = repository,
            clockService = ClockService(repository),
            commandIdStore = commandIdStore,
            clockEnvironment = FixedClockEnvironment,
        )
    }

    private fun validCommand(
        schema: String = CLOCK_COMMAND_SCHEMA_V1,
        commandId: String = "cmd-1",
        kind: String = "clock.start",
        fileName: String = "2026-03-01.org",
        headingPath: String = "Work/Project A",
    ): String {
        return """
            {
              "schema": "$schema",
              "command_id": "$commandId",
              "kind": "$kind",
              "target": {
                "file_name": "$fileName",
                "heading_path": "$headingPath"
              },
              "requested_at": "2026-03-01T12:34:56Z",
              "from_device_id": "device-a",
              "request_id": "trace-1"
            }
        """.trimIndent()
    }

    private fun baseFile(): List<String> = listOf(
        "* Work",
        "** Project A",
        ":LOGBOOK:",
        ":END:",
    )
}

private object FixedClockEnvironment : ClockEnvironment {
    override fun now(): Instant = Instant.parse("2026-03-01T13:00:00Z")
    override fun currentTimeZone(): TimeZone = TimeZone.UTC
}

private class RecordingSyncCoreClient : OrgSyncCoreClient {
    val reported = mutableListOf<ClockResultPayload>()
    var started: Boolean = false
    var flushCount: Int = 0

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        started = false
    }

    override suspend fun flushNow() {
        flushCount += 1
    }

    override suspend fun observeIncomingCommands(): List<String> = emptyList()

    override suspend fun reportResult(result: ClockResultPayload) {
        reported += result
    }

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()

    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
}

private class SharedBackingCommandIdStore(
    private val ids: MutableSet<String>,
) : CommandIdStore {
    override fun contains(commandId: String): Boolean = ids.contains(commandId)

    override fun markProcessed(commandId: String) {
        ids.add(commandId)
    }
}

private class FakeClockRepository(
    val files: MutableMap<String, List<String>>,
    private val alwaysConflictOnSaveFile: Boolean = false,
    private val listFilesError: Throwable? = null,
) : ClockRepository {
    var saveFileCallCount: Int = 0

    override suspend fun listOrgFiles(): Result<List<OrgFileEntry>> {
        listFilesError?.let { return Result.failure(it) }
        return Result.success(
            files.keys.sorted().map { fileName ->
                OrgFileEntry(
                    fileId = fileName,
                    displayName = fileName,
                    modifiedAt = null,
                )
            },
        )
    }

    override suspend fun loadFile(fileId: String): Result<OrgDocument> {
        val lines = files[fileId] ?: return Result.failure(IllegalArgumentException("File not found: $fileId"))
        return Result.success(
            OrgDocument(
                date = LocalDate(2026, 3, 1),
                lines = lines,
                hash = hash(lines),
            ),
        )
    }

    override suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent,
    ): SaveResult {
        saveFileCallCount += 1
        val current = files[fileId] ?: return SaveResult.IoError("missing file")
        if (alwaysConflictOnSaveFile) {
            return SaveResult.Conflict("forced conflict")
        }
        if (hash(current) != expectedHash) {
            return SaveResult.Conflict("hash mismatch")
        }
        files[fileId] = lines
        return SaveResult.Success
    }

    override suspend fun loadDaily(date: LocalDate): Result<OrgDocument> {
        return Result.failure(UnsupportedOperationException("Not used in sync tests"))
    }

    override suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult {
        return SaveResult.IoError("Not used in sync tests")
    }

    private fun hash(lines: List<String>): String {
        val joined = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(joined)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
