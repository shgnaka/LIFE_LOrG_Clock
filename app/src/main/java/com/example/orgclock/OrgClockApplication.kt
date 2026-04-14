package com.example.orgclock

import android.app.Application
import android.os.StrictMode
import com.example.orgclock.di.AppGraph
import com.example.orgclock.di.DefaultAppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OrgClockApplication : Application() {
    val appGraph: AppGraph by lazy { DefaultAppGraph(applicationContext) }
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .penaltyDeath()
                    .build(),
            )
        }
        startupScope.launch {
            appGraph.syncIntegrationService().onAppStarted()
            appGraph.androidEventSyncRuntime().onAppStarted()
        }
    }
}
