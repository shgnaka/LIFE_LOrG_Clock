package com.example.orgclock.di

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.notification.ClockInNotificationService
import com.example.orgclock.notification.ClockInScanner
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.notification.NotificationServiceConfig
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.SystemClockEnvironment
import com.example.orgclock.time.today
import com.example.orgclock.ui.app.OrgClockRouteDependencies

interface AppGraph {
    fun routeDependencies(
        activity: ComponentActivity,
        notificationPermissionChecker: NotificationPermissionChecker,
    ): OrgClockRouteDependencies
}

class DefaultAppGraph(
    private val appContext: Context,
    private val clockEnvironment: ClockEnvironment = SystemClockEnvironment,
) : AppGraph {
    private val repository by lazy { SafOrgRepository(appContext) }
    private val clockService by lazy { ClockService(repository) }
    private val clockInScanner by lazy { ClockInScanner(repository) }
    private val notificationServiceConfig: NotificationServiceConfig = NotificationServiceConfig()

    override fun routeDependencies(
        activity: ComponentActivity,
        notificationPermissionChecker: NotificationPermissionChecker,
    ): OrgClockRouteDependencies {
        val prefs = activity.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        ClockInNotificationService.clockEnvironmentFactory = { clockEnvironment }

        return OrgClockRouteDependencies(
            loadSavedUri = { prefs.getString(NotificationPrefs.KEY_ROOT_URI, null)?.let(Uri::parse) },
            saveUri = { uri -> prefs.edit().putString(NotificationPrefs.KEY_ROOT_URI, uri.toString()).apply() },
            openRoot = { uri -> repository.openRoot(uri) },
            listFiles = { repository.listOrgFiles() },
            listFilesWithOpenClock = {
                clockInScanner.scan(clockEnvironment.zoneId()).map { scanResult ->
                    scanResult.entries.asSequence().map { it.fileId }.toSet()
                }
            },
            listHeadings = { fileId -> clockService.listHeadings(fileId) },
            startClock = { fileId, lineIndex ->
                clockService.startClockInFile(fileId, lineIndex, clockEnvironment.now())
            },
            stopClock = { fileId, lineIndex ->
                clockService.stopClockInFile(fileId, lineIndex, clockEnvironment.now())
            },
            cancelClock = { fileId, lineIndex ->
                clockService.cancelClockInFile(fileId, lineIndex)
            },
            listClosedClocks = { fileId, lineIndex ->
                clockService.listClosedClocksInFile(fileId, lineIndex, clockEnvironment.now())
            },
            editClosedClock = { fileId, headingLineIndex, clockLineIndex, start, end ->
                clockService.editClosedClockInFile(fileId, headingLineIndex, clockLineIndex, start, end)
            },
            deleteClosedClock = { fileId, headingLineIndex, clockLineIndex ->
                clockService.deleteClosedClockInFile(fileId, headingLineIndex, clockLineIndex)
            },
            createL1Heading = { fileId, title, attachTplTag ->
                clockService.createL1HeadingInFile(fileId, title, attachTplTag)
            },
            createL2Heading = { fileId, parentL1LineIndex, title, attachTplTag ->
                clockService.createL2HeadingInFile(fileId, parentL1LineIndex, title, attachTplTag)
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
            nowProvider = { clockEnvironment.now() },
            todayProvider = { clockEnvironment.today() },
            zoneIdProvider = { clockEnvironment.zoneId() },
            showPerfOverlay = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }
}
