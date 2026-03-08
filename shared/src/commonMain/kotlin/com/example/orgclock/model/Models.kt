package com.example.orgclock.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

data class HeadingPath(val segments: List<String>) {
    init {
        require(segments.isNotEmpty()) { "Heading path must have at least one segment." }
    }

    override fun toString(): String = segments.joinToString("/")

    companion object {
        fun parse(raw: String): HeadingPath {
            val segments = raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            require(segments.isNotEmpty()) { "Heading path cannot be empty." }
            return HeadingPath(segments)
        }
    }
}

data class ClockEntry(
    val start: Instant,
    val end: Instant?,
)

data class OpenClock(
    val fileDate: LocalDate,
    val headingPath: HeadingPath,
    val start: Instant,
)

data class OrgDocument(
    val date: LocalDate,
    val lines: List<String>,
    val hash: String,
)

data class HeadingNode(
    val lineIndex: Int,
    val level: Int,
    val title: String,
    val path: HeadingPath,
    val parentL1: String?,
)

data class OpenClockState(
    val startedAt: Instant,
)

data class ClosedClockEntry(
    val headingPath: HeadingPath,
    val headingLineIndex: Int,
    val clockLineIndex: Int,
    val start: Instant,
    val end: Instant,
    val durationMinutes: Long,
)

data class HeadingViewItem(
    val node: HeadingNode,
    val canStart: Boolean,
    val openClock: OpenClockState?,
)
