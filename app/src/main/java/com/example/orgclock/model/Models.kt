package com.example.orgclock.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZonedDateTime

@Immutable
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

@Immutable
data class ClockEntry(
    val start: ZonedDateTime,
    val end: ZonedDateTime?,
)

@Immutable
data class OpenClock(
    val fileDate: LocalDate,
    val headingPath: HeadingPath,
    val start: ZonedDateTime,
)

@Immutable
data class OrgDocument(
    val date: LocalDate,
    val lines: List<String>,
    val hash: String,
)

@Immutable
data class HeadingNode(
    val lineIndex: Int,
    val level: Int,
    val title: String,
    val path: HeadingPath,
    val parentL1: String?,
)

@Immutable
data class OpenClockState(
    val startedAt: ZonedDateTime,
)

@Immutable
data class ClosedClockEntry(
    val headingLineIndex: Int,
    val clockLineIndex: Int,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val durationMinutes: Long,
)

@Immutable
data class HeadingViewItem(
    val node: HeadingNode,
    val canStart: Boolean,
    val openClock: OpenClockState?,
)
