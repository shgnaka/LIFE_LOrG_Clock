package com.example.orgclock.sync

import android.content.Context
import java.io.File

internal class InternalSyncCoreDatabaseBackupManager(
    private val databaseFileProvider: () -> File,
) {
    fun backupPreV3IfPresent(): InternalSyncCoreDatabaseBackupResult {
        val databaseFile = databaseFileProvider()
        if (!databaseFile.exists()) {
            return InternalSyncCoreDatabaseBackupResult.NotNeeded
        }
        val copied = mutableListOf<File>()
        listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
        ).filter { it.exists() }.forEach { source ->
            val target = File(source.path + PRE_V3_SUFFIX)
            if (!target.exists()) {
                source.copyTo(target, overwrite = false)
            }
            copied += target
        }
        return InternalSyncCoreDatabaseBackupResult.Created(copied)
    }

    companion object {
        const val PRE_V3_SUFFIX = ".pre-v3"

        fun forContext(context: Context): InternalSyncCoreDatabaseBackupManager = InternalSyncCoreDatabaseBackupManager {
            context.getDatabasePath(InternalSyncCoreDatabaseFactory.DATABASE_NAME)
        }
    }
}

internal sealed interface InternalSyncCoreDatabaseBackupResult {
    data object NotNeeded : InternalSyncCoreDatabaseBackupResult
    data class Created(val files: List<File>) : InternalSyncCoreDatabaseBackupResult
}

