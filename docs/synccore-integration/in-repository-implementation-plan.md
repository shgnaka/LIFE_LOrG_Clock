# In-repository sync-core Implementation Plan

## 1. Status

- Status: Implemented through PR-7 / Gate 5
- Scope: replace external command-sync core without changing domain/UI behavior
- Inputs: requirements, API contract, test specification, storage plan, threat
  model, and legacy fixtures in this directory/repository

## 2. Implementation Principles

- Tests are written before or with each behavior.
- Each PR was independently buildable; the external path is no longer part of
  the normal build/runtime after cutover.
- No PR removes external dependencies before internal contract parity.
- Core code remains platform-neutral.
- Database changes are additive and migration-tested.
- Security checks occur before durable ingress acceptance.

## 3. PR Sequence

### PR-1: Module and public API

Deliver:

- add `:sync-core` KMP Android/JVM module;
- add public API types and ports from the API baseline;
- add deterministic common test harness;
- add traceability and architecture verification tasks.

Required green IDs:

- ARC-02..06
- QUA-01
- API compile/visibility checks

No queue behavior or app integration in this PR.

### PR-2: Outgoing queue engine

Deliver:

- submit/idempotency/content hash;
- state machine;
- retry/expiration;
- peer FIFO/concurrency;
- dispatch lease;
- cancel/manual retry/query/metrics.

Required green IDs:

- ENG-01..25
- ENG-28..32
- QUA-03, QUA-04

Use `TransactionalTestStore`; no Room dependency.

### PR-3: Durable incoming engine

Deliver:

- verified ingress persistence;
- dedupe/conflict;
- claim/ack/processing lease;
- durable result route;
- topic policy and ingress outcome mapping.

Required green IDs:

- ENG-26
- ING-01..09
- FR-07/08/17/19/20 traceability

### PR-4: Security codecs and verification

Deliver:

- versioned canonical envelope codec;
- legacy v1 decoder;
- ES256 verifier/signer ports with optional Ed25519 legacy verification;
- timestamp, role, and resource-limit enforcement;
- redaction policy.
- pairing v2 identity exchange and deterministic legacy trust classification.

Required green IDs:

- SEC-01..15
- INT-01..03
- INS-04

### PR-5: Android Room/Keystore/transport adapter

Deliver:

- Room schema v3 and migration;
- pre-v3 backup behavior;
- Android Keystore identity;
- pinned TLS ingress/egress;
- release/debug endpoint policy.

Required green IDs:

- DB-01..11
- INS-01
- SEC-05..10, SEC-14 adapter variants

### PR-6: App adapter and parity

Deliver:

- internal `OrgSyncCoreClient` adapter;
- command/result expiration;
- lifecycle mode mapping;
- external/internal contract test suite;
- failure isolation.

Required green IDs:

- ENG-27
- INT-04..09, INT-11
- existing CV/AD/RS/ID/RG suite

External implementation was selectable during parity work. After PR-7, the
normal provider is the in-repository implementation.

### PR-7: Cutover and cleanup

Deliver:

- make internal implementation the only production provider;
- remove `SYNC_CORE_DIR`, composite build, and external coordinates;
- remove reflection/fallback loading;
- update docs and CI;
- retain migration fixtures and compatibility decoder.

Required green IDs:

- ARC-01
- INT-10
- INS-03
- all Gate 2-4 suites

## 4. Branch and Review Rules

- Suggested branch prefix: `codex/sync-core-`.
- One PR owns one sequence item; avoid combining storage migration and cutover.
- Public API change requires API-contract diff in the same PR.
- State/error/wire change requires requirement/test/fixture diff in the same PR.
- Security-sensitive PRs require explicit threat-model mapping in the PR body.

## 5. CI Gates to Add

```text
sync-core-common-tests
sync-core-jvm-tests
sync-core-architecture
sync-core-traceability
sync-core-android-unit
sync-core-android-instrumented
sync-core-security-loop
sync-core-benchmark (non-blocking until baseline is recorded)
```

Blocking from PR-1:

- common/JVM tests
- architecture
- traceability

Blocking from PR-5:

- Android unit/instrumented migration and Keystore tests

Blocking from PR-7:

- security loop
- no-external-dependency build
- complete regression suite

## 6. Rollout

1. Ship internal implementation behind existing build/runtime flag. Done.
2. Run parity in debug/internal distribution. Done through `OrgSyncCoreClient`
   contract and adapter tests.
3. Default internal provider on while sync runtime remains opt-in. Done.
4. Observe migration, queue, ingress, and persistence metrics. Covered by
   metrics, connected adapter inspection, and acceptance gates.
5. Remove external provider from normal build/runtime. Done in Gate 5.

Rollback disables sync runtime/provider execution. It does not downgrade Room v3.

## 7. Definition of Ready

- [x] Requirements and design decisions are frozen.
- [x] Public API and module boundary are defined.
- [x] Acceptance tests have setup, operation, and oracle.
- [x] Legacy wire and schema fixtures exist.
- [x] Storage v3 migration and failure policy are defined.
- [x] Threat model and residual risks are defined.
- [x] Implementation PR sequence and CI gates are defined.
- [x] Existing app boundary and cutover mapping are defined.
- [x] Deferred long-term event-sync questions do not block command-sync core.

## 8. Definition of Done

Implementation is complete after Gate 5 in the requirements baseline. Current
evidence is tracked by `generateSyncCoreAcceptanceAuditReport`,
`verifySyncCoreRepositoryOwnership`, `verifyNoExternalSyncCoreBuild`, security
inspection tasks, and Android connected adapter inspection.
