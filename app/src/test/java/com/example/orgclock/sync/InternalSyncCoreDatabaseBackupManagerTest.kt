package com.example.orgclock.sync

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InternalSyncCoreDatabaseBackupManagerTest {
    @Test
    fun backupPreV3IfPresentCopiesDatabaseWalAndShm() {
        val dir = createTempDirectory("synccore-backup-test").toFile()
        val database = File(dir, "orgclock_sync_queue.db")
        val wal = File(dir, "orgclock_sync_queue.db-wal")
        val shm = File(dir, "orgclock_sync_queue.db-shm")
        database.writeText("db")
        wal.writeText("wal")
        shm.writeText("shm")

        val result = InternalSyncCoreDatabaseBackupManager { database }.backupPreV3IfPresent()

        val created = assertIs<InternalSyncCoreDatabaseBackupResult.Created>(result)
        assertEquals(3, created.files.size)
        assertEquals("db", File(database.path + ".pre-v3").readText())
        assertEquals("wal", File(wal.path + ".pre-v3").readText())
        assertEquals("shm", File(shm.path + ".pre-v3").readText())
    }

    @Test
    fun backupPreV3IfPresentSkipsMissingDatabase() {
        val dir = createTempDirectory("synccore-backup-test").toFile()
        val database = File(dir, "orgclock_sync_queue.db")

        val result = InternalSyncCoreDatabaseBackupManager { database }.backupPreV3IfPresent()

        assertEquals(InternalSyncCoreDatabaseBackupResult.NotNeeded, result)
        assertFalse(File(database.path + ".pre-v3").exists())
    }
}
