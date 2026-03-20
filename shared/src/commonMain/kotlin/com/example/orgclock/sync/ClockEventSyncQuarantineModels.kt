package com.example.orgclock.sync

import kotlinx.datetime.Clock

enum class ClockEventSyncDirection {
    Incoming,
    Outgoing,
}

enum class ClockEventSyncRejectKind {
    SchemaMismatch,
    DeviceMismatch,
    ViewerPeerRejected,
    BatchOrderInvalid,
    TransportRejected,
    AckRejected,
}

data class ClockEventSyncQuarantineEntry(
    val peerId: String,
    val direction: ClockEventSyncDirection,
    val kind: ClockEventSyncRejectKind,
    val reason: String,
    val eventId: String? = null,
    val cursor: ClockEventCursor? = null,
    val quarantinedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
) {
    init {
        require(peerId.isNotBlank()) { "Peer ID cannot be blank." }
        require(reason.isNotBlank()) { "Quarantine reason cannot be blank." }
    }
}

interface ClockEventSyncQuarantineStore {
    fun list(): List<ClockEventSyncQuarantineEntry>

    fun record(entry: ClockEventSyncQuarantineEntry)

    fun clear(peerId: String? = null)
}

object NoOpClockEventSyncQuarantineStore : ClockEventSyncQuarantineStore {
    override fun list(): List<ClockEventSyncQuarantineEntry> = emptyList()

    override fun record(entry: ClockEventSyncQuarantineEntry) = Unit

    override fun clear(peerId: String?) = Unit
}

