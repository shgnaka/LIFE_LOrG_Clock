package com.example.orgclock.ui.app

import android.net.Uri
import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.HeadingViewItem
import com.example.orgclock.model.OpenClockState
import com.example.orgclock.notification.NotificationDisplayMode
import com.example.orgclock.time.toKotlinInstantCompat
import com.example.orgclock.ui.state.OrgClockUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito
import java.time.ZoneId
import java.time.ZonedDateTime

class OrgClockRouteNotificationSyncTest {
    @Test
    fun buildNotificationSyncKey_ignoresHeadingOnlyChangesWithoutOpenClockFootprintChange() {
        val rootUri = Mockito.mock(Uri::class.java)
        val state1 = OrgClockUiState(
            rootUri = rootUri,
            notificationEnabled = true,
            notificationDisplayMode = NotificationDisplayMode.ActiveOnly,
            headings = listOf(
                heading(lineIndex = 1, title = "Project A", open = true),
                heading(lineIndex = 2, title = "Project B", open = false),
            ),
        )
        val state2 = state1.copy(
            headings = listOf(
                heading(lineIndex = 1, title = "Project A renamed", open = true),
                heading(lineIndex = 2, title = "Project B renamed", open = false),
            ),
        )

        assertEquals(buildNotificationSyncKey(state1), buildNotificationSyncKey(state2))
    }

    @Test
    fun buildNotificationSyncKey_changesWhenNotificationRelevantStateChanges() {
        val rootUri = Mockito.mock(Uri::class.java)
        val base = OrgClockUiState(
            rootUri = rootUri,
            notificationEnabled = true,
            notificationDisplayMode = NotificationDisplayMode.ActiveOnly,
            headings = listOf(
                heading(lineIndex = 1, title = "A", open = true),
                heading(lineIndex = 2, title = "B", open = false),
            ),
        )

        val modeChanged = base.copy(notificationDisplayMode = NotificationDisplayMode.Always)
        val footprintChanged = base.copy(
            headings = listOf(
                heading(lineIndex = 1, title = "A", open = false),
                heading(lineIndex = 2, title = "B", open = true),
            ),
        )

        assertNotEquals(buildNotificationSyncKey(base), buildNotificationSyncKey(modeChanged))
        assertNotEquals(buildNotificationSyncKey(base), buildNotificationSyncKey(footprintChanged))
    }

    private fun heading(lineIndex: Int, title: String, open: Boolean): HeadingViewItem {
        return HeadingViewItem(
            node = HeadingNode(
                lineIndex = lineIndex,
                level = 2,
                title = title,
                path = HeadingPath.parse("Work/$title"),
                parentL1 = "Work",
            ),
            canStart = true,
            openClock = if (open) {
                OpenClockState(
                    startedAt = ZonedDateTime.of(2026, 2, 22, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")).toKotlinInstantCompat(),
                )
            } else {
                null
            },
        )
    }
}
