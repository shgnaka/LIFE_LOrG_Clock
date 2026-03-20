package com.example.orgclock.desktop

import com.example.orgclock.sync.ClockEventCursor
import com.example.orgclock.sync.ClockEventSyncDirection
import com.example.orgclock.sync.ClockEventSyncQuarantineEntry
import com.example.orgclock.sync.ClockEventSyncQuarantineStore
import com.example.orgclock.sync.ClockEventSyncRejectKind
import java.util.prefs.Preferences

class DesktopClockEventSyncQuarantineStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) : ClockEventSyncQuarantineStore {
    override fun list(): List<ClockEventSyncQuarantineEntry> = recordIds().mapNotNull(::readRecord)

    override fun record(entry: ClockEventSyncQuarantineEntry) {
        val id = stableKey(entry)
        val ids = recordIds().toMutableSet()
        ids.add(id)
        preferences.put(KEY_RECORD_IDS, ids.sorted().joinToString("\n"))
        preferences.put("$KEY_RECORD_PREFIX$id:$KEY_PEER_ID", entry.peerId)
        preferences.put("$KEY_RECORD_PREFIX$id:$KEY_DIRECTION", entry.direction.name)
        preferences.put("$KEY_RECORD_PREFIX$id:$KEY_KIND", entry.kind.name)
        preferences.put("$KEY_RECORD_PREFIX$id:$KEY_REASON", entry.reason)
        entry.eventId?.let { preferences.put("$KEY_RECORD_PREFIX$id:$KEY_EVENT_ID", it) }
            ?: preferences.remove("$KEY_RECORD_PREFIX$id:$KEY_EVENT_ID")
        entry.cursor?.let { preferences.putLong("$KEY_RECORD_PREFIX$id:$KEY_CURSOR", it.value) }
            ?: preferences.remove("$KEY_RECORD_PREFIX$id:$KEY_CURSOR")
        preferences.putLong("$KEY_RECORD_PREFIX$id:$KEY_RECORDED_AT", entry.quarantinedAtEpochMs)
        preferences.flush()
    }

    override fun clear(peerId: String?) {
        val normalized = peerId?.trim()
        if (normalized.isNullOrBlank()) {
            preferences.remove(KEY_RECORD_IDS)
            preferences.flush()
            return
        }
        val remaining = recordIds().filterNot { readRecord(it)?.peerId == normalized }.toMutableSet()
        preferences.put(KEY_RECORD_IDS, remaining.sorted().joinToString("\n"))
        preferences.flush()
    }

    private fun recordIds(): List<String> {
        return preferences.get(KEY_RECORD_IDS, null)
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun readRecord(id: String): ClockEventSyncQuarantineEntry? {
        val prefix = "$KEY_RECORD_PREFIX$id:"
        val peerId = preferences.get(prefix + KEY_PEER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val direction = preferences.get(prefix + KEY_DIRECTION, null)
            ?.let { runCatching { ClockEventSyncDirection.valueOf(it) }.getOrNull() }
            ?: return null
        val kind = preferences.get(prefix + KEY_KIND, null)
            ?.let { runCatching { ClockEventSyncRejectKind.valueOf(it) }.getOrNull() }
            ?: return null
        val reason = preferences.get(prefix + KEY_REASON, null)?.takeIf { it.isNotBlank() } ?: return null
        val eventId = preferences.get(prefix + KEY_EVENT_ID, null)?.takeIf { it.isNotBlank() }
        val cursor = preferences.getLong(prefix + KEY_CURSOR, MISSING_CURSOR_VALUE)
            .takeIf { it != MISSING_CURSOR_VALUE }
            ?.let(::ClockEventCursor)
        val recordedAt = preferences.getLong(prefix + KEY_RECORDED_AT, MISSING_CURSOR_VALUE)
        if (recordedAt == MISSING_CURSOR_VALUE) return null
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

    private fun sanitize(value: String): String {
        return value.trim()
            .replace("%", "%25")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace(" ", "%20")
    }

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop/sync/quarantine"
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
