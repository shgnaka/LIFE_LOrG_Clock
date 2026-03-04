package com.example.orgclock.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val service = SyncRuntimeEntryPoint.syncIntegrationService ?: return Result.success()
        return runCatching {
            service.flushNow()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
