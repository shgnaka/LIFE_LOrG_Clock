package com.example.orgclock.ui.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.orgclock.ui.perf.PerformanceMonitor
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
                    status = UiStatus("Select org directory", StatusTone.Info),
                ),
                performanceMonitor = PerformanceMonitor(composeRule.activity.window),
                zoneIdProvider = { ZoneId.systemDefault() },
                nowProvider = { ZonedDateTime.now() },
                onPickRoot = {},
                onAction = {},
            )
        }

        composeRule.onNodeWithText("Org Clock").assertIsDisplayed()
        composeRule.onNodeWithText("Select org directory").assertIsDisplayed()
    }
}
