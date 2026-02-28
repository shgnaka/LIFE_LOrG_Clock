package com.example.orgclock.time

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

interface ClockEnvironment {
    fun now(): ZonedDateTime
    fun zoneId(): ZoneId
}

object SystemClockEnvironment : ClockEnvironment {
    override fun now(): ZonedDateTime = ZonedDateTime.now()

    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

fun ClockEnvironment.today(): LocalDate = now().toLocalDate()
