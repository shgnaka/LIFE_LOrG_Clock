package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import java.util.prefs.Preferences

data class DesktopHostSettings(
    val lastRootReference: RootReference? = null,
)

class DesktopSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) {
    fun load(): DesktopHostSettings {
        val rawRoot = preferences.get(KEY_LAST_ROOT, null)
        return DesktopHostSettings(
            lastRootReference = rawRoot?.takeIf { it.isNotBlank() }?.let(::RootReference),
        )
    }

    fun save(settings: DesktopHostSettings) {
        val root = settings.lastRootReference?.rawValue
        if (root.isNullOrBlank()) {
            preferences.remove(KEY_LAST_ROOT)
        } else {
            preferences.put(KEY_LAST_ROOT, root)
        }
        preferences.flush()
    }

    fun clear() {
        preferences.remove(KEY_LAST_ROOT)
        preferences.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop"
        const val KEY_LAST_ROOT = "last_root"
    }
}
