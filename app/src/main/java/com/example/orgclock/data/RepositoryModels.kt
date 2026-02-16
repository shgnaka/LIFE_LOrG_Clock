package com.example.orgclock.data

import android.net.Uri
import com.example.orgclock.model.OrgDocument
import java.time.Instant
import java.time.LocalDate

data class RootAccess(
    val rootUri: Uri,
    val displayName: String,
)

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
}

interface OrgRepository {
    suspend fun openRoot(uri: Uri): Result<RootAccess>

    suspend fun listOrgFiles(): Result<List<OrgFileEntry>> {
        return Result.failure(UnsupportedOperationException("listOrgFiles is not implemented"))
    }

    suspend fun loadFile(fileId: String): Result<OrgDocument> {
        return Result.failure(UnsupportedOperationException("loadFile is not implemented"))
    }

    suspend fun saveFile(
        fileId: String,
        lines: List<String>,
        expectedHash: String,
        writeIntent: FileWriteIntent = FileWriteIntent.UserEdit,
    ): SaveResult {
        return SaveResult.ValidationError("saveFile is not implemented")
    }

    suspend fun loadDaily(date: LocalDate): Result<OrgDocument>
    suspend fun saveDaily(date: LocalDate, lines: List<String>, expectedHash: String): SaveResult
}
