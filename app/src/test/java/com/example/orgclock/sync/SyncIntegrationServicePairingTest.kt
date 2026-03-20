package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncIntegrationServicePairingTest {
    @Test
    fun pairTrustedPeer_persistsTrustRecordAndRefreshesSnapshot() = runTest {
        val store = RecordingPeerTrustStore()
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-a"
            },
            runtimePrefs = TestSyncRuntimePrefs(),
            peerTrustStore = store,
            peerHealthChecker = object : PeerHealthChecker {
                override suspend fun probe(peerId: String): PeerProbeResult {
                    return PeerProbeResult(
                        peerId = peerId,
                        reachable = true,
                        checkedAtEpochMs = 1234L,
                    )
                }
            },
        )

        val result = service.pairTrustedPeer(
            PeerRegistrationRequest(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Desktop Host",
                publicKeyBase64 = "pk-a",
                role = PeerTrustRole.Viewer,
                requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )

        assertTrue(result.reachable)
        assertEquals(listOf("peer-a"), store.listTrusted())
        val record = store.getTrustRecord("peer-a")
        assertEquals("Desktop Host", record?.displayName)
        assertEquals(PeerTrustRole.Viewer, record?.role)
        assertEquals("pk-a", record?.publicKeyBase64)
        assertEquals("peer-a", service.snapshot.value.trustedPeers.single())
        val peerState = service.snapshot.value.peerStates.single()
        assertEquals("Desktop Host", peerState.displayName)
        assertEquals(PeerTrustRole.Viewer, peerState.role)
        assertTrue(peerState.publicKeyRegistered)
        assertTrue(peerState.reachable == true)
    }

    @Test
    fun pairTrustedPeer_stillPersistsTrustRecordWhenPeerIsOffline() = runTest {
        val store = RecordingPeerTrustStore()
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledSyncIntegrationFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-a"
            },
            runtimePrefs = TestSyncRuntimePrefs(),
            peerTrustStore = store,
            peerHealthChecker = object : PeerHealthChecker {
                override suspend fun probe(peerId: String): PeerProbeResult {
                    return PeerProbeResult(
                        peerId = peerId,
                        reachable = false,
                        checkedAtEpochMs = 1234L,
                        reason = "unreachable",
                    )
                }
            },
        )

        val result = service.pairTrustedPeer(
            PeerRegistrationRequest(
                peerId = "peer-a",
                deviceId = "device-a",
                displayName = "Desktop Host",
                publicKeyBase64 = "pk-a",
                role = PeerTrustRole.Viewer,
                requestedAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )

        assertTrue(!result.reachable)
        assertEquals(listOf("peer-a"), store.listTrusted())
        assertEquals(PeerTrustRole.Viewer, store.getTrustRecord("peer-a")?.role)
    }
}

private class RecordingPeerTrustStore : PeerTrustStore {
    private val records = linkedMapOf<String, PeerTrustRecord>()
    private val legacyTrusted = linkedSetOf<String>()

    override fun isTrusted(peerId: String): Boolean {
        return legacyTrusted.contains(peerId) || records[peerId]?.isActive == true
    }

    override fun listTrusted(): List<String> {
        return (legacyTrusted + records.values.filter { it.isActive }.map { it.peerId }).sorted()
    }

    override fun trust(peerId: String) {
        legacyTrusted.add(peerId)
    }

    override fun trust(peerId: String, publicKeyBase64: String) {
        trust(
            PeerTrustRecord(
                peerId = peerId,
                deviceId = peerId,
                displayName = peerId,
                publicKeyBase64 = publicKeyBase64,
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
    }

    override fun trust(record: PeerTrustRecord) {
        records[record.peerId] = record
    }

    override fun getTrustRecord(peerId: String): PeerTrustRecord? = records[peerId]

    override fun listTrustRecords(): List<PeerTrustRecord> = records.values.toList()

    override fun revoke(peerId: String) {
        legacyTrusted.remove(peerId)
        records.remove(peerId)
    }

    override fun getTrustedPublicKey(peerId: String): String? = records[peerId]?.publicKeyBase64
}

private class NoOpClockCommandExecutor : ClockCommandExecutor {
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

private object AlwaysEnabledSyncIntegrationFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}

private class TestSyncRuntimePrefs : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = SyncRuntimeMode.Standard
    override fun setSelectedMode(mode: SyncRuntimeMode) {}
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}
