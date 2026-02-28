package com.example.orgclock.time

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toKotlinLocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

fun Instant.toJavaZonedDateTime(zoneId: ZoneId): ZonedDateTime = toJavaInstant().atZone(zoneId)

fun ZonedDateTime.toKotlinInstantCompat(): Instant = toInstant().toKotlinInstant()

fun java.time.LocalDate.toKotlinLocalDateCompat(): LocalDate = toKotlinLocalDate()

fun LocalDate.toJavaLocalDateCompat(): java.time.LocalDate = toJavaLocalDate()

fun Instant.formatWithZone(
    formatter: java.time.format.DateTimeFormatter,
    zoneId: ZoneId,
): String = toJavaZonedDateTime(zoneId).format(formatter)

fun Instant.toKotlinLocalDateIn(zoneId: ZoneId): LocalDate =
    toJavaZonedDateTime(zoneId).toLocalDate().toKotlinLocalDateCompat()

fun systemTimeZone(): TimeZone = TimeZone.currentSystemDefault()
