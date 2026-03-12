package com.example.orgclock.template

import android.net.Uri
import com.example.orgclock.data.SaveResult
import com.example.orgclock.model.OrgDocument
import kotlinx.datetime.LocalDate

interface TemplateSyncRepository {
    suspend fun loadFile(fileId: String): Result<OrgDocument>
    suspend fun loadTemplate(): Result<OrgDocument>
    suspend fun saveTemplate(lines: List<String>, expectedHash: String): SaveResult
}

interface TemplateAutoGenerationRepository {
    suspend fun openRoot(uri: Uri): Result<Unit>
    suspend fun createDailyFromTemplateIfMissing(date: LocalDate): Result<Boolean>
}
