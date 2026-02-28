package com.example.orgclock.time

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

interface ClockEnvironment {
    fun now(): Instant
    fun currentTimeZone(): TimeZone
}

fun ClockEnvironment.today(): LocalDate = now().toLocalDateTime(currentTimeZone()).date
