package com.example.orgclock.notification

import com.example.orgclock.model.HeadingPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ClockInNotificationServiceDecisionTest {
    @Test
    fun shouldStopForActiveOnly_trueOnlyWhenNoEntriesAndNoFailures() {
        val empty = ClockInScanResult(entries = emptyList(), failedFiles = emptyList())
        val withFailures = ClockInScanResult(
            entries = emptyList(),
            failedFiles = listOf(FileScanFailure("f1", "a.org", "read failed")),
        )
        val withEntries = ClockInScanResult(
            entries = listOf(sampleEntry()),
            failedFiles = emptyList(),
        )

        assertTrue(shouldStopForActiveOnly(NotificationDisplayMode.ActiveOnly, empty))
        assertFalse(shouldStopForActiveOnly(NotificationDisplayMode.ActiveOnly, withFailures))
        assertFalse(shouldStopForActiveOnly(NotificationDisplayMode.ActiveOnly, withEntries))
        assertFalse(shouldStopForActiveOnly(NotificationDisplayMode.Always, empty))
    }

    @Test
    fun shouldStopForActiveOnly_neverStopsInAlwaysMode_evenWhenEmptyOrFailed() {
        val empty = ClockInScanResult(entries = emptyList(), failedFiles = emptyList())
        val failed = ClockInScanResult(
            entries = emptyList(),
            failedFiles = listOf(FileScanFailure("f1", "a.org", "read failed")),
        )

        assertFalse(shouldStopForActiveOnly(NotificationDisplayMode.Always, empty))
        assertFalse(shouldStopForActiveOnly(NotificationDisplayMode.Always, failed))
    }

    @Test
    fun clockNotificationId_staysStableAcrossLineMoves() {
        val headingPath = HeadingPath.parse("Work/A")

        assertEquals(
            clockNotificationId("f1", headingPath),
            clockNotificationId("f1", headingPath),
        )
    }

    @Test
    fun clockNotificationId_changesWhenHeadingPathChanges() {
        assertNotEquals(
            clockNotificationId("f1", HeadingPath.parse("Work/A")),
            clockNotificationId("f1", HeadingPath.parse("Work/B")),
        )
    }

    private fun sampleEntry(): ClockInEntry {
        return ClockInEntry(
            fileId = "f1",
            fileName = "a.org",
            headingTitle = "A",
            l1Title = "Work",
            headingPath = HeadingPath.parse("Work/A"),
            startedAt = ZonedDateTime.of(2026, 2, 22, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")),
        )
    }
}
