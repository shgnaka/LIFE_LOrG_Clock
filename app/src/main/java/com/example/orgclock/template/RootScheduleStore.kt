package com.example.orgclock.template

import android.content.SharedPreferences
import java.security.MessageDigest
import java.time.DayOfWeek

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
            ?.mapNotNull { raw -> raw.toIntOrNull()?.let { day -> DayOfWeek.of(day) } }
            ?.toSet()
            .orEmpty()

        return RootScheduleConfig(
            rootUri = rootUri,
            enabled = prefs.getBoolean("${prefix}enabled", false),
            ruleType = ruleType,
            hour = prefs.getInt("${prefix}hour", 0).coerceIn(0, 23),
            minute = prefs.getInt("${prefix}minute", 0).coerceIn(0, 59),
            daysOfWeek = if (storedDays.isEmpty()) setOf(DayOfWeek.MONDAY) else storedDays,
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
                    .sortedBy { it.value }
                    .joinToString(",") { it.value.toString() },
            )
            .apply()
    }

    private fun prefix(rootUri: String): String = "root_schedule_${stableHash(rootUri)}_"

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
