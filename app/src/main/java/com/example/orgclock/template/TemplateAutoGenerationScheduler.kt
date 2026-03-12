package com.example.orgclock.template

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.orgclock.data.SafOrgRepository
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.time.toKotlinLocalDateCompat
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class TemplateAutoGenerationScheduler(
    private val appContext: Context,
    private val scheduleStore: RootScheduleStore,
    private val failureReporter: TemplateAutoGenerationFailureReporter,
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
        workScheduler.cancel()
    }

    private suspend fun maybeGenerateToday(rootUri: Uri, config: RootScheduleConfig) {
        val now = nowProvider()
        if (!TemplateAutoGenerationPolicy.isDue(now, config)) return

        val repository = repositoryFactory()
        val opened = repository.openRoot(RootReference(rootUri.toString()))
        if (opened.isFailure) {
            val reason = opened.exceptionOrNull()?.message ?: "Failed to open root"
            logError("Template auto-generation could not open root $rootUri: $reason")
            failureReporter.reportFailure(
                rootUri = rootUri.toString(),
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
            TemplateGenerationResult.SkippedDailyAlreadyExists -> failureReporter.clear(rootUri.toString())
            is TemplateGenerationResult.Failed -> {
                logError("Template auto-generation failed for $rootUri: ${result.reason}")
                failureReporter.reportFailure(
                    rootUri = rootUri.toString(),
                    title = "Template auto-generation failed",
                    message = result.reason,
                )
            }
        }
    }

    private fun scheduleNext(rootUri: Uri, config: RootScheduleConfig) {
        val nextRunAt = TemplateAutoGenerationPolicy.nextRunAt(nowProvider(), config)
        val delayMs = maxOf(0L, Duration.between(nowProvider(), nextRunAt).toMillis())
        workScheduler.schedule(rootUri, delayMs)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "org-clock-template-auto-generation"
        private const val TAG = "TemplateAutoGen"
    }
}
