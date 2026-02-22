package com.example.orgclock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.example.orgclock.di.AppGraph
import com.example.orgclock.di.DefaultAppGraph
import com.example.orgclock.notification.DefaultNotificationPermissionChecker
import com.example.orgclock.notification.NotificationPermissionChecker
import com.example.orgclock.ui.app.OrgClockRoute
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.theme.OrgClockTheme

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
    }

    companion object {
        @Volatile
        internal var appGraphFactory: (ComponentActivity) -> AppGraph = { activity ->
            DefaultAppGraph(activity.applicationContext)
        }
    }
}
