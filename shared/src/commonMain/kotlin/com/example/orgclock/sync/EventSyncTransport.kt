package com.example.orgclock.sync

interface ClockEventSyncTransport {
    suspend fun fetch(request: ClockEventFetchRequest): ClockEventFetchResponse

    suspend fun push(request: ClockEventPushRequest): ClockEventPushResponse

    suspend fun acknowledge(ack: ClockEventTransportAck): ClockEventTransportAckResult
}

sealed interface ClockEventTransportAckResult {
    data object Accepted : ClockEventTransportAckResult
    data class Rejected(val reason: String) : ClockEventTransportAckResult
}

data class ClockEventTransportBinding(
    val localPeerId: String,
    val remotePeerId: String,
    val role: PeerTrustRole = PeerTrustRole.Full,
) {
    init {
        require(localPeerId.isNotBlank()) { "Local peer ID cannot be blank." }
        require(remotePeerId.isNotBlank()) { "Remote peer ID cannot be blank." }
    }
}

data class ClockEventSyncRoundTrip(
    val binding: ClockEventTransportBinding,
    val request: ClockEventFetchRequest,
    val response: ClockEventFetchResponse,
    val ack: ClockEventTransportAck,
    val ackResult: ClockEventTransportAckResult,
)

