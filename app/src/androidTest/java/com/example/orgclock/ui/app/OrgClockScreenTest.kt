package com.example.orgclock.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.example.orgclock.R
import com.example.orgclock.ui.perf.PerformanceMonitor
import com.example.orgclock.data.OrgFileEntry
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.ui.state.OrgClockUiState
import com.example.orgclock.ui.state.Screen
import com.example.orgclock.ui.state.StatusTone
import com.example.orgclock.ui.state.UiStatus
import org.junit.Rule
import org.junit.Test
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
}
