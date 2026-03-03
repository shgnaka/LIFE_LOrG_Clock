package com.example.orgclock.sync

data class SyncDeliveryState(
    val commandId: String,
    val state: String,
    val detail: String? = null,
)

interface SyncCoreClient {
    suspend fun observeIncomingCommands(): List<String>
    suspend fun reportResult(result: ClockResultPayload)
    suspend fun observeDeliveryState(): List<SyncDeliveryState>
}

class NoOpSyncCoreClient : SyncCoreClient {
    override suspend fun observeIncomingCommands(): List<String> = emptyList()

    override suspend fun reportResult(result: ClockResultPayload) {
    }

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()
}
