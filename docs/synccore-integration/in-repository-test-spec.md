# In-repository sync-core Test Specification

## 1. Status and Authority

- Status: Test Specification v1
- Requirements source:
  `docs/synccore-integration/in-repository-requirements.md`
- Scope: every `ARC-*`, `ENG-*`, `ING-*`, `SEC-*`, `DB-*`, `INT-*`,
  `QUA-*`, and `INS-*` acceptance-test ID in the requirements source
- Rule: an implementation is not complete because a similarly named test
  passes. The test MUST use the setup, operation, and oracle defined here.

This document defines tests, not production implementation. Test class and
method names are normative enough to establish ownership but MAY change if the
catalog ID remains attached to the test.

## 2. Test Layers and Locations

| Layer | Intended location | Runtime |
|---|---|---|
| Core contract | `sync-core/src/commonTest` | Kotlin common test + coroutine test scheduler |
| JVM core/store | `sync-core/src/jvmTest` | JUnit/Kotlin test + temporary SQLite database |
| Android adapter/store | `app/src/test` or `app/src/androidTest` | JVM where possible; device only for Android APIs |
| Desktop adapter | `desktopApp/src/test` | JVM test |
| Integration | `app/src/test`, `app/src/androidTest`, dedicated Gradle tasks | fake transport first, loopback TLS only where required |
| Architecture/inspection | `scripts/` verification task or Gradle verification task | static dependency/source/artifact inspection |
| Performance | dedicated non-default benchmark task | controlled JVM/device environment |

Tests MUST NOT depend on execution order. Each test owns its database,
dispatcher, scope, port, key material, and temporary directory.

## 3. Common Deterministic Harness

### 3.1 Fixed values

Unless a test states otherwise:

| Name | Value |
|---|---|
| `T0` | `2026-03-01T12:00:00Z` / `1772366400000` epoch ms |
| Local peer | `peer-local` |
| Remote peer A | `peer-a` |
| Remote peer B | `peer-b` |
| Message IDs | `msg-001`, `msg-002`, ... |
| Topic | `clock.command.v1` |
| Payload | `{"fixture":"payload-001"}` |
| Command expiration | `T0 + 24h` |
| Result expiration | `T0 + 7d` |

### 3.2 Required test doubles

`ManualClock`

- Returns a mutable UTC epoch value.
- Advances only when the test calls `advanceBy`.
- Production code under test MUST NOT call system time in a core test.

`SequenceRandom`

- Returns a configured sequence in `[0.0, 1.0]`.
- Retry tests use `0.0`, `0.5`, and `1.0` to prove minimum, midpoint, and
  maximum additive jitter.

`ScriptedTransport`

- Records message, peer, start time, completion time, and in-flight count.
- Returns queued `accepted`, `rejected`, `retryable`, timeout, or cancellation
  outcomes.
- Supports a barrier so concurrency and cancellation can be asserted without
  real sleep or sockets.

`TransactionalTestStore`

- Implements the same store port as production.
- Commits changes atomically to immutable snapshots.
- Supports reopen from the last committed snapshot.
- Supports one-shot failure injection at read, insert, transition, event,
  inbox, cleanup, and commit boundaries.
- Exposes committed rows for assertions; tests MUST NOT inspect private engine
  memory as a substitute for persisted state.

`RecordingObserver`

- Collects typed events and metrics.
- Can suspend, throw, or cancel to verify observer isolation.

`TestKeySet`

- Generates ES256 keys at test start from deterministic test-only material
  where supported, or stores the generated public key and `alg` with the test context. Ed25519 keys are generated only for optional/legacy compatibility cases.
- Private test keys MUST NOT be production fixtures.

### 3.3 Scheduler rules

- Core coroutine tests use `runTest` and its virtual scheduler.
- A core test containing `Thread.sleep`, real `delay`, wall-clock polling, or
  an unbounded timeout fails `QUA-01`.
- Concurrent operations start behind a barrier and are released in one
  scheduler turn.
- All child jobs and flows MUST be completed or cancelled before test exit.

### 3.4 Standard retry oracle

For attempt number `n`, where initial dispatch is attempt 1:

