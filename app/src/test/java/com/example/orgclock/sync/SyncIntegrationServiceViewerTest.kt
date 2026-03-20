package com.example.orgclock.sync

import com.example.orgclock.model.HeadingPath
import com.example.orgclock.ui.state.OrgDivergenceCategory
import com.example.orgclock.ui.state.OrgDivergenceRecommendedAction
import com.example.orgclock.ui.state.OrgDivergenceSeverity
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncIntegrationServiceViewerTest {
    @Test
    fun refreshSnapshotPublishesReadOnlyProjectionForViewerPeers() = runTest {
        val eventStore = InMemoryViewerClockEventStore().also {
            it.seed(
                storedEvent(
                    cursor = 1L,
                    eventId = "evt-1",
                    type = ClockEventType.Started,
                    createdAt = "2026-03-18T09:00:00Z",
                ),
            )
            it.seed(
                storedEvent(
                    cursor = 2L,
                    eventId = "evt-2",
                    type = ClockEventType.Stopped,
                    createdAt = "2026-03-18T10:30:00Z",
                ),
            )
        }
        val trustStore = ViewerAwarePeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-viewer",
                deviceId = "device-viewer",
                displayName = "Dashboard",
                publicKeyBase64 = "pk-viewer",
                role = PeerTrustRole.Viewer,
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledViewerFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpViewerClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-local"
            },
            runtimePrefs = TestViewerSyncRuntimePrefs(),
            peerTrustStore = trustStore,
            clockEventStoreProvider = { eventStore },
            peerHealthChecker = object : PeerHealthChecker {
                override suspend fun probe(peerId: String): PeerProbeResult {
                    return PeerProbeResult(peerId = peerId, reachable = true, checkedAtEpochMs = 444L)
                }
            },
        )

        service.probePeer("peer-viewer")

        val snapshot = service.snapshot.value
        assertEquals(1, snapshot.viewerPeerCount)
        assertNotNull(snapshot.viewerProjection)
        assertTrue(snapshot.viewerProjection!!.activeClocks.isEmpty())
        assertEquals(1, snapshot.viewerProjection!!.historyEntries.size)
        assertTrue(snapshot.viewerProjection!!.issues.isEmpty())
        assertEquals("peer-viewer", snapshot.peerStates.single().peerId)
        assertEquals(PeerTrustRole.Viewer, snapshot.peerStates.single().role)
    }

    @Test
    fun refreshSnapshotExposesProjectionReplayFailureAsDivergence() = runTest {
        val eventStore = InMemoryViewerClockEventStore().also {
            it.seed(
                storedEvent(
                    cursor = 1L,
                    eventId = "evt-1",
                    type = ClockEventType.Stopped,
                    createdAt = "2026-03-18T10:30:00Z",
                ),
            )
        }
        val trustStore = ViewerAwarePeerTrustStore(
            PeerTrustRecord(
                peerId = "peer-viewer",
                deviceId = "device-viewer",
                displayName = "Dashboard",
                publicKeyBase64 = "pk-viewer",
                role = PeerTrustRole.Viewer,
                registeredAt = Instant.parse("2026-03-10T09:00:00Z"),
            ),
        )
        val service = SyncIntegrationService(
            featureFlag = AlwaysEnabledViewerFeatureFlag,
            syncCoreClient = NoOpOrgSyncCoreClient(),
            commandExecutor = NoOpViewerClockCommandExecutor(),
            deviceIdProvider = object : DeviceIdProvider {
                override fun getOrCreate(): String = "device-local"
            },
            runtimePrefs = TestViewerSyncRuntimePrefs(),
            peerTrustStore = trustStore,
            clockEventStoreProvider = { eventStore },
            peerHealthChecker = object : PeerHealthChecker {
                override suspend fun probe(peerId: String): PeerProbeResult {
                    return PeerProbeResult(peerId = peerId, reachable = true, checkedAtEpochMs = 444L)
                }
            },
        )

        service.probePeer("peer-viewer")

        val snapshot = service.snapshot.value
        assertNotNull(snapshot.viewerProjection)
        assertTrue(snapshot.viewerProjection!!.issues.isNotEmpty())
        assertNotNull(snapshot.orgDivergenceSnapshot)
        assertEquals(OrgDivergenceCategory.ProjectionReplayFailure, snapshot.orgDivergenceSnapshot?.category)
        assertEquals(OrgDivergenceSeverity.RecoveryRequired, snapshot.orgDivergenceSnapshot?.severity)
        assertEquals(OrgDivergenceRecommendedAction.RebuildFromEventLog, snapshot.orgDivergenceSnapshot?.recommendedAction)
    }
}

