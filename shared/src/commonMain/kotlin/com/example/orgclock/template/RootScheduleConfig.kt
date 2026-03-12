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
    val templateFileUri: String? = null,
)

enum class TemplateReferenceMode {
    Explicit,
    LegacyHiddenFile,
}

enum class TemplateAvailability {
    Available,
    Missing,
    Unreadable,
}

data class TemplateFileStatus(
    val availability: TemplateAvailability,
    val referenceMode: TemplateReferenceMode,
    val fileId: String? = null,
    val displayName: String? = null,
    val detailMessage: String? = null,
)
