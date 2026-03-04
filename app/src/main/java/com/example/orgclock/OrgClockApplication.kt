package com.example.orgclock

import android.app.Application
import com.example.orgclock.di.AppGraph
import com.example.orgclock.di.DefaultAppGraph

class OrgClockApplication : Application() {
    val appGraph: AppGraph by lazy { DefaultAppGraph(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        appGraph.syncIntegrationService().onAppStarted()
    }
}
