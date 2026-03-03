package com.example.orgclock.sync

data class SyncDeliveryState(
    val commandId: String,
    val state: String,
    val detail: String? = null,
)

data class SyncMetricsSnapshot(
    val commandsSubmittedTotal: Long = 0L,
    val commandsAppliedTotal: Long = 0L,
    val retryAttemptsTotal: Long = 0L,
    val queueDepth: Long = 0L,
)

data class OutgoingClockCommand(
    val commandId: String,
    val payloadJson: String,
    val targetPeerId: String,
)

enum class IncomingVerificationState {
    Verified,
    Rejected,
}

data class VerifiedIncomingCommand(
    val payloadJson: String,
    val commandId: String,
    val senderDeviceId: String,
    val peerId: String?,
    val verificationState: IncomingVerificationState,
    val verificationReason: String? = null,
    val receivedAtEpochMs: Long,
)

sealed interface SubmitResult {
    data object Submitted : SubmitResult
    data class Rejected(val reason: String) : SubmitResult
    data class Failed(val reason: String) : SubmitResult
}

interface OrgSyncCoreClient {
    suspend fun start()
    suspend fun stop()
    suspend fun flushNow()
    suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult
    suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand>
    suspend fun reportResult(result: ClockResultPayload)
    suspend fun observeDeliveryState(): List<SyncDeliveryState>
    suspend fun metricsSnapshot(): SyncMetricsSnapshot
}

class NoOpOrgSyncCoreClient : OrgSyncCoreClient {
    override suspend fun start() {
    }

    override suspend fun stop() {
    }

    override suspend fun flushNow() {
    }

    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult {
        return SubmitResult.Rejected("sync core not available")
    }

    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = emptyList()

    override suspend fun reportResult(result: ClockResultPayload) {
    }

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()

    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
}
