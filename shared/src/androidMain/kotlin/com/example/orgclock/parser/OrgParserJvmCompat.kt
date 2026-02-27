package com.example.orgclock.parser

import com.example.orgclock.model.ClosedClockEntry
import com.example.orgclock.model.HeadingPath
import com.example.orgclock.time.toKotlinInstant
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinTimeZone
import java.time.ZoneId
import java.time.ZonedDateTime

fun OrgParser.parseHeadingsWithOpenClock(lines: List<String>, zoneId: ZoneId): List<OrgParser.HeadingWithOpenClock> =
    parseHeadingsWithOpenClock(lines, zoneId.toKotlinTimeZone())

fun OrgParser.appendOpenClock(lines: List<String>, headingPath: HeadingPath, start: ZonedDateTime): List<String> =
    appendOpenClock(lines, headingPath, start.toKotlinInstant(), start.zone.toKotlinTimeZone())

fun OrgParser.appendClosedClock(
    lines: List<String>,
    headingPath: HeadingPath,
    start: ZonedDateTime,
    end: ZonedDateTime,
): List<String> = appendClosedClock(lines, headingPath, start.toKotlinInstant(), end.toKotlinInstant(), end.zone.toKotlinTimeZone())

fun OrgParser.appendOpenClockAtLine(lines: List<String>, headingLineIndex: Int, start: ZonedDateTime): List<String> =
    appendOpenClockAtLine(lines, headingLineIndex, start.toKotlinInstant(), start.zone.toKotlinTimeZone())

fun OrgParser.closeLatestOpenClock(lines: List<String>, headingPath: HeadingPath, end: ZonedDateTime): OrgParser.CloseOpenResult =
    closeLatestOpenClock(lines, headingPath, end.toKotlinInstant(), end.zone.toKotlinTimeZone())

fun OrgParser.closeLatestOpenClockAtLine(
    lines: List<String>,
    headingLineIndex: Int,
    end: ZonedDateTime,
): OrgParser.CloseOpenResult = closeLatestOpenClockAtLine(lines, headingLineIndex, end.toKotlinInstant(), end.zone.toKotlinTimeZone())

fun OrgParser.findOpenClock(lines: List<String>, headingPath: HeadingPath, zoneId: ZoneId): Instant? =
    findOpenClock(lines, headingPath, zoneId.toKotlinTimeZone())

fun OrgParser.findOpenClockAtLine(lines: List<String>, headingLineIndex: Int, zoneId: ZoneId): Instant? =
    findOpenClockAtLine(lines, headingLineIndex, zoneId.toKotlinTimeZone())

fun OrgParser.listClosedClocksAtLine(lines: List<String>, headingLineIndex: Int, zoneId: ZoneId): List<ClosedClockEntry> =
    listClosedClocksAtLine(lines, headingLineIndex, zoneId.toKotlinTimeZone())

fun OrgParser.replaceClosedClockAtLine(
    lines: List<String>,
    headingLineIndex: Int,
    clockLineIndex: Int,
    newStart: ZonedDateTime,
    newEnd: ZonedDateTime,
): List<String> = replaceClosedClockAtLine(
    lines,
    headingLineIndex,
    clockLineIndex,
    newStart.toKotlinInstant(),
    newEnd.toKotlinInstant(),
    newEnd.zone.toKotlinTimeZone(),
)
