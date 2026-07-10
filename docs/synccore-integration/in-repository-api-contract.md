# In-repository sync-core API Contract

## 1. Status

- Status: API Baseline v1
- Applies to: initial `:sync-core` Kotlin Multiplatform module
- Requirements:
  `docs/synccore-integration/in-repository-requirements.md`
- Tests:
  `docs/synccore-integration/in-repository-test-spec.md`

This contract is implementation-ready. Changes to public names or semantics
require updating requirements, acceptance IDs, and compatibility fixtures first.

## 2. Package and Module Boundary

The module is `:sync-core`.

```text
io.github.shgnaka.orgclock.synccore.api
io.github.shgnaka.orgclock.synccore.engine       (internal)
io.github.shgnaka.orgclock.synccore.store        (internal ports)
io.github.shgnaka.orgclock.synccore.security     (internal ports)
```

Only the `api` package is public. Host adapters may implement public port
interfaces declared in `api`, but may not access internal engine classes.

The module targets Android and JVM from the first implementation. Common code
must not reference Android SDK, Room, JDBC, HTTP server implementations, or
application-layer types such as `ClockResultPayload`.

## 3. Public Value Types

```kotlin
@JvmInline
value class MessageId(val value: String)

@JvmInline
value class PeerId(val value: String)

@JvmInline
value class Topic(val value: String)

data class SyncMessage(
    val messageId: MessageId,
    val topic: Topic,
    val payloadJson: String,
    val targetPeerId: PeerId,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
)
```

Validation:

- IDs and topic are trimmed, non-empty, and at most 256 UTF-8 bytes.
- `payloadJson` is a JSON value and at most 96 KiB UTF-8.
- `createdAtEpochMs > 0`.
- expiration is null or strictly greater than creation.
- `SyncMessage` equality includes every field.

```kotlin
enum class DeliveryState {
    Pending,
    Dispatching,
    RetryWait,
    Acked,
    Rejected,
    Failed,
    Expired,
    Cancelled,
}

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
```

`sequence` is monotonically increasing within one local store and is used only
for observation/paging, not cross-device ordering.

## 4. Error Contract

```kotlin
enum class SyncErrorCode {
    InvalidMessage,
    PayloadTooLarge,
    MessageIdConflict,
    PeerNotTrusted,
    SignatureInvalid,
    TimestampOutOfRange,
    ReplayConflict,
    CertificatePinMismatch,
    PeerNotAuthorized,
    QueueFull,
    InboxFull,
    RateLimited,
    NetworkUnreachable,
    Timeout,
    RemoteBusy,
    ProtocolError,
    Expired,
    Cancelled,
    RetryExhausted,
    ResultRouteNotFound,
    StoreUnavailable,
    StoreWriteFailed,
    StoreReadFailed,
    MigrationFailed,
    InvalidStateTransition,
}

data class SyncError(
    val code: SyncErrorCode,
    val detail: String? = null,
    val retryAtEpochMs: Long? = null,
)
```

`detail` is sanitized and limited to 512 characters. Public operations return
typed outcomes for expected errors. Exceptions are reserved for programmer
errors and coroutine cancellation.

When serialized for diagnostics or protocol responses, enum values use the
existing upper-snake-case wire codes from the requirements, for example
`MessageIdConflict -> MESSAGE_ID_CONFLICT`.

## 5. Public Outcomes

```kotlin
sealed interface SubmitOutcome {
    data object Submitted : SubmitOutcome
    data object AlreadySubmitted : SubmitOutcome
    data class Rejected(val error: SyncError) : SubmitOutcome
    data class Failed(val error: SyncError) : SubmitOutcome
}

sealed interface CancelOutcome {
    data object Cancelled : CancelOutcome
    data object AlreadyTerminal : CancelOutcome
    data object NotFound : CancelOutcome
    data class Failed(val error: SyncError) : CancelOutcome
}

sealed interface RetryOutcome {
    data object Scheduled : RetryOutcome
    data object NotRetryable : RetryOutcome
    data object NotFound : RetryOutcome
    data class Failed(val error: SyncError) : RetryOutcome
}

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
```

## 6. Queue Query Contract

```kotlin
data class OutgoingQuery(
    val peerId: PeerId? = null,
    val topic: Topic? = null,
    val states: Set<DeliveryState> = emptySet(),
    val afterSequence: Long? = null,
    val limit: Int = 100,
)

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

data class OutgoingPage(
    val items: List<OutgoingSummary>,
    val nextSequence: Long?,
)
```

