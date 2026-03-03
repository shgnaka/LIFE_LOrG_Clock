package com.example.orgclock.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SyncRuntimeManager(
    private val appContext: Context,
    private val runtimeController: SyncRuntimeController,
) {
    suspend fun enableStandardMode() {
        runtimeController.enableStandardMode()
        SyncTickerService.stop(appContext)
        schedulePeriodicWork()
    }

    suspend fun enableActiveMode() {
        runtimeController.enableActiveMode()
        cancelPeriodicWork()
        SyncTickerService.start(appContext)
    }

    suspend fun stop() {
        SyncTickerService.stop(appContext)
        cancelPeriodicWork()
        runtimeController.stop()
    }

    suspend fun flushNow() {
        runtimeController.flushNow()
    }

    private fun schedulePeriodicWork() {
        val workRequest = PeriodicWorkRequestBuilder<SyncMaintenanceWorker>(STANDARD_PERIOD_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest,
        )
    }

    private fun cancelPeriodicWork() {
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "org-clock-sync-maintenance"
        const val STANDARD_PERIOD_MINUTES = 15L
    }
}

object SyncRuntimeEntryPoint {
    @Volatile
    var syncIntegrationService: SyncIntegrationService? = null
}
