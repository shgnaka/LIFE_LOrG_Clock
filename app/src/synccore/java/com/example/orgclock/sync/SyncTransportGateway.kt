package com.example.orgclock.sync

import io.github.shgnaka.synccore.api.DeliveryErrorCode
import io.github.shgnaka.synccore.api.SyncCommand
import io.github.shgnaka.synccore.api.SyncResult

interface SyncTransportGateway {
    suspend fun dispatch(command: SyncCommand): TransportDispatchResult
}

sealed interface TransportDispatchResult {
    data class Accepted(val result: SyncResult? = null) : TransportDispatchResult
    data class RetryableFailure(
        val errorCode: DeliveryErrorCode,
        val errorMessage: String? = null,
    ) : TransportDispatchResult

    data class Rejected(
        val errorCode: DeliveryErrorCode,
        val errorMessage: String? = null,
    ) : TransportDispatchResult
}

class NoOpSyncTransportGateway : SyncTransportGateway {
    override suspend fun dispatch(command: SyncCommand): TransportDispatchResult {
        return TransportDispatchResult.Rejected(
            errorCode = DeliveryErrorCode.NETWORK_UNREACHABLE,
            errorMessage = "sync transport is not configured",
        )
    }
}
