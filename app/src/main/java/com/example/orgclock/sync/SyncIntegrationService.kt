package com.example.orgclock.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.logging.Logger

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

    /**
     * Executes one sync command payload and reports the mapped result to the sync-core client.
     *
     * When the feature flag is disabled, this returns `Rejected/VALIDATION_FAILED` and does not
     * call the executor or client.
     */
    suspend fun executeManualCommand(rawPayload: String): ClockResultPayload {
        if (!featureFlag.isEnabled()) {
            val disabledResult = ClockResultPayload(
                commandId = MANUAL_COMMAND_ID,
                status = ClockResultStatus.Rejected,
                errorCode = ClockErrorCode.VALIDATION_FAILED,
                errorMessage = "Sync integration is disabled by feature flag",
                appliedAt = kotlinx.datetime.Clock.System.now(),
                byDeviceId = LOCAL_DEVICE_ID,
            )
            logger.fine("sync.manual.rejected feature_flag_disabled")
            updateLastResult(disabledResult, disabledResult.errorMessage)
            refreshStateSnapshot()
            return disabledResult
        }

        val result = commandExecutor.execute(rawPayload)
        runCatching { syncCoreClient.reportResult(result) }
            .onFailure { error ->
                logger.fine(
                    "sync.report.failed commandId=${result.commandId} " +
                        "status=${result.status.wireValue} reason=${error.message ?: "unknown"}",
                )
                updateLastResult(result, error.message ?: "Failed to report sync result")
                refreshStateSnapshot()
                return result
            }

        if (result.status != ClockResultStatus.Applied) {
            logger.fine(
                "sync.command.failed commandId=${result.commandId} " +
                    "code=${result.errorCode?.name ?: "none"}",
            )
        }
        updateLastResult(result)
        refreshStateSnapshot()
        return result
    }

    /**
     * Polls and processes available incoming payloads once.
     *
     * Returns the number of payloads fetched from the client.
     */
    suspend fun pollIncomingCommandsOnce(): Int {
        if (!featureFlag.isEnabled()) return 0

        val incoming = syncCoreClient.observeIncomingCommands()
        logger.fine("sync.poll.received count=${incoming.size}")
        for (payload in incoming) {
            executeManualCommand(payload)
        }
        refreshStateSnapshot()
        return incoming.size
    }

    /** Enables standard runtime mode (periodic/background managed path). */
    suspend fun enableStandardMode() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.enableStandardMode() ?: syncCoreClient.start()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Standard) }
        refreshStateSnapshot()
    }

    /** Enables active runtime mode (foreground/short tick path). */
    suspend fun enableActiveMode() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.enableActiveMode() ?: syncCoreClient.start()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Active) }
        refreshStateSnapshot()
    }

    /** Stops sync runtime processing regardless of currently selected mode. */
    suspend fun stopRuntime() {
        runtimeManager?.stop() ?: syncCoreClient.stop()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Off) }
        refreshStateSnapshot()
    }

    /** Triggers immediate flush of pending sync work. */
    suspend fun flushNow() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.flushNow() ?: syncCoreClient.flushNow()
        refreshStateSnapshot()
    }

    fun onAppStarted() {
        if (!featureFlag.isEnabled()) return
        scope.launch {
            runCatching {
                enableStandardMode()
                flushNow()
            }.onFailure { error ->
                logger.fine("sync.startup.failed reason=${error.message ?: "unknown"}")
                _snapshot.update { it.copy(lastError = error.message ?: "sync startup failed") }
            }
        }
    }

    private fun updateLastResult(
        result: ClockResultPayload,
        overrideError: String? = null,
    ) {
        _snapshot.update {
            it.copy(
                lastResult = result,
                lastError = overrideError ?: if (result.status == ClockResultStatus.Applied) null else result.errorMessage,
            )
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
        private val logger: Logger = Logger.getLogger(SyncIntegrationService::class.java.name)
        private const val MAX_DELIVERY_STATES = 20
        private const val MANUAL_COMMAND_ID = "manual"
        private const val LOCAL_DEVICE_ID = "local-device"
    }
}
