package com.example.orgclock.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationInboxLinesTest {
    @Test
    fun buildInboxLines_truncatesAndAddsMoreLine_whenMaxLinesIsTwo() {
        val entries = listOf("A", "B", "C")

        val lines = buildInboxLines(
            entries = entries,
            maxLines = 2,
            entryLineBuilder = { "entry:$it" },
            moreLineBuilder = { "...more:$it" },
        )

        assertEquals(listOf("entry:A", "entry:B", "...more:1"), lines)
    }

    @Test
    fun buildInboxLines_returnsEmpty_whenEntriesIsEmpty() {
        val lines = buildInboxLines(
            entries = emptyList<String>(),
            maxLines = 2,
            entryLineBuilder = { "entry:$it" },
            moreLineBuilder = { "...more:$it" },
        )

        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun buildInboxLines_maxLinesZero_clampsToOneLine() {
        val lines = buildInboxLines(
            entries = listOf("A", "B"),
            maxLines = 0,
            entryLineBuilder = { "entry:$it" },
            moreLineBuilder = { "...more:$it" },
        )

        assertEquals(listOf("entry:A", "...more:1"), lines)
    }
}
