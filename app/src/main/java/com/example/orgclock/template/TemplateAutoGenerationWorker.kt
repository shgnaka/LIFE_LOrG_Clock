package com.example.orgclock.template

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.orgclock.notification.NotificationPrefs

class TemplateAutoGenerationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val runner = TemplateAutoGenerationWorkerRunner()

    override suspend fun doWork(): Result {
        val fallbackRootUri = applicationContext
            .getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(NotificationPrefs.KEY_ROOT_URI, null)
        return runner.run(
            appContext = applicationContext,
            inputRootUri = inputData.getString(KEY_ROOT_URI),
            fallbackRootUri = fallbackRootUri,
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