```text
baseDelay(n) = min(1000ms * 2^(n - 1), 60000ms)
jitter(n, r) = floor(baseDelay(n) * 0.20 * r)
nextAttempt = failureTime + baseDelay(n) + jitter(n, r)
```

The attempt that failed is used for the calculation. After attempt 10 fails,
there is no next attempt and state is `failed/RETRY_EXHAUSTED`.

### 3.5 Persistence oracle

A persistence test passes only when:

1. the first engine/store instance is closed;
2. all in-memory references are discarded;
3. a new instance opens the same committed database/snapshot;
4. behavior is asserted through public ports and persisted rows.

Reusing the same object does not prove restart durability.

### 3.6 Security oracle

Rejected ingress MUST prove all of the following:

- no inbox row committed;
- no consumer callback invoked;
- no result route created;
- stable error/status returned;
- rejection metric incremented exactly once;
- log capture contains no payload, signature, key, secret, or credential.

## 4. Engine Test Catalog

### 4.1 Submission and state

| ID | Test owner / method | Setup and operation | Required oracle |
|---|---|---|---|
| ENG-01 | `SubmitContractTest.validMessage` | Submit one valid message; store pauses before commit | caller remains incomplete before commit; afterwards one `pending` row and one event |
| ENG-02 | `SubmitContractTest.sameIdSameContent` | Submit identical message twice | both idempotent success; one row/event sequence; counters not doubled |
| ENG-03 | `SubmitContractTest.sameIdDifferentContent` | Change payload with same ID | `MESSAGE_ID_CONFLICT`; original row byte-equivalent and no new event |
| ENG-04 | `SubmitValidationTest.invalidInputs` | Parameterize blank ID/topic/peer, invalid times, 96 KiB and 96 KiB+1 payload | boundary accepted; invalid/oversize rejected before store call |
| ENG-05 | `DispatchStateTest.accepted` | One due message, transport accepted | exact states `pending, dispatching, acked`; one attempt |
| ENG-06 | `DispatchStateTest.rejected` | Transport terminal reject | exact states `pending, dispatching, rejected`; no scheduled retry |
| ENG-07 | `RetryPolicyTest.jitter` | Retryable failure with random 0/.5/1 | exact formula in 3.4; attempt and error committed atomically |
| ENG-08 | `RetryPolicyTest.boundaryTime` | Advance to 1 ms before and exactly next attempt | no call before; one call at boundary |
| ENG-09 | `RetryPolicyTest.classification` | Parameterized timeout, connection, 408/425/429/5xx and terminal statuses | each maps to required retry/terminal state and stable code |
| ENG-10 | `RetryPolicyTest.exhaustion` | Fail attempts 1 through 10 | attempts 1-9 schedule; attempt 10 becomes `failed/RETRY_EXHAUSTED` |
| ENG-11 | `ExpirationTest.expiredBeforeDispatch` | Expiration `T0`, clock `T0` | no transport call; one `expired` transition |
| ENG-12 | `ExpirationTest.expiresInFlight` | Start before expiration, complete retryable after expiration | response recorded; no retry; terminal `expired` |
| ENG-13 | `StateMachineTest.validTransitions` | Exercise every transition listed in FR-06 | persisted state/event includes attempt, code, detail, and exact clock time |
| ENG-14 | `StateMachineTest.invalidTransitions` | Parameterize every terminal-to-active transition except manual retry | typed transition error; row/events unchanged |

### 4.2 Lifecycle, ordering, and control

