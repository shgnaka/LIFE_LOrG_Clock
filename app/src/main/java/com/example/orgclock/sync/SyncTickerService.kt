package com.example.orgclock.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.orgclock.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SyncTickerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tickerJob?.isActive == true) return START_STICKY
            tickerJob = scope.launch {
                while (isActive) {
                    SyncRuntimeEntryPoint.syncIntegrationService?.flushNow()
                    AndroidEventSyncRuntimeEntryPoint.runtime?.onPeriodicTick()
                    delay(TICK_INTERVAL_MS)
                }
            }
        return START_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        tickerJob = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_active_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sync_active_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.sync_active_notification_title))
            .setContentText(getString(R.string.sync_active_notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sync-active-mode"
        private const val NOTIFICATION_ID = 32001
        private const val TICK_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, SyncTickerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncTickerService::class.java))
        }
    }
}
