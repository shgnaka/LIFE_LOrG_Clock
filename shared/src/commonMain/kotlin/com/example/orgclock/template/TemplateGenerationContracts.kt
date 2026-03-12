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

interface TemplateAutoGenerationRepository {
    suspend fun openRoot(rootReference: RootReference): Result<Unit>

    suspend fun createDailyFromTemplateIfMissing(
        date: LocalDate,
        templateFileUri: String?,
    ): Result<TemplateGenerationResult>
}