| ID | Test owner / method | Setup and operation | Required oracle |
|---|---|---|---|
| ENG-15 | `LifecycleTest.idempotentStartStop` | start/start, stop/stop, start again | at most one worker/listener; stop prevents new dispatch; restart creates one |
| ENG-16 | `FlushTest.concurrentFlush` | Two flush calls released together for one due message | one transport call and one state sequence |
| ENG-17 | `OrderingTest.samePeerFifo` | Three same-peer rows; first retries | only first dispatched until acked; then second and third in registration order |
| ENG-18 | `OrderingTest.crossPeerConcurrency` | Five peers behind transport barrier | maximum global in-flight 4 and per-peer in-flight 1 |
| ENG-19 | `DispatchLeaseTest.expiredLeaseRecovery` | Persist dispatching lease ending before reopen clock | recovered pending, attempt incremented on resend, recovery metric +1 |
| ENG-20 | `DispatchLeaseTest.liveLeaseRecovery` | Persist dispatching lease after reopen clock | no resend until exact lease boundary |
| ENG-21 | `ManualRetryTest.failedMessage` | Retry exhausted then manual retry | same ID/content, attempt reset to 0, new pending event, old history retained |
| ENG-22 | `CancellationTest.byState` | Parameterize pending, retry_wait, dispatching, terminal | exact FR-13 behavior; dispatching response cannot schedule retry after cancel |
| ENG-23 | `QueueQueryTest.paginationAndFilters` | Mixed peers/topics/states with stable registration sequence | stable page cursor, no duplicate/skip, filters correct, payload decoder never called |
| ENG-24 | `StoreFailureTest.atomicity` | Inject each store fault point | typed persistence error; committed state and metrics remain transaction-consistent |
| ENG-25 | `ObserverIsolationTest.slowAndFailing` | Slow observer then throwing observer | dispatch completes; event order retained per observer policy; worker survives |
| ENG-26 | `IncomingLeaseTest.expiredProcessingLease` | Claim inbox, close consumer, reopen after 60s | message reclaimed once with incremented delivery count |
| ENG-27 | `RuntimeModeTest.transitions` | All pairwise Off/Standard/Active transitions | listener, periodic work, active loop counts exactly match FR-18 |
| ENG-28 | `MetricsTest.mixedOutcomes` | Submit/accept/reject/retry/ingress reject/lease recovery/store error | every metric equals persisted facts and per-peer last success |
| ENG-29 | `CapacityTest.outgoingLimits` | Fill 500 for A and 2,000 total, then submit | exact boundary accepted; overflow `QUEUE_FULL`; no active eviction |
| ENG-30 | `ConcurrencyTest.cancellationAndRaces` | Repeated submit/flush/cancel under virtual barriers | one row per ID, legal transitions only, cancellation propagated |
| ENG-31 | `ErrorContractTest.stableCodes` | Trigger one case per OR-02 code | exact typed code; detail sanitized and <=512 chars |
| ENG-32 | `ObservationContractTest.typedModels` | Observe state and metrics through public API | typed values contain required fields; no string/log parsing |

## 5. Ingress and Security Test Catalog

