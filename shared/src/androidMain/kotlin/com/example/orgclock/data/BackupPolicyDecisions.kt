package com.example.orgclock.data

internal fun shouldCreateClockBackup(
    lastClockBackupAtMs: Long?,
    nowMs: Long,
    clockBackupIntervalMs: Long,
): Boolean {
    if (lastClockBackupAtMs == null) return true
    return (nowMs - lastClockBackupAtMs) >= clockBackupIntervalMs
}

internal fun <T> backupsToPrune(
    backupsSortedDesc: List<T>,
    backupGenerations: Int,
): List<T> {
    val generations = maxOf(0, backupGenerations)
    return backupsSortedDesc.drop(generations)
}

