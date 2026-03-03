package com.example.orgclock.sync

import android.content.SharedPreferences

interface CommandIdStore {
    fun contains(commandId: String): Boolean
    fun markProcessed(commandId: String)
}

class SharedPreferencesCommandIdStore(
    private val sharedPreferences: SharedPreferences,
) : CommandIdStore {
    override fun contains(commandId: String): Boolean {
        return sharedPreferences.getStringSet(KEY_PROCESSED_COMMAND_IDS, emptySet())
            ?.contains(commandId) == true
    }

    override fun markProcessed(commandId: String) {
        val existing = sharedPreferences.getStringSet(KEY_PROCESSED_COMMAND_IDS, emptySet()) ?: emptySet()
        if (existing.contains(commandId)) return
        val updated = existing.toMutableSet().apply { add(commandId) }
        sharedPreferences.edit().putStringSet(KEY_PROCESSED_COMMAND_IDS, updated).apply()
    }

    private companion object {
        const val KEY_PROCESSED_COMMAND_IDS = "sync_processed_command_ids"
    }
}

class InMemoryCommandIdStore : CommandIdStore {
    private val processed = linkedSetOf<String>()

    override fun contains(commandId: String): Boolean = processed.contains(commandId)

    override fun markProcessed(commandId: String) {
        processed.add(commandId)
    }
}
