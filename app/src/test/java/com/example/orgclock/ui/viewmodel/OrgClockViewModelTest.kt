package com.example.orgclock.ui.viewmodel

import android.net.Uri
import com.example.orgclock.R
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockOperationCode
import com.example.orgclock.domain.ClockOperationException
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.time.toKotlinInstantCompat
import com.example.orgclock.notification.NotificationDisplayMode
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
import org.mockito.Mockito
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
    fun initialize_withSavedUri_loadsFilesWithOpenClock() = runTest {
        // Use a mocked Uri in JVM tests; Android Uri static factories/fields are not reliable here.
        val savedUri = Mockito.mock(Uri::class.java)
        val vm = testViewModel(
            loadSavedUri = { savedUri },
            openRoot = { uri ->
                Result.success(com.example.orgclock.data.RootAccess(uri, "org"))
            },
            todayProvider = { LocalDate.of(2026, 2, 16) },
            listFiles = { Result.success(listOf(OrgFileEntry("f_today", "2026-02-16.org", null))) },
            listFilesWithOpenClock = { Result.success(setOf("f_today")) },
            listHeadings = { Result.success(emptyList()) },
        )

        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(setOf("f_today"), vm.uiState.value.filesWithOpenClock)
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
    fun startClock_success_refreshesFilesWithOpenClock() = runTest {
        var openFiles = emptySet<String>()
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            listFilesWithOpenClock = { Result.success(openFiles) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()

        openFiles = setOf("f1")
        vm.onAction(OrgClockUiAction.StartClock(sampleHeadings()[1]))
        advanceUntilIdle()

        assertEquals(setOf("f1"), vm.uiState.value.filesWithOpenClock)
    }

    @Test
    fun startClock_failureShowsWarningWhenAlreadyRunning() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            startClock = { _, _ ->
                Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.AlreadyRunning,
                        message = "Clock already running",
                    ),
                )
            },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.StartClock(sampleHeadings()[1]))
        advanceUntilIdle()

        val status = vm.uiState.value.status
        assertEquals(StatusTone.Warning, status.tone)
        assertEquals(R.string.status_start_failed, status.messageResId)
    }

    @Test
    fun startClock_failureShowsErrorForUnknownCode() = runTest {
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            startClock = { _, _ ->
                Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.IoFailed,
                        message = "write failed",
                    ),
                )
            },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.StartClock(sampleHeadings()[1]))
        advanceUntilIdle()

        val status = vm.uiState.value.status
        assertEquals(StatusTone.Error, status.tone)
        assertEquals(R.string.status_start_failed, status.messageResId)
    }

    @Test
    fun startClock_appliesOptimisticUiWhileRequestIsRunning() = runTest {
        val startedAt = ZonedDateTime.of(2026, 2, 16, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            nowProvider = { startedAt },
            startClock = { _, lineIndex ->
                kotlinx.coroutines.delay(1_000)
                Result.success(ClockMutationResult(headingLineIndex = lineIndex, startedAt = startedAt.toKotlinInstantCompat()))
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

    @Test
    fun toggleNotificationEnabled_withoutPermission_requestsPermission() = runTest {
        val vm = testViewModel(
            loadNotificationEnabled = { false },
            notificationPermissionGrantedProvider = { false },
        )
        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        vm.onAction(OrgClockUiAction.ToggleNotificationEnabled(true))

        val state = vm.uiState.value
        assertTrue(state.notificationPermissionRequestPending)
        assertFalse(state.notificationEnabled)
    }

    @Test
    fun notificationPermissionResult_granted_enablesNotification() = runTest {
        var savedEnabled = false
        val vm = testViewModel(
            loadNotificationEnabled = { false },
            notificationPermissionGrantedProvider = { false },
            saveNotificationEnabled = { savedEnabled = it },
        )
        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        vm.onAction(OrgClockUiAction.ToggleNotificationEnabled(true))
        vm.onAction(OrgClockUiAction.NotificationPermissionResult(true))

        val state = vm.uiState.value
        assertTrue(savedEnabled)
        assertTrue(state.notificationEnabled)
        assertTrue(state.notificationPermissionGranted)
    }

    @Test
    fun refreshNotificationPermissionState_updatesPermissionFromProvider() = runTest {
        var granted = false
        val vm = testViewModel(
            notificationPermissionGrantedProvider = { granted },
        )

        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.notificationPermissionGranted)

        granted = true
        vm.onAction(OrgClockUiAction.RefreshNotificationPermissionState)

        assertTrue(vm.uiState.value.notificationPermissionGranted)
    }

    @Test
    fun beginEdit_roundsMinutesBySharedStepBoundaries() = runTest {
        val vm = testViewModel()
        val zone = ZoneId.of("Asia/Tokyo")

        val cases = listOf(
            0 to 0,
            2 to 0,
            58 to 55,
            59 to 55,
        )

        for ((input, expected) in cases) {
            val start = ZonedDateTime.of(2026, 2, 16, 9, input, 0, 0, zone)
            val end = ZonedDateTime.of(2026, 2, 16, 10, input, 0, 0, zone)
            val entry = ClosedClockEntry(
                headingLineIndex = 1,
                clockLineIndex = 3,
                start = start.toKotlinInstantCompat(),
                end = end.toKotlinInstantCompat(),
                durationMinutes = 60,
            )

            vm.onAction(OrgClockUiAction.BeginEdit(entry))
            val draft = vm.uiState.value.editingDraft
            assertEquals(expected, draft?.startMinute)
            assertEquals(expected, draft?.endMinute)
        }
    }

    @Test
    fun initialize_calledTwice_withSavedUri_runsInitializationOnlyOnce() = runTest {
        val savedUri = Mockito.mock(Uri::class.java)
        var openRootCalls = 0
        val vm = testViewModel(
            loadSavedUri = { savedUri },
            openRoot = { uri ->
                openRootCalls += 1
                Result.success(com.example.orgclock.data.RootAccess(uri, "org"))
            },
            listFiles = { Result.success(emptyList()) },
            todayProvider = { LocalDate.of(2026, 2, 16) },
        )

        vm.onAction(OrgClockUiAction.Initialize)
        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(1, openRootCalls)
    }

    @Test
    fun initialize_withSavedUri_openRootFailure_routesToRootSetupAndError() = runTest {
        val savedUri = Mockito.mock(Uri::class.java)
        val vm = testViewModel(
            loadSavedUri = { savedUri },
            openRoot = { Result.failure(IllegalStateException("permission denied")) },
        )

        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(Screen.RootSetup, vm.uiState.value.screen)
        assertEquals(StatusTone.Error, vm.uiState.value.status.tone)
    }

    @Test
    fun initialize_withRootAndListFilesFailure_routesToFilePickerWithError() = runTest {
        val savedUri = Mockito.mock(Uri::class.java)
        val vm = testViewModel(
            loadSavedUri = { savedUri },
            openRoot = { uri -> Result.success(com.example.orgclock.data.RootAccess(uri, "org")) },
            listFiles = { Result.failure(IllegalStateException("list failed")) },
        )

        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(Screen.FilePicker, vm.uiState.value.screen)
        assertEquals(StatusTone.Error, vm.uiState.value.status.tone)
    }

    @Test
    fun initialize_whenTodayFileMissing_setsWarningAndKeepsFilePicker() = runTest {
        val savedUri = Mockito.mock(Uri::class.java)
        val vm = testViewModel(
            loadSavedUri = { savedUri },
            openRoot = { uri -> Result.success(com.example.orgclock.data.RootAccess(uri, "org")) },
            todayProvider = { LocalDate.of(2026, 2, 16) },
            listFiles = { Result.success(listOf(OrgFileEntry("f1", "projects.org", null))) },
        )

        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(Screen.FilePicker, vm.uiState.value.screen)
        assertEquals(StatusTone.Warning, vm.uiState.value.status.tone)
    }

    @Test
    fun stopClock_failure_restoresOptimisticOpenClock() = runTest {
        val headings = sampleHeadingsWithOpenClock()
        val vm = testViewModel(
            listHeadings = { Result.success(headings) },
            stopClock = { _, _ -> Result.failure(IllegalStateException("stop failed")) },
        )

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        val original = vm.uiState.value.headings.first { it.node.lineIndex == 1 }.openClock
        vm.onAction(OrgClockUiAction.StopClock(vm.uiState.value.headings.first { it.node.lineIndex == 1 }))
        advanceUntilIdle()

        assertEquals(original, vm.uiState.value.headings.first { it.node.lineIndex == 1 }.openClock)
        assertEquals(StatusTone.Error, vm.uiState.value.status.tone)
        assertTrue(vm.uiState.value.pendingClockOps.isEmpty())
    }

    @Test
    fun confirmDelete_whenAlreadyInProgress_ignoresSecondTrigger() = runTest {
        var calls = 0
        val vm = testViewModel(
            listHeadings = { Result.success(sampleHeadings()) },
            deleteClosedClock = { _, _, _ ->
                calls += 1
                kotlinx.coroutines.delay(1_000)
                Result.success(Unit)
            },
        )
        val entry = sampleClosedEntry()

        vm.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-02-16.org", null)))
        advanceUntilIdle()
        vm.onAction(OrgClockUiAction.BeginDelete(entry))
        vm.onAction(OrgClockUiAction.ConfirmDelete)
        runCurrent()
        vm.onAction(OrgClockUiAction.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertNull(vm.uiState.value.deletingEntry)
        assertFalse(vm.uiState.value.deletingInProgress)
    }

    @Test
    fun toggleNotificationEnabled_off_clearsAllPendingFlags() = runTest {
        val vm = testViewModel(
            loadNotificationEnabled = { false },
            notificationPermissionGrantedProvider = { false },
        )
        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        vm.onAction(OrgClockUiAction.ToggleNotificationEnabled(true))
        vm.onAction(OrgClockUiAction.ToggleNotificationEnabled(false))

        val state = vm.uiState.value
        assertFalse(state.notificationEnabled)
        assertFalse(state.pendingEnableNotificationAfterPermission)
        assertFalse(state.notificationPermissionRequestPending)
        assertFalse(state.openAppNotificationSettingsPending)
    }

    @Test
    fun notificationPermissionResult_denied_afterPending_showsWarningAndKeepsDisabled() = runTest {
        var savedEnabled = true
        val vm = testViewModel(
            loadNotificationEnabled = { false },
            notificationPermissionGrantedProvider = { false },
            saveNotificationEnabled = { savedEnabled = it },
        )
        vm.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        vm.onAction(OrgClockUiAction.ToggleNotificationEnabled(true))
        vm.onAction(OrgClockUiAction.NotificationPermissionResult(false))

        val state = vm.uiState.value
        assertFalse(state.notificationEnabled)
        assertFalse(state.pendingEnableNotificationAfterPermission)
        assertFalse(state.notificationPermissionRequestPending)
        assertEquals(StatusTone.Warning, state.status.tone)
        assertFalse(savedEnabled)
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
            start = start.toKotlinInstantCompat(),
            end = end.toKotlinInstantCompat(),
            durationMinutes = 30,
        )
    }

    private fun sampleHeadingsWithOpenClock(): List<HeadingViewItem> {
        val zone = ZoneId.of("Asia/Tokyo")
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
            openClock = com.example.orgclock.model.OpenClockState(
                ZonedDateTime.of(2026, 2, 16, 9, 0, 0, 0, zone).toKotlinInstantCompat(),
            ),
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
        listFilesWithOpenClock: suspend () -> Result<Set<String>> = { Result.success(emptySet()) },
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
        loadNotificationEnabled: () -> Boolean = { true },
        saveNotificationEnabled: (Boolean) -> Unit = {},
        loadNotificationDisplayMode: () -> NotificationDisplayMode = { NotificationDisplayMode.ActiveOnly },
        saveNotificationDisplayMode: (NotificationDisplayMode) -> Unit = {},
        notificationPermissionGrantedProvider: () -> Boolean = { true },
        nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
        todayProvider: () -> LocalDate = { LocalDate.now() },
    ): OrgClockViewModel {
        return OrgClockViewModel(
            loadSavedUri = loadSavedUri,
            saveUri = saveUri,
            openRoot = openRoot,
            listFiles = listFiles,
            listFilesWithOpenClock = listFilesWithOpenClock,
            listHeadings = listHeadings,
            startClock = startClock,
            stopClock = stopClock,
            cancelClock = cancelClock,
            listClosedClocks = listClosedClocks,
            editClosedClock = editClosedClock,
            deleteClosedClock = deleteClosedClock,
            createL1Heading = createL1Heading,
            createL2Heading = createL2Heading,
            loadNotificationEnabled = loadNotificationEnabled,
            saveNotificationEnabled = saveNotificationEnabled,
            loadNotificationDisplayMode = loadNotificationDisplayMode,
            saveNotificationDisplayMode = saveNotificationDisplayMode,
            notificationPermissionGrantedProvider = notificationPermissionGrantedProvider,
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
