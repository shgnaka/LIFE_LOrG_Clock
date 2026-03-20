package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncIntegrationServiceCheckpointTest {
    @Test
    fun refreshStateSnapshotIncludesPeerCheckpointProgress() = runTest {
        val trustStore = CheckpointRecordingPeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Desktop Host",
                publicKeyBase64 = "pk-a",
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
        val checkpointStore = InMemoryPeerSyncCheckpointStore().also {
            it.markSeen("peer-a", ClockEventCursor(9), seenAtEpochMs = 111L)
            it.markSent("peer-a", ClockEventCursor(14), syncedAtEpochMs = 222L)
        }
        val service = SyncIntegrationService(
            featureFlag = CheckpointAlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = CheckpointNoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-a"
            },
            runtimePrefs = CheckpointTestSyncRuntimePrefs(),
            peerTrustStore = trustStore,
            peerSyncCheckpointStore = checkpointStore,
            peerHealthChecker = object : PeerHealthChecker {
                override suspend fun probe(peerId: String): PeerProbeResult {
                    return PeerProbeResult(peerId = peerId, reachable = true, checkedAtEpochMs = 333L)
                }
            },
        )

        service.probePeer("peer-a")

        val peerState = service.snapshot.value.peerStates.single()
        assertEquals("peer-a", peerState.peerId)
        assertEquals(9L, peerState.lastSeenCursor)
        assertEquals(14L, peerState.lastSentCursor)
        assertTrue(peerState.reachable == true)
    }
}

private class CheckpointRecordingPeerTrustStore(
    initialRecord: PeerTrustRecord,
) : PeerTrustStore {
    private val records = linkedMapOf(initialRecord.peerId to initialRecord)

    override fun isTrusted(peerId: String): Boolean = records[peerId]?.isActive == true

    override fun listTrusted(): List<String> = records.values.filter { it.isActive }.map { it.peerId }.sorted()

    override fun trust(peerId: String) {}

    override fun trust(peerId: String, publicKeyBase64: String) {}

    override fun trust(record: PeerTrustRecord) {
        records[record.peerId] = record
    }

    override fun getTrustRecord(peerId: String): PeerTrustRecord? = records[peerId]

    override fun listTrustRecords(): List<PeerTrustRecord> = records.values.toList()

    override fun revoke(peerId: String) {
        records[peerId]?.let {
            records[peerId] = it.revoke(Instant.parse("2026-03-11T09:00:00Z"))
        }
    }

    override fun repair(peerId: String) {
        records[peerId]?.let {
            records[peerId] = it.repair(Instant.parse("2026-03-12T09:00:00Z"))
        }
    }

    override fun getTrustedPublicKey(peerId: String): String? = records[peerId]?.publicKeyBase64
}

private class CheckpointNoOpClockCommandExecutor : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        return ClockResultPayload(
            commandId = "noop",
            status = ClockResultStatus.Rejected,
            errorCode = ClockErrorCode.VALIDATION_FAILED,
            errorMessage = "not used",
            appliedAt = Instant.parse("2026-03-10T09:00:00Z"),
            byDeviceId = "device-a",
        )
    }
}

private object CheckpointAlwaysEnabledSyncIntegrationFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}

private class CheckpointTestSyncRuntimePrefs : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = SyncRuntimeMode.Standard
    override fun setSelectedMode(mode: SyncRuntimeMode) {}
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}
