package com.example.orgclock.ui.viewmodel

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun submitCreateL2Heading_callsUseCaseAndClosesDialog() = runTest {
        var called = false
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            createL2Heading = { fileId, parentLine, title, attachTplTag ->
                called = fileId == "f1" && parentLine == 0 && title == "Project B" && !attachTplTag
                Result.success(Unit)
            },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.OpenCreateL2Dialog(sampleHeadings().first()))
        vm.onAction(OrgClockUiAction.UpdateCreateHeadingTitle("Project B"))
        vm.onAction(OrgClockUiAction.SubmitCreateHeading)
        advanceUntilIdle()

        assertTrue(called)
        assertNull(vm.uiState.value.createHeadingDialog)
        assertEquals(StatusTone.Success, vm.uiState.value.status.tone)
    }

    @Test
    fun submitCreateL1Heading_failureKeepsDialogAndShowsWarning() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            createL1Heading = { _, _, _ -> Result.failure(IllegalArgumentException("already exists")) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.OpenCreateL1Dialog)
        vm.onAction(OrgClockUiAction.UpdateCreateHeadingTitle("Work"))
        vm.onAction(OrgClockUiAction.SubmitCreateHeading)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.createHeadingDialog != null)
        assertEquals(StatusTone.Warning, vm.uiState.value.status.tone)
    }

    @Test
    fun openCreateHeadingDialog_dailyFile_enablesTplOption() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.OpenCreateL1Dialog)

        val dialog = vm.uiState.value.createHeadingDialog
        assertTrue(dialog?.canAttachTplTag == true)
        assertTrue(dialog?.attachTplTag == false)
    }

    @Test
    fun openCreateHeadingDialog_nonDailyFile_disablesTplOption() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "projects.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.OpenCreateL1Dialog)

        val dialog = vm.uiState.value.createHeadingDialog
        assertTrue(dialog?.canAttachTplTag == false)
        assertTrue(dialog?.attachTplTag == false)
    }

    @Test
    fun submitCreateL1Heading_withTplEnabled_passesFlagToUseCase() = runTest {
        var called = false
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            createL1Heading = { fileId, title, attachTplTag ->
                called = fileId == "f1" && title == "Template Seed" && attachTplTag
                Result.success(Unit)
            },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.OpenCreateL1Dialog)
        vm.onAction(OrgClockUiAction.UpdateCreateHeadingTitle("Template Seed"))
        vm.onAction(OrgClockUiAction.SetCreateHeadingTplTag(true))
        vm.onAction(OrgClockUiAction.SubmitCreateHeading)
        advanceUntilIdle()

        assertTrue(called)
        assertEquals(StatusTone.Success, vm.uiState.value.status.tone)
    }

    @Test
    fun beginDelete_setsDeletingEntry() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
        )
        val entry = sampleClosedEntry()

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.BeginDelete(entry))

        assertEquals(entry, vm.uiState.value.deletingEntry)
        assertFalse(vm.uiState.value.deletingInProgress)
    }

    @Test
    fun confirmDelete_success_clearsDialogAndShowsSuccess() = runTest {
        var called = false
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            deleteClosedClock = { fileId, headingLineIndex, clockLineIndex ->
                called = fileId == "f1" && headingLineIndex == 1 && clockLineIndex == 3
                Result.success(Unit)
            },
        )
        val entry = sampleClosedEntry()

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.BeginDelete(entry))
        vm.onAction(OrgClockUiAction.ConfirmDelete)
        advanceUntilIdle()

        assertTrue(called)
        assertNull(vm.uiState.value.deletingEntry)
        assertFalse(vm.uiState.value.deletingInProgress)
        assertEquals(StatusTone.Success, vm.uiState.value.status.tone)
    }

    @Test
    fun confirmDelete_failure_showsErrorAndKeepsDialog() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            deleteClosedClock = { _, _, _ -> Result.failure(IllegalStateException("boom")) },
        )
        val entry = sampleClosedEntry()

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.BeginDelete(entry))
        vm.onAction(OrgClockUiAction.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(entry, vm.uiState.value.deletingEntry)
        assertFalse(vm.uiState.value.deletingInProgress)
        assertEquals(StatusTone.Error, vm.uiState.value.status.tone)
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

    private fun sampleClosedEntry(): ClosedClockEntry {
        val zone = ZoneId.of("Asia/Tokyo")
        val start = ZonedDateTime.of(2026, 2, 16, 9, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 2, 16, 9, 30, 0, 0, zone)
        return ClosedClockEntry(
            headingLineIndex = 1,
            clockLineIndex = 3,
            start = start,
            end = end,
            durationMinutes = 30,
        )
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
        deleteClosedClock: suspend (String, Int, Int) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
        createL1Heading: suspend (String, String, Boolean) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
        createL2Heading: suspend (String, Int, String, Boolean) -> Result<Unit> = { _, _, _, _ -> Result.success(Unit) },
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
            deleteClosedClock = deleteClosedClock,
            createL1Heading = createL1Heading,
            createL2Heading = createL2Heading,
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
