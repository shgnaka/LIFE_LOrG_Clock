package com.example.orgclock.notification

import org.junit.Assert.assertFalse
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

    private fun sampleEntry(): ClockInEntry {
        return ClockInEntry(
            fileId = "f1",
            fileName = "a.org",
            headingTitle = "A",
            l1Title = "Work",
            startedAt = ZonedDateTime.of(2026, 2, 22, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")),
            headingLineIndex = 1,
        )
    }
}
