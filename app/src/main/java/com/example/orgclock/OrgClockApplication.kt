package com.example.orgclock

import android.app.Application
import android.os.StrictMode
import com.example.orgclock.di.AppGraph
import com.example.orgclock.di.DefaultAppGraph

class OrgClockApplication : Application() {
    val appGraph: AppGraph by lazy { DefaultAppGraph(applicationContext) }

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
        appGraph.syncIntegrationService().onAppStarted()
        appGraph.androidEventSyncRuntime().onAppStarted()
    }
}
