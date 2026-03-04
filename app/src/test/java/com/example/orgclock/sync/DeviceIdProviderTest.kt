package com.example.orgclock.sync

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdProviderTest {
    @Test
    fun getOrCreate_persistsGeneratedId() {
        val prefs = SimplePrefs()
        val provider = SharedPreferencesDeviceIdProvider(prefs)

        val first = provider.getOrCreate()
        val second = provider.getOrCreate()

        assertTrue(first.startsWith("dev_"))
        assertEquals(first, second)
    }
}

private class SimplePrefs : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (values[key] as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) values[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) this.values[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) values[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) values[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) values[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) values[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) values.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
