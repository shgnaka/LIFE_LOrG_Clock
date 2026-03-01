package com.example.orgclock.data

data class BackupPolicyConfig(
    val backupGenerations: Int = DEFAULT_BACKUP_GENERATIONS,
    val clockBackupIntervalMs: Long = DEFAULT_CLOCK_BACKUP_INTERVAL_MS,
) {
    companion object {
        const val DEFAULT_BACKUP_GENERATIONS: Int = 20
        const val DEFAULT_CLOCK_BACKUP_INTERVAL_MS: Long = 15 * 60 * 1000L
    }
}

