package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.prefs.Preferences

class DesktopSettingsStoreTest {
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanup() {
        nodes.asReversed().forEach { node ->
            runCatching {
                node.removeNode()
                node.parent()?.flush()
            }
        }
        nodes.clear()
    }

    @Test
    fun saveAndLoad_lastRootReference() {
        val store = DesktopSettingsStore(testNode())

        store.save(DesktopHostSettings(lastRootReference = RootReference("/tmp/org-root")))

        val loaded = store.load()
        assertEquals("/tmp/org-root", loaded.lastRootReference?.rawValue)
    }

    @Test
    fun clear_removesPersistedRoot() {
        val store = DesktopSettingsStore(testNode())
        store.save(DesktopHostSettings(lastRootReference = RootReference("/tmp/org-root")))

        store.clear()

        assertNull(store.load().lastRootReference)
    }

    private fun testNode(): Preferences =
        Preferences.userRoot()
            .node("com/example/orgclock/desktop/test/${System.nanoTime()}")
            .also(nodes::add)
}
