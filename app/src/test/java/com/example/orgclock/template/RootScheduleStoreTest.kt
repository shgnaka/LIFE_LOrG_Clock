package com.example.orgclock.template

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootScheduleStoreTest {
    @Test
    fun saveAndLoad_roundTripsPerRootConfiguration() {
        val prefs = InMemorySharedPreferences()
        val store = RootScheduleStore(prefs)
        val config = RootScheduleConfig(
            rootUri = "content://root-a",
            enabled = true,
            ruleType = ScheduleRuleType.Weekly,
            hour = 6,
            minute = 45,
            daysOfWeek = setOf(ScheduleWeekday.Monday, ScheduleWeekday.Friday),
        )

        store.save(config)

        assertEquals(config, store.load("content://root-a"))
        assertFalse(store.load("content://root-b").enabled)
    }
}

internal class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
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
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
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