| ID | Test owner / method | Setup and operation | Required oracle |
|---|---|---|---|
| ING-01 | `IngressDurabilityTest.commitBeforeSuccess` | Pause inbox commit while receive is active | no 202 before commit; after commit one inbox row and 202 |
| ING-02 | `IngressDurabilityTest.restartBeforeAck` | Commit, claim, close without ack, reopen after lease | one re-delivery through public claim port |
| ING-03 | `IngressDedupTest.sameContent` | Deliver same signed envelope twice | both 202; one inbox/route; one consumer publication |
| ING-04 | `IngressDedupTest.conflictingContent` | Same sender/ID, altered payload and valid new signature | 409/`REPLAY_CONFLICT`; original row unchanged |
| ING-05 | `IncomingAckTest.outcomes` | Parameterize processed/rejected/retry_later | terminal rows not reclaimed; retry_later reclaimed after lease |
| ING-06 | `ResultRouteTest.restart` | Receive command, reopen store, report result | result queued to original peer with stable result ID |
| ING-07 | `ResultRouteTest.missing` | Report result for unknown command | no outgoing row; `RESULT_ROUTE_NOT_FOUND` observable |
| ING-08 | `IngressHttpMappingTest.allOutcomes` | Trigger every FR-19 row | exact status and `Retry-After` presence/absence |
| ING-09 | `TopicPolicyTest.beforePersistence` | Parameterize supported/unsupported topic and role | policy called before store; unsupported leaves no row/route |
| SEC-01 | `EnvelopeSignatureTest.valid` | Sign canonical input with trusted ES256 key | accepted and trusted peer identity propagated |
| SEC-02 | `EnvelopeSignatureTest.unknownAndRevoked` | Valid signatures from unknown then revoked key | security oracle in 3.6 with `PEER_NOT_TRUSTED` |
| SEC-03 | `EnvelopeSignatureTest.tampering` | Modify each signed field and payload after signing | every variant `SIGNATURE_INVALID`; no persistence |
| SEC-04 | `EnvelopeTimestampTest.boundaries` | `T0 +/- 300s`, then one ms outside | inclusive boundary accepted; outside `TIMESTAMP_OUT_OF_RANGE` |
| SEC-05 | `EndpointPolicyTest.cleartext` | Resolve `http://` target | reject before DNS/socket/transport invocation |
| SEC-06 | `TlsPinningTest.certificatePins` | Loopback TLS with matching and different cert | only matching SHA-256 pin connects; mismatch terminal |
| SEC-07 | `IngressSizeLimitTest.boundaries` | Encoded 128 KiB and +1; decoded 96 KiB and +1 | boundaries accepted, +1 returns 413 without persistence |
| SEC-08 | `RateLimitTest.window` | 121 requests from one IP at T0; advance 60s | first 120 pass limiter, 121 gets 429; next window accepts |
| SEC-09 | `InboxCapacityTest.full` | Fill exactly 2,000 unprocessed rows then receive | next gets 503/`INBOX_FULL`; existing rows unchanged |
| SEC-10 | `SecretRedactionTest.allSinks` | Inject unique secret markers into every field/error | no marker in logs, DB metadata, metrics, or response; detail <=512 |
| SEC-11 | `CanonicalizationTest.fieldOrder` | Reorder JSON object fields and whitespace | canonical bytes/hash identical and signature valid |
| SEC-12 | `EnvelopeVersionTest.legacyAndCurrent` | Decode legacy fixtures and current fixtures | supported versions accepted unambiguously; downgrade mutation rejected |
| SEC-13 | `PeerAuthorizationTest.viewerMutation` | Viewer signs `clock.command.v1` | 403/`PEER_NOT_AUTHORIZED`; no persistence |
| SEC-14 | `UnsignedEndpointTest.buildVariants` | Inspect release manifest/routes; exercise debug route configurations | release route absent; debug requires loopback and explicit opt-in |
| SEC-15 | `DomainTimestampBoundaryTest.oldPayloadFreshEnvelope` | Old `requested_at`, fresh valid envelope | transport accepts; upper-layer validator owns domain rejection |

## 6. Persistence and Retention Test Catalog

| ID | Test owner / method | Setup and operation | Required oracle |
|---|---|---|---|
| DB-01 | `QueueReopenTest.allFields` | Persist each active state/error, close and reopen | all message/state/attempt/time/error fields equal |
| DB-02 | `RetryReopenTest.nextAttempt` | Persist retry wait and reopen before boundary | no early dispatch; exact-boundary dispatch |
| DB-03 | `InboxReopenTest.messageAndRoute` | Persist unprocessed inbox and route, reopen | claim and result routing both succeed |
| DB-04 | `RoomMigrationTest.v1ToCurrent` | Create DB from `schema-v1.sql` with sentinel rows, run migrations | all sentinel rows/values preserved; new schema valid |
| DB-05 | `RoomMigrationTest.v2ToCurrent` | Create DB from `schema-v2.sql` with all table sentinels | all rows and replay key preserved |
| DB-06 | `RoomMigrationTest.failureIsNonDestructive` | Inject invalid/failing migration on copied fixture DB | open fails typed; original DB bytes/tables remain |
| DB-07 | `RetentionTest.boundariesAndCaps` | Rows immediately before/at/after TTL and over max | only eligible terminal oldest rows removed; boundary rule explicit (`< cutoff`) |
| DB-08 | `RetentionTest.activeRows` | Old pending/retry/dispatching/inbox rows | cleanup removes none |
| DB-09 | `RetentionTest.cleanupFailure` | Fail cleanup transaction then dispatch due message | dispatch succeeds; persistence error metric +1; no partial delete |
| DB-10 | `PeerRevocationTest.pendingRows` | Revoke peer with active/terminal rows and route | credentials/routes disabled; active rows transition rejected; history retained |
| DB-11 | `CapacityTransactionTest.concurrentWriters` | Writers race at total/per-peer/inbox boundaries | committed count never exceeds limit; no lost/duplicate rows |