Payload is intentionally absent from `OutgoingSummary`.

## 7. Incoming Contract

```kotlin
data class VerifiedEnvelope(
    val schemaVersion: Int,
    val alg: String,
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

sealed interface IngressOutcome {
    data object Accepted : IngressOutcome
    data object AlreadyAccepted : IngressOutcome
    data class Rejected(val httpStatus: Int, val error: SyncError) : IngressOutcome
    data class RetryLater(
        val httpStatus: Int,
        val retryAfterSeconds: Int,
        val error: SyncError,
    ) : IngressOutcome
}

data class IncomingReceipt(
    val receiptId: String,
    val messageId: MessageId,
    val senderPeerId: PeerId,
    val topic: Topic,
    val payloadJson: String,
    val receivedAtEpochMs: Long,
    val deliveryCount: Int,
)

data class IncomingRoute(
    val receiptId: String,
    val messageId: MessageId,
    val senderPeerId: PeerId,
    val topic: Topic,
    val receivedAtEpochMs: Long,
)

sealed interface ClaimIncomingOutcome {
    data class Claimed(val receipts: List<IncomingReceipt>) : ClaimIncomingOutcome
    data class Failed(val error: SyncError) : ClaimIncomingOutcome
}

sealed interface IncomingProcessingOutcome {
    data object Processed : IncomingProcessingOutcome
    data class Rejected(val error: SyncError) : IncomingProcessingOutcome
    data class RetryLater(val retryAtEpochMs: Long?) : IncomingProcessingOutcome
}

sealed interface AckIncomingOutcome {
    data object Acked : AckIncomingOutcome
    data object NotFound : AckIncomingOutcome
    data class Failed(val error: SyncError) : AckIncomingOutcome
}
```

`VerifiedEnvelope` is produced after cryptographic and trust checks. Raw network
bytes are accepted by a host adapter through the public API facade below, not
directly by the engine.

```kotlin
class SyncRawIngressReceiver(
    syncCore: SyncCore,
    trustedPeerResolver: TrustedPeerResolver,
    clock: SyncClock,
    limitPolicy: SyncRawIngressLimitPolicy = SyncRawIngressLimitPolicy(),
) {
    suspend fun receive(rawJson: String, sourceKey: String = "unknown"): IngressOutcome
}

data class SyncRawIngressLimitPolicy(
    val maxEncodedBodyBytes: Int = 128 * 1024,
    val maxDecodedPayloadBytes: Int = 96 * 1024,
    val maxRequestsPerWindow: Int = 120,
    val rateLimitWindowMs: Long = 60_000L,
    val maxTrackedRateLimitSourceKeys: Int = 256,
)
```

## 8. Engine API

```kotlin
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
    suspend fun ackIncoming(
        receiptId: String,
        outcome: IncomingProcessingOutcome,
    ): AckIncomingOutcome
    suspend fun revokePeer(peerId: PeerId): PeerRevokeOutcome
    fun observeDeliveryEvents(afterSequence: Long? = null): Flow<DeliveryEvent>
    suspend fun metricsSnapshot(): SyncMetrics
    suspend fun healthCheck(): SyncHealth
}

sealed interface StartOutcome {
    data object Started : StartOutcome
    data object AlreadyStarted : StartOutcome
    data class Failed(val error: SyncError) : StartOutcome
}

sealed interface PeerRevokeOutcome {
    data class Revoked(
        val outgoingRejected: Int,
        val incomingRejected: Int,
    ) : PeerRevokeOutcome
    data class Failed(val error: SyncError) : PeerRevokeOutcome
}
```

`start` starts the continuous due-message loop only. Host listener and platform
scheduler lifecycle remain host responsibilities.

`revokePeer` is the core-side reaction to host trust revocation. The host still
owns credential deletion. Core marks active outgoing rows for the peer as
`rejected/PEER_NOT_TRUSTED`, rejects unprocessed incoming rows from the peer,
retains existing terminal history, and disables terminal incoming rows as result
routes.

## 9. Metrics and Health

```kotlin
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

sealed interface SyncHealth {
    data object Healthy : SyncHealth
    data class Degraded(val errors: List<SyncError>) : SyncHealth
    data class Unavailable(val error: SyncError) : SyncHealth
}
```

Counters are monotonic for the lifetime of persisted metrics. Implementations
may reconstruct them from durable facts but must not silently reset them during
normal restart.

