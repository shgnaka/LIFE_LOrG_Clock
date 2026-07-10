package com.example.orgclock.sync

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactionIntegrationTest {
    @Test
    fun allSinks() = runTest {
        val secret = "SECRET-LOG-MARKER-SHOULD-NOT-LEAK"
        val logger = Logger.getLogger(SyncIntegrationService::class.java.name)
        val handler = RecordingLogHandler()
        val previousLevel = logger.level
        val previousUseParentHandlers = logger.useParentHandlers
        logger.level = Level.FINE
        logger.useParentHandlers = false
        logger.addHandler(handler)
        try {
            val client = RedactionSyncCoreClient(secret)
            val service = SyncIntegrationService(
                featureFlag = RedactionFeatureFlag,
                syncCoreClient = client,
                commandExecutor = RedactionClockCommandExecutor,
                deviceIdProvider = RedactionDeviceIdProvider,
                runtimePrefs = RedactionRuntimePrefs,
                peerTrustStore = RedactionPeerTrustStore,
            )

            service.pollIncomingCommandsOnce()
            service.executeManualCommand("""{"secret":"$secret"}""")

            val captured = handler.messages.joinToString("\n")
            assertTrue(captured.contains("sync.incoming.rejected"))
            assertTrue(captured.contains("sync.report.failed"))
            assertFalse(captured.contains(secret), captured)
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousUseParentHandlers
        }
    }
}

private class RecordingLogHandler : Handler() {
    val messages = mutableListOf<String>()

    override fun publish(record: LogRecord) {
        messages += record.message.orEmpty()
    }

    override fun flush() {}
    override fun close() {}
}

private class RedactionSyncCoreClient(
    private val secret: String,
) : OrgSyncCoreClient {
    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun flushNow() {
        throw IllegalStateException("flush failed with $secret")
    }
    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult = SubmitResult.Submitted
    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = listOf(
        VerifiedIncomingCommand(
            payloadJson = """{"secret":"$secret"}""",
            commandId = "incoming-secret",
            senderDeviceId = "device-b",
            peerId = "peer-b",
            verificationState = IncomingVerificationState.Rejected,
            verificationReason = "signature contained $secret",
            receivedAtEpochMs = 1_000L,
        ),
    )
    override suspend fun reportResult(result: ClockResultPayload) {
        throw IllegalStateException("report failed with $secret")
    }
    override suspend fun observeDeliveryState(): List<SyncDeliveryState> = emptyList()
    override suspend fun metricsSnapshot(): SyncMetricsSnapshot = SyncMetricsSnapshot()
}

private object RedactionClockCommandExecutor : ClockCommandExecutor {
    override suspend fun execute(rawPayload: String): ClockResultPayload {
        return ClockResultPayload(
            commandId = "manual-secret",
            status = ClockResultStatus.Applied,
            appliedAt = Instant.parse("2026-03-10T09:00:00Z"),
            byDeviceId = "device-a",
        )
    }
}

private object RedactionDeviceIdProvider : DeviceIdProvider {
    override fun getOrCreate(): String = "device-a"
}

private object RedactionRuntimePrefs : SyncRuntimePrefs {
    override fun isEnabled(): Boolean = true
    override fun setEnabled(enabled: Boolean) {}
    override fun selectedMode(): SyncRuntimeMode = SyncRuntimeMode.Standard
    override fun setSelectedMode(mode: SyncRuntimeMode) {}
    override fun defaultPeerId(): String? = null
    override fun setDefaultPeerId(peerId: String?) {}
}

private object RedactionPeerTrustStore : PeerTrustStore {
    override fun isTrusted(peerId: String): Boolean = false
    override fun listTrusted(): List<String> = emptyList()
    override fun trust(peerId: String) {}
    override fun trust(peerId: String, publicKeyBase64: String) {}
    override fun revoke(peerId: String) {}
    override fun getTrustedPublicKey(peerId: String): String? = null
}

private object RedactionFeatureFlag : SyncIntegrationFeatureFlag {
    override fun isEnabled(): Boolean = true
}
