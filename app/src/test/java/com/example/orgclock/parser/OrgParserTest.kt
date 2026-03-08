package com.example.orgclock.parser

import com.example.orgclock.model.HeadingPath
import com.example.orgclock.time.toJavaZonedDateTime
import com.example.orgclock.time.toKotlinInstantCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.datetime.toKotlinTimeZone
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
    fun findHeadingNode_returnsResolvedHeadingMetadata() {
        val lines = listOf(
            "* Work",
            "** Project A :tag:",
            "*** Task 1",
        )

        val heading = parser.findHeadingNode(lines, HeadingPath.parse("Work/Project A"))

        assertNotNull(heading)
        assertEquals(1, heading?.lineIndex)
        assertEquals(2, heading?.level)
        assertEquals("Project A", heading?.title)
        assertEquals("Work/Project A", heading?.path.toString())
        assertEquals("Work", heading?.parentL1)
    }

    @Test
    fun findHeadingNode_returnsNullWhenPathMissing() {
        val lines = listOf(
            "* Work",
            "** Project A",
        )

        val heading = parser.findHeadingNode(lines, HeadingPath.parse("Work/Project B"))

        assertNull(heading)
    }

    @Test
    fun findLevel2HeadingNode_returnsNullForLevelOneHeading() {
        val lines = listOf(
            "* Work",
            "** Project A",
        )

        val heading = parser.findLevel2HeadingNode(lines, HeadingPath.parse("Work"))

        assertNull(heading)
    }

    @Test
    fun findHeadingNode_retargetsCurrentLineAfterInsertions() {
        val lines = listOf(
            "* Inbox",
            "** Triage",
            "* Work",
            "** Project A",
        )

        val heading = parser.findHeadingNode(lines, HeadingPath.parse("Work/Project A"))

        assertEquals(3, heading?.lineIndex)
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
    fun appendOpenClock_missingHeading_throwsIllegalArgumentException() {
        val lines = listOf(
            "* Work",
            "** Project A",
        )
        val now = ZonedDateTime.of(2026, 2, 15, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

        try {
            parser.appendOpenClock(lines, HeadingPath.parse("Work/Unknown"), now)
            fail("Expected IllegalArgumentException")
        } catch (ex: IllegalArgumentException) {
            assertTrue(ex.message!!.contains("Heading not found"))
        }
    }

    @Test
    fun closeLatestOpenClock_invalidTimestamp_throwsIllegalStateException() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [invalid]",
            ":END:",
        )
        val end = ZonedDateTime.of(2026, 2, 15, 10, 30, 0, 0, ZoneId.of("Asia/Tokyo"))

        try {
            parser.closeLatestOpenClock(lines, HeadingPath.parse("Work/Project A"), end)
            fail("Expected IllegalStateException")
        } catch (ex: IllegalStateException) {
            assertTrue(ex.message!!.contains("Failed to parse CLOCK start timestamp"))
        }
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
    fun deleteClosedClockAtLine_removesSpecifiedLine() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:30:00] =>  0:30",
            "CLOCK: [2026-02-15 Sun 10:00:00]--[2026-02-15 Sun 10:45:00] =>  0:45",
            ":END:",
        )

        val updated = parser.deleteClosedClockAtLine(lines, 1, 3)

        assertEquals(5, updated.size)
        assertTrue(updated.none { it.contains("09:00:00") })
        assertTrue(updated.any { it.contains("10:00:00") })
    }

    @Test
    fun deleteClosedClockAtLine_rejectsLineOutsideHeadingScope() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]--[2026-02-15 Sun 09:30:00] =>  0:30",
            ":END:",
            "* Home",
            "** Chores",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 10:00:00]--[2026-02-15 Sun 10:10:00] =>  0:10",
            ":END:",
        )

        val result = runCatching { parser.deleteClosedClockAtLine(lines, 1, 8) }

        assertTrue(result.isFailure)
    }

    @Test
    fun deleteClosedClockAtLine_rejectsNonClosedClockLine() {
        val lines = listOf(
            "* Work",
            "** Project A",
            ":LOGBOOK:",
            "CLOCK: [2026-02-15 Sun 09:00:00]",
            ":END:",
        )

        val result = runCatching { parser.deleteClosedClockAtLine(lines, 1, 3) }

        assertTrue(result.isFailure)
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
        assertEquals(0, entries[0].start.toJavaZonedDateTime(ZoneId.of("Asia/Tokyo")).second)
        assertEquals(0, entries[0].end.toJavaZonedDateTime(ZoneId.of("Asia/Tokyo")).second)
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
    fun appendL1Heading_withTplTag_appendsTplSuffix() {
        val lines = listOf(
            "* Work",
        )

        val updated = parser.appendL1Heading(lines, "Meeting", attachTplTag = true)

        assertEquals("* Meeting :TPL:", updated.last())
    }

    @Test
    fun appendL1Heading_withExistingTags_appendsTplWithoutDuplication() {
        val lines = listOf(
            "* Work",
        )

        val updated = parser.appendL1Heading(lines, "Meeting :work:urgent:", attachTplTag = true)

        assertEquals("* Meeting :work:urgent:TPL:", updated.last())
    }

    @Test
    fun appendL1Heading_withExistingTpl_doesNotDuplicateTpl() {
        val lines = listOf(
            "* Work",
        )

        val updated = parser.appendL1Heading(lines, "Meeting :TPL:", attachTplTag = true)

        assertEquals("* Meeting :TPL:", updated.last())
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

    @Test
    fun appendL2HeadingUnderL1_withTplTag_appendsTplSuffix() {
        val lines = listOf(
            "* Work",
            "** Project A",
            "* Home",
        )

        val updated = parser.appendL2HeadingUnderL1(lines, 0, "Project B", attachTplTag = true)

        assertEquals("** Project B :TPL:", updated[2])
    }
}

private fun OrgParser.parseHeadingsWithOpenClock(lines: List<String>, zoneId: ZoneId) =
    parseHeadingsWithOpenClock(lines, zoneId.toKotlinTimeZone())

private fun OrgParser.appendOpenClock(lines: List<String>, headingPath: HeadingPath, start: ZonedDateTime) =
    appendOpenClock(lines, headingPath, start.toKotlinInstantCompat(), start.zone.toKotlinTimeZone())

private fun OrgParser.appendClosedClock(
    lines: List<String>,
    headingPath: HeadingPath,
    start: ZonedDateTime,
    end: ZonedDateTime,
) = appendClosedClock(lines, headingPath, start.toKotlinInstantCompat(), end.toKotlinInstantCompat(), end.zone.toKotlinTimeZone())

private fun OrgParser.appendOpenClockAtLine(lines: List<String>, headingLineIndex: Int, start: ZonedDateTime) =
    appendOpenClockAtLine(lines, headingLineIndex, start.toKotlinInstantCompat(), start.zone.toKotlinTimeZone())

private fun OrgParser.closeLatestOpenClock(lines: List<String>, headingPath: HeadingPath, end: ZonedDateTime) =
    closeLatestOpenClock(lines, headingPath, end.toKotlinInstantCompat(), end.zone.toKotlinTimeZone())

private fun OrgParser.closeLatestOpenClockAtLine(lines: List<String>, headingLineIndex: Int, end: ZonedDateTime) =
    closeLatestOpenClockAtLine(lines, headingLineIndex, end.toKotlinInstantCompat(), end.zone.toKotlinTimeZone())

private fun OrgParser.findOpenClock(lines: List<String>, headingPath: HeadingPath, zoneId: ZoneId) =
    findOpenClock(lines, headingPath, zoneId.toKotlinTimeZone())

private fun OrgParser.findOpenClockAtLine(lines: List<String>, headingLineIndex: Int, zoneId: ZoneId) =
    findOpenClockAtLine(lines, headingLineIndex, zoneId.toKotlinTimeZone())

private fun OrgParser.listClosedClocksAtLine(lines: List<String>, headingLineIndex: Int, zoneId: ZoneId) =
    listClosedClocksAtLine(lines, headingLineIndex, zoneId.toKotlinTimeZone())

private fun OrgParser.replaceClosedClockAtLine(
    lines: List<String>,
    headingLineIndex: Int,
    clockLineIndex: Int,
    newStart: ZonedDateTime,
    newEnd: ZonedDateTime,
) = replaceClosedClockAtLine(
    lines,
    headingLineIndex,
    clockLineIndex,
    newStart.toKotlinInstantCompat(),
    newEnd.toKotlinInstantCompat(),
    newEnd.zone.toKotlinTimeZone(),
)
