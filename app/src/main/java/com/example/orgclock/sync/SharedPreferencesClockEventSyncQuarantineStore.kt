package com.example.orgclock.sync

import android.content.SharedPreferences
import kotlinx.datetime.Clock

class SharedPreferencesClockEventSyncQuarantineStore(
    private val prefs: SharedPreferences,
) : ClockEventSyncQuarantineStore {
    override fun list(): List<ClockEventSyncQuarantineEntry> {
        val ids = recordIds()
        return ids.mapNotNull { readRecord(it) }
    }

    override fun record(entry: ClockEventSyncQuarantineEntry) {
        val ids = recordIds().toMutableSet()
        ids.add(stableKey(entry))
        prefs.edit()
            .putString(KEY_RECORD_IDS, ids.sorted().joinToString("\n"))
            .putString(recordPeerIdKey(entry), entry.peerId)
            .putString(recordDirectionKey(entry), entry.direction.name)
            .putString(recordKindKey(entry), entry.kind.name)
            .putString(recordReasonKey(entry), entry.reason)
            .putString(recordEventIdKey(entry), entry.eventId)
            .putLong(recordCursorKey(entry), entry.cursor?.value ?: MISSING_CURSOR_VALUE)
            .putLong(recordRecordedAtKey(entry), entry.quarantinedAtEpochMs)
            .apply()
    }

    override fun clear(peerId: String?) {
        val normalizedPeerId = peerId?.trim()
        if (normalizedPeerId.isNullOrBlank()) {
            val ids = recordIds()
            val editor = prefs.edit()
            ids.forEach { id ->
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_PEER_ID")
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_DIRECTION")
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_KIND")
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_REASON")
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_EVENT_ID")
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_CURSOR")
                editor.remove("$KEY_RECORD_PREFIX$id:$KEY_RECORDED_AT")
            }
            editor.remove(KEY_RECORD_IDS).apply()
            return
        }
        val remaining = recordIds().filterNot { readRecord(it)?.peerId == normalizedPeerId }.toMutableSet()
        val removed = recordIds().filter { readRecord(it)?.peerId == normalizedPeerId }
        val editor = prefs.edit()
        removed.forEach { id ->
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_PEER_ID")
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_DIRECTION")
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_KIND")
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_REASON")
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_EVENT_ID")
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_CURSOR")
            editor.remove("$KEY_RECORD_PREFIX$id:$KEY_RECORDED_AT")
        }
        editor.putString(KEY_RECORD_IDS, remaining.sorted().joinToString("\n")).apply()
    }

    private fun recordIds(): List<String> {
        return prefs.getString(KEY_RECORD_IDS, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun readRecord(id: String): ClockEventSyncQuarantineEntry? {
        val peerId = prefs.getString("$id:${KEY_PEER_ID}", null)?.takeIf { it.isNotBlank() } ?: return null
        val direction = prefs.getString("$id:${KEY_DIRECTION}", null)
            ?.let { runCatching { ClockEventSyncDirection.valueOf(it) }.getOrNull() }
            ?: return null
        val kind = prefs.getString("$id:${KEY_KIND}", null)
            ?.let { runCatching { ClockEventSyncRejectKind.valueOf(it) }.getOrNull() }
            ?: return null
        val reason = prefs.getString("$id:${KEY_REASON}", null)?.takeIf { it.isNotBlank() } ?: return null
        val eventId = prefs.getString("$id:${KEY_EVENT_ID}", null)?.takeIf { it.isNotBlank() }
        val cursor = prefs.getLong("$id:${KEY_CURSOR}", MISSING_CURSOR_VALUE)
            .takeIf { it != MISSING_CURSOR_VALUE }
            ?.let(::ClockEventCursor)
        val recordedAt = prefs.getLong("$id:${KEY_RECORDED_AT}", Clock.System.now().toEpochMilliseconds())
        return ClockEventSyncQuarantineEntry(
            peerId = peerId,
            direction = direction,
            kind = kind,
            reason = reason,
            eventId = eventId,
            cursor = cursor,
            quarantinedAtEpochMs = recordedAt,
        )
    }

    private fun stableKey(entry: ClockEventSyncQuarantineEntry): String {
        return listOf(
            sanitize(entry.peerId),
            entry.direction.name,
            entry.kind.name,
            entry.eventId.orEmpty(),
            entry.cursor?.value?.toString().orEmpty(),
            entry.quarantinedAtEpochMs.toString(),
        ).joinToString("_")
    }

    private fun recordPeerIdKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_PEER_ID"
    private fun recordDirectionKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_DIRECTION"
    private fun recordKindKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_KIND"
    private fun recordReasonKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_REASON"
    private fun recordEventIdKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_EVENT_ID"
    private fun recordCursorKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_CURSOR"
    private fun recordRecordedAtKey(entry: ClockEventSyncQuarantineEntry): String = "$KEY_RECORD_PREFIX${stableKey(entry)}:$KEY_RECORDED_AT"

    private fun sanitize(value: String): String {
        return value.trim()
            .replace("%", "%25")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace(" ", "%20")
    }

    private companion object {
        const val KEY_RECORD_IDS = "quarantine_record_ids"
        const val KEY_RECORD_PREFIX = "quarantine_record_"
        const val KEY_PEER_ID = "peer_id"
        const val KEY_DIRECTION = "direction"
        const val KEY_KIND = "kind"
        const val KEY_REASON = "reason"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_CURSOR = "cursor"
        const val KEY_RECORDED_AT = "recorded_at"
        const val MISSING_CURSOR_VALUE = Long.MIN_VALUE
    }
}
