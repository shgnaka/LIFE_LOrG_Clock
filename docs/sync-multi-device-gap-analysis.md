# Multi-device Sync Gap Analysis

## 1. Status

- Analysis date: 2026-06-13
- Scope: command sync, clock event sync, template sync, viewer delivery,
  pairing/trust, recovery, platform runtime, and in-repository sync-core
- Conclusion:
  - in-repository command sync-core is ready to enter implementation;
  - the product-wide multi-device synchronization design is not yet frozen;
  - the P0 decisions and correctness fixes below must be completed before
    event sync is treated as the primary synchronization path.

This document distinguishes two projects that must not be conflated.

The initial decisions that close the P0 topology, cursor, concurrency,
atomicity, coexistence, and identity questions are frozen in
`docs/sync-multi-device-decisions.md`.

1. `sync-core` internalization replaces the external command-delivery engine.
2. Multi-device convergence replaces command sync with event-log-first state
   synchronization.

The first project can start now. It does not by itself complete the second.

## 2. Documents Reviewed

The review covered all `docs/sync-*.md` documents, `docs/template-sharing.md`,
and `docs/synccore-integration/`, then compared them with the shared, Android,
Desktop, and sync-core implementations.

The main documentation issue is status drift. Phase 1 and much of Phase 2 are
implemented, while several documents still describe them as drafts or open
questions. Conversely, some important three-device and convergence semantics
are absent from both the documents and tests.

## 3. Current Capability Inventory

### Implemented

- Clock event model, local append-only stores, projection, and event recording.
- Android Room and Desktop JDBC event stores.
- Android and Desktop peer trust and per-peer checkpoint stores.
- Android and Desktop event push/fetch/ack runtimes.
- Invalid-event quarantine and basic sync/recovery status.
- HTTPS certificate pinning and pairing credentials for event/template sync.
- Template revision sharing with fast-forward and divergence detection.
- Viewer projection snapshot generation.
- Command sync integration behind flags, currently backed by external
  sync-core when available.

### Prepared but not implemented

- Internal KMP `:sync-core`.
- Durable generic outgoing queue and incoming inbox.
- Pairing v2 with separated signing key and transport credential.
- Room v3 sync-core migration and external dependency removal.

### Not sufficiently specified

- Three or more full peers and event forwarding topology.
- Concurrent clock-operation conflict resolution.
- Stable heading identity and structural synchronization.
- Event retention, compaction, snapshot bootstrap, and long-term replay.
- Mixed-version capability negotiation.
- Unified identity, transport, runtime mode, and observability model across
  command, event, template, and viewer paths.

## 4. P0 Decisions Before Primary Event Sync

### P0-1: Fix the topology model

Current event sync only sends events authored by the directly connected local
device and rejects events whose `deviceId` differs from the paired peer. This
implements direct two-party replication, not general multi-device convergence.

Choose and document one model:

- full mesh: every device pairs and exchanges origin streams with every other
  device;
- hub-and-spoke: a designated host stores and redistributes all origin streams;
- forwarding mesh: peers relay events while preserving origin identity and
  provenance.

The decision must define loop prevention, authorization of relayed events,
origin signatures, peer removal, and whether a device can catch up through a
peer other than the event author.

### P0-2: Replace the cursor model with origin-scoped replication positions

The current cursor is a local database sequence assigned again when a remote
event is appended. It is not a stable event position across devices.

Known correctness risks:

- `nextFetchCursor()` increments an already-exclusive cursor, which can skip an
  event if used.
- Desktop fetch scans a global local cursor, then filters to locally authored
  events. An empty filtered page does not advance the receiver and can
  permanently hide later local events.
- `acceptedCursor` and `rejectedEventIds` are not fully honored before the
  sender advances `lastSentCursor`.
- one global `lastSyncedCursor` is advanced after syncing one peer, so
  `pendingSyncCount` does not represent delivery to all required peers.

Define a durable position per `(originDeviceId, targetPeerId)` or replace
cursor progress with an explicit immutable origin sequence. Add gap detection;
never infer durable acceptance from the last scanned local row.

### P0-3: Define concurrent operation semantics