## 10. Host Ports

```kotlin
interface SyncStore {
    suspend fun <T> transaction(block: suspend SyncStoreTransaction.() -> T): T
    suspend fun healthCheck(): StoreHealth
}

interface SyncTransport {
    suspend fun dispatch(message: SyncMessage, attempt: Int): DispatchOutcome
}

sealed interface DispatchOutcome {
    data object Accepted : DispatchOutcome
    data class Rejected(val error: SyncError) : DispatchOutcome
    data class Retryable(val error: SyncError) : DispatchOutcome
}

fun interface SyncClock {
    fun nowEpochMs(): Long
}

fun interface SyncRandom {
    fun nextUnitDouble(): Double
}

fun interface TrustedPeerResolver {
    suspend fun resolve(peerId: PeerId): TrustedPeer?
}

interface TopicPolicy {
    fun authorize(
        peer: TrustedPeer,
        topic: Topic,
        direction: MessageDirection,
    ): TopicAuthorization
}
```

Store transaction methods are defined by the engine implementation and remain
internal. A platform adapter implements them without exposing Room/JDBC models.

The durable store representation preserves retention timestamps on records:

- terminal outgoing records carry `terminalAtEpochMs`;
- processed or rejected incoming records carry `processedAtEpochMs`.

Retention cleanup uses these timestamps only for terminal rows. Pending,
retry-wait, dispatching, claimed, retry-later, or otherwise unprocessed rows are
not retention-pruned.

TrustedPeerResolver is the core-facing trust lookup port used by ingress
verification and topic authorization. A missing peer is treated as untrusted before
any inbox persistence.

## 11. Trust Types

```kotlin
enum class PeerRole { Full, Viewer }
enum class MessageDirection { Incoming, Outgoing }

data class TrustedPeer(
    val peerId: PeerId,
    val deviceId: String,
    val displayName: String,
    val signingPublicKeyBase64: String,
    val role: PeerRole,
    val endpoint: String?,
    val active: Boolean,
    val signingAlg: String,
)
```

`signingAlg` is `ES256` for the standard P-256/ECDSA/SHA-256 path. `Ed25519`
is accepted only for optional/legacy compatibility. For migration v1, `peerId` is the routing/trust primary key. `deviceId` is signed
payload metadata and may equal `peerId`, but equality is not required. Pairing
is directional: each local host stores its own trust record. Bidirectional sync
requires both hosts to hold active trust records.

Transport credentials are deliberately absent from `TrustedPeer`. They are
resolved by the platform `SyncTransport` using protected host storage keyed by
`peerId`. Identity migration is defined in
`in-repository-identity-migration.md`.

## 12. App Adapter Mapping

`OrgSyncCoreClient` remains the app boundary during migration.

| Existing app operation | New API mapping |
|---|---|
| `start` / `stop` | host listener/scheduler plus `SyncCore.start/stop` |
| `flushNow` | `flushDue` |
| `submitOutgoing` | build `SyncMessage`, then `submit` |
| `observeIncomingCommands` | `claimIncoming`, map to `VerifiedIncomingCommand` |
| `reportResult` | `resolveIncomingRoute`; if missing, expose `RESULT_ROUTE_NOT_FOUND` via delivery state; otherwise build result `SyncMessage`, `submit`, then `ackIncoming` |
| `observeDeliveryState` | event stream/page mapped to existing snapshot |
| `metricsSnapshot` | `SyncMetrics` adapter |

Command messages receive `T0 + 24h` expiration. Result messages receive
`T0 + 7d` expiration.

## 13. Compatibility Rules

- Legacy `commandId` maps to `messageId`.
- Legacy enum/wire values are decoded at the host codec boundary.
- Legacy envelope v1 remains accepted during migration.
- New envelope schema is versioned independently from topic schema.
- Internal state enum names are not serialized as the network protocol.
- No public API exposes Room entity, JDBC row, Android `Context`, HTTP class, or
  `ClockResultPayload`.

## 14. API Review Checklist

- [x] Public values and outcomes are defined.
- [x] Store, transport, time, random, and topic-policy ports are defined.
- [x] Incoming claim/ack semantics are defined.
- [x] Error, metrics, and health contracts are defined.
- [x] Trust identity semantics are defined.
- [x] Existing `OrgSyncCoreClient` mapping is defined.
- [x] Android/platform types are excluded from the core API.

