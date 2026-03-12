package com.example.orgclock.template

import android.content.SharedPreferences
import com.example.orgclock.template.ScheduleWeekday
import java.security.MessageDigest

class RootScheduleStore(
    private val prefs: SharedPreferences,
) {
    fun load(rootUri: String): RootScheduleConfig {
        val prefix = prefix(rootUri)
        val ruleType = runCatching {
            ScheduleRuleType.valueOf(
                prefs.getString("${prefix}rule_type", ScheduleRuleType.Daily.name) ?: ScheduleRuleType.Daily.name,
            )
        }.getOrDefault(ScheduleRuleType.Daily)
        val storedDays = prefs.getString("${prefix}days_of_week", null)
            ?.split(',')
            ?.mapNotNull { raw -> raw.toIntOrNull()?.let(::scheduleWeekdayFromIso) }
            ?.toSet()
            .orEmpty()

        return RootScheduleConfig(
            rootUri = rootUri,
            enabled = prefs.getBoolean("${prefix}enabled", false),
            ruleType = ruleType,
            hour = prefs.getInt("${prefix}hour", 0).coerceIn(0, 23),
            minute = prefs.getInt("${prefix}minute", 0).coerceIn(0, 59),
            daysOfWeek = if (storedDays.isEmpty()) setOf(ScheduleWeekday.Monday) else storedDays,
            templateFileUri = prefs.getString("${prefix}template_file_uri", null),
        )
    }

    fun save(config: RootScheduleConfig) {
        val prefix = prefix(config.rootUri)
        prefs.edit()
            .putBoolean("${prefix}enabled", config.enabled)
            .putString("${prefix}rule_type", config.ruleType.name)
            .putInt("${prefix}hour", config.hour.coerceIn(0, 23))
            .putInt("${prefix}minute", config.minute.coerceIn(0, 59))
            .putString(
                "${prefix}days_of_week",
                config.daysOfWeek
                    .sortedBy { scheduleWeekdayToIso(it) }
                    .joinToString(",") { scheduleWeekdayToIso(it).toString() },
            )
            .putString("${prefix}template_file_uri", config.templateFileUri)
            .apply()
    }

    private fun scheduleWeekdayFromIso(day: Int): ScheduleWeekday? = when (day) {
        1 -> ScheduleWeekday.Monday
        2 -> ScheduleWeekday.Tuesday
        3 -> ScheduleWeekday.Wednesday
        4 -> ScheduleWeekday.Thursday
        5 -> ScheduleWeekday.Friday
        6 -> ScheduleWeekday.Saturday
        7 -> ScheduleWeekday.Sunday
        else -> null
    }

    private fun scheduleWeekdayToIso(day: ScheduleWeekday): Int = when (day) {
        ScheduleWeekday.Monday -> 1
        ScheduleWeekday.Tuesday -> 2
        ScheduleWeekday.Wednesday -> 3
        ScheduleWeekday.Thursday -> 4
        ScheduleWeekday.Friday -> 5
        ScheduleWeekday.Saturday -> 6
        ScheduleWeekday.Sunday -> 7
    }

    private fun prefix(rootUri: String): String = "root_schedule_${stableHash(rootUri)}_"

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
