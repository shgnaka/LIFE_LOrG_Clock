package io.github.shgnaka.orgclock.synccore.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

private const val MAX_ID_BYTES = 256
private const val MAX_PAYLOAD_BYTES = 96 * 1024

@JvmInline value class MessageId(val value: String) { init { requireValidToken(value, "messageId") } }
@JvmInline value class PeerId(val value: String) { init { requireValidToken(value, "peerId") } }
@JvmInline value class Topic(val value: String) { init { requireValidToken(value, "topic") } }

data class SyncMessage(
    val messageId: MessageId,
    val topic: Topic,
    val payloadJson: String,
    val targetPeerId: PeerId,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
) {
    init {
        require(createdAtEpochMs > 0) { "createdAtEpochMs must be > 0." }
        require(expiresAtEpochMs == null || expiresAtEpochMs > createdAtEpochMs) { "expiresAtEpochMs must be greater than createdAtEpochMs." }
        require(payloadJson.encodeToByteArray().size <= MAX_PAYLOAD_BYTES) { "payloadJson must be at most $MAX_PAYLOAD_BYTES bytes." }
        runCatching { Json.parseToJsonElement(payloadJson) }.getOrElse { throw IllegalArgumentException("payloadJson must be valid JSON.", it) }
    }
}

enum class DeliveryState { Pending, Dispatching, RetryWait, Acked, Rejected, Failed, Expired, Cancelled }

data class DeliveryEvent(
    val sequence: Long,
    val messageId: MessageId,
    val peerId: PeerId,
    val topic: Topic,
    val state: DeliveryState,
    val attempt: Int,
    val occurredAtEpochMs: Long,
    val error: SyncError?,
)

enum class SyncErrorCode {
    InvalidMessage, PayloadTooLarge, MessageIdConflict, PeerNotTrusted, SignatureInvalid,
    TimestampOutOfRange, ReplayConflict, CertificatePinMismatch, PeerNotAuthorized,
    QueueFull, InboxFull, RateLimited, NetworkUnreachable, Timeout, RemoteBusy,
    ProtocolError, Expired, Cancelled, RetryExhausted, ResultRouteNotFound,
    StoreUnavailable, StoreWriteFailed, StoreReadFailed, MigrationFailed, InvalidStateTransition,
}

data class SyncError(val code: SyncErrorCode, val detail: String? = null, val retryAtEpochMs: Long? = null) {
    init { require(detail == null || detail.length <= 512) { "error detail must be at most 512 characters." } }
}

sealed interface SubmitOutcome { data object Submitted : SubmitOutcome; data object AlreadySubmitted : SubmitOutcome; data class Rejected(val error: SyncError) : SubmitOutcome; data class Failed(val error: SyncError) : SubmitOutcome }
sealed interface CancelOutcome { data object Cancelled : CancelOutcome; data object AlreadyTerminal : CancelOutcome; data object NotFound : CancelOutcome; data class Failed(val error: SyncError) : CancelOutcome }
sealed interface RetryOutcome { data object Scheduled : RetryOutcome; data object NotRetryable : RetryOutcome; data object NotFound : RetryOutcome; data class Failed(val error: SyncError) : RetryOutcome }

data class FlushSummary(
    val considered: Int,
    val dispatched: Int,
    val accepted: Int,
    val rejected: Int,
    val retryScheduled: Int,
    val expired: Int,
    val failed: Int,
    val persistenceFailures: Int = 0,
    val lastPersistenceError: SyncError? = null,
)

data class OutgoingQuery(val peerId: PeerId? = null, val topic: Topic? = null, val states: Set<DeliveryState> = emptySet(), val afterSequence: Long? = null, val limit: Int = 100) {
    init { require(limit in 1..500) { "limit must be between 1 and 500." } }
}

data class OutgoingSummary(
    val sequence: Long,
    val messageId: MessageId,
    val peerId: PeerId,
    val topic: Topic,
    val state: DeliveryState,
    val attempt: Int,
    val createdAtEpochMs: Long,
    val nextAttemptAtEpochMs: Long?,
    val expiresAtEpochMs: Long?,
    val lastError: SyncError?,
)

data class OutgoingPage(val items: List<OutgoingSummary>, val nextSequence: Long?)

data class VerifiedEnvelope(
    val schemaVersion: Int,
    val alg: String = DEFAULT_ENVELOPE_SIGNING_ALG,
    val envelopeId: String,
    val messageId: MessageId,
    val senderPeerId: PeerId,
    val targetPeerId: PeerId,
    val topic: Topic,
    val payloadJson: String,
    val sentAtEpochMs: Long,
    val nonce: String,
    val payloadSha256: String,
)

