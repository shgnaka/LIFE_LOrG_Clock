package io.github.shgnaka.orgclock.synccore.api

interface SyncStore {
    suspend fun load(): SyncStoreLoadResult
    suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult
    suspend fun healthCheck(): StoreHealth
}

sealed interface SyncStoreLoadResult {
    data class Loaded(val snapshot: SyncStoreSnapshot) : SyncStoreLoadResult
    data class Failed(val error: SyncError) : SyncStoreLoadResult
}

sealed interface SyncStoreSaveResult {
    data object Saved : SyncStoreSaveResult
    data class Failed(val error: SyncError) : SyncStoreSaveResult
}

sealed interface StoreHealth {
    data object Healthy : StoreHealth
    data class Unavailable(val error: SyncError) : StoreHealth
}

data class SyncStoreSnapshot(
    val outgoing: List<SyncStoreOutgoingRecord> = emptyList(),
    val incoming: List<SyncStoreIncomingRecord> = emptyList(),
    val deliveryEvents: List<DeliveryEvent> = emptyList(),
    val metrics: SyncStoreMetrics = SyncStoreMetrics(),
    val nextRecordSequence: Long = 1L,
    val nextEventSequence: Long = 1L,
    val nextReceiptSequence: Long = 1L,
) {
    init {
        require(nextRecordSequence > 0) { "nextRecordSequence must be > 0." }
        require(nextEventSequence > 0) { "nextEventSequence must be > 0." }
        require(nextReceiptSequence > 0) { "nextReceiptSequence must be > 0." }
        require(outgoing.map { it.message.messageId }.distinct().size == outgoing.size) { "outgoing message IDs must be unique." }
        require(incoming.map { it.senderPeerId to it.messageId }.distinct().size == incoming.size) { "incoming sender/message IDs must be unique." }
    }
}

data class SyncStoreOutgoingRecord(
    val sequence: Long,
    val message: SyncMessage,
    val state: DeliveryState,
    val attempt: Int,
    val nextAttemptAtEpochMs: Long?,
    val lastError: SyncError?,
    val leaseUntilEpochMs: Long?,
    val terminalAtEpochMs: Long? = null,
) {
    init {
        require(sequence > 0) { "sequence must be > 0." }
        require(attempt >= 0) { "attempt must be >= 0." }
        require(terminalAtEpochMs == null || terminalAtEpochMs > 0) { "terminalAtEpochMs must be > 0 when present." }
    }
}

data class SyncStoreIncomingRecord(
    val receiptId: String,
    val senderPeerId: PeerId,
    val targetPeerId: PeerId,
    val messageId: MessageId,
    val topic: Topic,
    val payloadJson: String,
    val payloadSha256: String,
    val receivedAtEpochMs: Long,
    val state: SyncStoreIncomingState,
    val availableAtEpochMs: Long?,
    val deliveryCount: Int,
    val lastError: SyncError?,
    val processedAtEpochMs: Long? = null,
) {
    init {
        require(receiptId.isNotBlank()) { "receiptId must be non-blank." }
        require(receivedAtEpochMs > 0) { "receivedAtEpochMs must be > 0." }
        require(deliveryCount >= 0) { "deliveryCount must be >= 0." }
        require(processedAtEpochMs == null || processedAtEpochMs > 0) { "processedAtEpochMs must be > 0 when present." }
    }
}

enum class SyncStoreIncomingState {
    Available,
    Claimed,
    RetryLater,
    Processed,
    Rejected,
}

data class SyncStoreMetrics(
    val submittedTotal: Long = 0,
    val acceptedTotal: Long = 0,
    val rejectedTotal: Long = 0,
    val retryAttemptsTotal: Long = 0,
    val incomingRejectedTotal: Long = 0,
    val expiredLeaseRecoveryTotal: Long = 0,
    val persistenceErrorTotal: Long = 0,
    val lastSuccessfulDispatchByPeer: Map<PeerId, Long> = emptyMap(),
) {
    init {
        require(submittedTotal >= 0) { "submittedTotal must be >= 0." }
        require(acceptedTotal >= 0) { "acceptedTotal must be >= 0." }
        require(rejectedTotal >= 0) { "rejectedTotal must be >= 0." }
        require(retryAttemptsTotal >= 0) { "retryAttemptsTotal must be >= 0." }
        require(incomingRejectedTotal >= 0) { "incomingRejectedTotal must be >= 0." }
        require(expiredLeaseRecoveryTotal >= 0) { "expiredLeaseRecoveryTotal must be >= 0." }
        require(persistenceErrorTotal >= 0) { "persistenceErrorTotal must be >= 0." }
    }
}