Lamport counters currently order events only within one author and the
projection replays local database cursor order. That does not define the same
result on every device for concurrent starts, stops, and cancels.

Freeze:

- deterministic total-order tie breaking;
- whether one global active clock or one active clock per heading is allowed;
- stop/cancel target identity, preferably a `session_id`;
- handling of concurrent start/start, start/stop, stop/stop, and cancel/stop;
- whether invalid sequences are rejected, retained as facts, or resolved into
  a deterministic projection issue.

Convergence tests must permute arrival order and still produce identical state.

### P0-4: Define the durable write and projection transaction boundary

The current local path appends the event before writing the org file. This
matches the intended event-first direction, but the operational contract is
still only an issue proposal.

Freeze and test:

- operation success point;
- recovery-required state after event append succeeds and org write fails;
- retry behavior after optimistic file-write conflict;
- crash points between append, read-model update, and org write;
- remote ingress ordering between durable event append and org application.

The remote path should first durably accept an event, then project it. A missing
target file or heading must not force the sender to retransmit indefinitely.

### P0-5: Separate identity and credentials for every sync path

`PeerTrustRecord.publicKeyBase64` currently also stores the encoded HTTPS
transport credential in the event/template path. The internal sync-core
baseline correctly plans to separate signing identity and transport
credential, but that migration must cover the shared peer model rather than
only command sync.

Required decisions:

- stable relationship among `peerId`, `deviceId`, and signing key ID;
- reinstall and restore behavior;
- key rotation versus mandatory re-pairing;
- revocation propagation and retained event authorship;
- protected storage on Android and Desktop;
- whether event/template messages receive origin signatures.

### P0-6: Define coexistence and double-application prevention

During migration, the same user action can travel through command sync and
event sync. The documents require avoiding duplicate application but do not
define a cross-path operation identity.

Add one immutable operation/session identifier shared by the command and event
representations, or ensure only one path is enabled for a peer capability set.
Define the exact feature-flag matrix, cutover criteria, and rollback behavior.

## 5. P1 Product and Protocol Work

### P1-1: Finalize clock event schema v1

The schema is implemented while its document remains a draft. Resolve
`fileName` optionality, signature policy, invalid-input policy, and the
`headingPath` deprecation boundary. Add `sessionId` before broader rollout.

### P1-2: Introduce stable heading identity

Adopt the documented hybrid direction: event-created `headingId` persisted in
the org heading property. Specify migration of existing headings, duplicate or
missing IDs, copy/paste behavior, rename/move/tombstone events, and fallback
removal. Structural sync must not start before this is complete.

### P1-3: Define retention, compaction, and bootstrap

Specify:

- how a new or long-offline device obtains history;
- event retention versus permanent audit requirements;
- deterministic snapshots and snapshot hashes;
- compaction safety relative to every peer checkpoint;
- recovery when a requested cursor has already been compacted;
- schema-versioned replay and projection migration.

### P1-4: Add protocol and capability negotiation

Pairing must exchange supported event schemas, topics, transport versions,
roles, maximum batch sizes, snapshot support, and required/optional features.
Unknown required capability must fail closed; optional capability must permit
mixed-version operation.

### P1-5: Unify the transport strategy

There are currently separate mechanisms for signed command envelopes and
HTTPS-secret event/template calls. Decide whether event and template traffic
will move onto the generic internal sync-core message API. Until then, define
the shared security minimum, port ownership, lifecycle, retry semantics, and
credential migration for both stacks.

### P1-6: Complete recovery as an executable workflow

Implement and test:

- projection rebuild and org-file rebuild with backup;
- quarantine inspection, retry, discard, and peer isolation;
- event-log integrity check;
- snapshot export for support;
- behavior when an event references a missing file or heading;
- recovery without deleting the primary event log.

`Reload from disk` is useful for external file changes but is not equivalent to
rebuilding org projection from the event log.

### P1-7: Define template and viewer distribution beyond one host

For templates, define conflict retention, user resolution, deletion/tombstone,
history retention, and whether revisions can be forwarded.

For viewers, define authentication, snapshot version/ETag, stale-state
indication, history limits, revocation, and whether a viewer is bound to one
host or can fail over.

