package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.createTempDirectory

class DesktopSettingsStoreTest {
    private val nodes = mutableListOf<Preferences>()
    private val tempRoots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        nodes.asReversed().forEach { node ->
            runCatching {
                node.removeNode()
                node.parent()?.flush()
            }
        }
        nodes.clear()
        tempRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        tempRoots.clear()
    }

    @Test
    fun saveAndLoad_lastRootReference() {
        val store = DesktopSettingsStore(testNode(), fallbackFile = null)

        store.save(DesktopHostSettings(lastRootReference = RootReference("/tmp/org-root")))

        val loaded = store.load()
        assertEquals("/tmp/org-root", loaded.lastRootReference?.rawValue)
    }

    @Test
    fun clear_removesPersistedRoot() {
        val store = DesktopSettingsStore(testNode(), fallbackFile = null)
        store.save(DesktopHostSettings(lastRootReference = RootReference("/tmp/org-root")))

        store.clear()

        assertNull(store.load().lastRootReference)
    }

    @Test
    fun fallbackFile_restoresRootWhenPreferencesAreUnavailable() {
        val fallbackFile = createTempDirectory("desktop-settings-test")
            .also(tempRoots::add)
            .resolve("desktop-root.txt")
        DesktopSettingsStore(testNode(), fallbackFile).save(
            DesktopHostSettings(lastRootReference = RootReference("C:\\Org Clock\\日本語")),
        )

        val loaded = DesktopSettingsStore(testNode(), fallbackFile).load()

        assertEquals("C:\\Org Clock\\日本語", loaded.lastRootReference?.rawValue)
    }

    @Test
    fun clear_tombstonePreventsStalePreferenceFromReturning() {
        val fallbackFile = createTempDirectory("desktop-settings-test")
            .also(tempRoots::add)
            .resolve("desktop-root.txt")
        val preferences = testNode()
        preferences.put("last_root", "C:\\stale-root")
        preferences.flush()
        val store = DesktopSettingsStore(preferences, fallbackFile)

        store.clear()
        preferences.put("last_root", "C:\\stale-root")
        preferences.flush()

        assertNull(store.load().lastRootReference)
    }

    private fun testNode(): Preferences =
        Preferences.userRoot()
            .node("com/example/orgclock/desktop/test/${System.nanoTime()}")
            .also(nodes::add)
}
