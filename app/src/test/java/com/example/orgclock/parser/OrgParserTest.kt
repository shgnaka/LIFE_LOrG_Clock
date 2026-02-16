package com.example.orgclock.parser

import com.example.orgclock.model.HeadingPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OrgParserTest {
    private val parser = OrgParser()

    @Test
    fun appendOpenClock_createsLogbookIfMissing() {
        val lines = listOf(
            "* Work",
            "** Project A",
            "Text",
        )
        val now = ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

        val updated = parser.appendOpenClock(lines, HeadingPath.parse("Work/Project A"), now)

        assertTrue(updated.contains(":LOGBOOK:"))
        assertTrue(updated.any { it.startsWith("CLOCK: [2026-02-15") })
    }

    @Test
    fun closeLatestOpenClock_replacesOpenClock() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]",
            ":END:",
        )
        val end = ZonedDateTime.of(2026, 2, 15, 10, 30, 0, 0, ZoneId.of("Asia/Tokyo"))

        val updated = parser.closeLatestOpenClock(lines, HeadingPath.parse("Work/Project A"), end).lines

        assertTrue(updated.any { it.contains("--[2026-02-15 Sun 10:30:00]") })
        assertTrue(updated.any { it.endsWith("=>  1:30") })
    }

    @Test
    fun findHeading_matchesPathIgnoringTags() {
        val lines = listOf(
            "* Work :office:",
            "** Project A :tag:",
            "Body",
        )

        val heading = parser.findHeading(lines, HeadingPath.parse("Work/Project A"))

        assertEquals(1, heading?.start)
    }

    @Test
    fun closeLatestOpenClock_doesNotUseChildHeadingClock() {
        val lines = listOf(
            "* Work",
            ":LOGBOOK:",
            ":END:",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]",
            ":END:",
        )
        val end = ZonedDateTime.of(2026, 2, 15, 10, 30, 0, 0, ZoneId.of("Asia/Tokyo"))

        val result = runCatching {
            parser.closeLatestOpenClock(lines, HeadingPath.parse("Work"), end)
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun parseHeadings_extractsLevelsAndPaths() {
        val lines = listOf(
            "* Work",
            "** Project A",
            "*** Task 1",
            "* Food",
            "** Lunch",
        )

        val headings = parser.parseHeadings(lines)

        assertEquals(5, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals(2, headings[1].level)
        assertEquals("Work/Project A", headings[1].path.toString())
        assertEquals("Work", headings[1].parentL1)
    }

    @Test
    fun cancelLatestOpenClockAtLine_removesOpenClock() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]",
            ":END:",
        )

        val updated = parser.cancelLatestOpenClockAtLine(lines, 1)
        val open = parser.findOpenClockAtLine(updated, 1, ZoneId.of("Asia/Tokyo"))

        assertNull(open)
        assertTrue(updated.none { it.startsWith("CLOCK: [2026-02-15 Sun 09:00:00]") })
    }

    @Test
    fun listClosedClocksAtLine_returnsOnlyTargetHeadingEntries() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:30:00] =>  0:30",
            ":END:",
            "*** Child",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 10:00:00]--[2026-02-15 Sun 10:30:00] =>  0:30",
            ":END:",
        )

        val entries = parser.listClosedClocksAtLine(lines, 1, ZoneId.of("Asia/Tokyo"))

        assertEquals(1, entries.size)
        assertEquals(3, entries[0].clockLineIndex)
        assertEquals(30L, entries[0].durationMinutes)
    }

    @Test
    fun replaceClosedClockAtLine_updatesSpecifiedLine() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:30:00] =>  0:30",
            ":END:",
        )
        val start = ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
        val end = ZonedDateTime.of(2026, 2, 15, 11, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

        val updated = parser.replaceClosedClockAtLine(lines, 1, 3, start, end)

        assertTrue(updated[3].contains("10:00:00]--[2026-02-15 Sun 11:00:00]"))
        assertTrue(updated[3].endsWith("=>  1:00"))
    }

    @Test
    fun listClosedClocksAtLine_parsesEnglishWeekdayWithoutSeconds() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00]--[2026-02-15 Sun 09:30] =>  0:30",
            ":END:",
        )

        val entries = parser.listClosedClocksAtLine(lines, 1, ZoneId.of("Asia/Tokyo"))

        assertEquals(1, entries.size)
        assertEquals(0, entries[0].start.second)
        assertEquals(0, entries[0].end.second)
        assertEquals(30L, entries[0].durationMinutes)
    }

    @Test
    fun listClosedClocksAtLine_parsesJapaneseWeekday() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 日 10:00:00]--[2026-02-15 日 10:40:00] =>  0:40",
            ":END:",
        )

        val entries = parser.listClosedClocksAtLine(lines, 1, ZoneId.of("Asia/Tokyo"))

        assertEquals(1, entries.size)
        assertEquals(40L, entries[0].durationMinutes)
    }

    @Test
    fun appendL1Heading_appendsToEnd() {
        val lines = listOf(
            "* Work",
            "** Project A",
            "* Home",
        )

        val updated = parser.appendL1Heading(lines, "Reading")

        assertEquals("* Reading", updated.last())
    }

    @Test
    fun appendL1Heading_rejectsDuplicate() {
        val lines = listOf(
            "* Work",
            "* Home",
        )

        val result = runCatching { parser.appendL1Heading(lines, "Work") }

        assertTrue(result.isFailure)
    }

    @Test
    fun appendL2HeadingUnderL1_appendsAtEndOfL1Scope() {
        val lines = listOf(
            "* Work",
            "** Project A",
            "*** Task",
            "* Home",
        )

        val updated = parser.appendL2HeadingUnderL1(lines, 0, "Project B")

        assertEquals("** Project B", updated[3])
    }

    @Test
    fun appendL2HeadingUnderL1_rejectsDuplicateUnderSameParent() {
        val lines = listOf(
            "* Work",
            "** Project A",
            "* Home",
            "** Project A",
        )

        val result = runCatching { parser.appendL2HeadingUnderL1(lines, 0, "Project A") }

        assertTrue(result.isFailure)
    }
}
