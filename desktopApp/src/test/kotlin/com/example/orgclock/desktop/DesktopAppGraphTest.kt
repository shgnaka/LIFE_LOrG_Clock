package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import kotlinx.coroutines.test.runTest
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
    fun restoresSavedRootOnStartup() = runTest {
        val root = tempRoot()
        write(root.resolve("2026-03-10.org"), "* Work\n** Task\n")
        val store = DesktopSettingsStore(testNode())
        store.save(DesktopHostSettings(lastRootReference = RootReference(root.toString())))

        val graph = DesktopAppGraph(settingsStore = store)
        val snapshot = graph.snapshot()

        assertEquals(Screen.FilePicker, snapshot.presentationState.screen)
        assertEquals(root.toString(), snapshot.presentationState.rootReference?.rawValue)
        assertEquals(StatusMessageKey.RootSet, snapshot.presentationState.status.text.key)
        assertEquals(1, snapshot.files.size)
    }

    @Test
    fun dependenciesProvideDesktopClockOperations() = runTest {
        val root = tempRoot()
        write(root.resolve("2026-03-10.org"), "* Work\n** Task\n")
        val graph = DesktopAppGraph(settingsStore = DesktopSettingsStore(testNode()))
        val dependencies = graph.dependencies()

        assertTrue(dependencies.openRoot(RootReference(root.toString())).isSuccess)

        val files = dependencies.listFiles().getOrThrow()
        assertEquals(1, files.size)

        val file = files.single()
        assertTrue(
            dependencies.startClock(file.fileId, com.example.orgclock.model.HeadingPath.parse("Work/Task")).isSuccess,
        )

        val filesWithOpenClock = dependencies.listFilesWithOpenClock().getOrThrow()
        assertEquals(setOf(file.fileId), filesWithOpenClock)
    }

    private fun tempRoot(): Path = createTempDirectory("desktop-graph-test").also(tempRoots::add)

    private fun testNode(): Preferences =
        Preferences.userRoot()
            .node("com/example/orgclock/desktop/test/${System.nanoTime()}")
            .also(nodes::add)

    private fun write(path: Path, text: String) {
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }
}