## 7. Identity and Pairing Migration Catalog

| ID | Test owner / method | Setup and operation | Required oracle |
|---|---|---|---|
| IDN-01 | `LegacyTrustMigrationTest.transportCredentialOnly` | Migrate `orgclock-https-v1` value | protected transport reference created; signing key null; mutation disabled |
| IDN-02 | `LegacyTrustMigrationTest.signingKeyOnly` | Migrate valid legacy Ed25519 X.509 public key | exact key and `signingAlg=Ed25519` preserved; no fabricated transport credential |
| IDN-03 | `LegacyTrustMigrationTest.incompleteValues` | Parameterize invite prefix and malformed/base64 wrong-key values | incomplete status; no ingress authorization |
| IDN-04 | `PairingV2ContractTest.bidirectionalIdentity` | Complete pinned invitation/request/response exchange | each host stores remote signing key separately from its credential |
| IDN-05 | `PairingV2ContractTest.roleReduction` | Request Full while host grants Viewer, then attempt mutation | Viewer stored; mutation rejected; no silent role elevation |
| IDN-06 | `PeerKeyChangeTest.requiresRepair` | Present changed valid key for existing peer through repair | trust remains inactive/old key retained; explicit re-pair required |
| IDN-07 | `PeerRevocationTest.disablesBothCredentials` | Revoke ready peer then sign/connect with old material | signing authorization and transport authentication both fail |
| IDN-08 | `CredentialStorageMigrationTest.removesPlaintextDuplicate` | Migrate legacy preference record | secret exists only in protected storage; legacy field cleared/redacted |

## 8. Architecture, Integration, Quality, and Inspection Catalog

### 8.1 Architecture

| ID | Verification implementation | Operation | Required oracle |
|---|---|---|---|
| ARC-01 | `verifySyncCoreRepositoryOwnership` | clean build with `SYNC_CORE_DIR` unset and external coordinates denied | required build tasks resolve no external sync-core |
| ARC-02 | `verifySyncCoreDependencies` | inspect `:sync-core` dependency graph and bytecode references | no Android SDK, Room, app, or desktop dependency |
| ARC-03 | `verifySyncCorePublicApi` | Kotlin binary API/source visibility dump | only approved `api` package is public |
| ARC-04 | `:sync-core:allTests` | compile/test Android and JVM targets | both consume same common tests and pass |
| ARC-05 | `verifySyncCoreDependencyDirection` | scan domain/UI imports and Gradle graph | no internal core types outside adapters |
| ARC-06 | `verifySyncCoreTestIsolation` | run common tests with Android/runtime/network unavailable | all common tests pass |

### 8.2 Integration

| ID | Test owner / method | Operation | Required oracle |
|---|---|---|---|
| INT-01 | `LegacyWireCompatibilityTest.commandFixture` | decode stored command/envelope fixture | exact IDs/topic/payload/domain fields |
| INT-02 | `LegacyWireCompatibilityTest.resultFixture` | encode result and decode with legacy codec/fixture expectations | legacy field names/enums/timestamp units |
| INT-03 | `ProtocolEvolutionTest.optionalAndUnknownVersion` | add unknown optional field; change required version | optional accepted; unsupported version stable reject |
| INT-04 | `CommandResultRoundTripTest.restartBetween` | receive, restart, execute/report, flush | result reaches original peer once logically |
| INT-05 | `HostRuntimeIntegrationTest.modeTransitions` | real host adapters with fake scheduler/service facade | listener/worker lifecycle matches mode |
| INT-06 | `FailureIsolationTest.databaseUnavailable` | make sync DB unavailable, perform local clock mutation | local mutation succeeds; sync typed error visible |
| INT-07 | `OrgSyncCoreClientContractTest` | run same black-box suite against external and internal factories | outcomes/state/metrics satisfy same contract |
| INT-08 | `SyncDisabledRegressionTest` | run existing local UI/notification/file tests with integration disabled | baseline observations unchanged |
| INT-09 | `UnsupportedProtocolTest` | send unsupported topic/envelope version | stable rejection; listener remains healthy |
| INT-10 | `verifyNoExternalSyncCoreBuild` | clean checkout build commands from AR-04 | all pass without external repo/artifact |
| INT-11 | `AdapterExpirationTest.commandAndResult` | submit command/result at `T0` | exact expirations `T0+24h` and `T0+7d` |

