package com.example.orgclock.sync

import kotlinx.datetime.Clock

data class PeerSyncCheckpoint(
    val peerId: String,
    val lastSeenCursor: ClockEventCursor? = null,
    val lastSentCursor: ClockEventCursor? = null,
    val lastSeenAtEpochMs: Long? = null,
    val lastSyncedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
) {
    init {
        require(peerId.isNotBlank()) { "Peer ID cannot be blank." }
    }

    fun markSeen(cursor: ClockEventCursor, seenAtEpochMs: Long = Clock.System.now().toEpochMilliseconds()): PeerSyncCheckpoint {
        return copy(
            lastSeenCursor = cursor,
            lastSeenAtEpochMs = seenAtEpochMs,
            updatedAtEpochMs = seenAtEpochMs,
        )
    }

    fun markSent(cursor: ClockEventCursor, syncedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds()): PeerSyncCheckpoint {
        return copy(
            lastSentCursor = cursor,
            lastSyncedAtEpochMs = syncedAtEpochMs,
            updatedAtEpochMs = syncedAtEpochMs,
        )
    }
}

interface PeerSyncCheckpointStore {
    fun get(peerId: String): PeerSyncCheckpoint?

    fun list(): List<PeerSyncCheckpoint>

    fun save(checkpoint: PeerSyncCheckpoint)

    fun markSeen(
        peerId: String,
        cursor: ClockEventCursor,
        seenAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    )

    fun markSent(
        peerId: String,
        cursor: ClockEventCursor,
        syncedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    )

    fun clear(peerId: String)
}

class InMemoryPeerSyncCheckpointStore : PeerSyncCheckpointStore {
    private val checkpoints = linkedMapOf<String, PeerSyncCheckpoint>()

    override fun get(peerId: String): PeerSyncCheckpoint? = checkpoints[peerId.trim()]

    override fun list(): List<PeerSyncCheckpoint> = checkpoints.values.toList()

    override fun save(checkpoint: PeerSyncCheckpoint) {
        checkpoints[checkpoint.peerId.trim()] = checkpoint
    }

    override fun markSeen(peerId: String, cursor: ClockEventCursor, seenAtEpochMs: Long) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = checkpoints[normalized] ?: PeerSyncCheckpoint(peerId = normalized)
        checkpoints[normalized] = current.markSeen(cursor, seenAtEpochMs)
    }

    override fun markSent(peerId: String, cursor: ClockEventCursor, syncedAtEpochMs: Long) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        val current = checkpoints[normalized] ?: PeerSyncCheckpoint(peerId = normalized)
        checkpoints[normalized] = current.markSent(cursor, syncedAtEpochMs)
    }

    override fun clear(peerId: String) {
        checkpoints.remove(peerId.trim())
    }
}

