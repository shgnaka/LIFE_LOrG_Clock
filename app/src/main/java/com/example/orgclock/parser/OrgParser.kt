package com.example.orgclock.parser

import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import java.time.ZoneId
import java.time.ZonedDateTime

class OrgParser {
    private val headingRegex = Regex("^(\\*+)\\s+(.*)$")
    private val openClockRegex = Regex("^\\s*CLOCK:\\s*(\\[[^\\]]+\\])\\s*$")
    private val closedClockRegex = Regex("^\\s*CLOCK:\\s*(\\[[^\\]]+\\])--(\\[[^\\]]+\\])\\s*(?:=>\\s*.*)?$")

    data class HeadingMatch(
        val start: Int,
        val endExclusive: Int,
        val level: Int,
    )

    data class CloseOpenResult(
        val lines: List<String>,
        val start: ZonedDateTime,
    )

    fun parseHeadings(lines: List<String>): List<HeadingNode> {
        val stack = mutableListOf<String>()
        var currentL1: String? = null
        val result = mutableListOf<HeadingNode>()

        for ((index, line) in lines.withIndex()) {
            val match = headingRegex.matchEntire(line) ?: continue
            val level = match.groupValues[1].length
            val title = normalizeHeadingTitle(match.groupValues[2])

            while (stack.size >= level) {
                stack.removeAt(stack.lastIndex)
            }
            stack.add(title)

            if (level == 1) {
                currentL1 = title
            }

            result += HeadingNode(
                lineIndex = index,
                level = level,
                title = title,
                path = HeadingPath(stack.toList()),
                parentL1 = currentL1,
            )
        }

        return result
    }

    fun headingLevelAtLine(lines: List<String>, lineIndex: Int): Int? {
        val line = lines.getOrNull(lineIndex) ?: return null
        val match = headingRegex.matchEntire(line) ?: return null
        return match.groupValues[1].length
    }

