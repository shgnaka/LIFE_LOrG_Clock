package com.example.orgclock.template

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateAutoGenerationPolicyTest {
    @Test
    fun isDue_dailyAfterScheduledTime_returnsTrue() {
        val now = ZonedDateTime.of(2026, 3, 11, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
        val config = RootScheduleConfig(
            rootUri = "content://root",
            enabled = true,
            ruleType = ScheduleRuleType.Daily,
            hour = 9,
            minute = 0,
        )

        assertTrue(TemplateAutoGenerationPolicy.isDue(now, config))
    }

    @Test
    fun isDue_weeklyOnNonScheduledDay_returnsFalse() {
        val now = ZonedDateTime.of(2026, 3, 11, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
        val config = RootScheduleConfig(
            rootUri = "content://root",
            enabled = true,
            ruleType = ScheduleRuleType.Weekly,
            hour = 9,
            minute = 0,
            daysOfWeek = setOf(DayOfWeek.THURSDAY),
        )

        assertFalse(TemplateAutoGenerationPolicy.isDue(now, config))
    }

    @Test
    fun nextRunAt_weeklyPicksNextMatchingWeekday() {
        val now = ZonedDateTime.of(2026, 3, 11, 9, 30, 0, 0, ZoneId.of("Asia/Tokyo"))
        val config = RootScheduleConfig(
            rootUri = "content://root",
            enabled = true,
            ruleType = ScheduleRuleType.Weekly,
            hour = 8,
            minute = 0,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        )

        val nextRun = TemplateAutoGenerationPolicy.nextRunAt(now, config)

        assertEquals(DayOfWeek.FRIDAY, nextRun.dayOfWeek)
        assertEquals(8, nextRun.hour)
        assertEquals(0, nextRun.minute)
    }
}
