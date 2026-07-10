package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeModeTest {
    @Test
    fun transitions() = runTest {
        val coordinator = CountingRuntimeCoordinator()
        val prefs = MutableRuntimeModePrefs(SyncRuntimeMode.Off)
        val service = SyncIntegrationService(
            featureFlag = RuntimeModeFeatureFlag,
            syncCoreClient = RuntimeModeSyncCoreClient(),
            commandExecutor = RuntimeModeClockCommandExecutor(),
            deviceIdProvider = RuntimeModeDeviceIdProvider,
            runtimePrefs = prefs,
            peerTrustStore = EmptyRuntimeModePeerTrustStore,
            runtimeManager = coordinator,
        )

        service.enableStandardMode()
        service.enableStandardMode()
        service.enableActiveMode()
        service.enableActiveMode()
        service.stopRuntime()
        service.stopRuntime()
        service.enableActiveMode()
        service.enableStandardMode()

        assertEquals(
            listOf(
                SyncRuntimeMode.Standard,
                SyncRuntimeMode.Active,
                SyncRuntimeMode.Off,
                SyncRuntimeMode.Active,
                SyncRuntimeMode.Standard,
            ),
            prefs.transitions,
        )
        assertEquals(2, coordinator.periodicWorkStarts)
        assertEquals(2, coordinator.activeLoopStarts)
        assertEquals(1, coordinator.stops)
        assertEquals(SyncRuntimeMode.Standard, service.snapshot.value.runtimeMode)
    }
}

private class CountingRuntimeCoordinator : SyncRuntimeCoordinator {
    var periodicWorkStarts = 0
        private set
    var activeLoopStarts = 0
        private set
    var stops = 0
        private set

    override suspend fun enableStandardMode() {
        periodicWorkStarts += 1
    }

    override suspend fun enableActiveMode() {
        activeLoopStarts += 1
    }

    override suspend fun stop() {
        stops += 1
    }

    override suspend fun flushNow() {}
}

private class MutableRuntimeModePrefs(initialMode: SyncRuntimeMode) : SyncRuntimePrefs {
    private var mode = initialMode
    val transitions = mutableListOf<SyncRuntimeMode>()

    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = mode
    override fun setSelectedMode(mode: SyncRuntimeMode) {
        this.mode = mode
        transitions += mode
    }
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}

private object EmptyRuntimeModePeerTrustStore : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean = false
    override fun listTrusted(): List<String> = emptyList()
    override fun trust(peerId: String) {}
    override fun trust(peerId: String, publicKeyBase64: String) {}
    override fun revoke(peerId: String) {}
    override fun getTrustedPublicKey(peerId: String): String? = null
}

private class RuntimeModeSyncCoreClient : OrgSyncCoreClient {
    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun flushNow() {}
    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult = SubmitResult.Submitted
    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = emptyList()
    override suspend fun reportResult(result: ClockResultPayload) {}
    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()
    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
}

private class RuntimeModeClockCommandExecutor : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        return ClockResultPayload(
            commandId = "runtime-mode",
            status = ClockResultStatus.Rejected,
            errorCode = ClockErrorCode.VALIDATION_FAILED,
            errorMessage = "not used",
            appliedAt = Instant.parse("2026-03-10T09:00:00Z"),
            byDeviceId = "device-a",
        )
    }
}

private object RuntimeModeDeviceIdProvider : DeviceIdProvider {
    override fun getOrCreate(): String = "device-a"
}

private object RuntimeModeFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}
