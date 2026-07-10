package com.example.orgclock.desktop

import io.github.shgnaka.orgclock.synccore.api.DeliveryEvent
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreIncomingState
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncStoreOutgoingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.Topic
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class DesktopFileSyncStoreTest {
    @Test
    fun saveAndLoadRoundTripsSyncStoreSnapshot() = runTest {
        val root = createTempDirectory("desktop-sync-core-store-test")
        val path = root.resolve(".orgclock").resolve("sync-core-store.json")
        val store = DesktopFileSyncStore(path)
        val snapshot = SyncStoreSnapshot(
            outgoing = listOf(
                SyncStoreOutgoingRecord(
                    sequence = 1,
                    message = SyncMessage(
                        messageId = MessageId("cmd-1"),
                        topic = Topic("clock.command.v1"),
                        payloadJson = """{"schema":"clock.command.v1","command_id":"cmd-1"}""",
                        targetPeerId = PeerId("peer-a"),
                        createdAtEpochMs = 1_700_000_000_000L,
                        expiresAtEpochMs = 1_700_086_400_000L,
                    ),
                    state = DeliveryState.RetryWait,
                    attempt = 2,
                    nextAttemptAtEpochMs = 1_700_000_010_000L,
                    lastError = SyncError(SyncErrorCode.Timeout, "timeout", retryAtEpochMs = 1_700_000_010_000L),
                    leaseUntilEpochMs = null,
                ),
                SyncStoreOutgoingRecord(
                    sequence = 2,
                    message = SyncMessage(
                        messageId = MessageId("cmd-2"),
                        topic = Topic("clock.command.v1"),
                        payloadJson = """{"schema":"clock.command.v1","command_id":"cmd-2"}""",
                        targetPeerId = PeerId("peer-a"),
                        createdAtEpochMs = 1_700_000_004_000L,
                        expiresAtEpochMs = 1_700_086_400_000L,
                    ),
                    state = DeliveryState.Acked,
                    attempt = 1,
                    nextAttemptAtEpochMs = null,
                    lastError = null,
                    leaseUntilEpochMs = null,
                    terminalAtEpochMs = 1_700_000_005_000L,
                ),
            ),
            incoming = listOf(
                SyncStoreIncomingRecord(
                    receiptId = "in-1",
                    senderPeerId = PeerId("peer-b"),
                    targetPeerId = PeerId("peer-local"),
                    messageId = MessageId("cmd-in"),
                    topic = Topic("clock.command.v1"),
                    payloadJson = """{"op":"start"}""",
                    payloadSha256 = "abc123",
                    receivedAtEpochMs = 1_700_000_001_000L,
                    state = SyncStoreIncomingState.Available,
                    availableAtEpochMs = 1_700_000_001_000L,
                    deliveryCount = 1,
                    lastError = null,
                ),
                SyncStoreIncomingRecord(
                    receiptId = "in-2",
                    senderPeerId = PeerId("peer-b"),
                    targetPeerId = PeerId("peer-local"),
                    messageId = MessageId("cmd-in-2"),
                    topic = Topic("clock.command.v1"),
                    payloadJson = """{"op":"stop"}""",
                    payloadSha256 = "def456",
                    receivedAtEpochMs = 1_700_000_004_000L,
                    state = SyncStoreIncomingState.Processed,
                    availableAtEpochMs = null,
                    deliveryCount = 1,
                    lastError = null,
                    processedAtEpochMs = 1_700_000_005_000L,
                ),
            ),
            deliveryEvents = listOf(
                DeliveryEvent(
                    sequence = 1,
                    messageId = MessageId("cmd-1"),
                    peerId = PeerId("peer-a"),
                    topic = Topic("clock.command.v1"),
                    state = DeliveryState.RetryWait,
                    attempt = 2,
                    occurredAtEpochMs = 1_700_000_002_000L,
                    error = SyncError(SyncErrorCode.Timeout, "timeout"),
                ),
            ),
            metrics = SyncStoreMetrics(
                submittedTotal = 1,
                retryAttemptsTotal = 1,
                persistenceErrorTotal = 1,
                lastSuccessfulDispatchByPeer = mapOf(PeerId("peer-a") to 1_700_000_003_000L),
            ),
            nextRecordSequence = 3,
            nextEventSequence = 2,
            nextReceiptSequence = 3,
        )

        assertEquals(SyncStoreSaveResult.Saved, store.save(snapshot))

        val loaded = assertIs<SyncStoreLoadResult.Loaded>(DesktopFileSyncStore(path).load())
        assertEquals(snapshot, loaded.snapshot)

        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
