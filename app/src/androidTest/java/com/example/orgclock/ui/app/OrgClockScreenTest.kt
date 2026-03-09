package com.example.orgclock.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.orgclock.R
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.ui.state.OrgClockUiAction
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.ZoneId
import java.time.ZonedDateTime

class OrgClockScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rootSetupScreen_showsRootPickerAction() {
        composeRule.setContent {
            OrgClockScreen(
                state = OrgClockUiState(
                    screen = Screen.RootSetup,
                    status = UiStatus(messageResId = R.string.status_select_org_directory, tone = StatusTone.Info),
                ),
                performanceMonitor = PerformanceMonitor(composeRule.activity.window),
                zoneIdProvider = { ZoneId.systemDefault() },
                nowProvider = { ZonedDateTime.now() },
                onPickRoot = {},
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Org Clock").assertIsDisplayed()
        composeRule.onAllNodesWithText("Select org directory").assertCountEquals(2)
    }

    @Test
    fun filePickerScreen_showsSettingsAndFileTitle() {
        composeRule.setContent {
            OrgClockScreen(
                state = OrgClockUiState(
                    screen = Screen.FilePicker,
                    files = listOf(OrgFileEntry("f1", "2026-02-16.org", null)),
                    status = UiStatus(messageResId = R.string.status_loaded_file, messageArgs = listOf("2026-02-16.org"), tone = StatusTone.Success),
                ),
                performanceMonitor = PerformanceMonitor(composeRule.activity.window),
                zoneIdProvider = { ZoneId.systemDefault() },
                nowProvider = { ZonedDateTime.now() },
                onPickRoot = {},
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("2026-02-16.org").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_showsNotificationControls() {
        composeRule.setContent {
            OrgClockScreen(
                state = OrgClockUiState(
                    screen = Screen.Settings,
                    notificationEnabled = true,
                    notificationPermissionGranted = false,
                    notificationDisplayMode = NotificationDisplayMode.ActiveOnly,
                    status = UiStatus(messageResId = R.string.status_notification_enabled, tone = StatusTone.Info),
                ),
                performanceMonitor = PerformanceMonitor(composeRule.activity.window),
                zoneIdProvider = { ZoneId.systemDefault() },
                nowProvider = { ZonedDateTime.now() },
                onPickRoot = {},
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("通知機能").assertIsDisplayed()
        composeRule.onNodeWithText("通知設定を開く").assertIsDisplayed()
    }

    @Test
    fun headingListScreen_runningPanelStopButtonDispatchesStopAction() {
        val actions = mutableListOf<OrgClockUiAction>()
        val runningPath = HeadingPath.parse("Work/Active Task")
        composeRule.setContent {
            OrgClockScreen(
                state = OrgClockUiState(
                    screen = Screen.HeadingList,
                    selectedFile = OrgFileEntry("f1", "2026-02-16.org", null),
                    headings = listOf(
                        headingItem(level = 1, title = "Work", path = "Work"),
                        headingItem(
                            level = 2,
                            title = "Active Task",
                            path = "Work/Active Task",
                            parentL1 = "Work",
                            openClock = OpenClockState(Instant.parse("2026-03-08T00:00:00Z")),
                        ),
                    ),
                ),
                performanceMonitor = PerformanceMonitor(composeRule.activity.window),
                zoneIdProvider = { ZoneId.of("UTC") },
                nowProvider = { ZonedDateTime.parse("2026-03-08T01:00:00Z") },
                onPickRoot = {},
                onAction = { actions += it },
            )
        }

        composeRule.onNodeWithTag("running_clocks_panel").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop").performClick()

        assertEquals(listOf(OrgClockUiAction.StopClock(runningPath)), actions)
    }

    @Test
    fun headingListScreen_runningPanelShowsAllFourRowsWhenFourClocksAreRunning() {
        setHeadingListContent(runningClockCount = 4)

        composeRule.onNodeWithTag("running_clocks_panel").assertIsDisplayed()
        repeat(4) { index ->
            val taskNumber = index + 1
            composeRule.onNodeWithTag(runningPanelRowTag("Work/Task $taskNumber")).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("running_panel_compact").assertDoesNotExist()
    }

    @Test
    fun headingListScreen_runningPanelAutoCollapsesWhenFiveClocksAreRunning() {
        setHeadingListContent(runningClockCount = 5)

        composeRule.onNodeWithTag("running_clocks_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("running_panel_toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("running_panel_compact").assertIsDisplayed()
        composeRule.onNodeWithTag(runningPanelRowTag("Work/Task 1")).assertDoesNotExist()
    }

    @Test
    fun headingListScreen_runningPanelCanExpandAfterAutoCollapse() {
        setHeadingListContent(runningClockCount = 5)

        composeRule.onNodeWithTag("running_panel_toggle").performClick()

        repeat(5) { index ->
            val taskNumber = index + 1
            composeRule.onNodeWithTag(runningPanelRowTag("Work/Task $taskNumber")).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("running_panel_compact").assertDoesNotExist()
    }

    private fun headingItem(
        level: Int,
        title: String,
        path: String,
        parentL1: String? = null,
        openClock: OpenClockState? = null,
    ): HeadingViewItem = HeadingViewItem(
        node = HeadingNode(
            lineIndex = 0,
            level = level,
            title = title,
            path = HeadingPath.parse(path),
            parentL1 = parentL1,
        ),
        canStart = level == 2,
        openClock = openClock,
    )

    private fun setHeadingListContent(runningClockCount: Int, totalTaskCount: Int = 12) {
        val headings = buildList {
            add(headingItem(level = 1, title = "Work", path = "Work"))
            repeat(totalTaskCount) { index ->
                val taskNumber = index + 1
                add(
                    headingItem(
                        level = 2,
                        title = "Task $taskNumber",
                        path = "Work/Task $taskNumber",
                        parentL1 = "Work",
                        openClock = if (index < runningClockCount) {
                            OpenClockState(Instant.parse("2026-03-08T0${index}:00:00Z"))
                        } else {
                            null
                        },
                    ),
                )
            }
        }

        composeRule.setContent {
            OrgClockScreen(
                state = OrgClockUiState(
                    screen = Screen.HeadingList,
                    selectedFile = OrgFileEntry("f1", "2026-02-16.org", null),
                    headings = headings,
                ),
                performanceMonitor = PerformanceMonitor(composeRule.activity.window),
                zoneIdProvider = { ZoneId.of("UTC") },
                nowProvider = { ZonedDateTime.parse("2026-03-08T05:00:00Z") },
                onPickRoot = {},
                onAction = {},
            )
        }
    }
}

private fun headingRowTag(path: String): String = "heading_row:$path"
private fun runningPanelRowTag(path: String): String = "running_panel_row:$path"
