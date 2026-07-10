package com.example.orgclock.desktop

import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.sync.CLOCK_COMMAND_SCHEMA_V1
import com.example.orgclock.sync.CLOCK_RESULT_SCHEMA_V1
import com.example.orgclock.sync.ClockErrorCode
import com.example.orgclock.sync.ClockResultPayload
import com.example.orgclock.sync.ClockResultStatus
import com.example.orgclock.sync.DEFAULT_SYNC_SIGNING_ALG
import com.example.orgclock.sync.OPTIONAL_SYNC_SIGNING_ALG_ED25519
import com.example.orgclock.sync.PeerTrustRole
import com.example.orgclock.sync.toResultPayloadJson
import com.example.orgclock.time.ClockEnvironment
import io.github.shgnaka.orgclock.synccore.api.AckIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingProcessingOutcome
import io.github.shgnaka.orgclock.synccore.api.IngressOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.StartOutcome
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncError
import io.github.shgnaka.orgclock.synccore.api.SyncErrorCode
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TopicAuthorization
import io.github.shgnaka.orgclock.synccore.api.TopicPolicy
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.api.TrustedPeerResolver
import io.github.shgnaka.orgclock.synccore.api.SyncRawIngressReceiver
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopSyncCoreRuntime(
    private val core: SyncCore,
    private val rawIngressReceiver: SyncRawIngressReceiver,
    private val commandExecutor: DesktopClockCommandExecutor,
    private val drainScope: CoroutineScope? = null,
    private val nowEpochMs: () -> Long,
    private val localDeviceId: () -> String,
    private val claimLimit: Int = DEFAULT_CLAIM_LIMIT,
) : DesktopSyncCoreIngressReceiver, AutoCloseable {
    private val drainMutex = Mutex()

    fun start() {
        when (val outcome = runBlocking { core.start() }) {
            StartOutcome.Started,
            StartOutcome.AlreadyStarted -> scheduleDrain()
            is StartOutcome.Failed -> throw IllegalStateException(outcome.error.detail ?: outcome.error.code.name)
        }
    }

    override fun close() {
        runBlocking { core.stop() }
    }

    override suspend fun receive(rawJson: String, sourceKey: String): IngressOutcome {
        val outcome = rawIngressReceiver.receive(rawJson, sourceKey)
        if (outcome == IngressOutcome.Accepted || outcome == IngressOutcome.AlreadyAccepted) {
            scheduleDrain()
        }
        return outcome
    }

    suspend fun drainIncomingCommandsOnce(): Int = drainMutex.withLock {
        val receipts = when (val outcome = core.claimIncoming(claimLimit)) {
            is ClaimIncomingOutcome.Claimed -> outcome.receipts
            is ClaimIncomingOutcome.Failed -> return@withLock 0
        }
        var acknowledged = 0
        receipts.forEach { receipt ->
            val result = runCatching { commandExecutor.execute(receipt.payloadJson) }
                .getOrElse { error ->
                    ClockResultPayload(
                        commandId = receipt.messageId.value,
                        status = ClockResultStatus.Failed,
                        errorCode = ClockErrorCode.IO_FAILURE,
                        errorMessage = error.message ?: "Desktop command execution failed",
                        appliedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(nowEpochMs()),
                        byDeviceId = localDeviceId(),
                    )
                }
            val submitted = submitResult(receipt.senderPeerId, result)
            if (submitted) {
                when (core.ackIncoming(receipt.receiptId, result.toIncomingProcessingOutcome())) {
                    AckIncomingOutcome.Acked,
                    AckIncomingOutcome.NotFound -> acknowledged += 1
                    is AckIncomingOutcome.Failed -> Unit
                }
            }
        }
        acknowledged
    }

    private suspend fun submitResult(targetPeerId: PeerId, result: ClockResultPayload): Boolean {
        val createdAt = nowEpochMs()
        val outcome = core.submit(
            SyncMessage(
                messageId = MessageId("result-${result.commandId}"),
                topic = Topic(CLOCK_RESULT_SCHEMA_V1),
                payloadJson = result.toResultPayloadJson(),
                targetPeerId = targetPeerId,
                createdAtEpochMs = createdAt,
                expiresAtEpochMs = createdAt + RESULT_EXPIRATION_MS,
            ),
        )
        return outcome == SubmitOutcome.Submitted || outcome == SubmitOutcome.AlreadySubmitted
    }

    private fun scheduleDrain() {
        drainScope?.launch {
            runCatching { drainIncomingCommandsOnce() }
        }
    }

    private companion object {
        const val DEFAULT_CLAIM_LIMIT = 100
        const val RESULT_EXPIRATION_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}

object DesktopSyncCoreRuntimeFactory {
    fun create(
        rootPath: Path,
        peerTrustStore: PeerTrustStore,
        repository: ClockRepository,
        clockService: ClockService,
        clockEnvironment: ClockEnvironment,
        localDeviceId: () -> String,
        scope: CoroutineScope?,
        syncIdentity: DesktopSyncIdentity = DesktopSyncIdentity(),
    ): DesktopSyncCoreRuntime {
        val syncClock = SyncClock { clockEnvironment.now().toEpochMilliseconds() }
        val trustedPeerResolver = DesktopTrustedPeerResolver(peerTrustStore)
        val core = SyncCoreFactory.createPersistent(
            store = DesktopFileSyncStore(syncCoreStorePath(rootPath)),
            clock = syncClock,
            trustedPeerResolver = trustedPeerResolver,
            topicPolicy = desktopCommandTopicPolicy,
            transport = DesktopSyncCoreTransport(
                peerTrustStore = peerTrustStore,
                credentialStore = DesktopEncryptedSyncCoreTransportCredentialStore(rootPath),
                signer = DesktopSyncCoreIdentityEnvelopeSigner(
                    rootPath = rootPath,
                    syncIdentity = syncIdentity,
                    senderPeerId = localDeviceId(),
                ),
                clockEpochMs = { clockEnvironment.now().toEpochMilliseconds() },
            ),
        )
        return DesktopSyncCoreRuntime(
            core = core,
            rawIngressReceiver = SyncRawIngressReceiver(
                syncCore = core,
                trustedPeerResolver = trustedPeerResolver,
                clock = syncClock,
            ),
            commandExecutor = DefaultDesktopClockCommandExecutor(
                repository = repository,
                clockService = clockService,
                commandIdStore = DesktopProcessedCommandIdStore(processedCommandPreferences(rootPath)),
                deviceIdProvider = localDeviceId,
                clockEnvironment = clockEnvironment,
            ),
            drainScope = scope,
            nowEpochMs = { clockEnvironment.now().toEpochMilliseconds() },
            localDeviceId = localDeviceId,
        )
    }

    private fun syncCoreStorePath(rootPath: Path): Path =
        rootPath.resolve(".orgclock").resolve("sync-core-store.json")

    private fun processedCommandPreferences(rootPath: Path): Preferences =
        Preferences.userRoot().node("com/example/orgclock/desktop/sync-core/commands/${stableRootKey(rootPath).hashCode()}")
}

class DesktopTrustedPeerResolver(
    private val peerTrustStore: PeerTrustStore,
) : TrustedPeerResolver {
    override suspend fun resolve(peerId: PeerId): TrustedPeer? {
        val record = peerTrustStore.getTrustRecord(peerId.value) ?: return null
        val explicit = record.signingPublicKeyBase64?.trim()?.takeIf { it.isNotBlank() }
        val signingKey = explicit?.takeIf(::isValidSupportedX509PublicKey)
            ?: record.publicKeyBase64.trim().takeIf(::isValidSupportedX509PublicKey)
            ?: return null
        val signingAlg = when {
            explicit != null -> resolveSigningAlg(record.signingAlg, explicit)
            isValidEs256X509PublicKey(signingKey) -> DEFAULT_SYNC_SIGNING_ALG
            else -> OPTIONAL_SYNC_SIGNING_ALG_ED25519
        }
        return TrustedPeer(
            peerId = PeerId(record.peerId),
            deviceId = record.deviceId,
            displayName = record.displayName,
            signingPublicKeyBase64 = signingKey,
            role = record.role.toSyncCoreRole(),
            endpoint = record.endpoint,
            active = record.isActive,
            signingAlg = signingAlg,
        )
    }
}

private fun PeerTrustRole.toSyncCoreRole(): PeerRole = when (this) {
    PeerTrustRole.Full -> PeerRole.Full
    PeerTrustRole.Viewer -> PeerRole.Viewer
}

private fun ClockResultPayload.toIncomingProcessingOutcome(): IncomingProcessingOutcome = when (status) {
    ClockResultStatus.Applied,
    ClockResultStatus.Duplicate -> IncomingProcessingOutcome.Processed
    ClockResultStatus.Failed,
    ClockResultStatus.Rejected -> IncomingProcessingOutcome.Rejected(
        SyncError(SyncErrorCode.InvalidMessage, errorMessage ?: errorCode?.name ?: status.wireValue),
    )
}

private val desktopCommandTopicPolicy = TopicPolicy { trustedPeer, topic, _ ->
    if (topic == Topic(CLOCK_COMMAND_SCHEMA_V1) && trustedPeer.role == PeerRole.Full) {
        TopicAuthorization.Allowed
    } else {
        TopicAuthorization.Denied
    }
}

private fun isValidEd25519X509PublicKey(value: String): Boolean = runCatching {
    KeyFactory.getInstance(ED25519_ALGORITHM)
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value.trim())))
    true
}.getOrDefault(false)

private fun isValidEs256X509PublicKey(value: String): Boolean = runCatching {
    val publicKey = KeyFactory.getInstance(EC_ALGORITHM)
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value.trim()))) as ECPublicKey
    publicKey.params.curve.field.fieldSize == 256
}.getOrDefault(false)

private fun isValidSupportedX509PublicKey(value: String): Boolean =
    isValidEs256X509PublicKey(value) || isValidEd25519X509PublicKey(value)

private fun resolveSigningAlg(storedAlg: String, signingPublicKeyBase64: String): String {
    if (storedAlg != DEFAULT_SYNC_SIGNING_ALG) return storedAlg
    return if (isValidEd25519X509PublicKey(signingPublicKeyBase64) && !isValidEs256X509PublicKey(signingPublicKeyBase64)) {
        OPTIONAL_SYNC_SIGNING_ALG_ED25519
    } else {
        DEFAULT_SYNC_SIGNING_ALG
    }
}

private const val ED25519_ALGORITHM = "Ed25519"
private const val EC_ALGORITHM = "EC"
