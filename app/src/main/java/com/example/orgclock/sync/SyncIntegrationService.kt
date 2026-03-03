package com.example.orgclock.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncIntegrationSnapshot(
    val lastResult: ClockResultPayload? = null,
    val lastError: String? = null,
    val lastDeliveryStates: List<SyncDeliveryState> = emptyList(),
    val metrics: SyncMetricsSnapshot = SyncMetricsSnapshot(),
    val runtimeMode: SyncRuntimeMode = SyncRuntimeMode.Off,
)

class SyncIntegrationService(
    private val featureFlag: SyncIntegrationFeatureFlag,
    private val syncCoreClient: OrgSyncCoreClient,
    private val commandExecutor: ClockCommandExecutor,
    private val runtimeManager: SyncRuntimeManager? = null,
) {
    private val _snapshot = MutableStateFlow(SyncIntegrationSnapshot())
    val snapshot: StateFlow<SyncIntegrationSnapshot> = _snapshot.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        refreshStateSnapshot()
        return result
    }

    suspend fun pollIncomingCommandsOnce(): Int {
        if (!featureFlag.isEnabled()) return 0

        val incoming = syncCoreClient.observeIncomingCommands()
        for (payload in incoming) {
            executeManualCommand(payload)
        }
        refreshStateSnapshot()
        return incoming.size
    }

    suspend fun enableStandardMode() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.enableStandardMode() ?: syncCoreClient.start()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Standard) }
        refreshStateSnapshot()
    }

    suspend fun enableActiveMode() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.enableActiveMode() ?: syncCoreClient.start()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Active) }
        refreshStateSnapshot()
    }

    suspend fun stopRuntime() {
        runtimeManager?.stop() ?: syncCoreClient.stop()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Off) }
        refreshStateSnapshot()
    }

    suspend fun flushNow() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.flushNow() ?: syncCoreClient.flushNow()
        refreshStateSnapshot()
    }

    fun onAppStarted() {
        if (!featureFlag.isEnabled()) return
        scope.launch {
            enableStandardMode()
            flushNow()
        }
    }

    private suspend fun refreshStateSnapshot() {
        val metrics = runCatching { syncCoreClient.metricsSnapshot() }.getOrDefault(SyncMetricsSnapshot())
        val states = runCatching { syncCoreClient.observeDeliveryState() }.getOrDefault(emptyList())
        _snapshot.update {
            it.copy(
                metrics = metrics,
                lastDeliveryStates = states.takeLast(MAX_DELIVERY_STATES),
            )
        }
    }

    companion object {
        private const val MAX_DELIVERY_STATES = 20
    }
}
