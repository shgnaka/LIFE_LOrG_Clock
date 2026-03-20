package com.example.orgclock.desktop

import com.example.orgclock.sync.ClockEventCursor
import com.example.orgclock.sync.PeerSyncCheckpoint
import com.example.orgclock.sync.PeerSyncCheckpointStore
import java.util.prefs.Preferences
import kotlinx.datetime.Clock

class DesktopPeerSyncCheckpointStore(
    private val preferences: Preferences = Preferences.userRoot().node(PREFERENCES_NODE),
) : PeerSyncCheckpointStore {
    override fun get(peerId: String): PeerSyncCheckpoint? {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return null
        return readRecord(normalized)
    }

    override fun list(): List<PeerSyncCheckpoint> {
        val ids = (preferences.get(KEY_CHECKPOINT_IDS, "") ?: "")
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sorted()
            .toList()
        return ids.mapNotNull { readRecord(it) }
    }

    override fun save(checkpoint: PeerSyncCheckpoint) {
        val normalized = checkpoint.peerId.trim()
        if (normalized.isBlank()) return
        writeRecord(checkpoint.copy(peerId = normalized))
    }

    override fun markSeen(peerId: String, cursor: ClockEventCursor, seenAtEpochMs: Long) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = readRecord(normalized) ?: PeerSyncCheckpoint(peerId = normalized)
        writeRecord(current.markSeen(cursor, seenAtEpochMs))
    }

    override fun markSent(peerId: String, cursor: ClockEventCursor, syncedAtEpochMs: Long) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = readRecord(normalized) ?: PeerSyncCheckpoint(peerId = normalized)
        writeRecord(current.markSent(cursor, syncedAtEpochMs))
    }

    override fun clear(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val ids = checkpointIds().toMutableSet()
        ids.remove(normalized)
        preferences.put(KEY_CHECKPOINT_IDS, ids.sorted().joinToString(","))
        preferences.remove(keyLastSeenCursor(normalized))
        preferences.remove(keyLastSentCursor(normalized))
        preferences.remove(keyLastSeenAt(normalized))
        preferences.remove(keyLastSyncedAt(normalized))
        preferences.remove(keyUpdatedAt(normalized))
        preferences.flush()
    }

    private fun writeRecord(checkpoint: PeerSyncCheckpoint) {
        val normalized = checkpoint.peerId.trim()
        val ids = checkpointIds().toMutableSet()
        ids.add(normalized)
        preferences.put(KEY_CHECKPOINT_IDS, ids.sorted().joinToString(","))
        preferences.putLong(keyLastSeenCursor(normalized), checkpoint.lastSeenCursor?.value ?: MISSING_CURSOR_VALUE)
        preferences.putLong(keyLastSentCursor(normalized), checkpoint.lastSentCursor?.value ?: MISSING_CURSOR_VALUE)
        preferences.putLong(keyLastSeenAt(normalized), checkpoint.lastSeenAtEpochMs ?: MISSING_EPOCH_MILLIS)
        preferences.putLong(keyLastSyncedAt(normalized), checkpoint.lastSyncedAtEpochMs ?: MISSING_EPOCH_MILLIS)
        preferences.putLong(keyUpdatedAt(normalized), checkpoint.updatedAtEpochMs)
        preferences.flush()
    }

    private fun readRecord(peerId: String): PeerSyncCheckpoint? {
        if (peerId !in checkpointIds()) return null
        val lastSeenCursor = preferences.getLong(keyLastSeenCursor(peerId), MISSING_CURSOR_VALUE)
            .takeIf { it != MISSING_CURSOR_VALUE }
            ?.let(::ClockEventCursor)
        val lastSentCursor = preferences.getLong(keyLastSentCursor(peerId), MISSING_CURSOR_VALUE)
            .takeIf { it != MISSING_CURSOR_VALUE }
            ?.let(::ClockEventCursor)
        val lastSeenAtEpochMs = preferences.getLong(keyLastSeenAt(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
        val lastSyncedAtEpochMs = preferences.getLong(keyLastSyncedAt(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
        val updatedAtEpochMs = preferences.getLong(keyUpdatedAt(peerId), Clock.System.now().toEpochMilliseconds())
        return PeerSyncCheckpoint(
            peerId = peerId,
            lastSeenCursor = lastSeenCursor,
            lastSentCursor = lastSentCursor,
            lastSeenAtEpochMs = lastSeenAtEpochMs,
            lastSyncedAtEpochMs = lastSyncedAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    private fun checkpointIds(): List<String> {
        return preferences.get(KEY_CHECKPOINT_IDS, "")
            .orEmpty()
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun keyLastSeenCursor(peerId: String): String = "$KEY_CHECKPOINT_PREFIX${sanitize(peerId)}_last_seen_cursor"
    private fun keyLastSentCursor(peerId: String): String = "$KEY_CHECKPOINT_PREFIX${sanitize(peerId)}_last_sent_cursor"
    private fun keyLastSeenAt(peerId: String): String = "$KEY_CHECKPOINT_PREFIX${sanitize(peerId)}_last_seen_at"
    private fun keyLastSyncedAt(peerId: String): String = "$KEY_CHECKPOINT_PREFIX${sanitize(peerId)}_last_synced_at"
    private fun keyUpdatedAt(peerId: String): String = "$KEY_CHECKPOINT_PREFIX${sanitize(peerId)}_updated_at"

    private fun sanitize(peerId: String): String {
        return peerId.trim()
            .replace("%", "%25")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace(" ", "%20")
    }

    private companion object {
        const val PREFERENCES_NODE = "com/example/orgclock/desktop/sync"
        const val KEY_CHECKPOINT_IDS = "sync_peer_checkpoint_ids"
        const val KEY_CHECKPOINT_PREFIX = "sync_peer_checkpoint_"
        const val MISSING_CURSOR_VALUE = Long.MIN_VALUE
        const val MISSING_EPOCH_MILLIS = Long.MIN_VALUE
    }
}
