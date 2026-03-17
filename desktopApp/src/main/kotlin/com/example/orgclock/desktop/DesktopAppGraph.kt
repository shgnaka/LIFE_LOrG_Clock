package com.example.orgclock.desktop

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.DesktopFileOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.domain.FileOperationCoordinator
import com.example.orgclock.domain.InMemoryFileOperationCoordinator
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.sync.SyncIntegrationSnapshot
import com.example.orgclock.template.RootScheduleConfig
import com.example.orgclock.template.TemplateAutoGenerationRuntimeState
import com.example.orgclock.template.TemplateAvailability
import com.example.orgclock.template.TemplateFileStatus
import com.example.orgclock.template.TemplateReferenceMode
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.today
import com.example.orgclock.ui.store.OrgClockStore
import com.example.orgclock.ui.state.ExternalChangeNotice
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
import kotlin.io.path.isReadable
import kotlin.io.path.name

class DesktopAppGraph(
    private val settingsStore: DesktopSettingsStore = DesktopSettingsStore(),
    private val rootScheduleStore: DesktopRootScheduleStore = DesktopRootScheduleStore(),
    private val clockEnvironment: ClockEnvironment = DesktopSystemClockEnvironment,
    private val repositoryFactory: (Path) -> ClockRepository = ::DesktopFileOrgRepository,
    private val coordinatorFactory: () -> FileOperationCoordinator = ::InMemoryFileOperationCoordinator,
    private val watchRootChanges: Boolean = true,
) {
    private var currentRootPath: Path? = null
    private var repository: ClockRepository? = null
    private var clockService: ClockService? = null
    private var openClockScanner: DesktopOpenClockScanner? = null
    private var rootWatcher: DesktopRootWatcher? = null
    private var scope: CoroutineScope? = null
    private val externalChangeFlow = MutableStateFlow<ExternalChangeNotice?>(null)
    private var externalChangeRevision = 0L

    fun createStore(scope: CoroutineScope): OrgClockStore {
        this.scope = scope
        val listFiles: suspend () -> Result<List<com.example.orgclock.data.OrgFileEntry>> = {
            repository?.listOrgFiles() ?: Result.failure(missingRootError())
        }
        val listFilesWithOpenClock: suspend () -> Result<Set<String>> = {
            openClockScanner
                ?.scan(clockEnvironment.currentTimeZone())
                ?.map { result -> result.entries.asSequence().map { it.fileId }.toSet() }
                ?: Result.failure(missingRootError())
        }
        val listHeadings: suspend (String) -> Result<List<com.example.orgclock.model.HeadingViewItem>> = { fileId ->
            clockService?.listHeadings(fileId, clockEnvironment.currentTimeZone())
                ?: Result.failure(missingRootError())
        }
        val startClock: suspend (String, com.example.orgclock.model.HeadingPath) -> Result<com.example.orgclock.domain.ClockMutationResult> = { fileId, headingPath ->
            clockService?.startClockInFile(fileId, headingPath, clockEnvironment.now(), clockEnvironment.currentTimeZone())
                ?: Result.failure(missingRootError())
        }
        val stopClock: suspend (String, com.example.orgclock.model.HeadingPath) -> Result<com.example.orgclock.domain.ClockMutationResult> = { fileId, headingPath ->
            clockService?.stopClockInFile(fileId, headingPath, clockEnvironment.now(), clockEnvironment.currentTimeZone())
                ?: Result.failure(missingRootError())
        }
        val cancelClock: suspend (String, com.example.orgclock.model.HeadingPath) -> Result<com.example.orgclock.domain.ClockMutationResult> = { fileId, headingPath ->
            clockService?.cancelClockInFile(fileId, headingPath)
                ?: Result.failure(missingRootError())
        }
        val listClosedClocks: suspend (String, com.example.orgclock.model.HeadingPath) -> Result<List<com.example.orgclock.model.ClosedClockEntry>> = { fileId, headingPath ->
            clockService?.listClosedClocksInFile(fileId, headingPath, clockEnvironment.currentTimeZone())
                ?: Result.failure(missingRootError())
        }
        val editClosedClock: suspend (String, com.example.orgclock.model.HeadingPath, Int, Instant, Instant) -> Result<Unit> = { fileId, headingPath, lineIndex, start, end ->
            clockService?.editClosedClockInFile(
                fileId = fileId,
                headingPath = headingPath,
                clockLineIndex = lineIndex,
                newStart = start,
                newEnd = end,
                timeZone = clockEnvironment.currentTimeZone(),
            ) ?: Result.failure(missingRootError())
        }
        val deleteClosedClock: suspend (String, com.example.orgclock.model.HeadingPath, Int) -> Result<Unit> = { fileId, headingPath, lineIndex ->
            clockService?.deleteClosedClockInFile(fileId, headingPath, lineIndex)
                ?: Result.failure(missingRootError())
        }
        val createL1Heading: suspend (String, String, Boolean) -> Result<Unit> = { fileId, title, attachTplTag ->
            clockService?.createL1HeadingInFile(fileId, title, attachTplTag)
                ?: Result.failure(missingRootError())
        }
        val createL2Heading: suspend (String, com.example.orgclock.model.HeadingPath, String, Boolean) -> Result<Unit> = { fileId, parent, title, attachTplTag ->
            clockService?.createL2HeadingInFile(fileId, parent, title, attachTplTag)
                ?: Result.failure(missingRootError())
        }

        return OrgClockStore(
            scope = scope,
            loadSavedRootReference = { settingsStore.load().lastRootReference },
            saveRootReference = { settingsStore.save(DesktopHostSettings(lastRootReference = it)) },
            clearSavedRootReference = { settingsStore.clear() },
            openRoot = ::openRoot,
            listFiles = listFiles,
            listTemplateCandidateFiles = { repository?.listTemplateCandidateFiles() ?: Result.failure(missingRootError()) },
            listFilesWithOpenClock = listFilesWithOpenClock,
            listHeadings = listHeadings,
            startClock = startClock,
            stopClock = stopClock,
            cancelClock = cancelClock,
            listClosedClocks = listClosedClocks,
            editClosedClock = editClosedClock,
            deleteClosedClock = deleteClosedClock,
            createL1Heading = createL1Heading,
            createL2Heading = createL2Heading,
            loadNotificationEnabled = { false },
            saveNotificationEnabled = { },
            loadNotificationDisplayMode = { NotificationDisplayMode.ActiveOnly },
            saveNotificationDisplayMode = { },
            notificationPermissionGrantedProvider = { false },
            loadRootScheduleConfig = { rootReference -> rootScheduleStore.load(rootReference.rawValue) },
            loadTemplateFileStatus = { config -> loadDesktopTemplateFileStatus(config) },
            loadTemplateAutoGenerationFailure = { null },
            loadAutoGenerationRuntimeState = { TemplateAutoGenerationRuntimeState() },
            saveRootScheduleConfig = { config -> rootScheduleStore.save(config) },
            syncRootScheduleConfig = { config -> rootScheduleStore.save(config) },
            runAutoGenerationCatchUp = {},
            createDefaultTemplateFileAction = { rootReference -> createDefaultTemplateFile(rootReference) },
            externalChangeFlow = if (watchRootChanges) externalChangeFlow else OrgClockStore.NO_EXTERNAL_CHANGE_FLOW,
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
        rootWatcher?.stop()
        scope?.takeIf { watchRootChanges }?.let { scope ->
            rootWatcher = DesktopRootWatcher(
                rootPath = normalized,
                scope = scope,
                onChange = { notice ->
                    externalChangeFlow.value = notice.copy(revision = ++externalChangeRevision)
                },
            ).also { it.start() }
        }
    }

    private fun normalizeRoot(rootReference: RootReference): Path {
        val path = Path.of(rootReference.rawValue).absolute().normalize()
        require(path.exists() && path.isDirectory()) { "Selected root must be an existing directory" }
        return path
    }

    private fun loadDesktopTemplateFileStatus(config: RootScheduleConfig): TemplateFileStatus {
        val rootPath = runCatching { normalizeRoot(RootReference(config.rootUri)) }.getOrNull()
        val explicitPath = config.templateFileUri?.let { raw ->
            runCatching { Path.of(raw).absolute().normalize() }.getOrNull()
        }
        val referenceMode = if (explicitPath != null) {
            TemplateReferenceMode.Explicit
        } else {
            TemplateReferenceMode.LegacyHiddenFile
        }
        val candidate = explicitPath ?: rootPath?.resolve(TEMPLATE_FILE_NAME)
        val displayName = explicitPath?.name ?: TEMPLATE_FILE_NAME

        if (candidate == null || !candidate.exists()) {
            return TemplateFileStatus(
                availability = TemplateAvailability.Missing,
                referenceMode = referenceMode,
                fileId = explicitPath?.toString(),
                displayName = displayName,
            )
        }
        if (!candidate.isReadable()) {
            return TemplateFileStatus(
                availability = TemplateAvailability.Unreadable,
                referenceMode = referenceMode,
                fileId = candidate.toString(),
                displayName = displayName,
                detailMessage = "Template file is not readable",
            )
        }
        return TemplateFileStatus(
            availability = TemplateAvailability.Available,
            referenceMode = referenceMode,
            fileId = candidate.toString(),
            displayName = displayName,
        )
    }

    private fun createDefaultTemplateFile(rootReference: RootReference): Result<String> = runCatching {
        attachRoot(rootReference)
        val desktopRepository = repository as? DesktopFileOrgRepository
            ?: error("Desktop template creation requires DesktopFileOrgRepository")
        desktopRepository.createDefaultTemplateFile().getOrThrow().fileId
    }

    private fun missingRootError(): IllegalStateException =
        IllegalStateException("Desktop root is not opened")

    private companion object {
        const val TEMPLATE_FILE_NAME = ".orgclock-template.org"
        val disabledSyncSnapshotFlow: StateFlow<SyncIntegrationSnapshot> =
            MutableStateFlow(SyncIntegrationSnapshot())
    }
}

private object DesktopSystemClockEnvironment : ClockEnvironment {
    override fun now(): Instant = Clock.System.now()

    override fun currentTimeZone(): TimeZone = TimeZone.currentSystemDefault()
}
