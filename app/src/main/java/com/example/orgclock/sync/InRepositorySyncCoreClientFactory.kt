package com.example.orgclock.sync

import android.content.Context
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.time.ClockEnvironment
import io.github.shgnaka.orgclock.synccore.api.AckIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingRoute
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.OutgoingQuery
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.SyncRawIngressReceiver
import kotlinx.datetime.Clock

class InRepositorySyncCoreClientFactory : SyncCoreClientFactory {
    override fun create(
        appContext: Context,
        repository: ClockRepository,
        clockService: ClockService,
        clockEnvironment: ClockEnvironment,
    ): OrgSyncCoreClient {
        val peerTrustStore = SharedPreferencesPeerTrustStore(
            appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
        )
        val deviceIdProvider = SharedPreferencesDeviceIdProvider(
            appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE),
        )
        val transportCredentialStore = AndroidKeystoreSyncCoreTransportCredentialStore(appContext)
        val tlsIdentity = AndroidKeystoreSyncCoreTlsIdentityStore().loadOrCreate()
        AndroidSyncCoreIdentityMigration(peerTrustStore, transportCredentialStore).migrateLegacyTrustRecords()
        InternalSyncCoreDatabaseBackupManager.forContext(appContext).backupPreV3IfPresent()
        val database = InternalSyncCoreDatabaseFactory.create(appContext)
        val syncClock = SyncClock { clockEnvironment.now().toEpochMilliseconds() }
        val trustedPeerResolver = AndroidTrustedPeerResolver(peerTrustStore)
        val core = SyncCoreFactory.createPersistent(
            store = AndroidRoomSyncStore(database.syncCoreDao()),
            clock = syncClock,
            trustedPeerResolver = trustedPeerResolver,
            transport = AndroidSyncCoreTransport(
                peerTrustStore = peerTrustStore,
                credentialStore = transportCredentialStore,
                signer = AndroidKeystoreSyncCoreEnvelopeSigner(deviceIdProvider),
                clockEpochMs = { clockEnvironment.now().toEpochMilliseconds() },
            ),
        )
        val ingressServer = AndroidSyncCoreIngressServer(
            receiver = SyncRawIngressReceiver(
                syncCore = core,
                trustedPeerResolver = trustedPeerResolver,
                clock = syncClock,
            ),
            sslServerSocketFactory = tlsIdentity.sslServerSocketFactory,
        )
        return InRepositoryOrgSyncCoreClient(core = core, ingressServer = ingressServer)
    }
}

