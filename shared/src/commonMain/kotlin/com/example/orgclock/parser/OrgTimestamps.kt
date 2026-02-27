package com.example.orgclock.parser

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object OrgTimestamps {
    private val writeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm:ss", Locale.US)
    private val parseFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd E HH:mm:ss", Locale.JAPAN),
        DateTimeFormatter.ofPattern("yyyy-MM-dd E HH:mm", Locale.JAPAN),
    )

    fun format(dateTime: ZonedDateTime): String = "[${dateTime.format(writeFormatter)}]"

    fun parse(raw: String): ZonedDateTime? {
        return parseLocal(raw, ZoneId.systemDefault())
    }

    fun parseLocal(raw: String, zoneId: ZoneId): ZonedDateTime? {
        val trimmed = raw.trim().removePrefix("[").removeSuffix("]")
        for (formatter in parseFormatters) {
            val parsed = runCatching { LocalDateTime.parse(trimmed, formatter).atZone(zoneId) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun formatDuration(start: ZonedDateTime, end: ZonedDateTime): String {
        val duration = Duration.between(start, end)
        require(!duration.isNegative) { "Clock end must be after start." }
        val totalMinutes = duration.seconds / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "%d:%02d".format(hours, minutes)
    }
}
