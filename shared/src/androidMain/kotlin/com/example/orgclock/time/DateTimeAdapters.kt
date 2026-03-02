package com.example.orgclock.time

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaZoneId
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

fun Instant.toZonedDateTime(zoneId: ZoneId): ZonedDateTime =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()).atZone(zoneId)

fun ZonedDateTime.toKotlinInstant(): Instant =
    Instant.fromEpochSeconds(toEpochSecond(), nano)

fun LocalDate.toJavaLocalDateCompat(): java.time.LocalDate = toJavaLocalDate()

fun java.time.LocalDate.toKotlinLocalDateCompat(): LocalDate = toKotlinLocalDate()

fun ZoneId.toKotlinTimeZoneCompat(): TimeZone = toKotlinTimeZone()

fun TimeZone.toJavaZoneIdCompat(): ZoneId = toJavaZoneId()

fun Instant.toLocalDateInZone(zoneId: ZoneId): LocalDate =
    toLocalDateTime(zoneId.toKotlinTimeZoneCompat()).date
