package com.example.orgclock.desktop

import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.template.RootScheduleConfig
import com.example.orgclock.template.TemplateReferenceMode
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
        val scheduleStore = DesktopRootScheduleStore(testNode())

        val graph = DesktopAppGraph(
            settingsStore = store,
            rootScheduleStore = scheduleStore,
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
        val scheduleStore = DesktopRootScheduleStore(testNode())
        val graph = DesktopAppGraph(
            settingsStore = settingsStore,
            rootScheduleStore = scheduleStore,
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun selectingTemplateFile_persistsAcrossDesktopStoreRecreation() = runTest {
        val root = tempRoot()
        val daily = root.resolve("2026-03-10.org")
        val template = root.resolve("project-template.org")
        write(daily, "* Work\n** Task\n")
        write(template, "* Template\n")
        val settingsStore = DesktopSettingsStore(testNode())
        val scheduleStore = DesktopRootScheduleStore(testNode())
        val graph = DesktopAppGraph(
            settingsStore = settingsStore,
            rootScheduleStore = scheduleStore,
            clockEnvironment = fixedClockEnvironment(),
            watchRootChanges = false,
        )
        val store = graph.createStore(this)

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.PickRoot(RootReference(root.toString())))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenTemplateFilePicker)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.SelectTemplateFile(store.uiState.value.templateCandidateFiles.first { it.fileId == template.toString() }))
        advanceUntilIdle()

        assertEquals(template.toString(), scheduleStore.load(root.toString()).templateFileUri)
        assertEquals(TemplateReferenceMode.Explicit, store.uiState.value.templateFileStatus.referenceMode)

        val restoredState = graph.createStore(this).run {
            onAction(OrgClockUiAction.Initialize)
            advanceUntilIdle()
            uiState.value
        }
        assertEquals(template.toString(), restoredState.templateFileStatus.fileId)
        assertEquals(TemplateReferenceMode.Explicit, restoredState.templateFileStatus.referenceMode)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clearingExplicitTemplateFile_restoresLegacyHiddenFileMode() = runTest {
        val root = tempRoot()
        val hiddenTemplate = root.resolve(".orgclock-template.org")
        write(root.resolve("2026-03-10.org"), "* Work\n** Task\n")
        write(hiddenTemplate, "* Default Template\n")
        val settingsStore = DesktopSettingsStore(testNode())
        val scheduleStore = DesktopRootScheduleStore(testNode())
        scheduleStore.save(
            RootScheduleConfig(
                rootUri = root.toString(),
                templateFileUri = root.resolve("project-template.org").toString(),
            ),
        )
        settingsStore.save(DesktopHostSettings(lastRootReference = RootReference(root.toString())))
        val graph = DesktopAppGraph(
            settingsStore = settingsStore,
            rootScheduleStore = scheduleStore,
            clockEnvironment = fixedClockEnvironment(),
            watchRootChanges = false,
        )
        val store = graph.createStore(this)

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.ClearExplicitTemplateFile)
        advanceUntilIdle()

        assertEquals(null, scheduleStore.load(root.toString()).templateFileUri)
        assertEquals(TemplateReferenceMode.LegacyHiddenFile, store.uiState.value.templateFileStatus.referenceMode)
        assertEquals(hiddenTemplate.toString(), store.uiState.value.templateFileStatus.fileId)
        assertEquals(StatusMessageKey.TemplateFileSelectionCleared, store.uiState.value.status.text.key)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun creatingDefaultTemplateFile_makesHiddenTemplateAvailable() = runTest {
        val root = tempRoot()
        write(root.resolve("2026-03-10.org"), "* Work\n** Task\n")
        val graph = DesktopAppGraph(
            settingsStore = DesktopSettingsStore(testNode()).also {
                it.save(DesktopHostSettings(lastRootReference = RootReference(root.toString())))
            },
            rootScheduleStore = DesktopRootScheduleStore(testNode()),
            clockEnvironment = fixedClockEnvironment(),
            watchRootChanges = false,
        )
        val store = graph.createStore(this)

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.CreateDefaultTemplateFile)
        advanceUntilIdle()

        assertEquals(StatusMessageKey.TemplateFileCreated, store.uiState.value.status.text.key)
        assertEquals(TemplateReferenceMode.LegacyHiddenFile, store.uiState.value.templateFileStatus.referenceMode)
        assertEquals(root.resolve(".orgclock-template.org").toString(), store.uiState.value.templateFileStatus.fileId)
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
