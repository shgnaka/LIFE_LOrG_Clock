package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface TemplateAutoGenerationWorkScheduler {
    fun cancel()

    fun schedule(rootUri: Uri, delayMs: Long)
}

class WorkManagerTemplateAutoGenerationWorkScheduler(
    private val appContext: Context,
) : TemplateAutoGenerationWorkScheduler {
    override fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(TemplateAutoGenerationScheduler.UNIQUE_WORK_NAME)
    }

    override fun schedule(rootUri: Uri, delayMs: Long) {
        val input = Data.Builder()
            .putString(TemplateAutoGenerationWorker.KEY_ROOT_URI, rootUri.toString())
            .build()
        val workRequest = OneTimeWorkRequestBuilder<TemplateAutoGenerationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            TemplateAutoGenerationScheduler.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }
}
