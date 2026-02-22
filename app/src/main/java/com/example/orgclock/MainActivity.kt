package com.example.orgclock

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.notification.ClockInScanner
import com.example.orgclock.notification.ClockInNotificationService
import com.example.orgclock.notification.DefaultNotificationPermissionChecker
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.notification.NotificationServiceConfig
import com.example.orgclock.ui.app.OrgClockRoute
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.theme.OrgClockTheme
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val notificationPermissionChecker: NotificationPermissionChecker =
        DefaultNotificationPermissionChecker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(NotificationPrefs.PREFS_NAME, MODE_PRIVATE)
        val repository = SafOrgRepository(this)
        val clockService = ClockService(repository)
        val clockInScanner = ClockInScanner(repository)
        val notificationServiceConfig = NotificationServiceConfig()
        val showPerfOverlay = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        setContent {
            val performanceMonitor = remember { PerformanceMonitor(window) }
            OrgClockTheme {
                OrgClockRoute(
                    loadSavedUri = { prefs.getString(NotificationPrefs.KEY_ROOT_URI, null)?.let(Uri::parse) },
                    saveUri = { uri -> prefs.edit().putString(NotificationPrefs.KEY_ROOT_URI, uri.toString()).apply() },
                    openRoot = { uri -> repository.openRoot(uri) },
                    listFiles = { repository.listOrgFiles() },
                    listFilesWithOpenClock = {
                        clockInScanner.scan(ZoneId.systemDefault()).map { entries ->
                            entries.asSequence().map { it.fileId }.toSet()
                        }
                    },
                    listHeadings = { fileId -> clockService.listHeadings(fileId) },
                    startClock = { fileId, lineIndex ->
                        clockService.startClockInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    stopClock = { fileId, lineIndex ->
                        clockService.stopClockInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
                    },
                    cancelClock = { fileId, lineIndex ->
                        clockService.cancelClockInFile(fileId, lineIndex)
                    },
                    listClosedClocks = { fileId, lineIndex ->
                        clockService.listClosedClocksInFile(fileId, lineIndex, java.time.ZonedDateTime.now())
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
                        notificationPermissionChecker.isGranted(this@MainActivity)
                    },
                    syncNotificationService = { enabled, mode ->
                        ClockInNotificationService.sync(
                            context = this@MainActivity,
                            enabled = enabled,
                            displayMode = mode,
                            config = notificationServiceConfig,
                        )
                    },
                    stopNotificationService = {
                        ClockInNotificationService.stop(this@MainActivity)
                    },
                    openAppNotificationSettings = { openAppNotificationSettings() },
                    performanceMonitor = performanceMonitor,
                    showPerfOverlay = showPerfOverlay,
                )
            }
        }
    }

    private fun openAppNotificationSettings() {
        val intent = android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }
}
