package com.example.orgclock.parser

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

object OrgTimestamps {
    private val tokenRegex = Regex(
        """^(\d{4})-(\d{2})-(\d{2})\s+(\S+)\s+(\d{2}):(\d{2})(?::(\d{2}))?$""",
    )

    fun format(dateTime: LocalDateTime): String {
        val date = dateTime.date
        val time = dateTime.time
        val weekday = weekdayLabel(date.dayOfWeek)
        return "[${pad(date.year, 4)}-${pad(date.monthNumber)}-${pad(date.dayOfMonth)} " +
            "$weekday ${pad(time.hour)}:${pad(time.minute)}:${pad(time.second)}]"
    }

    fun format(instant: Instant, timeZone: TimeZone): String = format(instant.toLocalDateTime(timeZone))

    fun parse(raw: String): LocalDateTime? = parseLocal(raw)

    fun parseLocal(raw: String): LocalDateTime? {
        val trimmed = raw.trim().removePrefix("[").removeSuffix("]")
        val match = tokenRegex.matchEntire(trimmed) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[5].toIntOrNull() ?: return null
        val minute = match.groupValues[6].toIntOrNull() ?: return null
        val second = match.groupValues[7].toIntOrBlank() ?: 0
        return runCatching {
            LocalDateTime(
                date = LocalDate(year, month, day),
                time = LocalTime(hour, minute, second),
            )
        }.getOrNull()
    }

    fun formatDuration(start: Instant, end: Instant): String {
        val duration: Duration = end - start
        require(!duration.isNegative()) { "Clock end must be after start." }
        val totalMinutes = duration.inWholeMinutes
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "$hours:${minutes.toString().padStart(2, '0')}"
    }

    private fun pad(value: Int, length: Int = 2): String = value.toString().padStart(length, '0')

    private fun weekdayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
        else -> "Mon"
    }

    private fun String.toIntOrBlank(): Int? = if (isBlank()) null else toIntOrNull()
}
