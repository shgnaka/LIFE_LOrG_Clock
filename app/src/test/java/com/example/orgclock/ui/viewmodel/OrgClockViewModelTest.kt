package com.example.orgclock.ui.viewmodel

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class OrgClockViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialize_withoutSavedUri_staysOnRootSetup() = runTest {
        val vm = testViewModel(loadSavedUri = { null })

        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(Screen.RootSetup, vm.uiState.value.screen)
    }

    @Test
    fun selectFile_loadsHeadingsAndRoutesToHeadingList() = runTest {
        val file = OrgFileEntry("f_today", "2026-02-16.org", null)
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(file))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(Screen.HeadingList, state.screen)
        assertEquals("f_today", state.selectedFile?.fileId)
        assertEquals(StatusTone.Success, state.status.tone)
    }

    @Test
    fun collapseAll_setsAllL1AsCollapsed() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.CollapseAll)

        assertTrue("Work" in vm.uiState.value.collapsedL1)
    }

    @Test
    fun startClock_failureShowsWarningWhenAlreadyRunning() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            startClock = { _, _ -> Result.failure(IllegalStateException("Clock already running")) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.StartClock(sampleHeadings()[1]))
        advanceUntilIdle()

        val status = vm.uiState.value.status
        assertEquals(StatusTone.Warning, status.tone)
        assertTrue(status.message.contains("Start failed"))
    }

    @Test
    fun startClock_appliesOptimisticUiWhileRequestIsRunning() = runTest {
        val startedAt = ZonedDateTime.of(2026, 2, 16, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            nowProvider = { startedAt },
            startClock = { _, lineIndex ->
                kotlinx.coroutines.delay(1_000)
                Result.success(ClockMutationResult(headingLineIndex = lineIndex, startedAt = startedAt))
            },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.StartClock(sampleHeadings()[1]))
        runCurrent()

        val pendingState = vm.uiState.value
        assertTrue(1 in pendingState.pendingClockOps)
        assertTrue(pendingState.headings.first { it.node.lineIndex == 1 }.openClock != null)

        advanceUntilIdle()

        assertTrue(vm.uiState.value.pendingClockOps.isEmpty())
    }

    private fun sampleHeadings(): List<HeadingViewItem> {
        val root = HeadingViewItem(
            node = HeadingNode(
                lineIndex = 0,
                level = 1,
                title = "Work",
                path = HeadingPath.parse("Work"),
                parentL1 = "Work",
            ),
            canStart = false,
            openClock = null,
        )
        val child = HeadingViewItem(
            node = HeadingNode(
                lineIndex = 1,
                level = 2,
                title = "Project A",
                path = HeadingPath.parse("Work/Project A"),
                parentL1 = "Work",
            ),
            canStart = true,
            openClock = null,
        )
        return listOf(root, child)
    }

    private fun testViewModel(
        loadSavedUri: () -> android.net.Uri? = { null },
        saveUri: (android.net.Uri) -> Unit = {},
        openRoot: suspend (android.net.Uri) -> Result<com.example.orgclock.data.RootAccess> = {
            Result.failure(UnsupportedOperationException())
        },
        listFiles: suspend () -> Result<List<OrgFileEntry>> = { Result.success(emptyList()) },
        listHeadings: suspend (String) -> Result<List<HeadingViewItem>> = { Result.success(emptyList()) },
        startClock: suspend (String, Int) -> Result<ClockMutationResult> = { _, lineIndex ->
            Result.success(ClockMutationResult(headingLineIndex = lineIndex))
        },
        stopClock: suspend (String, Int) -> Result<ClockMutationResult> = { _, lineIndex ->
            Result.success(ClockMutationResult(headingLineIndex = lineIndex))
        },
        cancelClock: suspend (String, Int) -> Result<ClockMutationResult> = { _, lineIndex ->
            Result.success(ClockMutationResult(headingLineIndex = lineIndex))
        },
        listClosedClocks: suspend (String, Int) -> Result<List<com.example.orgclock.model.ClosedClockEntry>> = { _, _ -> Result.success(emptyList()) },
        editClosedClock: suspend (String, Int, Int, java.time.ZonedDateTime, java.time.ZonedDateTime) -> Result<Unit> = { _, _, _, _, _ -> Result.success(Unit) },
        nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
        todayProvider: () -> LocalDate = { LocalDate.now() },
    ): OrgClockViewModel {
        return OrgClockViewModel(
            loadSavedUri = loadSavedUri,
            saveUri = saveUri,
            openRoot = openRoot,
            listFiles = listFiles,
            listHeadings = listHeadings,
            startClock = startClock,
            stopClock = stopClock,
            cancelClock = cancelClock,
            listClosedClocks = listClosedClocks,
            editClosedClock = editClosedClock,
            nowProvider = nowProvider,
            todayProvider = todayProvider,
            showPerfOverlay = true,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val dispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