sealed interface IngressOutcome { data object Accepted : IngressOutcome; data object AlreadyAccepted : IngressOutcome; data class Rejected(val httpStatus: Int, val error: SyncError) : IngressOutcome; data class RetryLater(val httpStatus: Int, val retryAfterSeconds: Int, val error: SyncError) : IngressOutcome }
data class IncomingReceipt(val receiptId: String, val messageId: MessageId, val senderPeerId: PeerId, val topic: Topic, val payloadJson: String, val receivedAtEpochMs: Long, val deliveryCount: Int)
data class IncomingRoute(val receiptId: String, val messageId: MessageId, val senderPeerId: PeerId, val topic: Topic, val receivedAtEpochMs: Long)
sealed interface ClaimIncomingOutcome { data class Claimed(val receipts: List<IncomingReceipt>) : ClaimIncomingOutcome; data class Failed(val error: SyncError) : ClaimIncomingOutcome }
sealed interface IncomingProcessingOutcome { data object Processed : IncomingProcessingOutcome; data class Rejected(val error: SyncError) : IncomingProcessingOutcome; data class RetryLater(val retryAtEpochMs: Long?) : IncomingProcessingOutcome }
sealed interface AckIncomingOutcome { data object Acked : AckIncomingOutcome; data object NotFound : AckIncomingOutcome; data class Failed(val error: SyncError) : AckIncomingOutcome }
sealed interface PeerRevokeOutcome { data class Revoked(val outgoingRejected: Int, val incomingRejected: Int) : PeerRevokeOutcome; data class Failed(val error: SyncError) : PeerRevokeOutcome }

interface SyncCore {
    suspend fun start(): StartOutcome
    suspend fun stop()
    suspend fun submit(message: SyncMessage): SubmitOutcome
    suspend fun cancel(messageId: MessageId): CancelOutcome
    suspend fun retry(messageId: MessageId): RetryOutcome
    suspend fun flushDue(): FlushSummary
    suspend fun listOutgoing(query: OutgoingQuery): OutgoingPage
    suspend fun receiveVerified(envelope: VerifiedEnvelope): IngressOutcome
    suspend fun claimIncoming(limit: Int = 100): ClaimIncomingOutcome
    suspend fun resolveIncomingRoute(messageId: MessageId): IncomingRoute?
    suspend fun ackIncoming(receiptId: String, outcome: IncomingProcessingOutcome): AckIncomingOutcome
    suspend fun revokePeer(peerId: PeerId): PeerRevokeOutcome
    fun observeDeliveryEvents(afterSequence: Long? = null): Flow<DeliveryEvent>
    suspend fun metricsSnapshot(): SyncMetrics
    suspend fun healthCheck(): SyncHealth
}

sealed interface StartOutcome { data object Started : StartOutcome; data object AlreadyStarted : StartOutcome; data class Failed(val error: SyncError) : StartOutcome }

data class SyncMetrics(
    val submittedTotal: Long,
    val acceptedTotal: Long,
    val rejectedTotal: Long,
    val retryAttemptsTotal: Long,
    val queueDepth: Long,
    val oldestPendingAgeMs: Long?,
    val incomingRejectedTotal: Long,
    val inboxUnprocessedDepth: Long,
    val expiredLeaseRecoveryTotal: Long,
    val persistenceErrorTotal: Long,
    val lastSuccessfulDispatchByPeer: Map<PeerId, Long>,
)

sealed interface SyncHealth { data object Healthy : SyncHealth; data class Degraded(val errors: List<SyncError>) : SyncHealth; data class Unavailable(val error: SyncError) : SyncHealth }

fun interface SyncTransport { suspend fun dispatch(message: SyncMessage, attempt: Int): DispatchOutcome }
sealed interface DispatchOutcome { data object Accepted : DispatchOutcome; data class Rejected(val error: SyncError) : DispatchOutcome; data class Retryable(val error: SyncError) : DispatchOutcome }
fun interface SyncClock { fun nowEpochMs(): Long }
fun interface SyncRandom { fun nextUnitDouble(): Double }
fun interface TopicPolicy { fun authorize(peer: TrustedPeer, topic: Topic, direction: MessageDirection): TopicAuthorization }
fun interface TrustedPeerResolver { suspend fun resolve(peerId: PeerId): TrustedPeer? }
enum class PeerRole { Full, Viewer }
enum class MessageDirection { Incoming, Outgoing }
enum class TopicAuthorization { Allowed, Denied }

data class TrustedPeer(
    val peerId: PeerId,
    val deviceId: String,
    val displayName: String,
    val signingPublicKeyBase64: String,
    val role: PeerRole,
    val endpoint: String?,
    val active: Boolean,
    val signingAlg: String = DEFAULT_ENVELOPE_SIGNING_ALG,
)

const val DEFAULT_ENVELOPE_SIGNING_ALG: String = "ES256"
const val OPTIONAL_ED25519_ENVELOPE_SIGNING_ALG: String = "Ed25519"

private fun requireValidToken(value: String, label: String) {
    require(value.trim() == value && value.isNotEmpty()) { "$label must be trimmed and non-empty." }
    require(value.encodeToByteArray().size <= MAX_ID_BYTES) { "$label must be at most $MAX_ID_BYTES bytes." }
}