### 8.3 Quality and inspection

| ID | Verification implementation | Operation | Required oracle |
|---|---|---|---|
| QUA-01 | `verifySyncCoreTestDeterminism` | source scan plus common tests repeated 20 times | no forbidden timing/socket APIs; identical outcomes |
| QUA-02 | `syncCoreBenchmark` | warmup 20, measure 100 iterations per QR-02 case | report p50/p95/max and environment; p95 within targets |
| QUA-03 | `CancellationContractTest` | cancel at transport/store/observer suspension points | cancellation propagates; transactions consistent |
| QUA-04 | `ConcurrencyStressTest` | seeded 10,000-operation schedule across 100 seeds | no illegal transition, duplicate in-flight, loss, or deadlock |
| QUA-05 | `verifySyncCoreTestTraceability` | parse requirement and test-spec IDs | no missing, duplicate, orphan, or blank oracle |
| INS-01 | `inspectAndroidKeyStorage` | static scan + instrumented key generation/signing | private key non-exportable and absent from DB/files |
| INS-02 | `inspectDesktopKeyStorage` | inspect file ACL/encryption and attempt unprivileged read | configured protection enforced; no plaintext key |
| INS-03 | `verifyExternalReferencesRemoved` | search code/docs/lockfiles/build graph after Gate 5 | no external coordinate, directory flag, or composite build |
| INS-04 | `SecretRedactionIntegrationTest` | capture logs across submit/receive/failures with markers | no prohibited marker in output |

## 9. Test Data and Fixture Rules

- Legacy fixtures under `app/src/test/resources/synccore/legacy-v1` are
  immutable after Requirements v1 approval. A necessary correction requires a
  new fixture version and a compatibility rationale.
- Boundary payload generators MUST count UTF-8 bytes, not Kotlin characters.
- Database fixtures MUST contain sentinel rows in every table, nullable and
  non-null variants, retry count, future/expired times, and error fields.
- Security tests generate real signatures. The shape-only signature in the
  legacy JSON fixture MUST NOT be treated as cryptographic proof.
- Secret-redaction tests use unique canary values so substring absence can be
  asserted across every captured sink.
- Property/stress tests record the seed on failure and support exact replay.

## 10. Pass, Failure, and Flake Policy

- One failed parameterized case fails its catalog ID.
- Retrying a failed test to make a gate green is prohibited.
- A flaky test is a gate failure until the race or nondeterminism is fixed.
- Platform/environment skips require an explicit gate-approved reason. Core,
  JVM, and architecture tests may not be skipped on CI.
- Inspection checks produce a text or JSON artifact containing command,
  timestamp, commit, environment, and result.
- Performance regressions fail their dedicated benchmark gate, not the normal
  correctness unit-test task.

## 11. Required Commands

The implementation MUST expose stable tasks equivalent to:

```bash
./gradlew :sync-core:allTests
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :desktopApp:test
./gradlew verifySyncCoreArchitecture
./gradlew verifySyncCoreTestTraceability
./gradlew syncCoreBenchmark
```

`connectedDebugAndroidTest` is required only for Android Keystore, Room
migration behavior that cannot run on JVM, and release/debug ingress exposure.
All engine semantics remain in common/JVM tests.

Before the Gradle verification task exists, the catalog itself is checked with:

```powershell
pwsh -File scripts/verify-synccore-test-spec.ps1
```

## 12. Completion Rule

The test-definition objective is complete when:

1. every acceptance ID in the requirements document appears exactly once in
   this specification;
2. every ID has an owner, deterministic setup/operation, and observable oracle;
3. every numbered requirement has one or more acceptance IDs;
4. fixture and boundary rules are explicit;
5. execution tasks and gate policy are defined;
6. an automated traceability verifier proves items 1 and 3.
