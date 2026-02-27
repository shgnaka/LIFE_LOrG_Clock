package com.example.orgclock.domain

import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.model.OpenClock
import kotlinx.datetime.toKotlinTimeZone
import java.time.ZoneId
import java.time.ZonedDateTime
import com.example.orgclock.time.toKotlinInstant

suspend fun ClockService.startClock(dateTime: ZonedDateTime, headingPath: HeadingPath): Result<ClockService.ClockSession> =
    startClock(dateTime.toKotlinInstant(), headingPath, dateTime.zone.toKotlinTimeZone())

suspend fun ClockService.startClockInFile(
    fileId: String,
    headingLineIndex: Int,
    now: ZonedDateTime,
): Result<ClockMutationResult> = startClockInFile(fileId, headingLineIndex, now.toKotlinInstant(), now.zone.toKotlinTimeZone())

suspend fun ClockService.stopClockInFile(
    fileId: String,
    headingLineIndex: Int,
    now: ZonedDateTime,
): Result<ClockMutationResult> = stopClockInFile(fileId, headingLineIndex, now.toKotlinInstant(), now.zone.toKotlinTimeZone())

suspend fun ClockService.listClosedClocksInFile(
    fileId: String,
    headingLineIndex: Int,
    now: ZonedDateTime,
): Result<List<ClosedClockEntry>> = listClosedClocksInFile(fileId, headingLineIndex, now.zone.toKotlinTimeZone())

suspend fun ClockService.editClosedClockInFile(
    fileId: String,
    headingLineIndex: Int,
    clockLineIndex: Int,
    newStart: ZonedDateTime,
    newEnd: ZonedDateTime,
): Result<Unit> = editClosedClockInFile(
    fileId = fileId,
    headingLineIndex = headingLineIndex,
    clockLineIndex = clockLineIndex,
    newStart = newStart.toKotlinInstant(),
    newEnd = newEnd.toKotlinInstant(),
    timeZone = newEnd.zone.toKotlinTimeZone(),
)

suspend fun ClockService.stopClock(
    dateTime: ZonedDateTime,
    headingPath: HeadingPath,
): ClockService.ClockStopResult = stopClock(dateTime.toKotlinInstant(), headingPath, dateTime.zone.toKotlinTimeZone())

suspend fun ClockService.recoverOpenClocks(
    now: ZonedDateTime,
    candidates: List<HeadingPath>,
): Result<List<OpenClock>> = recoverOpenClocks(now.toKotlinInstant(), candidates, now.zone.toKotlinTimeZone())

fun ZoneId.toKotlinTimeZoneCompat(): kotlinx.datetime.TimeZone = toKotlinTimeZone()
