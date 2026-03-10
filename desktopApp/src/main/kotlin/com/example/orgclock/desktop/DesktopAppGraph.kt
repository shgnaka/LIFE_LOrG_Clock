package com.example.orgclock.desktop

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.DesktopFileOrgRepository
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.domain.ClockService
import com.example.orgclock.domain.FileOperationCoordinator
import com.example.orgclock.domain.InMemoryFileOperationCoordinator
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.presentation.OrgClockPresentationState
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusText
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.today
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toJavaZoneId
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

data class DesktopMvpDependencies(
    val loadSavedRootReference: () -> RootReference?,
    val saveRootReference: (RootReference) -> Unit,
    val openRoot: suspend (RootReference) -> Result<Unit>,
    val listFiles: suspend () -> Result<List<com.example.orgclock.data.OrgFileEntry>>,
    val listFilesWithOpenClock: suspend () -> Result<Set<String>>,
    val listHeadings: suspend (String) -> Result<List<HeadingViewItem>>,
    val startClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    val stopClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    val cancelClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
    val listClosedClocks: suspend (String, HeadingPath) -> Result<List<ClosedClockEntry>>,
    val editClosedClock: suspend (String, HeadingPath, Int, java.time.ZonedDateTime, java.time.ZonedDateTime) -> Result<Unit>,
    val deleteClosedClock: suspend (String, HeadingPath, Int) -> Result<Unit>,
    val createL1Heading: suspend (String, String, Boolean) -> Result<Unit>,
    val createL2Heading: suspend (String, HeadingPath, String, Boolean) -> Result<Unit>,
    val nowProvider: () -> java.time.ZonedDateTime,
    val todayProvider: () -> java.time.LocalDate,
    val zoneIdProvider: () -> java.time.ZoneId,
)

data class DesktopHostSnapshot(
    val presentationState: OrgClockPresentationState,
    val files: List<com.example.orgclock.data.OrgFileEntry> = emptyList(),
    val filesWithOpenClock: Set<String> = emptySet(),
    val openClockCount: Int = 0,
    val fileFailureCount: Int = 0,
)

