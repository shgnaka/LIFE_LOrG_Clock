package com.example.orgclock.desktop

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.DesktopFileOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.domain.FileOperationCoordinator
import com.example.orgclock.domain.InMemoryFileOperationCoordinator
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.sync.SyncIntegrationSnapshot
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.today
import com.example.orgclock.ui.store.OrgClockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class DesktopAppGraph(
    private val settingsStore: DesktopSettingsStore = DesktopSettingsStore(),
    private val clockEnvironment: ClockEnvironment = DesktopSystemClockEnvironment,
    private val repositoryFactory: (Path) -> ClockRepository = ::DesktopFileOrgRepository,
    private val coordinatorFactory: () -> FileOperationCoordinator = ::InMemoryFileOperationCoordinator,
) {
    private var currentRootPath: Path? = null
    private var repository: ClockRepository? = null
    private var clockService: ClockService? = null
    private var openClockScanner: DesktopOpenClockScanner? = null

    fun createStore(scope: CoroutineScope): OrgClockStore {
        return OrgClockStore(
            scope = scope,
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
                    newStart = start,
                    newEnd = end,
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
            loadNotificationEnabled = { false },
            saveNotificationEnabled = { },
            loadNotificationDisplayMode = { NotificationDisplayMode.ActiveOnly },
            saveNotificationDisplayMode = { },
            notificationPermissionGrantedProvider = { false },
            syncSnapshotFlow = disabledSyncSnapshotFlow,
            nowProvider = { clockEnvironment.now() },
            todayProvider = { clockEnvironment.today() },
            timeZoneProvider = { clockEnvironment.currentTimeZone() },
            showPerfOverlay = false,
        )
    }

    fun currentRootReference(): RootReference? = currentRootPath?.let { RootReference(it.toString()) }

    private suspend fun openRoot(rootReference: RootReference): Result<Unit> = runCatching {
        attachRoot(rootReference)
    }

    private fun attachRoot(rootReference: RootReference) {
        val normalized = normalizeRoot(rootReference)
        val repository = repositoryFactory(normalized)
        val clockService = ClockService(repository, fileOperationCoordinator = coordinatorFactory())
        currentRootPath = normalized
        this.repository = repository
        this.clockService = clockService
        this.openClockScanner = DesktopOpenClockScanner(repository)
    }

    private fun normalizeRoot(rootReference: RootReference): Path {
        val path = Path.of(rootReference.rawValue).absolute().normalize()
        require(path.exists() && path.isDirectory()) { "Selected root must be an existing directory" }
        return path
    }

    private fun missingRootError(): IllegalStateException =
        IllegalStateException("Desktop root is not opened")

    private companion object {
        val disabledSyncSnapshotFlow: StateFlow<SyncIntegrationSnapshot> =
            MutableStateFlow(SyncIntegrationSnapshot())
    }
}

private object DesktopSystemClockEnvironment : ClockEnvironment {
    override fun now(): Instant = Clock.System.now()

    override fun currentTimeZone(): TimeZone = TimeZone.currentSystemDefault()
}
