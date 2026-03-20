package com.example.orgclock.sync

import android.content.SharedPreferences
import kotlinx.datetime.Clock

class SharedPreferencesPeerSyncCheckpointStore(
    private val prefs: SharedPreferences,
) : PeerSyncCheckpointStore {
    override fun get(peerId: String): PeerSyncCheckpoint? {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return null
        return readRecord(normalized)
    }

    override fun list(): List<PeerSyncCheckpoint> {
        val ids = (prefs.getStringSet(KEY_CHECKPOINT_IDS, emptySet()) ?: emptySet())
            .asSequence()
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
        val ids = (prefs.getStringSet(KEY_CHECKPOINT_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.remove(normalized)
        prefs.edit()
            .putStringSet(KEY_CHECKPOINT_IDS, ids)
            .remove(keyLastSeenCursor(normalized))
            .remove(keyLastSentCursor(normalized))
            .remove(keyLastSeenAt(normalized))
            .remove(keyLastSyncedAt(normalized))
            .remove(keyUpdatedAt(normalized))
            .apply()
    }

    private fun writeRecord(checkpoint: PeerSyncCheckpoint) {
        val normalized = checkpoint.peerId.trim()
        val ids = (prefs.getStringSet(KEY_CHECKPOINT_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(normalized)
        prefs.edit()
            .putStringSet(KEY_CHECKPOINT_IDS, ids)
            .putLong(keyLastSeenCursor(normalized), checkpoint.lastSeenCursor?.value ?: MISSING_CURSOR_VALUE)
            .putLong(keyLastSentCursor(normalized), checkpoint.lastSentCursor?.value ?: MISSING_CURSOR_VALUE)
            .putLong(keyLastSeenAt(normalized), checkpoint.lastSeenAtEpochMs ?: MISSING_EPOCH_MILLIS)
            .putLong(keyLastSyncedAt(normalized), checkpoint.lastSyncedAtEpochMs ?: MISSING_EPOCH_MILLIS)
            .putLong(keyUpdatedAt(normalized), checkpoint.updatedAtEpochMs)
            .apply()
    }

    private fun readRecord(peerId: String): PeerSyncCheckpoint? {
        val ids = prefs.getStringSet(KEY_CHECKPOINT_IDS, emptySet()) ?: emptySet()
        if (peerId !in ids) return null
        val lastSeenCursor = prefs.getLong(keyLastSeenCursor(peerId), MISSING_CURSOR_VALUE)
            .takeIf { it != MISSING_CURSOR_VALUE }
            ?.let(::ClockEventCursor)
        val lastSentCursor = prefs.getLong(keyLastSentCursor(peerId), MISSING_CURSOR_VALUE)
            .takeIf { it != MISSING_CURSOR_VALUE }
            ?.let(::ClockEventCursor)
        val lastSeenAtEpochMs = prefs.getLong(keyLastSeenAt(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
        val lastSyncedAtEpochMs = prefs.getLong(keyLastSyncedAt(peerId), MISSING_EPOCH_MILLIS)
            .takeIf { it != MISSING_EPOCH_MILLIS }
        val updatedAtEpochMs = prefs.getLong(keyUpdatedAt(peerId), Clock.System.now().toEpochMilliseconds())
        return PeerSyncCheckpoint(
            peerId = peerId,
            lastSeenCursor = lastSeenCursor,
            lastSentCursor = lastSentCursor,
            lastSeenAtEpochMs = lastSeenAtEpochMs,
            lastSyncedAtEpochMs = lastSyncedAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs,
        )
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
        const val KEY_CHECKPOINT_IDS = "sync_peer_checkpoint_ids"
        const val KEY_CHECKPOINT_PREFIX = "sync_peer_checkpoint_"
        const val MISSING_CURSOR_VALUE = Long.MIN_VALUE
        const val MISSING_EPOCH_MILLIS = Long.MIN_VALUE
    }
}
