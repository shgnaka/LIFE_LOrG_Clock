package com.example.orgclock.di

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.example.orgclock.BuildConfig
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.data.RootAccessGateway
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.domain.ClockService
import com.example.orgclock.domain.InMemoryFileOperationCoordinator
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.notification.ClockInNotificationService
import com.example.orgclock.notification.ClockInScanResult
import com.example.orgclock.notification.ClockInScanner
import com.example.orgclock.notification.DefaultNotificationPermissionChecker
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.notification.NotificationServiceConfig
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.sync.BuildConfigSyncIntegrationFeatureFlag
import com.example.orgclock.sync.ClockEventStoreSnapshot
import com.example.orgclock.sync.ClockCommandKind
import com.example.orgclock.sync.PeerRegistrationRequest
import com.example.orgclock.sync.RoomClockEventStore
import com.example.orgclock.sync.DefaultClockCommandExecutor
import com.example.orgclock.sync.AndroidEventSyncRuntime
import com.example.orgclock.sync.AndroidEventSyncRuntimeEntryPoint
import com.example.orgclock.sync.AndroidEventSyncTransportProvider
import com.example.orgclock.sync.LocalClockOperationPublisher
import com.example.orgclock.sync.RuntimeSyncIntegrationFeatureFlag
import com.example.orgclock.sync.RoomCommandIdStore
import com.example.orgclock.sync.SharedPreferencesPeerSyncCheckpointStore
import com.example.orgclock.sync.SharedPreferencesClockEventSyncQuarantineStore
import com.example.orgclock.sync.SharedPreferencesDeviceIdProvider
import com.example.orgclock.sync.SharedPreferencesPeerTrustStore
import com.example.orgclock.sync.SharedPreferencesSyncRuntimePrefs
import com.example.orgclock.sync.StoreBackedClockEventRecorder
import com.example.orgclock.sync.SyncCoreClientFactory
import com.example.orgclock.sync.SyncIntegrationService
import com.example.orgclock.sync.SyncRuntimeEntryPoint
import com.example.orgclock.sync.SyncRuntimeManager
import com.example.orgclock.template.RootScheduleStore
import com.example.orgclock.template.SharedPrefsTemplateAutoGenerationFailureReporter
import com.example.orgclock.template.TemplateAutoGenerationEntryPoint
import com.example.orgclock.template.TemplateAutoGenerationRuntimeStore
import com.example.orgclock.template.TemplateAutoGenerationScheduler
import com.example.orgclock.template.TemplateSyncService
import androidx.lifecycle.lifecycleScope
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.SystemClockEnvironment
import com.example.orgclock.time.toJavaLocalDateCompat
import com.example.orgclock.time.toJavaZonedDateTime
import com.example.orgclock.time.toKotlinInstantCompat
import com.example.orgclock.time.today
import kotlinx.datetime.toJavaZoneId
import com.example.orgclock.ui.app.OrgClockRouteDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

interface AppGraph {
    fun routeDependencies(
        activity: ComponentActivity,
        notificationPermissionChecker: NotificationPermissionChecker,
    ): OrgClockRouteDependencies

    fun notificationServiceDependencies(): NotificationServiceDependencies

    fun syncIntegrationService(): SyncIntegrationService

    fun androidEventSyncRuntime(): AndroidEventSyncRuntime
}

internal data class PublishTarget(
    val fileName: String,
    val headingPath: String,
)

data class NotificationServiceDependencies(
    val openRoot: suspend (Uri) -> Result<Unit>,
    val scan: suspend () -> Result<ClockInScanResult>,
    val stopClock: suspend (String, HeadingPath) -> Result<ClockMutationResult>,
)

