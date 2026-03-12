package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.time.toKotlinLocalDateCompat
import java.security.MessageDigest
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

interface TemplateAutoGenerationWorkScheduler {
    fun enqueue(uniqueWorkName: String, rootUri: String, delayMs: Long)
    fun cancel(uniqueWorkName: String)
}

class WorkManagerTemplateAutoGenerationWorkScheduler(
    private val appContext: Context,
) : TemplateAutoGenerationWorkScheduler {
    override fun enqueue(uniqueWorkName: String, rootUri: String, delayMs: Long) {
        val input = Data.Builder()
            .putString(TemplateAutoGenerationWorker.KEY_ROOT_URI, rootUri)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<TemplateAutoGenerationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    override fun cancel(uniqueWorkName: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(uniqueWorkName)
    }
}

open class TemplateAutoGenerationScheduler(
    private val appContext: Context,
    private val scheduleStore: RootScheduleStore,
    private val repositoryFactory: () -> TemplateAutoGenerationRepository,
    private val workScheduler: TemplateAutoGenerationWorkScheduler = WorkManagerTemplateAutoGenerationWorkScheduler(appContext),
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    suspend fun sync(rootUri: Uri, config: RootScheduleConfig) {
        scheduleStore.save(config)
        val workName = uniqueWorkName(rootUri.toString())
        if (!config.enabled) {
            workScheduler.cancel(workName)
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    open suspend fun runWorker(rootUri: Uri) {
        val config = scheduleStore.load(rootUri.toString())
        val workName = uniqueWorkName(rootUri.toString())
        if (!config.enabled) {
            workScheduler.cancel(workName)
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    fun cancel(rootUri: String) {
        workScheduler.cancel(uniqueWorkName(rootUri))
    }

    private suspend fun maybeGenerateToday(rootUri: Uri, config: RootScheduleConfig) {
        val now = nowProvider()
        if (!TemplateAutoGenerationPolicy.isDue(now, config)) return

        val repository = repositoryFactory()
        repository.openRoot(rootUri).getOrElse { return }
        repository.createDailyFromTemplateIfMissing(now.toLocalDate().toKotlinLocalDateCompat())
            .getOrElse { return }
    }

    private fun scheduleNext(rootUri: Uri, config: RootScheduleConfig) {
        val now = nowProvider()
        val nextRunAt = TemplateAutoGenerationPolicy.nextRunAt(now, config)
        val delayMs = maxOf(0L, Duration.between(now, nextRunAt).toMillis())
        workScheduler.enqueue(uniqueWorkName(rootUri.toString()), rootUri.toString(), delayMs)
    }

    private fun uniqueWorkName(rootUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rootUri.toByteArray())
        val suffix = digest.joinToString("") { "%02x".format(it) }.take(12)
        return "${UNIQUE_WORK_NAME_PREFIX}-$suffix"
    }

    companion object {
        const val UNIQUE_WORK_NAME_PREFIX = "org-clock-template-auto-generation"
    }
}

class TemplateAutoGenerationWorkerRunner(
    private val schedulerProvider: (Context) -> TemplateAutoGenerationScheduler = { appContext ->
        TemplateAutoGenerationEntryPoint.scheduler ?: TemplateAutoGenerationScheduler(
            appContext = appContext,
            scheduleStore = RootScheduleStore(
                appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
            ),
            repositoryFactory = { com.example.orgclock.data.SafOrgRepository(appContext) },
        )
    },
    private val uriFactory: (String) -> Uri = Uri::parse,
) {
    suspend fun run(
        appContext: Context,
        inputRootUri: String?,
        fallbackRootUri: String?,
    ): androidx.work.ListenableWorker.Result {
        val rootUri = inputRootUri ?: fallbackRootUri ?: return androidx.work.ListenableWorker.Result.success()
        val scheduler = schedulerProvider(appContext)
        return runCatching {
            scheduler.runWorker(uriFactory(rootUri))
        }.fold(
            onSuccess = { androidx.work.ListenableWorker.Result.success() },
            onFailure = { androidx.work.ListenableWorker.Result.retry() },
        )
    }
}
