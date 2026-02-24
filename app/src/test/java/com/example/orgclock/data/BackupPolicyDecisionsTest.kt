package com.example.orgclock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPolicyDecisionsTest {
    @Test
    fun shouldCreateClockBackup_returnsTrueWhenNoPreviousBackup() {
        assertTrue(
            shouldCreateClockBackup(
                lastClockBackupAtMs = null,
                nowMs = 1_000L,
                clockBackupIntervalMs = 500L,
            ),
        )
    }

    @Test
    fun shouldCreateClockBackup_respectsConfigurableInterval() {
        assertFalse(
            shouldCreateClockBackup(
                lastClockBackupAtMs = 1_000L,
                nowMs = 1_500L,
                clockBackupIntervalMs = 600L,
            ),
        )
        assertTrue(
            shouldCreateClockBackup(
                lastClockBackupAtMs = 1_000L,
                nowMs = 1_600L,
                clockBackupIntervalMs = 600L,
            ),
        )
    }

    @Test
    fun backupsToPrune_prunesWhenGenerationsIsSmall() {
        val backups = listOf("b4", "b3", "b2", "b1")
        val toPrune = backupsToPrune(backupsSortedDesc = backups, backupGenerations = 2)

        assertEquals(listOf("b2", "b1"), toPrune)
    }

    @Test
    fun shouldCreateClockBackup_returnsTrueAtExactIntervalBoundary() {
        assertTrue(
            shouldCreateClockBackup(
                lastClockBackupAtMs = 1_000L,
                nowMs = 1_600L,
                clockBackupIntervalMs = 600L,
            ),
        )
    }

    @Test
    fun backupsToPrune_negativeGenerations_prunesAll() {
        val backups = listOf("b3", "b2", "b1")
        val toPrune = backupsToPrune(backupsSortedDesc = backups, backupGenerations = -1)

        assertEquals(backups, toPrune)
    }

    @Test
    fun backupsToPrune_whenGenerationsExceedsSize_prunesNone() {
        val backups = listOf("b3", "b2", "b1")
        val toPrune = backupsToPrune(backupsSortedDesc = backups, backupGenerations = 10)

        assertTrue(toPrune.isEmpty())
    }
}
