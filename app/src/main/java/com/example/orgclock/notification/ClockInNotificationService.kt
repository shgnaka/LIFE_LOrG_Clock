package com.example.orgclock.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.orgclock.MainActivity
import com.example.orgclock.R
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.parser.OrgParser
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.time.SystemClockEnvironment
import com.example.orgclock.time.toZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.datetime.toJavaZoneId

class ClockInNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { SafOrgRepository(this) }
    private val scanner by lazy { ClockInScanner(repository) }
    private val clockService by lazy { ClockService(repository, OrgParser()) }
    private val notificationPermissionChecker: NotificationPermissionChecker =
        DefaultNotificationPermissionChecker()
    private val clockEnvironment: ClockEnvironment by lazy { clockEnvironmentFactory() }
    private var config: NotificationServiceConfig = NotificationServiceConfig()

    private var enabled: Boolean = true
    private var displayMode: NotificationDisplayMode = NotificationDisplayMode.ActiveOnly
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceAndNotification()
                return START_NOT_STICKY
            }

            ACTION_STOP_CLOCK -> {
                val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: return START_STICKY
                val lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, -1)
                if (lineIndex < 0) return START_STICKY
                scope.launch {
                    clockService.stopClockInFile(fileId, lineIndex, clockEnvironment.now(), clockEnvironment.currentTimeZone())
                    refreshOnce()
                }
                return START_STICKY
            }

            ACTION_SYNC, null -> {
                enabled = intent?.getBooleanExtra(EXTRA_ENABLED, enabled) ?: enabled
                displayMode = NotificationDisplayMode.fromStorage(
                    intent?.getStringExtra(EXTRA_DISPLAY_MODE) ?: displayMode.storageValue,
                )
                config = NotificationServiceConfig(
                    scanIntervalMs = intent?.getLongExtra(EXTRA_SCAN_INTERVAL_MS, config.scanIntervalMs)
                        ?: config.scanIntervalMs,
                    maxLines = intent?.getIntExtra(EXTRA_MAX_LINES, config.maxLines) ?: config.maxLines,
                )

                if (!enabled || !notificationPermissionChecker.isGranted(this)) {
                    stopServiceAndNotification()
                    return START_NOT_STICKY
                }

                ensureForegroundPlaceholder()
                if (loopJob == null || loopJob?.isCancelled == true) {
                    loopJob = scope.launch { scanLoop() }
                } else {
                    scope.launch { refreshOnce() }
                }
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private suspend fun scanLoop() {
        while (scope.isActive) {
            val shouldContinue = refreshOnce()
            if (!shouldContinue) {
                return
            }
            delay(config.scanIntervalMs)
        }
    }

    private suspend fun refreshOnce(): Boolean {
        if (!enabled || !notificationPermissionChecker.isGranted(this)) {
            stopServiceAndNotification()
            return false
        }

        val rootUri = loadRootUri() ?: run {
            val notification = buildForegroundStatusNotification(
                title = getString(R.string.notif_title_clock_in),
                summary = getString(R.string.notif_status_root_not_set),
            )
            notifyForeground(notification)
            return true
        }

        val opened = repository.openRoot(rootUri)
        if (opened.isFailure) {
            val reason = opened.exceptionOrNull()?.message ?: "unknown"
            val notification = buildForegroundStatusNotification(
                title = getString(R.string.notif_title_clock_in),
                summary = getString(R.string.notif_status_root_open_failed, reason),
            )
            notifyForeground(notification)
            return true
        }

        val result = scanner.scan(clockEnvironment.currentTimeZone())
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            val notification = buildForegroundStatusNotification(
                title = getString(R.string.notif_title_clock_in),
                summary = getString(R.string.notif_status_refresh_failed, reason),
            )
            notifyForeground(notification)
            return true
        }

        val scanResult = result.getOrThrow()
        val entries = scanResult.entries
        val failedFiles = scanResult.failedFiles
        if (failedFiles.isNotEmpty()) {
            val failureNames = failedFiles.take(3).joinToString { it.fileName }
            Log.w(TAG, "Clock scan degraded: ${failedFiles.size} file(s) failed: $failureNames")
        }
        if (shouldStopForActiveOnly(displayMode, scanResult)) {
            stopServiceAndNotification()
            return false
        }

        notifyIndividualClockNotifications(entries)
        return true
    }

    private fun notifyIndividualClockNotifications(entries: List<ClockInEntry>) {
        val manager = getSystemService(NotificationManager::class.java)
        
        val currentIds = mutableSetOf<Int>()
        
        if (entries.isEmpty()) {
            val notification = buildClockStatusNotification(
                title = getString(R.string.notif_title_clock_in),
                summary = getString(R.string.notif_summary_no_active),
            )
            manager.notify(CLOCK_NOTIFICATION_BASE, notification)
            currentIds.add(CLOCK_NOTIFICATION_BASE)
        } else {
            entries.forEach { entry ->
                val notificationId = getNotificationId(entry.fileId, entry.headingLineIndex)
                val notification = buildIndividualClockNotification(entry)
                manager.notify(notificationId, notification)
                currentIds.add(notificationId)
            }
        }
        
        for (i in CLOCK_NOTIFICATION_BASE until CLOCK_NOTIFICATION_BASE + CLOCK_NOTIFICATION_RANGE) {
            if (i !in currentIds) {
                manager.cancel(i)
            }
        }
    }

    private fun getNotificationId(fileId: String, lineIndex: Int): Int {
        return CLOCK_NOTIFICATION_BASE + Math.abs((fileId.hashCode() + lineIndex) % CLOCK_NOTIFICATION_RANGE)
    }

    private fun buildIndividualClockNotification(entry: ClockInEntry): Notification {
        val stopIntent = Intent(this, ClockInNotificationService::class.java).apply {
            action = ACTION_STOP_CLOCK
            putExtra(EXTRA_FILE_ID, entry.fileId)
            putExtra(EXTRA_LINE_INDEX, entry.headingLineIndex)
        }
        val notificationId = getNotificationId(entry.fileId, entry.headingLineIndex)
        val stopPendingIntent = PendingIntent.getService(
            this,
            notificationId,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = headingLabel(entry)
        val minutes = elapsedMinutes(entry.startedAt)
        val started = entry.startedAt.format(TIME_FORMATTER)
        val summary = getString(R.string.notif_summary_first_entry, title, started, minutes)

        return NotificationCompat.Builder(this, CLOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(entry.headingTitle)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(createOpenAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_clock),
                stopPendingIntent,
            )
            .build()
    }

    private fun ensureForegroundPlaceholder() {
        val placeholder = buildForegroundStatusNotification(
            title = getString(R.string.notif_title_clock_in),
            summary = getString(R.string.notif_status_syncing),
        )
        startForegroundCompat(placeholder)
    }

    private fun notifyForeground(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundStatusNotification(title: String, summary: String): Notification {
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()
    }

    private fun buildClockStatusNotification(title: String, summary: String): Notification {
        return NotificationCompat.Builder(this, CLOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun elapsedMinutes(startedAt: ZonedDateTime): Long {
        val now = clockEnvironment
            .now()
            .toZonedDateTime(clockEnvironment.currentTimeZone().toJavaZoneId())
        return maxOf(0L, Duration.between(startedAt, now).toMinutes())
    }

    private fun headingLabel(entry: ClockInEntry): String {
        return if (!entry.l1Title.isNullOrBlank()) {
            "${entry.headingTitle} (${entry.l1Title})"
        } else {
            entry.headingTitle
        }
    }

    private fun loadRootUri(): Uri? {
        val prefs = getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(NotificationPrefs.KEY_ROOT_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val foregroundChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            getString(R.string.notif_foreground_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_foreground_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val clockChannel = NotificationChannel(
            CLOCK_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MAX,
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(foregroundChannel, clockChannel))
    }

    private fun stopServiceAndNotification() {
        loopJob?.cancel()
        loopJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "ClockInNotificationSvc"
        private const val FOREGROUND_CHANNEL_ID = "clock_in_foreground"
        private const val CLOCK_CHANNEL_ID = "clock_in_ongoing"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val CLOCK_NOTIFICATION_BASE = 2000
        private const val CLOCK_NOTIFICATION_RANGE = 100
        private const val ACTION_SYNC = "com.example.orgclock.notification.SYNC"
        private const val ACTION_STOP = "com.example.orgclock.notification.STOP"
        private const val ACTION_STOP_CLOCK = "com.example.orgclock.notification.STOP_CLOCK"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_DISPLAY_MODE = "display_mode"
        private const val EXTRA_SCAN_INTERVAL_MS = "scan_interval_ms"
        private const val EXTRA_MAX_LINES = "max_lines"
        private const val EXTRA_FILE_ID = "file_id"
        private const val EXTRA_LINE_INDEX = "line_index"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        @Volatile
        internal var clockEnvironmentFactory: () -> ClockEnvironment = { SystemClockEnvironment }

        fun sync(
            context: Context,
            enabled: Boolean,
            displayMode: NotificationDisplayMode,
            config: NotificationServiceConfig = NotificationServiceConfig(),
        ) {
            if (!enabled) {
                stop(context)
                return
            }
            val intent = Intent(context, ClockInNotificationService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_ENABLED, enabled)
                putExtra(EXTRA_DISPLAY_MODE, displayMode.storageValue)
                putExtra(EXTRA_SCAN_INTERVAL_MS, config.scanIntervalMs)
                putExtra(EXTRA_MAX_LINES, config.maxLines)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClockInNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

internal fun shouldStopForActiveOnly(
    displayMode: NotificationDisplayMode,
    scanResult: ClockInScanResult,
): Boolean {
    return displayMode == NotificationDisplayMode.ActiveOnly &&
        scanResult.entries.isEmpty() &&
        scanResult.failedFiles.isEmpty()
}
