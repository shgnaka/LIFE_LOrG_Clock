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

class SyncIntegrationService(
    private val featureFlag: SyncIntegrationFeatureFlag,
    private val syncCoreClient: OrgSyncCoreClient,
    private val commandExecutor: ClockCommandExecutor,
    private val deviceIdProvider: DeviceIdProvider,
    private val runtimePrefs: SyncRuntimePrefs,
    private val peerTrustStore: PeerTrustStore,
    private val peerHealthChecker: PeerHealthChecker = HttpPeerHealthChecker(),
    private val runtimeManager: SyncRuntimeManager? = null,
) {
    private val peerStates = linkedMapOf<String, SyncPeerState>()
    private val _snapshot = MutableStateFlow(
        SyncIntegrationSnapshot(
            runtimeEnabled = runtimePrefs.isEnabled(),
            runtimeMode = runtimePrefs.selectedMode(),
            defaultPeerId = runtimePrefs.defaultPeerId(),
            trustedPeers = peerTrustStore.listTrusted(),
        ),
    )
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
                byDeviceId = deviceIdProvider.getOrCreate(),
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
        for (command in incoming) {
            if (command.verificationState != IncomingVerificationState.Verified) {
                logger.fine("sync.incoming.rejected commandId=${command.commandId} reason=${command.verificationReason}")
                continue
            }
            if (!command.replayCheckPassed) {
                logger.fine("sync.incoming.rejected commandId=${command.commandId} reason=replay_check_failed")
                continue
            }
            executeManualCommand(command.payloadJson)
        }
        refreshStateSnapshot()
        return incoming.size
    }

    /** Enables standard runtime mode (periodic/background managed path). */
    suspend fun enableStandardMode() {
        if (!featureFlag.isEnabled()) return
        runtimePrefs.setSelectedMode(SyncRuntimeMode.Standard)
        runtimeManager?.enableStandardMode() ?: syncCoreClient.start()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Standard) }
        refreshStateSnapshot()
    }

    /** Enables active runtime mode (foreground/short tick path). */
    suspend fun enableActiveMode() {
        if (!featureFlag.isEnabled()) return
        runtimePrefs.setSelectedMode(SyncRuntimeMode.Active)
        runtimeManager?.enableActiveMode() ?: syncCoreClient.start()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Active) }
        refreshStateSnapshot()
    }

    /** Stops sync runtime processing regardless of currently selected mode. */
    suspend fun stopRuntime() {
        runtimePrefs.setSelectedMode(SyncRuntimeMode.Off)
        runtimeManager?.stop() ?: syncCoreClient.stop()
        _snapshot.update { it.copy(runtimeMode = SyncRuntimeMode.Off) }
        refreshStateSnapshot()
    }

    /** Triggers immediate flush of pending sync work. */
    suspend fun flushNow() {
        if (!featureFlag.isEnabled()) return
        runtimeManager?.flushNow() ?: syncCoreClient.flushNow()
        val incomingCount = pollIncomingCommandsOnce()
        if (incomingCount > 0) {
            runtimeManager?.flushNow() ?: syncCoreClient.flushNow()
        }
        refreshStateSnapshot()
    }

    fun onAppStarted() {
        if (!featureFlag.isEnabled()) return
        scope.launch {
            runCatching {
                when (runtimePrefs.selectedMode()) {
                    SyncRuntimeMode.Standard -> enableStandardMode()
                    SyncRuntimeMode.Active -> enableActiveMode()
                    SyncRuntimeMode.Off -> stopRuntime()
                }
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
        val trusted = peerTrustStore.listTrusted()
        val peerStatesSnapshot = synchronized(peerStates) {
            trusted.forEach { peerId ->
                peerStates.putIfAbsent(peerId, SyncPeerState(peerId = peerId))
            }
            peerStates.keys.retainAll(trusted.toSet())
            trusted.mapNotNull { peerStates[it] }.takeLast(MAX_PEER_STATES)
        }
        _snapshot.update {
            it.copy(
                metrics = metrics,
                lastDeliveryStates = states.takeLast(MAX_DELIVERY_STATES),
                trustedPeers = trusted,
                peerStates = peerStatesSnapshot,
            )
        }
    }

    suspend fun submitOutgoingCommand(command: OutgoingClockCommand): SubmitResult {
        if (!featureFlag.isEnabled()) {
            return SubmitResult.Rejected("sync integration is disabled")
        }
        if (!peerTrustStore.isTrusted(command.targetPeerId)) {
            return SubmitResult.Rejected("peer is not trusted: ${command.targetPeerId}")
        }
        val result = syncCoreClient.submitOutgoing(command)
        if (result is SubmitResult.Submitted) {
            updatePeerState(
                peerId = command.targetPeerId,
                reachable = true,
                lastCheckedAtEpochMs = null,
                lastSyncedAtEpochMs = System.currentTimeMillis(),
            )
            refreshStateSnapshot()
        }
        return result
    }

    fun markSyncError(message: String) {
        _snapshot.update { it.copy(lastError = message) }
    }

    suspend fun setRuntimeEnabled(enabled: Boolean) {
        runtimePrefs.setEnabled(enabled)
        _snapshot.update { it.copy(runtimeEnabled = enabled) }
        if (!enabled) {
            stopRuntime()
        } else {
            when (runtimePrefs.selectedMode()) {
                SyncRuntimeMode.Active -> enableActiveMode()
                SyncRuntimeMode.Standard -> enableStandardMode()
                SyncRuntimeMode.Off -> enableStandardMode()
            }
        }
    }

    fun setDefaultPeerId(peerId: String?) {
        val normalized = peerId?.trim().orEmpty()
        if (normalized.isBlank()) {
            runtimePrefs.setDefaultPeerId(null)
            _snapshot.update { it.copy(defaultPeerId = null) }
            return
        }
        if (!peerTrustStore.isTrusted(normalized)) {
            markSyncError("peer is not trusted: $normalized")
            return
        }
        runtimePrefs.setDefaultPeerId(normalized)
        _snapshot.update { it.copy(defaultPeerId = normalized) }
    }

    fun listTrustedPeers(): List<String> = peerTrustStore.listTrusted()

    suspend fun addTrustedPeer(peerId: String): PeerProbeResult {
        val normalized = peerId.trim()
        if (normalized.isBlank()) {
            return PeerProbeResult(
                peerId = normalized,
                reachable = false,
                checkedAtEpochMs = System.currentTimeMillis(),
                reason = "peer id is empty",
            )
        }
        val probe = peerHealthChecker.probe(normalized)
        updatePeerState(
            peerId = normalized,
            reachable = probe.reachable,
            lastCheckedAtEpochMs = probe.checkedAtEpochMs,
        )
        if (probe.reachable) {
            peerTrustStore.trust(normalized)
        }
        refreshStateSnapshot()
        return probe
    }

    suspend fun probePeer(peerId: String): PeerProbeResult {
        val normalized = peerId.trim()
        if (normalized.isBlank()) {
            return PeerProbeResult(
                peerId = normalized,
                reachable = false,
                checkedAtEpochMs = System.currentTimeMillis(),
                reason = "peer id is empty",
            )
        }
        val probe = peerHealthChecker.probe(normalized)
        updatePeerState(
            peerId = normalized,
            reachable = probe.reachable,
            lastCheckedAtEpochMs = probe.checkedAtEpochMs,
        )
        refreshStateSnapshot()
        return probe
    }

    suspend fun revokePeer(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        peerTrustStore.revoke(normalized)
        if (runtimePrefs.defaultPeerId() == normalized) {
            runtimePrefs.setDefaultPeerId(null)
            _snapshot.update { it.copy(defaultPeerId = null) }
        }
        synchronized(peerStates) {
            peerStates.remove(normalized)
        }
        refreshStateSnapshot()
    }

    private fun updatePeerState(
        peerId: String,
        reachable: Boolean? = null,
        lastCheckedAtEpochMs: Long? = null,
        lastSyncedAtEpochMs: Long? = null,
    ) {
        synchronized(peerStates) {
            val current = peerStates[peerId] ?: SyncPeerState(peerId = peerId)
            peerStates[peerId] = current.copy(
                reachable = reachable ?: current.reachable,
                lastCheckedAtEpochMs = lastCheckedAtEpochMs ?: current.lastCheckedAtEpochMs,
                lastSyncedAtEpochMs = lastSyncedAtEpochMs ?: current.lastSyncedAtEpochMs,
            )
        }
    }

    companion object {
        private val logger: Logger = Logger.getLogger(SyncIntegrationService::class.java.name)
        private const val MAX_DELIVERY_STATES = 20
        private const val MAX_PEER_STATES = 50
        private const val MANUAL_COMMAND_ID = "manual"
    }
}
