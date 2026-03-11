package com.example.orgclock.template

import java.time.DayOfWeek
import java.time.ZonedDateTime

object TemplateAutoGenerationPolicy {
    fun isDue(now: ZonedDateTime, config: RootScheduleConfig): Boolean {
        if (!config.enabled) return false
        val today = now.dayOfWeek
        if (config.ruleType == ScheduleRuleType.Weekly && today !in config.daysOfWeek.map { it.toJavaDayOfWeek() }.toSet()) return false
        val scheduled = now.withHour(config.hour).withMinute(config.minute).withSecond(0).withNano(0)
        return !now.isBefore(scheduled)
    }

    fun nextRunAt(now: ZonedDateTime, config: RootScheduleConfig): ZonedDateTime {
        val base = now.withSecond(0).withNano(0)
        return when (config.ruleType) {
            ScheduleRuleType.Daily -> nextDailyRun(base, config.hour, config.minute)
            ScheduleRuleType.Weekly -> nextWeeklyRun(
                base,
                config.hour,
                config.minute,
                config.daysOfWeek.map { it.toJavaDayOfWeek() }.toSet(),
            )
        }
    }

    private fun nextDailyRun(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun nextWeeklyRun(
        now: ZonedDateTime,
        hour: Int,
        minute: Int,
        daysOfWeek: Set<DayOfWeek>,
    ): ZonedDateTime {
        for (offset in 0..7L) {
            val candidateDate = now.plusDays(offset)
            if (candidateDate.dayOfWeek !in daysOfWeek) continue
            val candidate = candidateDate.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (candidate.isAfter(now)) {
                return candidate
            }
        }
        return now.plusWeeks(1).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    }
}

private fun ScheduleWeekday.toJavaDayOfWeek(): DayOfWeek = when (this) {
    ScheduleWeekday.Monday -> DayOfWeek.MONDAY
    ScheduleWeekday.Tuesday -> DayOfWeek.TUESDAY
    ScheduleWeekday.Wednesday -> DayOfWeek.WEDNESDAY
    ScheduleWeekday.Thursday -> DayOfWeek.THURSDAY
    ScheduleWeekday.Friday -> DayOfWeek.FRIDAY
    ScheduleWeekday.Saturday -> DayOfWeek.SATURDAY
    ScheduleWeekday.Sunday -> DayOfWeek.SUNDAY
}
