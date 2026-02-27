package com.example.orgclock.time

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

object SystemClockEnvironment : ClockEnvironment {
    override fun now(): Instant = Clock.System.now()

    override fun currentTimeZone(): TimeZone = TimeZone.currentSystemDefault()
}
