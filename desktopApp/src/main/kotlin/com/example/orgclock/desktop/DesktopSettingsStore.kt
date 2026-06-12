package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.util.prefs.Preferences
import kotlin.io.path.exists

data class DesktopHostSettings(
    val lastRootReference: RootReference? = null,
)

class DesktopSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
    private val fallbackFile: Path? = defaultFallbackFile(),
) {
    fun load(): DesktopHostSettings {
        val fallbackExists = fallbackFile?.exists() == true
        val rawRoot = if (fallbackExists) {
            fallbackFile?.let { file ->
                runCatching { Files.readString(file, StandardCharsets.UTF_8).trim() }.getOrNull()
            }
        } else {
            runCatching { preferences.get(KEY_LAST_ROOT, null) }.getOrNull()
        }
        return DesktopHostSettings(
            lastRootReference = rawRoot?.takeIf { it.isNotBlank() }?.let(::RootReference),
        )
    }

    fun save(settings: DesktopHostSettings) {
        val root = settings.lastRootReference?.rawValue
        runCatching {
            if (root.isNullOrBlank()) {
                preferences.remove(KEY_LAST_ROOT)
            } else {
                preferences.put(KEY_LAST_ROOT, root)
            }
            preferences.flush()
        }
        fallbackFile?.let { file ->
            writeFallback(file, root.orEmpty())
        }
    }

    fun clear() {
        runCatching {
            preferences.remove(KEY_LAST_ROOT)
            preferences.flush()
        }
        fallbackFile?.let { file ->
            writeFallback(file, "")
        }
    }

    private fun writeFallback(file: Path, value: String) {
        file.parent?.let(Files::createDirectories)
        val temp = Files.createTempFile(file.parent, ".desktop-root.", ".tmp")
        try {
            Files.writeString(
                temp,
                value,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (atomicError: IOException) {
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                } catch (fallbackError: IOException) {
                    fallbackError.addSuppressed(atomicError)
                    throw fallbackError
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop"
        const val KEY_LAST_ROOT = "last_root"

        fun defaultFallbackFile(): Path? {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home")?.takeIf { it.isNotBlank() }
                ?: return null
            return Path.of(base, "OrgClock", "desktop-root.txt")
        }
    }
}