private class InMemoryViewerClockEventStore : ClockEventStore {
    private val events = linkedMapOf<String, StoredClockEvent>()

    fun seed(event: StoredClockEvent) {
        events[event.event.eventId] = event
    }

    override suspend fun append(event: ClockEvent): AppendClockEventResult {
        error("append is not used in viewer projection test")
    }

    override suspend fun contains(eventId: String): Boolean = events.containsKey(eventId)

    override suspend fun readAllForReplay(): List<StoredClockEvent> = events.values.sortedBy { it.cursor.value }

    override suspend fun listSince(cursorExclusive: ClockEventCursor?, limit: Int): List<StoredClockEvent> =
        readAllForReplay().filter { cursorExclusive == null || it.cursor.value > cursorExclusive.value }.take(limit)

    override suspend fun readSnapshot(): ClockEventStoreSnapshot {
        val lastCursor = events.values.maxByOrNull { it.cursor.value }?.cursor
        return ClockEventStoreSnapshot(
            lastCursor = lastCursor,
            lastSyncedCursor = null,
            pendingSyncCount = events.size,
        )
    }

    override suspend fun updateSyncCheckpoint(cursorInclusive: ClockEventCursor) = Unit
}

private class ViewerAwarePeerTrustStore(
    private val record: PeerTrustRecord,
) : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean = peerId == record.peerId

    override fun listTrusted(): List<String> = listOf(record.peerId)

    override fun trust(peerId: String) {}

    override fun trust(peerId: String, publicKeyBase64: String) {}

    override fun trust(record: PeerTrustRecord) {}

    override fun getTrustRecord(peerId: String): PeerTrustRecord? = if (peerId == record.peerId) record else null

    override fun listTrustRecords(): List<PeerTrustRecord> = listOf(record)

    override fun revoke(peerId: String) {}

    override fun repair(peerId: String) {}

    override fun getTrustedPublicKey(peerId: String): String? = if (peerId == record.peerId) record.publicKeyBase64 else null
}

private class NoOpViewerClockCommandExecutor : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        return ClockResultPayload(
            commandId = "noop",
            status = ClockResultStatus.Rejected,
            errorCode = ClockErrorCode.VALIDATION_FAILED,
            errorMessage = "not used",
            appliedAt = Instant.parse("2026-03-10T09:00:00Z"),
            byDeviceId = "device-local",
        )
    }
}

private object AlwaysEnabledViewerFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}

private class TestViewerSyncRuntimePrefs : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = SyncRuntimeMode.Standard
    override fun setSelectedMode(mode: SyncRuntimeMode) {}
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}

private fun storedEvent(
    cursor: Long,
    eventId: String,
    type: ClockEventType,
    createdAt: String,
): StoredClockEvent = StoredClockEvent(
    cursor = ClockEventCursor(cursor),
    event = ClockEvent(
        eventId = eventId,
        eventType = type,
        deviceId = "device-local",
        createdAt = Instant.parse(createdAt),
        logicalDay = LocalDate.parse("2026-03-18"),
        fileName = "2026-03-18.org",
        headingPath = HeadingPath.parse("Work/Project A"),
        causalOrder = ClockEventCausalOrder(counter = cursor),
    ),
)