class DesktopAppGraph(
    private val settingsStore: DesktopSettingsStore = DesktopSettingsStore(),
    private val clockEnvironment: ClockEnvironment = DesktopSystemClockEnvironment,
    private val repositoryFactory: (Path) -> ClockRepository = ::DesktopFileOrgRepository,
    private val coordinatorFactory: () -> FileOperationCoordinator = ::InMemoryFileOperationCoordinator,
) {
    private var currentRootReference: RootReference? = null
    private var repository: ClockRepository? = null
    private var clockService: ClockService? = null
    private var openClockScanner: DesktopOpenClockScanner? = null
    private var status: UiStatus = defaultStatus()

    init {
        restoreSavedRoot()
    }

    fun dependencies(): DesktopMvpDependencies {
        return DesktopMvpDependencies(
            loadSavedRootReference = { settingsStore.load().lastRootReference },
            saveRootReference = { settingsStore.save(DesktopHostSettings(lastRootReference = it)) },
            openRoot = ::openRoot,
            listFiles = { repository?.listOrgFiles() ?: Result.failure(missingRootError()) },
            listFilesWithOpenClock = {
                openClockScanner
                    ?.scan(clockEnvironment.currentTimeZone())
                    ?.map { result -> result.entries.asSequence().map { it.fileId }.toSet() }
                    ?: Result.failure(missingRootError())
            },
            listHeadings = { fileId ->
                clockService?.listHeadings(fileId, clockEnvironment.currentTimeZone())
                    ?: Result.failure(missingRootError())
            },
            startClock = { fileId, headingPath ->
                clockService?.startClockInFile(fileId, headingPath, clockEnvironment.now(), clockEnvironment.currentTimeZone())
                    ?: Result.failure(missingRootError())
            },
            stopClock = { fileId, headingPath ->
                clockService?.stopClockInFile(fileId, headingPath, clockEnvironment.now(), clockEnvironment.currentTimeZone())
                    ?: Result.failure(missingRootError())
            },
            cancelClock = { fileId, headingPath ->
                clockService?.cancelClockInFile(fileId, headingPath)
                    ?: Result.failure(missingRootError())
            },
            listClosedClocks = { fileId, headingPath ->
                clockService?.listClosedClocksInFile(fileId, headingPath, clockEnvironment.currentTimeZone())
                    ?: Result.failure(missingRootError())
            },
            editClosedClock = { fileId, headingPath, lineIndex, start, end ->
                clockService?.editClosedClockInFile(
                    fileId = fileId,
                    headingPath = headingPath,
                    clockLineIndex = lineIndex,
                    newStart = Instant.fromEpochMilliseconds(start.toInstant().toEpochMilli()),
                    newEnd = Instant.fromEpochMilliseconds(end.toInstant().toEpochMilli()),
                    timeZone = clockEnvironment.currentTimeZone(),
                ) ?: Result.failure(missingRootError())
            },
            deleteClosedClock = { fileId, headingPath, lineIndex ->
                clockService?.deleteClosedClockInFile(fileId, headingPath, lineIndex)
                    ?: Result.failure(missingRootError())
            },
            createL1Heading = { fileId, title, attachTplTag ->
                clockService?.createL1HeadingInFile(fileId, title, attachTplTag)
                    ?: Result.failure(missingRootError())
            },
            createL2Heading = { fileId, parent, title, attachTplTag ->
                clockService?.createL2HeadingInFile(fileId, parent, title, attachTplTag)
                    ?: Result.failure(missingRootError())
            },
            nowProvider = { clockEnvironment.now().toJavaInstant().atZone(clockEnvironment.currentTimeZone().toJavaZoneId()) },
            todayProvider = {
                val today = clockEnvironment.today()
                java.time.LocalDate.of(today.year, today.monthNumber, today.dayOfMonth)
            },
            zoneIdProvider = { clockEnvironment.currentTimeZone().toJavaZoneId() },
        )
    }

    suspend fun openRoot(rootReference: RootReference): Result<Unit> = runCatching {
        attachRoot(rootReference, persist = true)
    }

    suspend fun snapshot(): DesktopHostSnapshot {
        val presentationState = OrgClockPresentationState(
            screen = if (currentRootReference == null) Screen.RootSetup else Screen.FilePicker,
            rootReference = currentRootReference,
            status = status,
        )
        val currentRepository = repository ?: return DesktopHostSnapshot(presentationState = presentationState)
        val files = currentRepository.listOrgFiles().getOrElse {
            return DesktopHostSnapshot(
                presentationState = presentationState.copy(
                    status = failureStatus(StatusMessageKey.FailedListingFiles, it.message ?: "unknown error"),
                ),
            )
        }
        val scanResult = openClockScanner?.scan(clockEnvironment.currentTimeZone())
        val openEntries = scanResult?.getOrNull()
        return DesktopHostSnapshot(
            presentationState = presentationState,
            files = files,
            filesWithOpenClock = openEntries?.entries?.asSequence()?.map { it.fileId }?.toSet().orEmpty(),
            openClockCount = openEntries?.entries?.size ?: 0,
            fileFailureCount = openEntries?.failedFiles?.size ?: 0,
        )
    }

    private fun restoreSavedRoot() {
        val savedRoot = settingsStore.load().lastRootReference ?: return
        runCatching {
            attachRoot(savedRoot, persist = false)
        }.onFailure { error ->
            settingsStore.clear()
            currentRootReference = null
            repository = null
            clockService = null
            openClockScanner = null
            status = failureStatus(StatusMessageKey.FailedOpenRoot, error.message ?: "unknown error")
        }
    }

    private fun attachRoot(rootReference: RootReference, persist: Boolean) {
        val normalized = normalizeRoot(rootReference)
        val repository = repositoryFactory(normalized)
        val clockService = ClockService(repository, fileOperationCoordinator = coordinatorFactory())
        currentRootReference = RootReference(normalized.toString())
        this.repository = repository
        this.clockService = clockService
        this.openClockScanner = DesktopOpenClockScanner(repository)
        status = UiStatus(
            text = StatusText(StatusMessageKey.RootSet),
            tone = StatusTone.Success,
        )
        if (persist) {
            settingsStore.save(DesktopHostSettings(lastRootReference = currentRootReference))
        }
    }

    private fun normalizeRoot(rootReference: RootReference): Path {
        val path = Path.of(rootReference.rawValue).absolute().normalize()
        require(path.exists() && path.isDirectory()) { "Selected root must be an existing directory" }
        return path
    }

    private fun missingRootError(): IllegalStateException =
        IllegalStateException("Desktop root is not opened")

    private fun failureStatus(key: StatusMessageKey, reason: String): UiStatus {
        return UiStatus(
            text = StatusText(key, listOf(reason)),
            tone = StatusTone.Error,
        )
    }

    private fun defaultStatus(): UiStatus {
        return UiStatus(
            text = StatusText(StatusMessageKey.SelectOrgDirectory),
            tone = StatusTone.Info,
        )
    }
}

private object DesktopSystemClockEnvironment : ClockEnvironment {
    override fun now(): Instant = Clock.System.now()

    override fun currentTimeZone(): TimeZone = TimeZone.currentSystemDefault()
}
