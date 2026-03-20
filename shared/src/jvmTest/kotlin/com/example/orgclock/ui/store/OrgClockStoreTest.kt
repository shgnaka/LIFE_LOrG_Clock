package com.example.orgclock.ui.store

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.domain.ClockOperationCode
import com.example.orgclock.domain.ClockOperationException
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.template.RootScheduleConfig
import com.example.orgclock.template.TemplateAutoGenerationRuntimeState
import com.example.orgclock.template.TemplateAvailability
import com.example.orgclock.template.TemplateFileStatus
import com.example.orgclock.template.TemplateReferenceMode
import com.example.orgclock.ui.state.OrgDivergenceCategory
import com.example.orgclock.ui.state.OrgDivergenceRecommendedAction
import com.example.orgclock.ui.state.OrgDivergenceSeverity
import com.example.orgclock.ui.state.OrgDivergenceSnapshot
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.ExternalChangeNotice
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.sync.SyncIntegrationSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OrgClockStoreTest {
    @Test
    fun initialize_withoutSavedRoot_routesToRootSetup() = runTest {
        val store = testStore(this)

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(Screen.RootSetup, store.uiState.value.screen)
        assertNull(store.uiState.value.rootReference)
    }

    @Test
    fun initialize_withSavedRoot_loadsTodayFileAndOpenClockState() = runTest {
        val store = testStore(
            scope = this,
            loadSavedRootReference = { RootReference("/tmp/org-root") },
            openRoot = { Result.success(Unit) },
            listFiles = { Result.success(listOf(OrgFileEntry("f1", "2026-03-10.org", null))) },
            listFilesWithOpenClock = { Result.success(setOf("f1")) },
            listHeadings = { Result.success(sampleHeadings()) },
        )

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        val state = store.uiState.value
        assertEquals(Screen.HeadingList, state.screen)
        assertEquals("/tmp/org-root", state.rootReference?.rawValue)
        assertEquals(StatusMessageKey.LoadedFile, state.status.text.key)
        assertEquals("2026-03-10.org", state.selectedFile?.displayName)
        assertEquals(setOf("f1"), state.filesWithOpenClock)
    }

    @Test
    fun externalChange_marksStateStale_andRefreshKeepsSelectedFileWhenItStillExists() = runTest {
        val externalChanges = MutableStateFlow<ExternalChangeNotice?>(null)
        val selectedFile = OrgFileEntry("f2", "projects.org", null)
        val storeScope = CoroutineScope(coroutineContext + Job())
        val store = testStore(
            scope = storeScope,
            listFiles = {
                Result.success(
                    listOf(
                        OrgFileEntry("f1", "2026-03-10.org", null),
                        selectedFile,
                    ),
                )
            },
            listHeadings = { fileId ->
                Result.success(
                    if (fileId == "f2") sampleProjectHeadings() else sampleHeadings()
                )
            },
            externalChangeFlow = externalChanges,
        )

        store.onAction(OrgClockUiAction.SelectFile(selectedFile))
        advanceUntilIdle()

        externalChanges.value = ExternalChangeNotice(
            revision = 1,
            changedFileIds = setOf("f2"),
        )
        advanceUntilIdle()

        assertTrue(store.uiState.value.externalChangePending)
        assertTrue(store.uiState.value.externalChangeAffectsSelectedFile)
        assertEquals(StatusMessageKey.SelectedFileChangedExternally, store.uiState.value.status.text.key)
        assertNotNull(store.uiState.value.divergenceSnapshot)
        assertEquals(OrgDivergenceCategory.ExternalChange, store.uiState.value.divergenceSnapshot?.category)
        assertEquals(OrgDivergenceSeverity.Warning, store.uiState.value.divergenceSnapshot?.severity)
        assertEquals(OrgDivergenceRecommendedAction.ReloadFromDisk, store.uiState.value.divergenceSnapshot?.recommendedAction)

        store.onAction(OrgClockUiAction.RefreshFiles)
        advanceUntilIdle()

        assertFalse(store.uiState.value.externalChangePending)
        assertEquals("f2", store.uiState.value.selectedFile?.fileId)
        assertEquals(Screen.HeadingList, store.uiState.value.screen)

        externalChanges.value = ExternalChangeNotice(
            revision = 2,
            changedFileIds = setOf("f2"),
        )
        advanceUntilIdle()
        assertTrue(store.uiState.value.externalChangePending)

        store.onAction(OrgClockUiAction.ReloadFromDisk)
        advanceUntilIdle()

        assertFalse(store.uiState.value.externalChangePending)
        assertNull(store.uiState.value.divergenceSnapshot)
        assertEquals(StatusMessageKey.LoadedFile, store.uiState.value.status.text.key)
        storeScope.cancel()
    }

    @Test
    fun reloadFromDisk_keepsRecoveryDivergenceWhenMismatchStillExists() = runTest {
        val selectedFile = OrgFileEntry("f2", "projects.org", null)
        val store = testStore(
            scope = this,
            listFiles = {
                Result.success(
                    listOf(
                        OrgFileEntry("f1", "2026-03-10.org", null),
                        selectedFile,
                    ),
                )
            },
            listHeadings = { fileId ->
                Result.success(
                    if (fileId == "f2") {
                        listOf(
                            HeadingViewItem(
                                node = HeadingNode(
                                    lineIndex = 0,
                                    level = 1,
                                    title = "Projects",
                                    path = HeadingPath.parse("Projects"),
                                    parentL1 = "Projects",
                                ),
                                canStart = false,
                                openClock = null,
                            ),
                        )
                    } else {
                        sampleHeadings()
                    },
                )
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(selectedFile))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.SelectHeading(HeadingPath.parse("Projects/Backlog")))

        store.onAction(OrgClockUiAction.ReloadFromDisk)
        advanceUntilIdle()

        val divergence = store.uiState.value.divergenceSnapshot
        assertNotNull(divergence)
        assertEquals(OrgDivergenceCategory.ContentMismatch, divergence.category)
        assertEquals(OrgDivergenceSeverity.RecoveryRequired, divergence.severity)
        assertEquals(OrgDivergenceRecommendedAction.ReloadFromDisk, divergence.recommendedAction)
        assertTrue(store.uiState.value.externalChangePending)
    }

    @Test
    fun refreshSyncDebug_clearsStaleDivergenceWhenSnapshotRecovers() = runTest {
        val syncSnapshotFlow = MutableStateFlow(
            SyncIntegrationSnapshot(
                orgDivergenceSnapshot = OrgDivergenceSnapshot(
                    severity = OrgDivergenceSeverity.Warning,
                    category = OrgDivergenceCategory.ExternalChange,
                    reason = "Selected file changed on disk",
                    affectedFileIds = setOf("f2"),
                    detectedAtEpochMs = 1L,
                    recommendedAction = OrgDivergenceRecommendedAction.ReloadFromDisk,
                ),
            ),
        )
        val selectedFile = OrgFileEntry("f2", "projects.org", null)
        val store = testStore(
            scope = this,
            listFiles = {
                Result.success(
                    listOf(
                        OrgFileEntry("f1", "2026-03-10.org", null),
                        selectedFile,
                    ),
                )
            },
            listHeadings = { Result.success(sampleProjectHeadings()) },
            syncSnapshotFlow = syncSnapshotFlow,
        )

        store.onAction(OrgClockUiAction.SelectFile(selectedFile))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.RefreshSyncDebug)

        assertTrue(store.uiState.value.externalChangePending)
        assertNotNull(store.uiState.value.divergenceSnapshot)

        syncSnapshotFlow.value = SyncIntegrationSnapshot()
        store.onAction(OrgClockUiAction.RefreshSyncDebug)

        val state = store.uiState.value
        assertFalse(state.externalChangePending)
        assertNull(state.divergenceSnapshot)
        assertTrue(state.externalChangeChangedFileIds.isEmpty())
        assertFalse(state.externalChangeAffectsSelectedFile)
    }

    @Test
    fun refreshHeadings_whenSelectedHeadingDisappears_marksContentMismatchRecoveryRequired() = runTest {
        var mismatch = false
        val selectedFile = OrgFileEntry("f2", "projects.org", null)
        val store = testStore(
            scope = this,
            listFiles = {
                Result.success(
                    listOf(
                        OrgFileEntry("f1", "2026-03-10.org", null),
                        selectedFile,
                    ),
                )
            },
            listHeadings = { fileId ->
                Result.success(
                    when {
                        fileId == "f2" && mismatch -> listOf(
                            HeadingViewItem(
                                node = HeadingNode(
                                    lineIndex = 0,
                                    level = 1,
                                    title = "Projects",
                                    path = HeadingPath.parse("Projects"),
                                    parentL1 = "Projects",
                                ),
                                canStart = false,
                                openClock = null,
                            ),
                        )
                        fileId == "f2" -> sampleProjectHeadings()
                        else -> sampleHeadings()
                    },
                )
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(selectedFile))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.SelectHeading(HeadingPath.parse("Projects/Backlog")))

        mismatch = true
        store.onAction(OrgClockUiAction.RefreshHeadings)
        advanceUntilIdle()

        val divergence = store.uiState.value.divergenceSnapshot
        assertNotNull(divergence)
        assertEquals(OrgDivergenceCategory.ContentMismatch, divergence.category)
        assertEquals(OrgDivergenceSeverity.RecoveryRequired, divergence.severity)
        assertEquals(OrgDivergenceRecommendedAction.ReloadFromDisk, divergence.recommendedAction)
        assertTrue(store.uiState.value.externalChangePending)
    }

    @Test
    fun refreshFiles_whenSelectedFileWasRemoved_routesToFilePickerWithWarning() = runTest {
        var listed = listOf(
            OrgFileEntry("f1", "2026-03-10.org", null),
            OrgFileEntry("f2", "projects.org", null),
        )
        val selectedFile = OrgFileEntry("f2", "projects.org", null)
        val store = testStore(
            scope = this,
            listFiles = { Result.success(listed) },
            listHeadings = { Result.success(sampleProjectHeadings()) },
        )

        store.onAction(OrgClockUiAction.SelectFile(selectedFile))
        advanceUntilIdle()

        listed = listOf(OrgFileEntry("f1", "2026-03-10.org", null))
        store.onAction(OrgClockUiAction.RefreshFiles)
        advanceUntilIdle()

        assertEquals(Screen.FilePicker, store.uiState.value.screen)
        assertEquals(StatusMessageKey.SelectedFileNoLongerAvailable, store.uiState.value.status.text.key)
        assertEquals("projects.org", store.uiState.value.status.text.args.single())
        assertNull(store.uiState.value.selectedFile)
    }

    @Test
    fun pickRoot_failure_routesBackToRootSetupWithError() = runTest {
        val store = testStore(
            scope = this,
            openRoot = { Result.failure(IllegalStateException("permission denied")) },
        )

        store.onAction(OrgClockUiAction.PickRoot(RootReference("/tmp/blocked")))
        advanceUntilIdle()

        assertEquals(Screen.RootSetup, store.uiState.value.screen)
        assertEquals(StatusMessageKey.FailedOpenRoot, store.uiState.value.status.text.key)
    }

    @Test
    fun refreshFiles_whenTodayFileMissing_keepsFilePickerAndWarning() = runTest {
        val store = testStore(
            scope = this,
            loadSavedRootReference = { RootReference("/tmp/org-root") },
            openRoot = { Result.success(Unit) },
            listFiles = { Result.success(listOf(OrgFileEntry("f2", "projects.org", null))) },
        )

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        assertEquals(Screen.FilePicker, store.uiState.value.screen)
        assertEquals(StatusMessageKey.TodayFileNotFound, store.uiState.value.status.text.key)
    }

    @Test
    fun selectFile_loadsHeadingsAndRoutesToHeadingList() = runTest {
        val file = OrgFileEntry("f1", "2026-03-10.org", null)
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
        )

        store.onAction(OrgClockUiAction.SelectFile(file))
        advanceUntilIdle()

        val state = store.uiState.value
        assertEquals(Screen.HeadingList, state.screen)
        assertEquals(file, state.selectedFile)
        assertEquals(2, state.headings.size)
    }

    @Test
    fun startClock_appliesOptimisticUiAndDeduplicatesInFlightRequests() = runTest {
        var calls = 0
        val startedAt = Instant.parse("2026-03-10T09:00:00Z")
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
            startClock = { _, _ ->
                calls += 1
                delay(1_000)
                Result.success(ClockMutationResult(startedAt = startedAt))
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()

        val path = HeadingPath.parse("Work/Project A")
        store.onAction(OrgClockUiAction.StartClock(path))
        store.onAction(OrgClockUiAction.StartClock(path))
        runCurrent()

        val pending = store.uiState.value
        assertEquals(1, calls)
        assertTrue(path in pending.pendingClockOps)
        assertNotNull(pending.headings.first { it.node.path == path }.openClock)

        advanceUntilIdle()

        val finalState = store.uiState.value
        assertTrue(finalState.pendingClockOps.isEmpty())
        assertEquals(StatusMessageKey.ClockStarted, finalState.status.text.key)
    }

    @Test
    fun startClock_whenAlreadyRunning_showsWarning() = runTest {
        val store = testStore(
            scope = this,
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

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.StartClock(HeadingPath.parse("Work/Project A")))
        advanceUntilIdle()

        assertEquals(StatusMessageKey.StartFailed, store.uiState.value.status.text.key)
        assertEquals(com.example.orgclock.presentation.StatusTone.Warning, store.uiState.value.status.tone)
    }

    @Test
    fun startClock_whenSaveRoundTripMismatch_marksRecoveryRequiredDivergence() = runTest {
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
            startClock = { _, _ ->
                Result.failure(
                    ClockOperationException(
                        code = ClockOperationCode.SaveRoundTripMismatch,
                        message = "Saved content changed after round-trip verification",
                    ),
                )
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.StartClock(HeadingPath.parse("Work/Project A")))
        advanceUntilIdle()

        val divergence = store.uiState.value.divergenceSnapshot
        assertNotNull(divergence)
        assertEquals(OrgDivergenceCategory.SaveRoundTripMismatch, divergence.category)
        assertEquals(OrgDivergenceSeverity.RecoveryRequired, divergence.severity)
        assertEquals(OrgDivergenceRecommendedAction.ReloadFromDisk, divergence.recommendedAction)
        assertTrue(store.uiState.value.externalChangePending)
        assertEquals(StatusMessageKey.StartFailed, store.uiState.value.status.text.key)
    }

    @Test
    fun stopClock_failure_restoresPreviousOpenClock() = runTest {
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadingsWithOpenClock()) },
            stopClock = { _, _ -> Result.failure(IllegalStateException("stop failed")) },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()

        val path = HeadingPath.parse("Work/Project A")
        val original = store.uiState.value.headings.first { it.node.path == path }.openClock
        store.onAction(OrgClockUiAction.StopClock(path))
        advanceUntilIdle()

        assertEquals(original, store.uiState.value.headings.first { it.node.path == path }.openClock)
        assertEquals(StatusMessageKey.StopFailed, store.uiState.value.status.text.key)
        assertTrue(store.uiState.value.pendingClockOps.isEmpty())
    }

    @Test
    fun openHistory_andDeleteFlow_refreshesEntriesAndHeadings() = runTest {
        var deleteCalls = 0
        var headingLoads = 0
        val initialEntry = sampleClosedEntry(clockLineIndex = 3)
        val remainingEntry = sampleClosedEntry(clockLineIndex = 5)
        val store = testStore(
            scope = this,
            listHeadings = {
                headingLoads += 1
                Result.success(sampleHeadings())
            },
            listClosedClocks = { _, _ ->
                Result.success(if (deleteCalls == 0) listOf(initialEntry, remainingEntry) else listOf(remainingEntry))
            },
            deleteClosedClock = { fileId, headingPath, clockLineIndex ->
                deleteCalls += 1
                assertEquals("f1", fileId)
                assertEquals(HeadingPath.parse("Work/Project A"), headingPath)
                assertEquals(3, clockLineIndex)
                Result.success(Unit)
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenHistory(sampleHeadings()[1]))
        advanceUntilIdle()
        assertEquals(2, store.uiState.value.historyEntries.size)

        store.onAction(OrgClockUiAction.BeginDelete(initialEntry))
        store.onAction(OrgClockUiAction.ConfirmDelete)
        advanceUntilIdle()

        assertEquals(1, deleteCalls)
        assertEquals(1, store.uiState.value.historyEntries.size)
        assertNull(store.uiState.value.deletingEntry)
        assertFalse(store.uiState.value.deletingInProgress)
        assertEquals(StatusMessageKey.ClockHistoryDeleted, store.uiState.value.status.text.key)
        assertTrue(headingLoads >= 2)
    }

    @Test
    fun saveEdit_callsUseCaseWithDraftTimes() = runTest {
        var edited: Pair<Instant, Instant>? = null
        val entry = sampleClosedEntry(clockLineIndex = 3)
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
            listClosedClocks = { _, _ -> Result.success(listOf(entry)) },
            editClosedClock = { fileId, headingPath, clockLineIndex, start, end ->
                assertEquals("f1", fileId)
                assertEquals(HeadingPath.parse("Work/Project A"), headingPath)
                assertEquals(3, clockLineIndex)
                edited = start to end
                Result.success(Unit)
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenHistory(sampleHeadings()[1]))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.BeginEdit(entry))
        store.onAction(OrgClockUiAction.SelectStartMinute(5))
        store.onAction(OrgClockUiAction.SelectEndMinute(35))
        store.onAction(OrgClockUiAction.SaveEdit)
        advanceUntilIdle()

        assertEquals(
            Instant.parse("2026-03-10T00:05:00Z") to Instant.parse("2026-03-10T00:35:00Z"),
            edited,
        )
        assertNull(store.uiState.value.editingEntry)
        assertEquals(StatusMessageKey.ClockHistoryUpdated, store.uiState.value.status.text.key)
    }

    @Test
    fun saveEdit_failure_keepsDraftAndFailureMessageForRetry() = runTest {
        val entry = sampleClosedEntry(clockLineIndex = 3)
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
            listClosedClocks = { _, _ -> Result.success(listOf(entry)) },
            editClosedClock = { _, _, _, _, _ ->
                Result.failure(IllegalStateException("File changed by another process."))
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenHistory(sampleHeadings()[1]))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.BeginEdit(entry))
        store.onAction(OrgClockUiAction.SelectStartMinute(5))
        store.onAction(OrgClockUiAction.SaveEdit)
        advanceUntilIdle()

        val state = store.uiState.value
        assertEquals(entry, state.editingEntry)
        assertEquals(5, state.editingDraft?.startMinute)
        assertEquals("File changed by another process.", state.editFailureMessage)
        assertEquals(StatusMessageKey.UpdateFailed, state.status.text.key)
    }

    @Test
    fun delete_failure_keepsEntryAndFailureMessageForRetry() = runTest {
        val entry = sampleClosedEntry(clockLineIndex = 3)
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
            listClosedClocks = { _, _ -> Result.success(listOf(entry)) },
            deleteClosedClock = { _, _, _ ->
                Result.failure(IllegalStateException("Disk I/O error"))
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenHistory(sampleHeadings()[1]))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.BeginDelete(entry))
        store.onAction(OrgClockUiAction.ConfirmDelete)
        advanceUntilIdle()

        val state = store.uiState.value
        assertEquals(entry, state.deletingEntry)
        assertEquals("Disk I/O error", state.deleteFailureMessage)
        assertEquals(StatusMessageKey.DeleteFailed, state.status.text.key)
    }

    @Test
    fun refreshFiles_rebindsHistoryEditContextAndKeepsDraft() = runTest {
        val originalEntry = sampleClosedEntry(clockLineIndex = 3)
        val refreshedEntry = sampleClosedEntry(clockLineIndex = 7)
        var historyLoads = 0
        val store = testStore(
            scope = this,
            listFiles = { Result.success(listOf(OrgFileEntry("f1", "2026-03-10.org", null))) },
            listHeadings = { Result.success(sampleHeadings()) },
            listClosedClocks = { _, _ ->
                historyLoads += 1
                Result.success(if (historyLoads == 1) listOf(originalEntry) else listOf(refreshedEntry))
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenHistory(sampleHeadings()[1]))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.BeginEdit(originalEntry))
        store.onAction(OrgClockUiAction.SelectEndMinute(35))
        store.onAction(OrgClockUiAction.RefreshFiles)
        advanceUntilIdle()

        val state = store.uiState.value
        assertEquals(HeadingPath.parse("Work/Project A"), state.historyTarget?.node?.path)
        assertEquals(1, state.historyEntries.size)
        assertEquals(refreshedEntry, state.editingEntry)
        assertEquals(35, state.editingDraft?.endMinute)
        assertNull(state.editFailureMessage)
    }

    @Test
    fun submitCreateL2Heading_callsUseCaseAndClosesDialog() = runTest {
        var called = false
        val store = testStore(
            scope = this,
            listHeadings = { Result.success(sampleHeadings()) },
            createL2Heading = { fileId, parentPath, title, attachTplTag ->
                called = fileId == "f1" && parentPath == HeadingPath.parse("Work") && title == "Project B" && !attachTplTag
                Result.success(Unit)
            },
        )

        store.onAction(OrgClockUiAction.SelectFile(OrgFileEntry("f1", "2026-03-10.org", null)))
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.OpenCreateL2Dialog(sampleHeadings().first()))
        store.onAction(OrgClockUiAction.UpdateCreateHeadingTitle("Project B"))
        store.onAction(OrgClockUiAction.SubmitCreateHeading)
        advanceUntilIdle()

        assertTrue(called)
        assertNull(store.uiState.value.createHeadingDialog)
        assertEquals(StatusMessageKey.HeadingCreated, store.uiState.value.status.text.key)
    }

    @Test
    fun openTemplateFilePicker_loadsTemplateCandidates() = runTest {
        val hiddenTemplate = OrgFileEntry("template", ".orgclock-template.org", null)
        val store = testStore(
            scope = this,
            listTemplateCandidateFiles = { Result.success(listOf(hiddenTemplate, OrgFileEntry("notes", "notes.org", null))) },
        )

        store.onAction(OrgClockUiAction.OpenTemplateFilePicker)
        advanceUntilIdle()

        assertTrue(store.uiState.value.selectingTemplateFile)
        assertEquals(Screen.FilePicker, store.uiState.value.screen)
        assertEquals(listOf(hiddenTemplate.displayName, "notes.org"), store.uiState.value.templateCandidateFiles.map { it.displayName })
    }

    @Test
    fun refreshTemplateStatus_reloadsTemplateFileStatus() = runTest {
        var currentConfig = RootScheduleConfig(rootUri = "/tmp/org-root")
        var refreshes = 0
        val store = testStore(
            scope = this,
            loadSavedRootReference = { RootReference("/tmp/org-root") },
            openRoot = { Result.success(Unit) },
            listFiles = { Result.success(emptyList()) },
            loadRootScheduleConfig = { currentConfig },
            loadTemplateFileStatus = { config ->
                refreshes += 1
                TemplateFileStatus(
                    availability = if (config.templateFileUri == null) TemplateAvailability.Missing else TemplateAvailability.Available,
                    referenceMode = if (config.templateFileUri == null) TemplateReferenceMode.LegacyHiddenFile else TemplateReferenceMode.Explicit,
                    fileId = config.templateFileUri,
                    displayName = config.templateFileUri ?: ".orgclock-template.org",
                )
            },
        )

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        currentConfig = currentConfig.copy(templateFileUri = "/tmp/org-root/template.org")
        store.onAction(OrgClockUiAction.RefreshTemplateStatus)
        advanceUntilIdle()

        assertTrue(refreshes >= 2)
        assertEquals(TemplateReferenceMode.Explicit, store.uiState.value.templateFileStatus.referenceMode)
        assertEquals("/tmp/org-root/template.org", store.uiState.value.templateFileStatus.fileId)
    }

    @Test
    fun createDefaultTemplateFile_updatesStatusOnSuccess() = runTest {
        val root = RootReference("/tmp/org-root")
        val createdPath = "/tmp/org-root/.orgclock-template.org"
        val store = testStore(
            scope = this,
            loadSavedRootReference = { root },
            openRoot = { Result.success(Unit) },
            listFiles = { Result.success(emptyList()) },
            createDefaultTemplateFile = { Result.success(createdPath) },
            loadTemplateFileStatus = {
                TemplateFileStatus(
                    availability = TemplateAvailability.Available,
                    referenceMode = TemplateReferenceMode.LegacyHiddenFile,
                    fileId = createdPath,
                    displayName = ".orgclock-template.org",
                )
            },
        )

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.CreateDefaultTemplateFile)
        advanceUntilIdle()

        assertEquals(StatusMessageKey.TemplateFileCreated, store.uiState.value.status.text.key)
        assertEquals(createdPath, store.uiState.value.templateFileStatus.fileId)
    }

    @Test
    fun refreshFiles_runsAutoGenerationCatchUpAndRefreshesRuntimeState() = runTest {
        var catchUpCalls = 0
        val runtimeState = TemplateAutoGenerationRuntimeState(
            lastAttemptAtEpochMs = 100L,
            lastSuccessAtEpochMs = 100L,
            nextScheduledRunAtEpochMs = 200L,
        )
        val store = testStore(
            scope = this,
            loadSavedRootReference = { RootReference("/tmp/org-root") },
            openRoot = { Result.success(Unit) },
            listFiles = { Result.success(listOf(OrgFileEntry("f1", "2026-03-10.org", null))) },
            listHeadings = { Result.success(sampleHeadings()) },
            runAutoGenerationCatchUp = { catchUpCalls += 1 },
            loadAutoGenerationRuntimeState = { runtimeState },
        )

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()
        store.onAction(OrgClockUiAction.RefreshFiles)
        advanceUntilIdle()

        assertTrue(catchUpCalls >= 1)
        assertEquals(runtimeState, store.uiState.value.autoGenerationRuntimeState)
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

    private fun sampleHeadingsWithOpenClock(): List<HeadingViewItem> {
        return sampleHeadings().map {
            if (it.node.path == HeadingPath.parse("Work/Project A")) {
                it.copy(openClock = OpenClockState(Instant.parse("2026-03-10T09:00:00Z")))
            } else {
                it
            }
        }
    }

    private fun sampleProjectHeadings(): List<HeadingViewItem> {
        return listOf(
            HeadingViewItem(
                node = HeadingNode(
                    lineIndex = 0,
                    level = 1,
                    title = "Projects",
                    path = HeadingPath.parse("Projects"),
                    parentL1 = "Projects",
                ),
                canStart = false,
                openClock = null,
            ),
            HeadingViewItem(
                node = HeadingNode(
                    lineIndex = 1,
                    level = 2,
                    title = "Backlog",
                    path = HeadingPath.parse("Projects/Backlog"),
                    parentL1 = "Projects",
                ),
                canStart = true,
                openClock = null,
            ),
        )
    }

    private fun sampleClosedEntry(clockLineIndex: Int): ClosedClockEntry {
        return ClosedClockEntry(
            headingPath = HeadingPath.parse("Work/Project A"),
            clockLineIndex = clockLineIndex,
            start = Instant.parse("2026-03-10T00:00:00Z"),
            end = Instant.parse("2026-03-10T00:30:00Z"),
            durationMinutes = 30,
        )
    }

    private fun testStore(
        scope: CoroutineScope,
        loadSavedRootReference: () -> RootReference? = { null },
        saveRootReference: (RootReference) -> Unit = {},
        openRoot: suspend (RootReference) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
        listFiles: suspend () -> Result<List<OrgFileEntry>> = { Result.success(emptyList()) },
        listTemplateCandidateFiles: suspend () -> Result<List<OrgFileEntry>> = { Result.success(emptyList()) },
        listFilesWithOpenClock: suspend () -> Result<Set<String>> = { Result.success(emptySet()) },
        listHeadings: suspend (String) -> Result<List<HeadingViewItem>> = { Result.success(emptyList()) },
        startClock: suspend (String, HeadingPath) -> Result<ClockMutationResult> = { _, _ -> Result.success(ClockMutationResult()) },
        stopClock: suspend (String, HeadingPath) -> Result<ClockMutationResult> = { _, _ -> Result.success(ClockMutationResult()) },
        cancelClock: suspend (String, HeadingPath) -> Result<ClockMutationResult> = { _, _ -> Result.success(ClockMutationResult()) },
        listClosedClocks: suspend (String, HeadingPath) -> Result<List<ClosedClockEntry>> = { _, _ -> Result.success(emptyList()) },
        editClosedClock: suspend (String, HeadingPath, Int, Instant, Instant) -> Result<Unit> = { _, _, _, _, _ -> Result.success(Unit) },
        deleteClosedClock: suspend (String, HeadingPath, Int) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
        createL1Heading: suspend (String, String, Boolean) -> Result<Unit> = { _, _, _ -> Result.success(Unit) },
        createL2Heading: suspend (String, HeadingPath, String, Boolean) -> Result<Unit> = { _, _, _, _ -> Result.success(Unit) },
        loadRootScheduleConfig: (RootReference) -> RootScheduleConfig = { rootReference ->
            RootScheduleConfig(rootUri = rootReference.rawValue)
        },
        loadTemplateFileStatus: suspend (RootScheduleConfig) -> TemplateFileStatus = { config ->
            TemplateFileStatus(
                availability = TemplateAvailability.Missing,
                referenceMode = if (config.templateFileUri == null) TemplateReferenceMode.LegacyHiddenFile else TemplateReferenceMode.Explicit,
                fileId = config.templateFileUri,
                displayName = config.templateFileUri ?: ".orgclock-template.org",
            )
        },
        loadTemplateAutoGenerationFailure: (RootReference) -> String? = { null },
        loadAutoGenerationRuntimeState: suspend (RootReference) -> TemplateAutoGenerationRuntimeState = { TemplateAutoGenerationRuntimeState() },
        saveRootScheduleConfig: suspend (RootScheduleConfig) -> Unit = {},
        syncRootScheduleConfig: suspend (RootScheduleConfig) -> Unit = {},
        runAutoGenerationCatchUp: suspend (RootReference) -> Unit = {},
        createDefaultTemplateFile: suspend (RootReference) -> Result<String> = { Result.failure(UnsupportedOperationException("template file creation unavailable")) },
        externalChangeFlow: StateFlow<ExternalChangeNotice?> = OrgClockStore.NO_EXTERNAL_CHANGE_FLOW,
        syncSnapshotFlow: StateFlow<SyncIntegrationSnapshot> = MutableStateFlow(SyncIntegrationSnapshot()),
    ): OrgClockStore {
        return OrgClockStore(
            scope = scope,
            loadSavedRootReference = loadSavedRootReference,
            saveRootReference = saveRootReference,
            openRoot = openRoot,
            listFiles = listFiles,
            listTemplateCandidateFiles = listTemplateCandidateFiles,
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
            loadNotificationEnabled = { true },
            saveNotificationEnabled = {},
            loadNotificationDisplayMode = { NotificationDisplayMode.ActiveOnly },
            saveNotificationDisplayMode = {},
            notificationPermissionGrantedProvider = { true },
            loadRootScheduleConfig = loadRootScheduleConfig,
            loadTemplateFileStatus = loadTemplateFileStatus,
            loadTemplateAutoGenerationFailure = loadTemplateAutoGenerationFailure,
            loadAutoGenerationRuntimeState = loadAutoGenerationRuntimeState,
            saveRootScheduleConfig = saveRootScheduleConfig,
            syncRootScheduleConfig = syncRootScheduleConfig,
            runAutoGenerationCatchUp = runAutoGenerationCatchUp,
            createDefaultTemplateFileAction = createDefaultTemplateFile,
            externalChangeFlow = externalChangeFlow,
            syncSnapshotFlow = syncSnapshotFlow,
            nowProvider = { Instant.parse("2026-03-10T09:00:00Z") },
            todayProvider = { LocalDate(2026, 3, 10) },
            timeZoneProvider = { TimeZone.UTC },
            showPerfOverlay = false,
        )
    }
}
