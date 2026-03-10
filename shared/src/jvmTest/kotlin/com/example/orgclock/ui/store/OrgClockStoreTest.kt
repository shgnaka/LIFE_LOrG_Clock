package com.example.orgclock.ui.store

import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.Screen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class OrgClockStoreTest {
    @Test
    fun initialize_withSavedRoot_loadsFilesWithoutAndroidViewModel() = runTest {
        val store = OrgClockStore(
            scope = this,
            loadSavedRootReference = { RootReference("/tmp/org-root") },
            saveRootReference = {},
            openRoot = { Result.success(Unit) },
            listFiles = { Result.success(listOf(OrgFileEntry("f1", "2026-03-10.org", null))) },
            listFilesWithOpenClock = { Result.success(emptySet()) },
            listHeadings = {
                Result.success(
                    listOf(
                        HeadingViewItem(
                            node = HeadingNode(
                                lineIndex = 0,
                                level = 1,
                                title = "Work",
                                path = HeadingPath.parse("Work"),
                                parentL1 = "Work",
                            ),
                            canStart = false,
                            openClock = null,
                        ),
                    ),
                )
            },
            startClock = { _, _ -> error("unused") },
            stopClock = { _, _ -> error("unused") },
            cancelClock = { _, _ -> error("unused") },
            listClosedClocks = { _, _ -> Result.success(emptyList()) },
            editClosedClock = { _, _, _, _, _ -> Result.success(Unit) },
            deleteClosedClock = { _, _, _ -> Result.success(Unit) },
            createL1Heading = { _, _, _ -> Result.success(Unit) },
            createL2Heading = { _, _, _, _ -> Result.success(Unit) },
            loadNotificationEnabled = { true },
            saveNotificationEnabled = {},
            loadNotificationDisplayMode = { com.example.orgclock.notification.NotificationDisplayMode.ActiveOnly },
            saveNotificationDisplayMode = {},
            notificationPermissionGrantedProvider = { true },
            nowProvider = { kotlinx.datetime.Instant.parse("2026-03-10T00:00:00Z") },
            todayProvider = { LocalDate(2026, 3, 10) },
            showPerfOverlay = false,
        )

        store.onAction(OrgClockUiAction.Initialize)
        advanceUntilIdle()

        val state = store.uiState.value
        assertEquals(Screen.HeadingList, state.screen)
        assertEquals("/tmp/org-root", state.rootReference?.rawValue)
        assertEquals(StatusMessageKey.LoadedFile, state.status.text.key)
        assertEquals("2026-03-10.org", state.selectedFile?.displayName)
    }
}
