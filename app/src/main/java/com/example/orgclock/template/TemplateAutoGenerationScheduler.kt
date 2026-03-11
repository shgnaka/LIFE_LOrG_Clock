package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.time.toKotlinLocalDateCompat
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class TemplateAutoGenerationScheduler(
    private val appContext: Context,
    private val scheduleStore: RootScheduleStore,
    private val repositoryFactory: () -> SafOrgRepository = { SafOrgRepository(appContext) },
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    suspend fun sync(rootUri: Uri, config: RootScheduleConfig) {
        scheduleStore.save(config)
        if (!config.enabled) {
            cancel()
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    suspend fun runWorker(rootUri: Uri) {
        val config = scheduleStore.load(rootUri.toString())
        if (!config.enabled) {
            cancel()
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private suspend fun maybeGenerateToday(rootUri: Uri, config: RootScheduleConfig) {
        val now = nowProvider()
        if (!TemplateAutoGenerationPolicy.isDue(now, config)) return

        val repository = repositoryFactory()
        val opened = repository.openRoot(RootReference(rootUri.toString()))
        if (opened.isFailure) return
        repository.createDailyFromTemplateIfMissing(now.toLocalDate().toKotlinLocalDateCompat())
    }

    private fun scheduleNext(rootUri: Uri, config: RootScheduleConfig) {
        val nextRunAt = TemplateAutoGenerationPolicy.nextRunAt(nowProvider(), config)
        val delayMs = maxOf(0L, Duration.between(nowProvider(), nextRunAt).toMillis())
        val input = Data.Builder()
            .putString(TemplateAutoGenerationWorker.KEY_ROOT_URI, rootUri.toString())
            .build()
        val workRequest = OneTimeWorkRequestBuilder<TemplateAutoGenerationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "org-clock-template-auto-generation"
    }
}
