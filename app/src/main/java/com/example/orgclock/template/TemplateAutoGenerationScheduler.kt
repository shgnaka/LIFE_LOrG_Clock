package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.time.toKotlinLocalDateCompat
import java.time.Duration
import java.time.ZonedDateTime

class TemplateAutoGenerationScheduler(
    private val appContext: Context,
    private val scheduleStore: RootScheduleStore,
    private val failureReporter: TemplateAutoGenerationFailureReporter,
    private val runtimeStore: TemplateAutoGenerationRuntimeStore = TemplateAutoGenerationRuntimeStore(
        appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
    ),
    private val repositoryFactory: () -> TemplateAutoGenerationRepository = { SafOrgRepository(appContext) },
    private val workScheduler: TemplateAutoGenerationWorkScheduler =
        WorkManagerTemplateAutoGenerationWorkScheduler(appContext),
    private val logError: (String) -> Unit = { message -> Log.e(TAG, message) },
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    suspend fun sync(rootUri: Uri, config: RootScheduleConfig) {
        scheduleStore.save(config)
        if (!config.enabled) {
            cancel()
            runtimeStore.saveNextScheduledRun(rootUri.toString(), null)
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    suspend fun runWorker(rootUri: Uri) {
        val config = scheduleStore.load(rootUri.toString())
        if (!config.enabled) {
            cancel()
            runtimeStore.saveNextScheduledRun(rootUri.toString(), null)
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    suspend fun runCatchUpIfDue(rootUri: Uri) {
        val config = scheduleStore.load(rootUri.toString())
        if (!config.enabled) {
            cancel()
            runtimeStore.saveNextScheduledRun(rootUri.toString(), null)
            return
        }
        maybeGenerateToday(rootUri, config)
        scheduleNext(rootUri, config)
    }

    suspend fun loadRuntimeState(rootUri: Uri): TemplateAutoGenerationRuntimeState {
        val rootKey = rootUri.toString()
        val stored = runtimeStore.load(rootKey)
        val config = scheduleStore.load(rootKey)
        val failure = failureReporter.loadLastFailure(rootKey)
        if (!config.enabled) {
            return stored.copy(
                nextScheduledRunAtEpochMs = null,
                overdue = false,
                lastFailureMessage = failure,
            )
        }

        val now = nowProvider()
        val repository = repositoryFactory()
        val opened = repository.openRoot(RootReference(rootKey))
        val hasDailyFile = if (opened.isSuccess) {
            repository.hasDailyFile(now.toLocalDate().toKotlinLocalDateCompat()).getOrDefault(false)
        } else {
            false
        }
        val nextScheduled = stored.nextScheduledRunAtEpochMs
            ?: TemplateAutoGenerationPolicy.nextRunAt(now, config).toInstant().toEpochMilli()
        return stored.copy(
            nextScheduledRunAtEpochMs = nextScheduled,
            overdue = TemplateAutoGenerationPolicy.isDue(now, config) && !hasDailyFile,
            lastFailureMessage = failure ?: opened.exceptionOrNull()?.message,
        )
    }

    fun cancel() {
        workScheduler.cancel()
    }

    private suspend fun maybeGenerateToday(rootUri: Uri, config: RootScheduleConfig) {
        val now = nowProvider()
        if (!TemplateAutoGenerationPolicy.isDue(now, config)) return
        val rootKey = rootUri.toString()
        if (!beginInFlight(rootKey)) return

        try {
            runtimeStore.saveLastAttempt(rootKey, now.toInstant().toEpochMilli())
            val repository = repositoryFactory()
            val opened = repository.openRoot(RootReference(rootKey))
            if (opened.isFailure) {
                val reason = opened.exceptionOrNull()?.message ?: "Failed to open root"
                logError("Template auto-generation could not open root $rootUri: $reason")
                failureReporter.reportFailure(
                    rootUri = rootKey,
                    title = "Template auto-generation failed",
                    message = reason,
                )
                return
            }
            when (val result = repository.createDailyFromTemplateIfMissing(
                date = now.toLocalDate().toKotlinLocalDateCompat(),
                templateFileUri = config.templateFileUri,
            ).getOrElse { error ->
                TemplateGenerationResult.Failed(
                    reason = error.message ?: "Unknown template auto-generation failure",
                    kind = TemplateGenerationFailureKind.SaveFailed,
                )
            }) {
                TemplateGenerationResult.Generated,
                TemplateGenerationResult.SkippedDailyAlreadyExists -> {
                    runtimeStore.saveLastSuccess(rootKey, now.toInstant().toEpochMilli())
                    failureReporter.clear(rootKey)
                }
                is TemplateGenerationResult.Failed -> {
                    logError("Template auto-generation failed for $rootUri: ${result.reason}")
                    failureReporter.reportFailure(
                        rootUri = rootKey,
                        title = "Template auto-generation failed",
                        message = result.reason,
                    )
                }
            }
        } finally {
            finishInFlight(rootKey)
        }
    }

    private fun scheduleNext(rootUri: Uri, config: RootScheduleConfig) {
        val nextRunAt = TemplateAutoGenerationPolicy.nextRunAt(nowProvider(), config)
        val delayMs = maxOf(0L, Duration.between(nowProvider(), nextRunAt).toMillis())
        runtimeStore.saveNextScheduledRun(rootUri.toString(), nextRunAt.toInstant().toEpochMilli())
        workScheduler.schedule(rootUri, delayMs)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "org-clock-template-auto-generation"
        private const val TAG = "TemplateAutoGen"
        private val inFlightRoots = mutableSetOf<String>()

        @Synchronized
        private fun beginInFlight(rootUri: String): Boolean = inFlightRoots.add(rootUri)

        @Synchronized
        private fun finishInFlight(rootUri: String) {
            inFlightRoots.remove(rootUri)
        }
    }
}
