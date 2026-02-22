package com.example.orgclock.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.orgclock.MainActivity
import com.example.orgclock.data.SafOrgRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ClockInNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { SafOrgRepository(this) }
    private val scanner by lazy { ClockInScanner(repository) }

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

            ACTION_SYNC, null -> {
                enabled = intent?.getBooleanExtra(EXTRA_ENABLED, enabled) ?: enabled
                displayMode = NotificationDisplayMode.fromStorage(
                    intent?.getStringExtra(EXTRA_DISPLAY_MODE) ?: displayMode.storageValue,
                )

                if (!enabled || !isNotificationPermissionGranted()) {
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
            delay(SCAN_INTERVAL_MS)
        }
    }

    private suspend fun refreshOnce(): Boolean {
        if (!enabled || !isNotificationPermissionGranted()) {
            stopServiceAndNotification()
            return false
        }

        val rootUri = loadRootUri() ?: run {
            val notification = buildStatusNotification(
                title = "記録中",
                summary = "org ルートが未設定です",
            )
            notifyForeground(notification)
            return true
        }

        val opened = repository.openRoot(rootUri)
        if (opened.isFailure) {
            val reason = opened.exceptionOrNull()?.message ?: "unknown"
            val notification = buildStatusNotification(
                title = "記録中",
                summary = "org ルートを開けません: $reason",
            )
            notifyForeground(notification)
            return true
        }

        val result = scanner.scan(ZoneId.systemDefault())
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            val notification = buildStatusNotification(
                title = "記録中",
                summary = "更新失敗: $reason",
            )
            notifyForeground(notification)
            return true
        }

        val entries = result.getOrThrow()
        if (entries.isEmpty() && displayMode == NotificationDisplayMode.ActiveOnly) {
            stopServiceAndNotification()
            return false
        }

        val notification = buildClockNotification(entries)
        notifyForeground(notification)
        return true
    }

    private fun ensureForegroundPlaceholder() {
        val placeholder = buildStatusNotification(
            title = "記録中",
            summary = "同期中...",
        )
        startForegroundCompat(placeholder)
    }

    private fun notifyForeground(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildClockNotification(entries: List<ClockInEntry>): Notification {
        val title = "記録中 ${entries.size}件"
        val summary = if (entries.isEmpty()) {
            "稼働中の見出しはありません"
        } else {
            val first = entries.first()
            val heading = headingLabel(first)
            val minutes = elapsedMinutes(first.startedAt)
            val started = first.startedAt.format(TIME_FORMATTER)
            "$heading / $started 開始 / ${minutes}分経過"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(createOpenAppPendingIntent())

        if (entries.isNotEmpty()) {
            val inbox = NotificationCompat.InboxStyle()
            entries.take(MAX_LINES).forEach { entry ->
                val line = "${headingLabel(entry)} / ${entry.startedAt.format(TIME_FORMATTER)} / ${elapsedMinutes(entry.startedAt)}分"
                inbox.addLine(line)
            }
            if (entries.size > MAX_LINES) {
                inbox.addLine("他 ${entries.size - MAX_LINES} 件")
            }
            builder.setStyle(inbox)
        }

        return builder.build()
    }

    private fun buildStatusNotification(title: String, summary: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        return maxOf(0L, Duration.between(startedAt, ZonedDateTime.now()).toMinutes())
    }

    private fun headingLabel(entry: ClockInEntry): String {
        return if (!entry.l1Title.isNullOrBlank()) {
            "${entry.headingTitle} (${entry.l1Title})"
        } else {
            entry.headingTitle
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return notificationsEnabled
        }
        val runtimeGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && notificationsEnabled
    }

    private fun loadRootUri(): Uri? {
        val prefs = getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(NotificationPrefs.KEY_ROOT_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Clock In",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Clock in 状態の常駐通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun stopServiceAndNotification() {
        loopJob?.cancel()
        loopJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "clock_in_ongoing"
        private const val NOTIFICATION_ID = 1001
        private const val SCAN_INTERVAL_MS = 5 * 60 * 1000L
        private const val MAX_LINES = 6

        private const val ACTION_SYNC = "com.example.orgclock.notification.SYNC"
        private const val ACTION_STOP = "com.example.orgclock.notification.STOP"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_DISPLAY_MODE = "display_mode"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun sync(
            context: Context,
            enabled: Boolean,
            displayMode: NotificationDisplayMode,
        ) {
            if (!enabled) {
                stop(context)
                return
            }
            val intent = Intent(context, ClockInNotificationService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_ENABLED, enabled)
                putExtra(EXTRA_DISPLAY_MODE, displayMode.storageValue)
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
