package com.example.orgclock.sync

import android.content.SharedPreferences

interface CommandIdStore {
    suspend fun contains(commandId: String): Boolean
    suspend fun markProcessed(commandId: String)
    suspend fun pruneOlderThan(epochMs: Long): Int = 0
    suspend fun size(): Long = 0L
}

class SharedPreferencesCommandIdStore(
    private val sharedPreferences: SharedPreferences,
) : CommandIdStore {
    override suspend fun contains(commandId: String): Boolean {
        return sharedPreferences.getStringSet(KEY_PROCESSED_COMMAND_IDS, emptySet())
            ?.contains(commandId) == true
    }

    override suspend fun markProcessed(commandId: String) {
        val existing = sharedPreferences.getStringSet(KEY_PROCESSED_COMMAND_IDS, emptySet()) ?: emptySet()
        if (existing.contains(commandId)) return
        val updated = existing.toMutableSet().apply { add(commandId) }
        sharedPreferences.edit().putStringSet(KEY_PROCESSED_COMMAND_IDS, updated).apply()
    }

    override suspend fun size(): Long {
        return (sharedPreferences.getStringSet(KEY_PROCESSED_COMMAND_IDS, emptySet()) ?: emptySet()).size.toLong()
    }

    private companion object {
        const val KEY_PROCESSED_COMMAND_IDS = "sync_processed_command_ids"
    }
}

class InMemoryCommandIdStore : CommandIdStore {
    private val processed = linkedSetOf<String>()

    override suspend fun contains(commandId: String): Boolean = processed.contains(commandId)

    override suspend fun markProcessed(commandId: String) {
        if (processed.contains(commandId)) return
        if (processed.size >= MAX_IN_MEMORY_IDS) {
            val oldest = processed.firstOrNull()
            if (oldest != null) {
                processed.remove(oldest)
            }
        }
        processed.add(commandId)
    }

    override suspend fun size(): Long = processed.size.toLong()

    private companion object {
        const val MAX_IN_MEMORY_IDS = 2_048
    }
}
