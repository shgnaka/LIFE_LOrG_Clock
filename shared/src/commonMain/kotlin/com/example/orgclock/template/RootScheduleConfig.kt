package com.example.orgclock.template

enum class ScheduleWeekday {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday,
}

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
    val daysOfWeek: Set<ScheduleWeekday> = setOf(ScheduleWeekday.Monday),
)
