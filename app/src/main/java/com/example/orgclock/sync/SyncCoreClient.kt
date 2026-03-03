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

interface OrgSyncCoreClient {
    suspend fun start()
    suspend fun stop()
    suspend fun flushNow()
    suspend fun observeIncomingCommands(): List<String>
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

    override suspend fun observeIncomingCommands(): List<String> = emptyList()

    override suspend fun reportResult(result: ClockResultPayload) {
    }

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()

    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
}