internal class InRepositoryOrgSyncCoreClient(
    private val core: SyncCore,
    private val ingressServer: SyncCoreIngressServer? = null,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : OrgSyncCoreClient {
    private val pendingIncomingRoutes = linkedMapOf<String, IncomingRoute>()
    private val resultRoutingFailures = ArrayDeque<SyncDeliveryState>()
    override suspend fun start() {
        core.start()
        ingressServer?.start()
    }

    override suspend fun stop() {
        ingressServer?.stop()
        core.stop()
    }

    override suspend fun flushNow() {
        core.flushDue()
    }

    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult {
        return runCatching {
            val createdAt = nowEpochMs()
            core.submit(
                SyncMessage(
                    messageId = MessageId(command.commandId),
                    topic = Topic(CLOCK_COMMAND_SCHEMA_V1),
                    payloadJson = command.payloadJson,
                    targetPeerId = PeerId(command.targetPeerId),
                    createdAtEpochMs = createdAt,
                    expiresAtEpochMs = createdAt + COMMAND_EXPIRATION_MS,
                ),
            )
        }.fold(
            onSuccess = { outcome -> outcome.toSubmitResult() },
            onFailure = { error ->
                if (error is IllegalArgumentException) {
                    SubmitResult.Rejected(error.message ?: "invalid message")
                } else {
                    SubmitResult.Failed(error.message ?: "submit failed")
                }
            },
        )
    }

    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> {
        val receipts = when (val outcome = core.claimIncoming()) {
            is ClaimIncomingOutcome.Claimed -> outcome.receipts
            is ClaimIncomingOutcome.Failed -> return emptyList()
        }
        return receipts.map { receipt ->
            pendingIncomingRoutes[receipt.messageId.value] = IncomingRoute(
                receiptId = receipt.receiptId,
                messageId = receipt.messageId,
                senderPeerId = receipt.senderPeerId,
                topic = receipt.topic,
                receivedAtEpochMs = receipt.receivedAtEpochMs,
            )
            VerifiedIncomingCommand(
                payloadJson = receipt.payloadJson,
                commandId = receipt.messageId.value,
                senderDeviceId = receipt.senderPeerId.value,
                peerId = receipt.senderPeerId.value,
                verifiedPeerId = receipt.senderPeerId.value,
                verificationState = IncomingVerificationState.Verified,
                verificationReason = null,
                verificationMethod = "sync-core-verified-envelope",
                signatureKeyId = null,
                replayCheckPassed = true,
                receivedAtEpochMs = receipt.receivedAtEpochMs,
            )
        }
    }

    override suspend fun reportResult(result: ClockResultPayload) {
        val route = pendingIncomingRoutes[result.commandId]
            ?: core.resolveIncomingRoute(MessageId(result.commandId))
            ?: return recordResultRouteMissing(result.commandId)
        val submitted = submitResult(route, result)
        if (submitted) {
            when (core.ackIncoming(route.receiptId, result.toIncomingProcessingOutcome())) {
                AckIncomingOutcome.Acked,
                AckIncomingOutcome.NotFound -> pendingIncomingRoutes.remove(result.commandId)
                is AckIncomingOutcome.Failed -> Unit
            }
        }
    }

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> {
        val coreStates = core.listOutgoing(OutgoingQuery(limit = MAX_DELIVERY_STATE_HISTORY)).items.map { item ->
            SyncDeliveryState(
                commandId = item.messageId.value,
                state = item.state.toPresentationState(),
                detail = item.lastError?.toPresentationDetail(),
            )
        }
        return (coreStates + resultRoutingFailures.toList()).takeLast(MAX_DELIVERY_STATE_HISTORY)
    }

    override suspend fun metricsSnapshot(): SyncMetricsSnapshot {
        val metrics = core.metricsSnapshot()
        return SyncMetricsSnapshot(
            commandsSubmittedTotal = metrics.submittedTotal,
            commandsAppliedTotal = metrics.acceptedTotal,
            retryAttemptsTotal = metrics.retryAttemptsTotal,
            queueDepth = metrics.queueDepth,
            persistenceErrorTotal = metrics.persistenceErrorTotal,
        )
    }

    override suspend fun revokePeer(peerId: String) {
        val normalized = peerId.trim()
        if (normalized.isBlank()) return
        pendingIncomingRoutes.entries.removeIf { it.value.senderPeerId.value == normalized }
        core.revokePeer(PeerId(normalized))
    }

    private suspend fun submitResult(route: IncomingRoute, result: ClockResultPayload): Boolean {
        val createdAt = nowEpochMs()
        val outcome = core.submit(
            SyncMessage(
                messageId = MessageId("result-${result.commandId}"),
                topic = Topic(CLOCK_RESULT_SCHEMA_V1),
                payloadJson = result.toResultPayloadJson(),
                targetPeerId = route.senderPeerId,
                createdAtEpochMs = createdAt,
                expiresAtEpochMs = createdAt + RESULT_EXPIRATION_MS,
            ),
        )
        return outcome == SubmitOutcome.Submitted || outcome == SubmitOutcome.AlreadySubmitted
    }

    private fun recordResultRouteMissing(commandId: String) {
        while (resultRoutingFailures.size >= MAX_DELIVERY_STATE_HISTORY) {
            resultRoutingFailures.removeFirst()
        }
        resultRoutingFailures.addLast(
            SyncDeliveryState(
                commandId = "result-$commandId",
                state = DeliveryState.Failed.toPresentationState(),
                detail = SyncError(
                    SyncErrorCode.ResultRouteNotFound,
                    "result route not found",
                ).toPresentationDetail(),
            ),
        )
    }

    private companion object {
        const val COMMAND_EXPIRATION_MS = 24L * 60L * 60L * 1_000L
        const val RESULT_EXPIRATION_MS = 7L * 24L * 60L * 60L * 1_000L
        const val MAX_DELIVERY_STATE_HISTORY = 100
    }
}

private fun ClockResultPayload.toIncomingProcessingOutcome(): IncomingProcessingOutcome = when (status) {
    ClockResultStatus.Applied, ClockResultStatus.Duplicate -> IncomingProcessingOutcome.Processed
    ClockResultStatus.Failed, ClockResultStatus.Rejected -> IncomingProcessingOutcome.Rejected(
        SyncError(SyncErrorCode.InvalidMessage, errorMessage ?: errorCode?.name ?: status.wireValue),
    )
}

private fun SubmitOutcome.toSubmitResult(): SubmitResult = when (this) {
    SubmitOutcome.Submitted -> SubmitResult.Submitted
    SubmitOutcome.AlreadySubmitted -> SubmitResult.Submitted
    is SubmitOutcome.Rejected -> SubmitResult.Rejected(error.toPresentationDetail())
    is SubmitOutcome.Failed -> SubmitResult.Failed(error.toPresentationDetail())
}

private fun DeliveryState.toPresentationState(): String = when (this) {
    DeliveryState.Pending -> "pending"
    DeliveryState.Dispatching -> "dispatching"
    DeliveryState.RetryWait -> "retry_wait"
    DeliveryState.Acked -> "acked"
    DeliveryState.Rejected -> "rejected"
    DeliveryState.Failed -> "failed"
    DeliveryState.Expired -> "expired"
    DeliveryState.Cancelled -> "cancelled"
}

private fun SyncError.toPresentationDetail(): String = listOfNotNull(code.toWireCode(), detail).joinToString(": ")

private fun SyncErrorCode.toWireCode(): String = name
    .replace(Regex("([a-z])([A-Z])"), "$1_$2")
    .uppercase()



