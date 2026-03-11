package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.orgclock.notification.NotificationPrefs

class TemplateAutoGenerationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val rootUri = inputData.getString(KEY_ROOT_URI)
            ?: applicationContext
                .getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(NotificationPrefs.KEY_ROOT_URI, null)
            ?: return Result.success()
        val scheduler = TemplateAutoGenerationEntryPoint.scheduler ?: TemplateAutoGenerationScheduler(
            appContext = applicationContext,
            scheduleStore = RootScheduleStore(
                applicationContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
        return runCatching {
            scheduler.runWorker(Uri.parse(rootUri))
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_ROOT_URI = "root_uri"
    }
}

object TemplateAutoGenerationEntryPoint {
    @Volatile
    var scheduler: TemplateAutoGenerationScheduler? = null
}
