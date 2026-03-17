package com.example.orgclock.desktop

import com.example.orgclock.template.RootScheduleConfig
import com.example.orgclock.template.ScheduleRuleType
import com.example.orgclock.template.ScheduleWeekday
import java.security.MessageDigest
import java.util.prefs.Preferences

class DesktopRootScheduleStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) {
    fun load(rootUri: String): RootScheduleConfig {
        val prefix = prefix(rootUri)
        val ruleType = runCatching {
            ScheduleRuleType.valueOf(
                preferences.get("${prefix}rule_type", ScheduleRuleType.Daily.name) ?: ScheduleRuleType.Daily.name,
            )
        }.getOrDefault(ScheduleRuleType.Daily)
        val storedDays = preferences.get("${prefix}days_of_week", null)
            ?.split(',')
            ?.mapNotNull { raw -> raw.toIntOrNull()?.let(::scheduleWeekdayFromIso) }
            ?.toSet()
            .orEmpty()

        return RootScheduleConfig(
            rootUri = rootUri,
            enabled = preferences.getBoolean("${prefix}enabled", false),
            ruleType = ruleType,
            hour = preferences.getInt("${prefix}hour", 0).coerceIn(0, 23),
            minute = preferences.getInt("${prefix}minute", 0).coerceIn(0, 59),
            daysOfWeek = if (storedDays.isEmpty()) setOf(ScheduleWeekday.Monday) else storedDays,
            templateFileUri = preferences.get("${prefix}template_file_uri", null),
        )
    }

    fun save(config: RootScheduleConfig) {
        val prefix = prefix(config.rootUri)
        preferences.putBoolean("${prefix}enabled", config.enabled)
        preferences.put("${prefix}rule_type", config.ruleType.name)
        preferences.putInt("${prefix}hour", config.hour.coerceIn(0, 23))
        preferences.putInt("${prefix}minute", config.minute.coerceIn(0, 59))
        preferences.put(
            "${prefix}days_of_week",
            config.daysOfWeek
                .sortedBy { scheduleWeekdayToIso(it) }
                .joinToString(",") { scheduleWeekdayToIso(it).toString() },
        )
        config.templateFileUri?.let { preferences.put("${prefix}template_file_uri", it) }
            ?: preferences.remove("${prefix}template_file_uri")
        preferences.flush()
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

    private fun prefix(rootUri: String): String = "rs_${stableHash(rootUri)}_"

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop/root_schedule"
        const val HASH_BYTES = 8
    }
}
