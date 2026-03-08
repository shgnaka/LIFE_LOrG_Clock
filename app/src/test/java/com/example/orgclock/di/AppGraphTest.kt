package com.example.orgclock.di

import com.example.orgclock.domain.ClockMutationResult
import com.example.orgclock.sync.ClockCommandKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGraphTest {
    @Test
    fun scheduleSyncPublishAfterLocalSave_success_enqueuesPublishAndReturnsOriginalResult() {
        val result = Result.success(ClockMutationResult())
        var publishedKind: ClockCommandKind? = null
        var publishedFileName: String? = null
        var publishedHeadingPath: String? = null

        val returned = scheduleSyncPublishAfterLocalSave(
            result = result,
            kind = ClockCommandKind.Start,
            fileName = "2026-02-16.org",
            headingPath = "Work/Project A",
        ) { kind, fileName, headingPath ->
            publishedKind = kind
            publishedFileName = fileName
            publishedHeadingPath = headingPath
        }

        assertTrue(returned.isSuccess)
        assertEquals(ClockCommandKind.Start, publishedKind)
        assertEquals("2026-02-16.org", publishedFileName)
        assertEquals("Work/Project A", publishedHeadingPath)
    }

    @Test
    fun scheduleSyncPublishAfterLocalSave_failure_doesNotEnqueuePublish() {
        val result = Result.failure<ClockMutationResult>(IllegalStateException("save failed"))
        var called = false

        val returned = scheduleSyncPublishAfterLocalSave(
            result = result,
            kind = ClockCommandKind.Stop,
            fileName = "2026-02-16.org",
            headingPath = "Work/Project A",
        ) { _, _, _ ->
            called = true
        }

        assertTrue(returned.isFailure)
        assertTrue(!called)
    }
}
