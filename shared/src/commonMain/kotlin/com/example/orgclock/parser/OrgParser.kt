package com.example.orgclock.parser

import com.example.orgclock.model.HeadingNode
import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

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
        val start: Instant,
    )

    data class HeadingWithOpenClock(
        val node: HeadingNode,
        val openClock: Instant?,
    )

    private data class ParsedHeading(
        val lineIndex: Int,
        val level: Int,
        val title: String,
        val path: HeadingPath,
        val parentL1: String?,
        val endExclusive: Int,
    )

    fun parseHeadings(lines: List<String>): List<HeadingNode> {
        return parseHeadingsWithRanges(lines).map {
            HeadingNode(
                lineIndex = it.lineIndex,
                level = it.level,
                title = it.title,
                path = it.path,
                parentL1 = it.parentL1,
            )
        }
    }

    fun parseHeadingsWithOpenClock(lines: List<String>, timeZone: TimeZone): List<HeadingWithOpenClock> {
        val headings = parseHeadingsWithRanges(lines)
        if (headings.isEmpty()) return emptyList()

        val openByLine = mutableMapOf<Int, Instant?>()
        for (i in headings.indices) {
            val heading = headings[i]
            val next = headings.getOrNull(i + 1)
            val directEnd = if (next != null && next.level > heading.level) {
                next.lineIndex
            } else {
                heading.endExclusive
            }
            var open: Instant? = null
            for (lineIndex in heading.lineIndex + 1 until directEnd) {
                val line = lines[lineIndex]
                if (!line.contains("CLOCK:")) continue
                val token = openClockRegex.matchEntire(line)?.groupValues?.get(1) ?: continue
                val parsed = OrgTimestamps.parseLocal(token)?.toInstant(timeZone) ?: continue
                open = parsed
            }
            openByLine[heading.lineIndex] = open
        }

        return headings.map { heading ->
            HeadingWithOpenClock(
                node = HeadingNode(
                    lineIndex = heading.lineIndex,
                    level = heading.level,
                    title = heading.title,
                    path = heading.path,
                    parentL1 = heading.parentL1,
                ),
                openClock = openByLine[heading.lineIndex],
            )
        }
    }

    private fun parseHeadingsWithRanges(lines: List<String>): List<ParsedHeading> {
        val stack = mutableListOf<String>()
        val headingStack = mutableListOf<Int>()
        var currentL1: String? = null
        val result = mutableListOf<ParsedHeading>()

        for ((index, line) in lines.withIndex()) {
            val match = headingRegex.matchEntire(line) ?: continue
            val level = match.groupValues[1].length
            val title = normalizeHeadingTitle(match.groupValues[2])

            while (headingStack.isNotEmpty() && result[headingStack.last()].level >= level) {
                val closing = headingStack.removeAt(headingStack.lastIndex)
                result[closing] = result[closing].copy(endExclusive = index)
            }

            while (stack.size >= level) {
                stack.removeAt(stack.lastIndex)
            }
            stack.add(title)

            if (level == 1) {
                currentL1 = title
            }

            result += ParsedHeading(
                lineIndex = index,
                level = level,
                title = title,
                path = HeadingPath(stack.toList()),
                parentL1 = currentL1,
                endExclusive = lines.size,
            )
            headingStack += result.lastIndex
        }

        while (headingStack.isNotEmpty()) {
            val openHeading = headingStack.removeAt(headingStack.lastIndex)
            result[openHeading] = result[openHeading].copy(endExclusive = lines.size)
        }

        return result
    }

    fun headingLevelAtLine(lines: List<String>, lineIndex: Int): Int? {
        val line = lines.getOrNull(lineIndex) ?: return null
        val match = headingRegex.matchEntire(line) ?: return null
        return match.groupValues[1].length
    }

    fun appendOpenClock(
        lines: List<String>,
        headingPath: HeadingPath,
        start: Instant,
        timeZone: TimeZone,
    ): List<String> {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath)
            ?: throw IllegalArgumentException("Heading not found: $headingPath")
        val insertAt = ensureLogbookAndClockInsertionIndex(working, match)
        working.add(insertAt, "CLOCK: ${OrgTimestamps.format(start, timeZone)}")
        return working
    }

    fun appendL1Heading(lines: List<String>, l1Title: String, attachTplTag: Boolean = false): List<String> {
        val normalizedTitle = normalizeHeadingTitle(normalizeNewHeadingTitle(l1Title))
        val l1Exists = parseHeadingsWithRanges(lines).any {
            it.level == 1 && it.title == normalizedTitle
        }
        if (l1Exists) {
            throw IllegalArgumentException("Level-1 heading already exists: $normalizedTitle")
        }
        val working = lines.toMutableList()
        val titleWithTag = normalizeNewHeadingTitleWithTpl(l1Title, attachTplTag)
        working.add("* $titleWithTag")
        return working
    }

    fun appendL2HeadingUnderL1(
        lines: List<String>,
        l1LineIndex: Int,
        l2Title: String,
        attachTplTag: Boolean = false,
    ): List<String> {
        val parent = findHeadingByLineIndex(lines, l1LineIndex)
            ?: throw IllegalArgumentException("Heading not found at line: $l1LineIndex")
        if (parent.level != 1) {
            throw IllegalArgumentException("Parent heading must be level-1")
        }

        val normalizedTitle = normalizeHeadingTitle(normalizeNewHeadingTitle(l2Title))
        val parentEnd = parent.endExclusive
        for (i in parent.start + 1 until parentEnd) {
            val match = headingRegex.matchEntire(lines[i]) ?: continue
            val level = match.groupValues[1].length
            if (level != 2) continue
            val existing = normalizeHeadingTitle(match.groupValues[2])
            if (existing == normalizedTitle) {
                throw IllegalArgumentException("Level-2 heading already exists under selected L1: $normalizedTitle")
            }
        }

        val working = lines.toMutableList()
        val titleWithTag = normalizeNewHeadingTitleWithTpl(l2Title, attachTplTag)
        working.add(parentEnd, "** $titleWithTag")
        return working
    }

    fun appendL2HeadingUnderL1(
        lines: List<String>,
        parentPath: HeadingPath,
        l2Title: String,
        attachTplTag: Boolean = false,
    ): List<String> {
        val parent = findHeading(lines, parentPath)
            ?: throw IllegalArgumentException("Heading not found: $parentPath")
        if (parent.level != 1) {
            throw IllegalArgumentException("Parent heading must be level-1")
        }

        val normalizedTitle = normalizeHeadingTitle(normalizeNewHeadingTitle(l2Title))
        val parentEnd = parent.endExclusive
        for (i in parent.start + 1 until parentEnd) {
            val match = headingRegex.matchEntire(lines[i]) ?: continue
            val level = match.groupValues[1].length
            if (level != 2) continue
            val existing = normalizeHeadingTitle(match.groupValues[2])
            if (existing == normalizedTitle) {
                throw IllegalArgumentException("Level-2 heading already exists under selected L1: $normalizedTitle")
            }
        }

        val working = lines.toMutableList()
        val titleWithTag = normalizeNewHeadingTitleWithTpl(l2Title, attachTplTag)
        working.add(parentEnd, "** $titleWithTag")
        return working
    }

    fun appendClosedClock(
        lines: List<String>,
        headingPath: HeadingPath,
        start: Instant,
        end: Instant,
        timeZone: TimeZone,
    ): List<String> {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath)
            ?: throw IllegalArgumentException("Heading not found: $headingPath")
        val insertAt = ensureLogbookAndClockInsertionIndex(working, match)
        working.add(insertAt, closedClockLine(start, end, timeZone))
        return working
    }

    fun appendOpenClockAtLine(
        lines: List<String>,
        headingLineIndex: Int,
        start: Instant,
        timeZone: TimeZone,
    ): List<String> {
        val working = lines.toMutableList()
        val match = findHeadingByLineIndex(working, headingLineIndex)
            ?: throw IllegalArgumentException("Heading not found at line: $headingLineIndex")
        val insertAt = ensureLogbookAndClockInsertionIndex(working, match)
        working.add(insertAt, "CLOCK: ${OrgTimestamps.format(start, timeZone)}")
        return working
    }

    fun closeLatestOpenClock(
        lines: List<String>,
        headingPath: HeadingPath,
        end: Instant,
        timeZone: TimeZone,
    ): CloseOpenResult {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath)
            ?: throw IllegalArgumentException("Heading not found: $headingPath")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: throw IllegalStateException("No open CLOCK found under heading: $headingPath")

        val original = working[clockLineIndex]
        val startToken = openClockRegex.matchEntire(original)?.groupValues?.get(1)
            ?: throw IllegalStateException("Open CLOCK line malformed: $original")
        val start = OrgTimestamps.parseLocal(startToken)?.toInstant(timeZone)
            ?: throw IllegalStateException("Failed to parse CLOCK start timestamp: $startToken")

        working[clockLineIndex] = closedClockLine(start, end, timeZone)
        return CloseOpenResult(working, start)
    }

    fun closeLatestOpenClockAtLine(
        lines: List<String>,
        headingLineIndex: Int,
        end: Instant,
        timeZone: TimeZone,
    ): CloseOpenResult {
        val working = lines.toMutableList()
        val match = findHeadingByLineIndex(working, headingLineIndex)
            ?: throw IllegalArgumentException("Heading not found at line: $headingLineIndex")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: throw IllegalStateException("No open CLOCK found at line: $headingLineIndex")

        val original = working[clockLineIndex]
        val startToken = openClockRegex.matchEntire(original)?.groupValues?.get(1)
            ?: throw IllegalStateException("Open CLOCK line malformed: $original")
        val start = OrgTimestamps.parseLocal(startToken)?.toInstant(timeZone)
            ?: throw IllegalStateException("Failed to parse CLOCK start timestamp: $startToken")

        working[clockLineIndex] = closedClockLine(start, end, timeZone)
        return CloseOpenResult(working, start)
    }

    fun cancelLatestOpenClockAtLine(lines: List<String>, headingLineIndex: Int): List<String> {
        val working = lines.toMutableList()
        val match = findHeadingByLineIndex(working, headingLineIndex)
            ?: throw IllegalArgumentException("Heading not found at line: $headingLineIndex")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: throw IllegalStateException("No open CLOCK found at line: $headingLineIndex")
        working.removeAt(clockLineIndex)
        return working
    }

    fun cancelLatestOpenClock(lines: List<String>, headingPath: HeadingPath): List<String> {
        val working = lines.toMutableList()
        val match = findHeading(working, headingPath)
            ?: throw IllegalArgumentException("Heading not found: $headingPath")
        val clockLineIndex = findLatestOpenClockIndex(working, match)
            ?: throw IllegalStateException("No open CLOCK found under heading: $headingPath")
        working.removeAt(clockLineIndex)
        return working
    }

    fun findOpenClock(lines: List<String>, headingPath: HeadingPath, timeZone: TimeZone): Instant? {
        val match = findHeading(lines, headingPath) ?: return null
        val idx = findLatestOpenClockIndex(lines, match) ?: return null
        val token = openClockRegex.matchEntire(lines[idx])?.groupValues?.get(1) ?: return null
        return OrgTimestamps.parseLocal(token)?.toInstant(timeZone)
    }

    fun findOpenClockAtLine(lines: List<String>, headingLineIndex: Int, timeZone: TimeZone): Instant? {
        val match = findHeadingByLineIndex(lines, headingLineIndex) ?: return null
        val idx = findLatestOpenClockIndex(lines, match) ?: return null
        val token = openClockRegex.matchEntire(lines[idx])?.groupValues?.get(1) ?: return null
        return OrgTimestamps.parseLocal(token)?.toInstant(timeZone)
    }

    fun listClosedClocks(
        lines: List<String>,
        headingPath: HeadingPath,
        timeZone: TimeZone,
    ): List<ClosedClockEntry> {
        val heading = findHeading(lines, headingPath) ?: return emptyList()
        val directEnd = directSectionEnd(lines, heading)
        val results = ArrayList<ClosedClockEntry>()
        for (i in heading.start + 1 until directEnd) {
            val match = closedClockRegex.matchEntire(lines[i]) ?: continue
            val start = OrgTimestamps.parseLocal(match.groupValues[1])?.toInstant(timeZone) ?: continue
            val end = OrgTimestamps.parseLocal(match.groupValues[2])?.toInstant(timeZone) ?: continue
            val durationMinutes = kotlin.runCatching {
                (end - start).inWholeMinutes.coerceAtLeast(0L)
            }.getOrDefault(0L)
            results += ClosedClockEntry(
                headingPath = headingPath,
                headingLineIndex = heading.start,
                clockLineIndex = i,
                start = start,
                end = end,
                durationMinutes = durationMinutes,
            )
        }
        return results.sortedByDescending { it.start }
    }

    fun listClosedClocksAtLine(lines: List<String>, headingLineIndex: Int, timeZone: TimeZone): List<ClosedClockEntry> {
        val heading = findHeadingByLineIndex(lines, headingLineIndex) ?: return emptyList()
        val headingPath = headingPathAtLine(lines, headingLineIndex) ?: return emptyList()
        val directEnd = directSectionEnd(lines, heading)
        val results = ArrayList<ClosedClockEntry>()
        for (i in heading.start + 1 until directEnd) {
            val match = closedClockRegex.matchEntire(lines[i]) ?: continue
            val start = OrgTimestamps.parseLocal(match.groupValues[1])?.toInstant(timeZone) ?: continue
            val end = OrgTimestamps.parseLocal(match.groupValues[2])?.toInstant(timeZone) ?: continue
            val durationMinutes = kotlin.runCatching {
                (end - start).inWholeMinutes.coerceAtLeast(0L)
            }.getOrDefault(0L)
            results += ClosedClockEntry(
                headingPath = headingPath,
                headingLineIndex = headingLineIndex,
                clockLineIndex = i,
                start = start,
                end = end,
                durationMinutes = durationMinutes,
            )
        }
        return results.sortedByDescending { it.start }
    }

    fun replaceClosedClock(
        lines: List<String>,
        headingPath: HeadingPath,
        clockLineIndex: Int,
        newStart: Instant,
        newEnd: Instant,
        timeZone: TimeZone,
    ): List<String> {
        val working = lines.toMutableList()
        val heading = findHeading(working, headingPath)
            ?: throw IllegalArgumentException("Heading not found: $headingPath")
        val directEnd = directSectionEnd(working, heading)
        if (clockLineIndex <= heading.start || clockLineIndex >= directEnd) {
            throw IllegalArgumentException("Clock line not in heading scope: $clockLineIndex")
        }
        val original = working.getOrNull(clockLineIndex)
            ?: throw IllegalArgumentException("Clock line index out of range: $clockLineIndex")
        if (!closedClockRegex.matches(original)) {
            throw IllegalArgumentException("Closed CLOCK line not found at line: $clockLineIndex")
        }
        working[clockLineIndex] = closedClockLine(newStart, newEnd, timeZone)
        return working
    }

    fun replaceClosedClockAtLine(
        lines: List<String>,
        headingLineIndex: Int,
        clockLineIndex: Int,
        newStart: Instant,
        newEnd: Instant,
        timeZone: TimeZone,
    ): List<String> {
        val working = lines.toMutableList()
        val heading = findHeadingByLineIndex(working, headingLineIndex)
            ?: throw IllegalArgumentException("Heading not found at line: $headingLineIndex")
        val directEnd = directSectionEnd(working, heading)
        if (clockLineIndex <= heading.start || clockLineIndex >= directEnd) {
            throw IllegalArgumentException("Clock line not in heading scope: $clockLineIndex")
        }
        val original = working.getOrNull(clockLineIndex)
            ?: throw IllegalArgumentException("Clock line index out of range: $clockLineIndex")
        if (!closedClockRegex.matches(original)) {
            throw IllegalArgumentException("Closed CLOCK line not found at line: $clockLineIndex")
        }
        working[clockLineIndex] = closedClockLine(newStart, newEnd, timeZone)
        return working
    }

    fun deleteClosedClockAtLine(
        lines: List<String>,
        headingLineIndex: Int,
        clockLineIndex: Int,
    ): List<String> {
        val working = lines.toMutableList()
        val heading = findHeadingByLineIndex(working, headingLineIndex)
            ?: throw IllegalArgumentException("Heading not found at line: $headingLineIndex")
        val directEnd = directSectionEnd(working, heading)
        if (clockLineIndex <= heading.start || clockLineIndex >= directEnd) {
            throw IllegalArgumentException("Clock line not in heading scope: $clockLineIndex")
        }
        val original = working.getOrNull(clockLineIndex)
            ?: throw IllegalArgumentException("Clock line index out of range: $clockLineIndex")
        if (!closedClockRegex.matches(original)) {
            throw IllegalArgumentException("Closed CLOCK line not found at line: $clockLineIndex")
        }
        working.removeAt(clockLineIndex)
        return working
    }

    fun deleteClosedClock(
        lines: List<String>,
        headingPath: HeadingPath,
        clockLineIndex: Int,
    ): List<String> {
        val working = lines.toMutableList()
        val heading = findHeading(working, headingPath)
            ?: throw IllegalArgumentException("Heading not found: $headingPath")
        val directEnd = directSectionEnd(working, heading)
        if (clockLineIndex <= heading.start || clockLineIndex >= directEnd) {
            throw IllegalArgumentException("Clock line not in heading scope: $clockLineIndex")
        }
        val original = working.getOrNull(clockLineIndex)
            ?: throw IllegalArgumentException("Clock line index out of range: $clockLineIndex")
        if (!closedClockRegex.matches(original)) {
            throw IllegalArgumentException("Closed CLOCK line not found at line: $clockLineIndex")
        }
        working.removeAt(clockLineIndex)
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

    fun headingPathAtLine(lines: List<String>, lineIndex: Int): HeadingPath? {
        return parseHeadingsWithRanges(lines)
            .firstOrNull { it.lineIndex == lineIndex }
            ?.path
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

    private fun closedClockLine(start: Instant, end: Instant, timeZone: TimeZone): String {
        val duration = OrgTimestamps.formatDuration(start, end)
        return "CLOCK: ${OrgTimestamps.format(start, timeZone)}--${OrgTimestamps.format(end, timeZone)} =>  $duration"
    }

    private fun normalizeHeadingTitle(raw: String): String {
        val trimmed = raw.trim()
        val tagMatch = Regex("^(.*?)(\\s+:[A-Za-z0-9_@#%:]+:)$").find(trimmed)
        return tagMatch?.groupValues?.get(1)?.trim()?.ifEmpty { trimmed } ?: trimmed
    }

    private fun normalizeNewHeadingTitle(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Heading title cannot be empty" }
        require(!trimmed.contains('\n') && !trimmed.contains('\r')) { "Heading title cannot contain newlines" }
        return trimmed
    }

    private fun normalizeNewHeadingTitleWithTpl(raw: String, attachTplTag: Boolean): String {
        val normalized = normalizeNewHeadingTitle(raw)
        if (!attachTplTag) return normalized

        val tagSuffixMatch = Regex("^(.*?)(\\s+:[A-Za-z0-9_@#%:]+:)$").find(normalized)
        if (tagSuffixMatch == null) {
            return "$normalized :TPL:"
        }

        val base = tagSuffixMatch.groupValues[1].trimEnd()
        val suffix = tagSuffixMatch.groupValues[2].trim()
        val tags = suffix.trim(':')
            .split(':')
            .filter { it.isNotBlank() }
        if (tags.any { it == "TPL" }) {
            return normalized
        }
        val updatedSuffix = ":" + (tags + "TPL").joinToString(":") + ":"
        return "$base $updatedSuffix"
    }
}
