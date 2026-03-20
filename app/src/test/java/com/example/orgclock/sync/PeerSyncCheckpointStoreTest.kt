package com.example.orgclock.sync

import android.content.SharedPreferences
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeerSyncCheckpointStoreTest {
    @Test
    fun sharedPreferencesStore_roundTripsSeenAndSentProgress() {
        val prefs = CheckpointInMemorySharedPreferences()
        val store = SharedPreferencesPeerSyncCheckpointStore(prefs)

        store.markSeen("peer-a", ClockEventCursor(3), seenAtEpochMs = 111L)
        store.markSent("peer-a", ClockEventCursor(6), syncedAtEpochMs = 222L)

        val checkpoint = store.get("peer-a")
        assertEquals("peer-a", checkpoint?.peerId)
        assertEquals(ClockEventCursor(3), checkpoint?.lastSeenCursor)
        assertEquals(ClockEventCursor(6), checkpoint?.lastSentCursor)
        assertEquals(111L, checkpoint?.lastSeenAtEpochMs)
        assertEquals(222L, checkpoint?.lastSyncedAtEpochMs)

        val restored = SharedPreferencesPeerSyncCheckpointStore(prefs).get("peer-a")
        assertEquals(checkpoint, restored)
    }

    @Test
    fun clearRemovesStoredCheckpoint() {
        val prefs = CheckpointInMemorySharedPreferences()
        val store = SharedPreferencesPeerSyncCheckpointStore(prefs)

        store.save(
            PeerSyncCheckpoint(
                peerId = "peer-a",
                lastSeenCursor = ClockEventCursor(1),
                lastSyncedAtEpochMs = 200L,
            ),
        )
        store.clear("peer-a")

        assertNull(store.get("peer-a"))
    }
}

private class CheckpointInMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { values[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            this.values[key!!] = values?.toMutableSet()
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { values[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { values[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { values[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { values[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { values.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { values.clear() }
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
