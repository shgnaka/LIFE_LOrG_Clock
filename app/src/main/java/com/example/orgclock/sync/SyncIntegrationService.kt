package com.example.orgclock.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncIntegrationSnapshot(
    val lastResult: ClockResultPayload? = null,
    val lastError: String? = null,
)

class SyncIntegrationService(
    private val featureFlag: SyncIntegrationFeatureFlag,
    private val syncCoreClient: SyncCoreClient,
    private val commandExecutor: ClockCommandExecutor,
) {
    private val _snapshot = MutableStateFlow(SyncIntegrationSnapshot())
    val snapshot: StateFlow<SyncIntegrationSnapshot> = _snapshot.asStateFlow()

    suspend fun executeManualCommand(rawPayload: String): ClockResultPayload {
        if (!featureFlag.isEnabled()) {
            val disabledResult = ClockResultPayload(
                commandId = "manual",
                status = ClockResultStatus.Rejected,
                errorCode = ClockErrorCode.VALIDATION_FAILED,
                errorMessage = "Sync integration is disabled by feature flag",
                appliedAt = kotlinx.datetime.Clock.System.now(),
                byDeviceId = "local-device",
            )
            _snapshot.value = _snapshot.value.copy(
                lastResult = disabledResult,
                lastError = disabledResult.errorMessage,
            )
            return disabledResult
        }

        val result = commandExecutor.execute(rawPayload)
        runCatching { syncCoreClient.reportResult(result) }
            .onFailure { error ->
                _snapshot.value = _snapshot.value.copy(
                    lastResult = result,
                    lastError = error.message ?: "Failed to report sync result",
                )
                return result
            }

        _snapshot.value = _snapshot.value.copy(
            lastResult = result,
            lastError = if (result.status == ClockResultStatus.Applied) null else result.errorMessage,
        )
        return result
    }

    suspend fun pollIncomingCommandsOnce(): Int {
        if (!featureFlag.isEnabled()) return 0

        val incoming = syncCoreClient.observeIncomingCommands()
        for (payload in incoming) {
            executeManualCommand(payload)
        }
        return incoming.size
    }
}
