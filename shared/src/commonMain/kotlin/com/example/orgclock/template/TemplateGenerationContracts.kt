package com.example.orgclock.template

import com.example.orgclock.presentation.RootReference
import kotlinx.datetime.LocalDate

sealed interface TemplateGenerationResult {
    data object Generated : TemplateGenerationResult
    data object SkippedDailyAlreadyExists : TemplateGenerationResult
    data class Failed(
        val reason: String,
        val kind: TemplateGenerationFailureKind,
        val templateStatus: TemplateFileStatus? = null,
    ) : TemplateGenerationResult
}

enum class TemplateGenerationFailureKind {
    RootOpenFailed,
    TemplateMissing,
    TemplateUnreadable,
    SaveFailed,
}

data class TemplateAutoGenerationRuntimeState(
    val lastAttemptAtEpochMs: Long? = null,
    val lastSuccessAtEpochMs: Long? = null,
    val nextScheduledRunAtEpochMs: Long? = null,
    val overdue: Boolean = false,
    val lastFailureMessage: String? = null,
)

interface TemplateAutoGenerationRepository {
    suspend fun openRoot(rootReference: RootReference): Result<Unit>

    suspend fun hasDailyFile(date: LocalDate): Result<Boolean>

    suspend fun createDailyFromTemplateIfMissing(
        date: LocalDate,
        templateFileUri: String?,
    ): Result<TemplateGenerationResult>
}
