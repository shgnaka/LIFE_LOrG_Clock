package com.example.orgclock.template

import java.time.DayOfWeek

enum class ScheduleRuleType {
    Daily,
    Weekly,
}

data class RootScheduleConfig(
    val rootUri: String,
    val enabled: Boolean = false,
    val ruleType: ScheduleRuleType = ScheduleRuleType.Daily,
    val hour: Int = 0,
    val minute: Int = 0,
    val daysOfWeek: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
)
