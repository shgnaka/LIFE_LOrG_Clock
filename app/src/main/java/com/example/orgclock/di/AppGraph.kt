package com.example.orgclock.di

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.example.orgclock.BuildConfig
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.data.RootAccessGateway
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.domain.ClockService
import com.example.orgclock.notification.ClockInNotificationService
import com.example.orgclock.notification.ClockInScanner
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.notification.NotificationServiceConfig
import com.example.orgclock.sync.BuildConfigSyncIntegrationFeatureFlag
import com.example.orgclock.sync.ClockCommandKind
import com.example.orgclock.sync.DefaultClockCommandExecutor
import com.example.orgclock.sync.LocalClockOperationPublisher
import com.example.orgclock.sync.RuntimeSyncIntegrationFeatureFlag
import com.example.orgclock.sync.RoomCommandIdStore
import com.example.orgclock.sync.SharedPreferencesDeviceIdProvider
import com.example.orgclock.sync.SharedPreferencesPeerTrustStore
import com.example.orgclock.sync.SharedPreferencesSyncRuntimePrefs
import com.example.orgclock.sync.SyncCoreClientFactory
import com.example.orgclock.sync.SyncIntegrationService
import com.example.orgclock.sync.SyncRuntimeEntryPoint
import com.example.orgclock.sync.SyncRuntimeManager
import androidx.lifecycle.lifecycleScope
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.SystemClockEnvironment
import com.example.orgclock.time.toJavaLocalDateCompat
import com.example.orgclock.time.toJavaZonedDateTime
import com.example.orgclock.time.toKotlinInstantCompat
import com.example.orgclock.time.today
import kotlinx.datetime.toJavaZoneId
import com.example.orgclock.ui.app.OrgClockRouteDependencies
import kotlinx.coroutines.launch

interface AppGraph {
    fun routeDependencies(
        activity: ComponentActivity,
        notificationPermissionChecker: NotificationPermissionChecker,
    ): OrgClockRouteDependencies

    fun syncIntegrationService(): SyncIntegrationService
}

class DefaultAppGraph(
    private val appContext: Context,
    private val clockEnvironment: ClockEnvironment = SystemClockEnvironment,
) : AppGraph {
    private val safRepository by lazy { SafOrgRepository(appContext) }
    private val repository: ClockRepository by lazy { safRepository }
    private val rootAccessGateway: RootAccessGateway by lazy { safRepository }
    private val clockService by lazy { ClockService(repository) }
    private val clockInScanner by lazy { ClockInScanner(repository) }
    private val notificationServiceConfig: NotificationServiceConfig = NotificationServiceConfig()
    private val syncCoreClientFactory: SyncCoreClientFactory by lazy {
        loadSyncCoreClientFactory()
    }
    private val runtimePrefs by lazy {
        SharedPreferencesSyncRuntimePrefs(
            appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    private val deviceIdProvider by lazy {
        SharedPreferencesDeviceIdProvider(
            appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
        )
    }
    private val syncIntegrationService: SyncIntegrationService by lazy {
        val prefs = appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
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
            runtimeManager = runtimeManager,
        )
    }

    override fun routeDependencies(
        activity: ComponentActivity,
        notificationPermissionChecker: NotificationPermissionChecker,
    ): OrgClockRouteDependencies {
        val prefs = activity.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        ClockInNotificationService.clockEnvironmentFactory = { clockEnvironment }
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
            loadSavedUri = { prefs.getString(NotificationPrefs.KEY_ROOT_URI, null)?.let(Uri::parse) },
            saveUri = { uri -> prefs.edit().putString(NotificationPrefs.KEY_ROOT_URI, uri.toString()).apply() },
            openRoot = { uri -> rootAccessGateway.openRoot(uri) },
            listFiles = { repository.listOrgFiles() },
            listFilesWithOpenClock = {
                clockInScanner.scan(clockEnvironment.currentTimeZone()).map { scanResult ->
                    scanResult.entries.asSequence().map { it.fileId }.toSet()
                }
            },
            listHeadings = { fileId -> clockService.listHeadings(fileId, clockEnvironment.currentTimeZone()) },
            startClock = { fileId, headingPath ->
                val result = clockService.startClockInFile(
                    fileId,
                    headingPath,
                    clockEnvironment.now(),
                    clockEnvironment.currentTimeZone(),
                )
                launchPublishIfSaved(
                    result = result,
                    kind = ClockCommandKind.Start,
                    fileId = fileId,
                    headingPath = headingPath.toString(),
                    launchPublish = launchClockPublish,
                )
            },
            stopClock = { fileId, headingPath ->
                val result = clockService.stopClockInFile(
                    fileId,
                    headingPath,
                    clockEnvironment.now(),
                    clockEnvironment.currentTimeZone(),
                )
                launchPublishIfSaved(
                    result = result,
                    kind = ClockCommandKind.Stop,
                    fileId = fileId,
                    headingPath = headingPath.toString(),
                    launchPublish = launchClockPublish,
                )
            },
            cancelClock = { fileId, headingPath ->
                val result = clockService.cancelClockInFile(fileId, headingPath)
                launchPublishIfSaved(
                    result = result,
                    kind = ClockCommandKind.Cancel,
                    fileId = fileId,
                    headingPath = headingPath.toString(),
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
        return syncIntegrationService
    }

    private suspend fun launchPublishIfSaved(
        result: Result<ClockMutationResult>,
        kind: ClockCommandKind,
        fileId: String,
        headingPath: String,
        launchPublish: (ClockCommandKind, String, String) -> Unit,
    ): Result<ClockMutationResult> {
        if (result.isFailure) {
            return result
        }
        val fileName = resolveFileNameForPublish(fileId) ?: return result
        return scheduleSyncPublishAfterLocalSave(
            result = result,
            kind = kind,
            fileName = fileName,
            headingPath = headingPath,
            launchPublish = launchPublish,
        )
    }

    private suspend fun resolveFileNameForPublish(fileId: String): String? {
        val files = repository.listOrgFiles().getOrElse {
            syncIntegrationService.markSyncError("file lookup failed: ${it.message ?: "unknown"}")
            return null
        }
        val fileName = files.firstOrNull { it.fileId == fileId }?.displayName
        if (fileName == null) {
            syncIntegrationService.markSyncError("unknown file id: $fileId")
        }
        return fileName
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
