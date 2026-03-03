package com.example.orgclock

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.example.orgclock.di.AppGraph
import com.example.orgclock.di.DefaultAppGraph
import com.example.orgclock.notification.DefaultNotificationPermissionChecker
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.ui.app.OrgClockRoute
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.theme.OrgClockTheme
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
    private val notificationPermissionChecker: NotificationPermissionChecker =
        DefaultNotificationPermissionChecker()

    private val appGraph: AppGraph by lazy {
        appGraphFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dependencies = appGraph.routeDependencies(
            activity = this,
            notificationPermissionChecker = notificationPermissionChecker,
        )

        setContent {
            val performanceMonitor = remember { PerformanceMonitor(window) }
            OrgClockTheme {
                OrgClockRoute(
                    dependencies = dependencies,
                    performanceMonitor = performanceMonitor,
                )
            }
        }

        appGraph.syncIntegrationService(this).onAppStarted()

        val debugPayload = intent.getStringExtra(EXTRA_SYNC_COMMAND_PAYLOAD)
        if (!debugPayload.isNullOrBlank()) {
            lifecycleScope.launch {
                val result = appGraph.syncIntegrationService(this@MainActivity)
                    .executeManualCommand(debugPayload)
                Log.d(TAG, "Executed debug sync command status=${result.status} error=${result.errorCode}")
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_SYNC_COMMAND_PAYLOAD = "sync_command_payload"

        @Volatile
        internal var appGraphFactory: (ComponentActivity) -> AppGraph = { activity ->
            DefaultAppGraph(activity.applicationContext)
        }
    }
}