class DefaultAppGraph(
    private val appContext: Context,
    private val clockEnvironment: ClockEnvironment = SystemClockEnvironment,
) : AppGraph {
    private val safRepository by lazy { SafOrgRepository(appContext) }
    private val repository: ClockRepository by lazy { safRepository }
    private val rootAccessGateway: RootAccessGateway by lazy { safRepository }
    private val fileOperationCoordinator by lazy { InMemoryFileOperationCoordinator() }
    private val clockEventStore by lazy { RoomClockEventStore.create(appContext) }
    private val clockEventSyncSnapshotFlow = MutableStateFlow(ClockEventStoreSnapshot(null, null, 0))
    private val clockInScanner by lazy { ClockInScanner(repository) }
    private val notificationServiceConfig: NotificationServiceConfig = NotificationServiceConfig()
    private val syncCoreClientFactory: SyncCoreClientFactory by lazy {
        loadSyncCoreClientFactory()
    }
    private val prefs by lazy {
        appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val rootScheduleStore by lazy { RootScheduleStore(prefs) }
    private val templateFailureReporter by lazy {
        SharedPrefsTemplateAutoGenerationFailureReporter(
            appContext = appContext,
            permissionChecker = DefaultNotificationPermissionChecker(),
        )
    }
    private val templateRuntimeStore by lazy {
        TemplateAutoGenerationRuntimeStore(prefs)
    }
    private val templateSyncService by lazy {
        TemplateSyncService(
            repository = safRepository,
            templateFileUriProvider = {
                prefs.getString(NotificationPrefs.KEY_ROOT_URI, null)
                    ?.let { rootScheduleStore.load(it).templateFileUri }
            },
        )
    }
    private val templateAutoGenerationScheduler by lazy {
        TemplateAutoGenerationScheduler(
            appContext = appContext,
            scheduleStore = rootScheduleStore,
            failureReporter = templateFailureReporter,
            runtimeStore = templateRuntimeStore,
        )
    }
    private val runtimePrefs by lazy {
        SharedPreferencesSyncRuntimePrefs(
            prefs,
        )
    }
    private val deviceIdProvider by lazy {
        SharedPreferencesDeviceIdProvider(
            appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    private val peerSyncCheckpointStore by lazy {
        SharedPreferencesPeerSyncCheckpointStore(prefs)
    }
    private val clockEventSyncQuarantineStore by lazy {
        SharedPreferencesClockEventSyncQuarantineStore(prefs)
    }
    private val androidEventSyncRuntime: AndroidEventSyncRuntime by lazy {
        AndroidEventSyncRuntime(
            clockEventStore = clockEventStore,
            peerTrustStore = SharedPreferencesPeerTrustStore(prefs),
            peerSyncCheckpointStore = peerSyncCheckpointStore,
            quarantineStore = clockEventSyncQuarantineStore,
            deviceIdProvider = deviceIdProvider,
            snapshotPublisher = { clockEventSyncSnapshotFlow.value = it },
            transportProvider = AndroidEventSyncTransportProvider { null },
        )
    }
    private val clockEventRecorder by lazy {
        StoreBackedClockEventRecorder(
            store = clockEventStore,
            deviceIdProvider = deviceIdProvider::getOrCreate,
            snapshotPublisher = { clockEventSyncSnapshotFlow.value = it },
        )
    }
    private val clockService by lazy {
        ClockService(
            repository,
            fileOperationCoordinator = fileOperationCoordinator,
            clockEventRecorder = clockEventRecorder,
        )
    }
    private val syncIntegrationService: SyncIntegrationService by lazy {
        val commandIdStore = RoomCommandIdStore.create(appContext)
        val orgSyncCoreClient = syncCoreClientFactory.create(
            appContext = appContext,
            repository = repository,
            clockService = clockService,
            clockEnvironment = clockEnvironment,
        )
        val commandExecutor = DefaultClockCommandExecutor(
            repository = repository,
            clockService = clockService,
            commandIdStore = commandIdStore,
            deviceIdProvider = deviceIdProvider,
            clockEnvironment = clockEnvironment,
        )
        val runtimeManager = SyncRuntimeManager(
            appContext = appContext,
            runtimeController = com.example.orgclock.sync.DefaultSyncRuntimeController(orgSyncCoreClient),
        )
        SyncIntegrationService(
            featureFlag = RuntimeSyncIntegrationFeatureFlag(
                buildConfigFlag = BuildConfigSyncIntegrationFeatureFlag(),
                runtimePrefs = runtimePrefs,
            ),
            syncCoreClient = orgSyncCoreClient,
            commandExecutor = commandExecutor,
            deviceIdProvider = deviceIdProvider,
            runtimePrefs = runtimePrefs,
            peerTrustStore = SharedPreferencesPeerTrustStore(prefs),
            clockEventStoreProvider = { clockEventStore },
            peerSyncCheckpointStore = peerSyncCheckpointStore,
            runtimeManager = runtimeManager,
        )
    }

    override fun routeDependencies(
        activity: ComponentActivity,
        notificationPermissionChecker: NotificationPermissionChecker,
    ): OrgClockRouteDependencies {
        ClockInNotificationService.clockEnvironmentFactory = { clockEnvironment }
        TemplateAutoGenerationEntryPoint.scheduler = templateAutoGenerationScheduler
        val localPublisher = LocalClockOperationPublisher(
            syncIntegrationService = syncIntegrationService,
            deviceIdProvider = deviceIdProvider,
            runtimePrefs = runtimePrefs,
        )
        val launchClockPublish: (ClockCommandKind, String, String) -> Unit = { kind, fileName, headingPath ->
            activity.lifecycleScope.launch {
                localPublisher.publish(kind, fileName, headingPath)
            }
        }

        return OrgClockRouteDependencies(
            loadSavedRootReference = { prefs.getString(NotificationPrefs.KEY_ROOT_URI, null)?.let(::RootReference) },
            saveSavedRootReference = { rootReference ->
                prefs.edit().putString(NotificationPrefs.KEY_ROOT_URI, rootReference.rawValue).apply()
            },
            openRoot = { rootReference -> rootAccessGateway.openRoot(rootReference) },
            listFiles = { repository.listOrgFiles() },
            listTemplateCandidateFiles = { repository.listTemplateCandidateFiles() },
            listFilesWithOpenClock = {
                clockInScanner.scan(clockEnvironment.currentTimeZone()).map { scanResult ->
                    scanResult.entries.asSequence().map { it.fileId }.toSet()
                }
            },
            listHeadings = { fileId -> clockService.listHeadings(fileId, clockEnvironment.currentTimeZone()) },
            startClock = { fileId, headingPath ->
                val result = withContext(Dispatchers.IO) {
                    clockService.startClockInFile(
                        fileId,
                        headingPath,
                        clockEnvironment.now(),
                        clockEnvironment.currentTimeZone(),
                    )
                }
                publishIfSaved(
                    result = result,
                    kind = ClockCommandKind.Start,
                    fileId = fileId,
                    headingPath = headingPath,
                    launchPublish = launchClockPublish,
                )
            },
            stopClock = { fileId, headingPath ->
                val result = withContext(Dispatchers.IO) {
                    clockService.stopClockInFile(
                        fileId,
                        headingPath,
                        clockEnvironment.now(),
                        clockEnvironment.currentTimeZone(),
                    )
                }
                publishIfSaved(
                    result = result,
                    kind = ClockCommandKind.Stop,
                    fileId = fileId,
                    headingPath = headingPath,
                    launchPublish = launchClockPublish,
                )
            },
            cancelClock = { fileId, headingPath ->
                val result = withContext(Dispatchers.IO) {
                    clockService.cancelClockInFile(fileId, headingPath)
                }
                publishIfSaved(
                    result = result,
                    kind = ClockCommandKind.Cancel,
                    fileId = fileId,
                    headingPath = headingPath,
                    launchPublish = launchClockPublish,
                )
            },
            listClosedClocks = { fileId, headingPath ->
                clockService.listClosedClocksInFile(fileId, headingPath, clockEnvironment.currentTimeZone())
            },
            editClosedClock = { fileId, headingPath, clockLineIndex, start, end ->
                clockService.editClosedClockInFile(
                    fileId,
                    headingPath,
                    clockLineIndex,
                    start.toKotlinInstantCompat(),
                    end.toKotlinInstantCompat(),
                    clockEnvironment.currentTimeZone(),
                )
            },
            deleteClosedClock = { fileId, headingPath, clockLineIndex ->
                clockService.deleteClosedClockInFile(fileId, headingPath, clockLineIndex)
            },
            createL1Heading = { fileId, title, attachTplTag ->
                clockService.createL1HeadingInFile(fileId, title, attachTplTag)
            },
            createL2Heading = { fileId, parentL1Path, title, attachTplTag ->
                clockService.createL2HeadingInFile(fileId, parentL1Path, title, attachTplTag)
            },
            loadNotificationEnabled = { prefs.getBoolean(NotificationPrefs.KEY_ENABLED, true) },
            saveNotificationEnabled = { enabled ->
                prefs.edit().putBoolean(NotificationPrefs.KEY_ENABLED, enabled).apply()
            },
            loadNotificationDisplayMode = {
                NotificationDisplayMode.fromStorage(
                    prefs.getString(NotificationPrefs.KEY_DISPLAY_MODE, null),
                )
            },
            saveNotificationDisplayMode = { mode ->
                prefs.edit().putString(NotificationPrefs.KEY_DISPLAY_MODE, mode.storageValue).apply()
            },
            notificationPermissionGrantedProvider = {
                notificationPermissionChecker.isGranted(activity)
            },
            loadRootScheduleConfig = { rootReference ->
                rootScheduleStore.load(rootReference.rawValue)
            },
            loadTemplateFileStatus = { config ->
                safRepository.inspectTemplateFile(config.templateFileUri).getOrThrow()
            },
            loadTemplateAutoGenerationFailure = { rootReference ->
                templateFailureReporter.loadLastFailure(rootReference.rawValue)
            },
            loadAutoGenerationRuntimeState = { rootReference ->
                templateAutoGenerationScheduler.loadRuntimeState(Uri.parse(rootReference.rawValue))
            },
            saveRootScheduleConfig = { config ->
                rootScheduleStore.save(config)
            },
            syncRootScheduleConfig = { config ->
                templateAutoGenerationScheduler.sync(Uri.parse(config.rootUri), config)
            },
            runAutoGenerationCatchUp = { rootReference ->
                templateAutoGenerationScheduler.runCatchUpIfDue(Uri.parse(rootReference.rawValue))
            },
            syncTemplateTaggedHeading = { fileId ->
                templateSyncService.syncFromFile(fileId)
            },
            syncNotificationService = { enabled, mode ->
                ClockInNotificationService.sync(
                    context = activity,
                    enabled = enabled,
                    displayMode = mode,
                    config = notificationServiceConfig,
                )
            },
            stopNotificationService = {
                ClockInNotificationService.stop(activity)
            },
            openAppNotificationSettings = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                }
                activity.startActivity(intent)
            },
            syncSnapshotFlow = syncIntegrationService.snapshot,
            clockEventSyncSnapshotFlow = clockEventSyncSnapshotFlow,
            syncEnableStandardMode = {
                syncIntegrationService.enableStandardMode()
            },
            syncEnableActiveMode = {
                syncIntegrationService.enableActiveMode()
            },
            syncStopRuntime = {
                syncIntegrationService.stopRuntime()
            },
            syncFlushNow = {
                syncIntegrationService.flushNow()
            },
            syncSetEnabled = { enabled ->
                syncIntegrationService.setRuntimeEnabled(enabled)
            },
            syncSetDefaultPeerId = { peerId ->
                syncIntegrationService.setDefaultPeerId(peerId)
            },
            syncListTrustedPeers = {
                syncIntegrationService.listTrustedPeers()
            },
            syncAddTrustedPeer = { peerId ->
                syncIntegrationService.addTrustedPeer(peerId)
            },
            syncPairTrustedPeer = { request: PeerRegistrationRequest ->
                syncIntegrationService.pairTrustedPeer(request)
            },
            syncRevokePeer = { peerId ->
                syncIntegrationService.revokePeer(peerId)
            },
            syncProbePeer = { peerId ->
                syncIntegrationService.probePeer(peerId)
            },
            syncFeatureEnabled = BuildConfig.SYNC_CORE_INCLUDED,
            syncDebugEnabled = BuildConfig.DEBUG && BuildConfig.SYNC_CORE_INCLUDED,
            nowProvider = { clockEnvironment.now().toJavaZonedDateTime(clockEnvironment.currentTimeZone().toJavaZoneId()) },
            todayProvider = { clockEnvironment.today().toJavaLocalDateCompat() },
            zoneIdProvider = { clockEnvironment.currentTimeZone().toJavaZoneId() },
            showPerfOverlay = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }

    override fun syncIntegrationService(): SyncIntegrationService {
        SyncRuntimeEntryPoint.syncIntegrationService = syncIntegrationService
        TemplateAutoGenerationEntryPoint.scheduler = templateAutoGenerationScheduler
        return syncIntegrationService
    }

    override fun androidEventSyncRuntime(): AndroidEventSyncRuntime {
        AndroidEventSyncRuntimeEntryPoint.runtime = androidEventSyncRuntime
        return androidEventSyncRuntime
    }

    override fun notificationServiceDependencies(): NotificationServiceDependencies {
        ClockInNotificationService.clockEnvironmentFactory = { clockEnvironment }
        return NotificationServiceDependencies(
            openRoot = { uri -> rootAccessGateway.openRoot(RootReference(uri.toString())) },
            scan = { clockInScanner.scan(clockEnvironment.currentTimeZone()) },
            stopClock = { fileId, headingPath ->
                clockService.stopClockInFile(
                    fileId,
                    headingPath,
                    clockEnvironment.now(),
                    clockEnvironment.currentTimeZone(),
                )
            },
        )
    }

    private suspend fun publishIfSaved(
        result: Result<ClockMutationResult>,
        kind: ClockCommandKind,
        fileId: String,
        headingPath: HeadingPath,
        launchPublish: (ClockCommandKind, String, String) -> Unit,
    ): Result<ClockMutationResult> {
        if (result.isFailure) {
            return result
        }
        val target = resolvePublishTarget(fileId, headingPath) ?: return result
        return scheduleSyncPublishAfterLocalSave(
            result = result,
            kind = kind,
            fileName = target.fileName,
            headingPath = target.headingPath,
            launchPublish = launchPublish,
        )
    }

    private suspend fun resolvePublishTarget(fileId: String, headingPath: HeadingPath): PublishTarget? {
        val files = repository.listOrgFiles().getOrElse {
            syncIntegrationService.markSyncError("file lookup failed: ${it.message ?: "unknown"}")
            return null
        }
        val target = resolvePublishTarget(fileId, headingPath, files)
        if (target == null) {
            syncIntegrationService.markSyncError("unknown file id: $fileId")
        }
        return target
    }

    private fun loadSyncCoreClientFactory(): SyncCoreClientFactory {
        if (!BuildConfig.SYNC_CORE_INCLUDED) {
            return com.example.orgclock.sync.NoOpSyncCoreClientFactory()
        }
        return runCatching {
            val clazz = Class.forName("com.example.orgclock.sync.SynccoreEngineClientFactory")
            clazz.getDeclaredConstructor().newInstance() as SyncCoreClientFactory
        }.getOrElse {
            com.example.orgclock.sync.NoOpSyncCoreClientFactory()
        }
    }
}

internal fun scheduleSyncPublishAfterLocalSave(
    result: Result<ClockMutationResult>,
    kind: ClockCommandKind,
    fileName: String,
    headingPath: String,
    launchPublish: (ClockCommandKind, String, String) -> Unit,
): Result<ClockMutationResult> {
    if (result.isSuccess) {
        launchPublish(kind, fileName, headingPath)
    }
    return result
}

internal fun resolvePublishTarget(
    fileId: String,
    headingPath: HeadingPath,
    files: List<OrgFileEntry>,
): PublishTarget? {
    val fileName = files.firstOrNull { it.fileId == fileId }?.displayName ?: return null
    return PublishTarget(
        fileName = fileName,
        headingPath = headingPath.toString(),
    )
}