### P1-8: Normalize runtime modes

Documents use `Off`, `ForegroundPreferred`, `Periodic`, `ManualOnly`; command
sync code and internal requirements use `Off`, `Standard`, `Active`; Desktop
event sync uses `Host`, `Listener`. Define separate axes for:

- enablement;
- scheduling policy;
- listener/host capability;
- foreground/background constraints.

Then map Android, Desktop, iOS, and macOS explicitly.

## 6. P2 Operational Work

- Endpoint discovery and endpoint rotation, including mDNS and stale address
  handling.
- Time-skew diagnostics and policy for user-visible clock timestamps.
- Battery, bandwidth, queue, batch, and history limits based on measurements.
- Backup/restore rules for event logs, trust data, credentials, and device IDs.
- Privacy and at-rest encryption policy for event and template data.
- Per-peer health: origin positions, lag, last attempt/success, quarantine,
  protocol version, and capability set.
- Structured diagnostic export with secrets and payloads redacted.
- iOS/macOS lifecycle implementation after semantic parity is proven.

## 7. Required Test Expansion

Before event sync becomes primary, add deterministic tests for:

1. Three-device convergence under all selected topology routes.
2. Partition, independent writes, reconnect, and repeated retries.
3. Every permutation of concurrent start/stop/cancel arrival.
4. Cursor gaps, empty filtered pages, partial acceptance, rejected IDs, and
   pagination over more than one batch.
5. Crash injection at every durable-write/projection boundary.
6. New-device bootstrap, snapshot restore, and compacted-history recovery.
7. Revoke/re-pair/key-change and credential migration.
8. Mixed protocol/schema/capability versions.
9. Command/event coexistence without double application.
10. Property-based replay: duplicate and reordered delivery produces the same
    projection on every peer.
11. Long-running queue, storage, battery, and memory limits.
12. Real Android-to-Desktop HTTPS integration with pinned certificates.

The current runtime tests mainly cover one peer and one batch. They are useful
unit tests but do not establish multi-device convergence.

## 8. Documentation Corrections

The following stale states should be corrected as implementation work starts:

- `sync-architecture-next.md`: heading ID, direct-edit policy, storage backend,
  viewer path, and some recovery questions now have decision documents.
- `sync-migration-plan.md`: storage backend is decided and Phase 1/2 work is
  substantially implemented; atomicity remains to be promoted to a decision.
- `sync-event-schema-v1-draft.md`: implementation exists; remaining questions
  need resolution and the schema must become a versioned baseline.
- `sync-peer-trust-model.md`: pairing exists, but the public-key field overload
  and pairing v2 migration must be reflected.
- `sync-phase1-issue-breakdown.md` and `sync-phase2-issue-breakdown.md`: convert
  from prospective issue lists to an implementation ledger.
- `sync-platform-runtime-matrix.md`: align terminology with actual runtime
  modes and the internal sync-core requirements.
- absolute `/home/...` documentation links must be replaced with repository
  relative links.

## 9. Recommended Execution Order

1. Start internal sync-core PR-1 because its command-delivery requirements and
   acceptance gates are ready.
2. In parallel, freeze P0-1 through P0-6 as event-sync decision records.
3. Correct cursor/checkpoint semantics and add three-device convergence tests.
4. Migrate shared trust identity to pairing v2 and separated credentials.
5. Finalize event schema/session identity and deterministic projection rules.
6. Add bootstrap/retention/capability negotiation.
7. Move event/template traffic onto the generic core, or explicitly accept and
   maintain two transport stacks.
8. Cut over from command sync only after the expanded convergence and recovery
   gates pass.

## 10. Readiness Gate

Product-wide multi-device sync is ready for primary use only when:

- all P0 decisions are frozen and implemented;
- three-device convergence and partition tests are green;
- no cursor/checkpoint path can silently skip accepted or pending data;
- command/event coexistence cannot double-apply an operation;
- identity, revocation, and credential migration are complete;
- bootstrap, compaction, rebuild, and mixed-version behavior are tested;
- user-visible per-peer lag and recovery actions are available.
