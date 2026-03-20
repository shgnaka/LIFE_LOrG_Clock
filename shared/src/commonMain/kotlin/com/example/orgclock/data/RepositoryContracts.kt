package com.example.orgclock.data

import com.example.orgclock.model.OrgDocument
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

data class OrgFileEntry(
    val fileId: String,
    val displayName: String,
    val modifiedAt: Instant?,
)

enum class FileWriteIntent {
    ClockMutation,
    UserEdit,
}

sealed interface SaveResult {
    data object Success : SaveResult
    data class Conflict(val reason: String) : SaveResult
    data class ValidationError(val reason: String) : SaveResult
    data class IoError(val reason: String) : SaveResult
    data class RoundTripMismatch(val reason: String) : SaveResult
}

interface ClockRepository {
    suspend fun listOrgFiles(): Result<List<OrgFileEntry>>
    suspend fun listTemplateCandidateFiles(): Result<List<OrgFileEntry>> = listOrgFiles()
    suspend fun loadFile(fileId: String): Result<OrgDocument>

    suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent = FileWriteIntent.UserEdit,
    ): SaveResult

    suspend fun loadDaily(date: LocalDate): Result<OrgDocument>
    suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult
}