    fun appendOpenClock(lines: List<String>, headingPath: HeadingPath, start: ZonedDateTime): List<String> {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath) ?: error("Heading not found: $headingPath")
        val insertAt = ensureLogbookAndClockInsertionIndex(working, match)
        working.add(insertAt, "CLOCK: ${OrgTimestamps.format(start)}")
        return working
    }

    fun appendClosedClock(lines: List<String>, headingPath: HeadingPath, start: ZonedDateTime, end: ZonedDateTime): List<String> {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath) ?: error("Heading not found: $headingPath")
        val insertAt = ensureLogbookAndClockInsertionIndex(working, match)
        working.add(insertAt, closedClockLine(start, end))
        return working
    }

    fun appendOpenClockAtLine(lines: List<String>, headingLineIndex: Int, start: ZonedDateTime): List<String> {
        val working = lines.toMutableList()
        val match = findHeadingByLineIndex(working, headingLineIndex)
            ?: error("Heading not found at line: $headingLineIndex")
        val insertAt = ensureLogbookAndClockInsertionIndex(working, match)
        working.add(insertAt, "CLOCK: ${OrgTimestamps.format(start)}")
        return working
    }

    fun closeLatestOpenClock(lines: List<String>, headingPath: HeadingPath, end: ZonedDateTime): CloseOpenResult {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath) ?: error("Heading not found: $headingPath")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: error("No open CLOCK found under heading: $headingPath")

        val original = working[clockLineIndex]
        val startToken = openClockRegex.matchEntire(original)?.groupValues?.get(1)
            ?: error("Open CLOCK line malformed: $original")
        val zone = end.zone
        val start = OrgTimestamps.parseLocal(startToken, zone)
            ?: error("Failed to parse CLOCK start timestamp: $startToken")

        working[clockLineIndex] = closedClockLine(start, end)
        return CloseOpenResult(working, start)
    }

    fun closeLatestOpenClockAtLine(lines: List<String>, headingLineIndex: Int, end: ZonedDateTime): CloseOpenResult {
        val working = lines.toMutableList()
        val match = findHeadingByLineIndex(working, headingLineIndex)
            ?: error("Heading not found at line: $headingLineIndex")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: error("No open CLOCK found at line: $headingLineIndex")

        val original = working[clockLineIndex]
        val startToken = openClockRegex.matchEntire(original)?.groupValues?.get(1)
            ?: error("Open CLOCK line malformed: $original")
        val start = OrgTimestamps.parseLocal(startToken, end.zone)
            ?: error("Failed to parse CLOCK start timestamp: $startToken")

        working[clockLineIndex] = closedClockLine(start, end)
        return CloseOpenResult(working, start)
    }

    fun cancelLatestOpenClockAtLine(lines: List<String>, headingLineIndex: Int): List<String> {
        val working = lines.toMutableList()
        val match = findHeadingByLineIndex(working, headingLineIndex)
            ?: error("Heading not found at line: $headingLineIndex")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: error("No open CLOCK found at line: $headingLineIndex")
        working.removeAt(clockLineIndex)
        return working
    }

    fun findOpenClock(lines: List<String>, headingPath: HeadingPath, zoneId: ZoneId): ZonedDateTime? {
        val match = findHeading(lines, headingPath) ?: return null
        val idx = findLatestOpenClockIndex(lines, match) ?: return null
        val token = openClockRegex.matchEntire(lines[idx])?.groupValues?.get(1) ?: return null
        return OrgTimestamps.parseLocal(token, zoneId)
    }

    fun findOpenClockAtLine(lines: List<String>, headingLineIndex: Int, zoneId: ZoneId): ZonedDateTime? {
        val match = findHeadingByLineIndex(lines, headingLineIndex) ?: return null
        val idx = findLatestOpenClockIndex(lines, match) ?: return null
        val token = openClockRegex.matchEntire(lines[idx])?.groupValues?.get(1) ?: return null
        return OrgTimestamps.parseLocal(token, zoneId)
    }

    fun listClosedClocksAtLine(lines: List<String>, headingLineIndex: Int, zoneId: ZoneId): List<ClosedClockEntry> {
        val heading = findHeadingByLineIndex(lines, headingLineIndex) ?: return emptyList()
        val directEnd = directSectionEnd(lines, heading)
        val results = ArrayList<ClosedClockEntry>()
        for (i in heading.start + 1 until directEnd) {
            val match = closedClockRegex.matchEntire(lines[i]) ?: continue
            val start = OrgTimestamps.parseLocal(match.groupValues[1], zoneId) ?: continue
            val end = OrgTimestamps.parseLocal(match.groupValues[2], zoneId) ?: continue
            val durationMinutes = kotlin.runCatching {
                java.time.Duration.between(start, end).toMinutes().coerceAtLeast(0L)
            }.getOrDefault(0L)
            results += ClosedClockEntry(
                headingLineIndex = headingLineIndex,
                clockLineIndex = i,
                start = start,
                end = end,
                durationMinutes = durationMinutes,
            )
        }
        return results.sortedByDescending { it.start }
    }

    fun replaceClosedClockAtLine(
        lines: List<String>,
        headingLineIndex: Int,
        clockLineIndex: Int,
        newStart: ZonedDateTime,
        newEnd: ZonedDateTime,
    ): List<String> {
        val working = lines.toMutableList()
        val heading = findHeadingByLineIndex(working, headingLineIndex)
            ?: error("Heading not found at line: $headingLineIndex")
        val directEnd = directSectionEnd(working, heading)
        if (clockLineIndex <= heading.start || clockLineIndex >= directEnd) {
            error("Clock line not in heading scope: $clockLineIndex")
        }
        val original = working.getOrNull(clockLineIndex)
            ?: error("Clock line index out of range: $clockLineIndex")
        if (!closedClockRegex.matches(original)) {
            error("Closed CLOCK line not found at line: $clockLineIndex")
        }
        working[clockLineIndex] = closedClockLine(newStart, newEnd)
        return working
    }

    fun findHeading(lines: List<String>, headingPath: HeadingPath): HeadingMatch? {
        val stack = mutableListOf<String>()
        for ((index, line) in lines.withIndex()) {
            val match = headingRegex.matchEntire(line) ?: continue
            val level = match.groupValues[1].length
            val title = normalizeHeadingTitle(match.groupValues[2])

            while (stack.size >= level) {
                stack.removeAt(stack.lastIndex)
            }
            stack.add(title)

            if (stack == headingPath.segments) {
                var end = lines.size
                for (cursor in index + 1 until lines.size) {
                    val next = headingRegex.matchEntire(lines[cursor]) ?: continue
                    val nextLevel = next.groupValues[1].length
                    if (nextLevel <= level) {
                        end = cursor
                        break
                    }
                }
                return HeadingMatch(index, end, level)
            }
        }
        return null
    }

    private fun findHeadingByLineIndex(lines: List<String>, lineIndex: Int): HeadingMatch? {
        for ((index, line) in lines.withIndex()) {
            if (index != lineIndex) {
                continue
            }
            val match = headingRegex.matchEntire(line) ?: return null
            val level = match.groupValues[1].length
            var end = lines.size
            for (cursor in index + 1 until lines.size) {
                val next = headingRegex.matchEntire(lines[cursor]) ?: continue
                val nextLevel = next.groupValues[1].length
                if (nextLevel <= level) {
                    end = cursor
                    break
                }
            }
            return HeadingMatch(index, end, level)
        }
        return null
    }

    private fun ensureLogbookAndClockInsertionIndex(lines: MutableList<String>, heading: HeadingMatch): Int {
        val directEnd = directSectionEnd(lines, heading)
        var logbookStart = -1
        var logbookEnd = -1

        for (i in heading.start + 1 until directEnd) {
            when (lines[i].trim()) {
                ":LOGBOOK:" -> logbookStart = i
                ":END:" -> if (logbookStart >= 0) {
                    logbookEnd = i
                    break
                }
            }
        }

        if (logbookStart >= 0 && logbookEnd >= 0) {
            return logbookEnd
        }

        val insertBase = heading.start + 1
        lines.add(insertBase, ":LOGBOOK:")
        lines.add(insertBase + 1, ":END:")
        return insertBase + 1
    }

    private fun findLatestOpenClockIndex(lines: List<String>, heading: HeadingMatch): Int? {
        val directEnd = directSectionEnd(lines, heading)
        var found: Int? = null
        for (i in heading.start + 1 until directEnd) {
            val line = lines[i]
            if (line.contains("CLOCK:") && openClockRegex.matches(line)) {
                found = i
            }
        }
        return found
    }

    private fun directSectionEnd(lines: List<String>, heading: HeadingMatch): Int {
        for (i in heading.start + 1 until heading.endExclusive) {
            val match = headingRegex.matchEntire(lines[i]) ?: continue
            val level = match.groupValues[1].length
            if (level > heading.level) {
                return i
            }
        }
        return heading.endExclusive
    }

    private fun closedClockLine(start: ZonedDateTime, end: ZonedDateTime): String {
        val duration = OrgTimestamps.formatDuration(start, end)
        return "CLOCK: ${OrgTimestamps.format(start)}--${OrgTimestamps.format(end)} =>  $duration"
    }

    private fun normalizeHeadingTitle(raw: String): String {
        val trimmed = raw.trim()
        val tagMatch = Regex("^(.*?)(\\s+:[A-Za-z0-9_@#%:]+:)$").find(trimmed)
        return tagMatch?.groupValues?.get(1)?.trim()?.ifEmpty { trimmed } ?: trimmed
    }
}
