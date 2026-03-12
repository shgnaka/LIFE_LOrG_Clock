package com.example.orgclock.template

import android.content.SharedPreferences
import java.security.MessageDigest

open class TemplateAutoGenerationRuntimeStore(
    private val prefs: SharedPreferences,
) {
    open fun load(rootUri: String): TemplateAutoGenerationRuntimeState {
        val prefix = prefix(rootUri)
        return TemplateAutoGenerationRuntimeState(
            lastAttemptAtEpochMs = prefs.getLong("${prefix}last_attempt_at", -1L).takeIf { it >= 0L },
            lastSuccessAtEpochMs = prefs.getLong("${prefix}last_success_at", -1L).takeIf { it >= 0L },
            nextScheduledRunAtEpochMs = prefs.getLong("${prefix}next_scheduled_run_at", -1L).takeIf { it >= 0L },
        )
    }

    open fun saveLastAttempt(rootUri: String, epochMs: Long) {
        prefs.edit().putLong("${prefix(rootUri)}last_attempt_at", epochMs).apply()
    }

    open fun saveLastSuccess(rootUri: String, epochMs: Long) {
        prefs.edit().putLong("${prefix(rootUri)}last_success_at", epochMs).apply()
    }

    open fun saveNextScheduledRun(rootUri: String, epochMs: Long?) {
        val editor = prefs.edit()
        val key = "${prefix(rootUri)}next_scheduled_run_at"
        if (epochMs == null) {
            editor.remove(key)
        } else {
            editor.putLong(key, epochMs)
        }
        editor.apply()
    }

    private fun prefix(rootUri: String): String = "template_auto_generation_runtime_${stableHash(rootUri)}_"

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
