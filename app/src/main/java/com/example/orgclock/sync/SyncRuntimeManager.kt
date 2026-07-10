package com.example.orgclock.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface SyncRuntimeCoordinator {
    suspend fun enableStandardMode()
    suspend fun enableActiveMode()
    suspend fun stop()
    suspend fun flushNow()
}

class SyncRuntimeManager(
    private val appContext: Context,
    private val runtimeController: SyncRuntimeController,
) : SyncRuntimeCoordinator {
    override suspend fun enableStandardMode() {
        runtimeController.enableStandardMode()
        SyncTickerService.stop(appContext)
        schedulePeriodicWork()
    }

    override suspend fun enableActiveMode() {
        runtimeController.enableActiveMode()
        cancelPeriodicWork()
        SyncTickerService.start(appContext)
    }

    override suspend fun stop() {
        SyncTickerService.stop(appContext)
        cancelPeriodicWork()
        runtimeController.stop()
    }

    override suspend fun flushNow() {
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
