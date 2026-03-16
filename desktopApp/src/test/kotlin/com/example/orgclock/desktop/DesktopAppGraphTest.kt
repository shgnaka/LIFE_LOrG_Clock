package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.time.ClockEnvironment
import com.example.orgclock.ui.state.OrgClockUiAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAppGraphTest {
    private val tempRoots = mutableListOf<Path>()
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanup() {
        tempRoots.asReversed().forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempRoots.clear()
        nodes.asReversed().forEach { node ->
            runCatching {
                node.removeNode()
                node.parent()?.flush()
            }
        }
        nodes.clear()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun restoresSavedRootOnStartupThroughSharedStore() = runTest {
        val root = tempRoot()
        write(root.resolve("2026-03-10.org"), "* Work\n** Task\n")
        val store = DesktopSettingsStore(testNode())
        store.save(DesktopHostSettings(lastRootReference = RootReference(root.toString())))

        val graph = DesktopAppGraph(
            settingsStore = store,
            clockEnvironment = fixedClockEnvironment(),
            watchRootChanges = false,
        )
        val orgClockStore = graph.createStore(this)
        orgClockStore.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        val state = orgClockStore.uiState.value
        assertEquals(Screen.HeadingList, state.screen)
        assertEquals(root.toString(), state.rootReference?.rawValue)
        assertEquals(StatusMessageKey.LoadedFile, state.status.text.key)
        assertEquals(1, state.files.size)
        assertEquals("2026-03-10.org", state.selectedFile?.displayName)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pickingRootPersistsAndEnablesDesktopClockOperations() = runTest {
        val root = tempRoot()
        write(root.resolve("2026-03-10.org"), "* Work\n** Task\n")
        val settingsStore = DesktopSettingsStore(testNode())
        val graph = DesktopAppGraph(
            settingsStore = settingsStore,
            clockEnvironment = fixedClockEnvironment(),
            watchRootChanges = false,
        )
        val store = graph.createStore(this)

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.PickRoot(RootReference(root.toString())))
        advanceUntilIdle()

        val state = store.uiState.value
        val file = state.selectedFile
        assertEquals(Screen.HeadingList, state.screen)
        assertEquals(root.toString(), settingsStore.load().lastRootReference?.rawValue)
        assertTrue(file != null)
        assertTrue(state.headings.isNotEmpty())

        val result = graph.createStore(this).run {
            onAction(OrgClockUiAction.Initialize)
            advanceUntilIdle()
            uiState.value
        }
        assertEquals(root.toString(), result.rootReference?.rawValue)
        assertEquals(Screen.HeadingList, result.screen)
    }

    private fun tempRoot(): Path = createTempDirectory("desktop-graph-test").also(tempRoots::add)

    private fun testNode(): Preferences =
        Preferences.userRoot()
            .node("com/example/orgclock/desktop/test/${System.nanoTime()}")
            .also(nodes::add)

    private fun write(path: Path, text: String) {
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }

    private fun fixedClockEnvironment(): ClockEnvironment = object : ClockEnvironment {
        override fun now(): Instant = Instant.parse("2026-03-10T09:00:00Z")

        override fun currentTimeZone(): TimeZone = TimeZone.UTC
    }
}
