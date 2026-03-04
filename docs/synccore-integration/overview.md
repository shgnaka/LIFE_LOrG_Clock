# Sync-core Integration Overview (M2 Runtime Baseline)

## Purpose
This document defines the active integration architecture between `org-clock` and `sync-core`.

As of branch `feat/synccore-integration-m1` (commit `1541eff`), runtime integration is already present behind feature-flag and runtime controls. The current objective is M2 hardening: test completeness, behavior guarantees, and rollout safety.

## Scope (Current State)
- In scope:
  - Command payload parsing (`clock.command.v1`)
  - Domain execution adapter (`ClockCommandExecutor`)
  - Result reporting (`clock.result.v1`)
  - Runtime controls (`off/standard/active`) and debug snapshot
  - Delivery state and metric observation through `OrgSyncCoreClient`
- Out of scope:
  - Production-grade transport implementation in this repository
  - Peer discovery UX and pairing UX
  - Default-on rollout without acceptance completion

## Fixed Constraints
- LAN-only communication for v1
- No NAT traversal / no STUN/TURN/relay in v1
- Per-device keys for peer trust (sync-core responsibility)
- Source of truth remains local org files on each device
- Sync path remains disabled unless integration feature flag is enabled

## Architecture Anchors
- Composition root:
  - `app/src/main/java/com/example/orgclock/di/AppGraph.kt`
- Adapter and contract:
  - `app/src/main/java/com/example/orgclock/sync/ClockCommandExecutor.kt`
  - `app/src/main/java/com/example/orgclock/sync/SyncContracts.kt`
- Runtime coordinator:
  - `app/src/main/java/com/example/orgclock/sync/SyncIntegrationService.kt`
- Client boundary:
  - `app/src/main/java/com/example/orgclock/sync/SyncCoreClient.kt`
  - `app/src/synccore/java/com/example/orgclock/sync/SynccoreEngineClientFactory.kt`
- Domain and repository:
  - `shared/src/commonMain/kotlin/com/example/orgclock/domain/ClockService.kt`
  - `shared/src/commonMain/kotlin/com/example/orgclock/data/RepositoryContracts.kt`

## Responsibility Boundary

### sync-core responsibilities
- Peer identity and key lifecycle
- Discovery and secure transport
- Delivery queue, retry policy, and backoff
- Command dispatch lifecycle and delivery state events

### org-clock responsibilities
- Parse and validate clock command payloads
- Resolve `file_name + heading_path` to local heading target
- Execute domain clock operations through `ClockService`
- Persist org mutations through repository contracts
- Map execution failures to stable `ClockErrorCode`
- Publish per-command result payloads to sync-core

## Data Flow (Implemented Baseline)
1. Command payload is received (`manual` path or client incoming poll path).
2. Adapter validates schema and required fields.
3. Target resolver locates file + heading.
4. Adapter executes `start / stop / cancel` on domain service.
5. Result is mapped to `clock.result.v1`.
6. Result is reported to sync-core client.
7. Snapshot is refreshed with latest metrics and delivery state.

## Compatibility Expectations
- Existing local UI clock flows must remain unchanged when sync is disabled.
- Notification behavior must remain unchanged when sync is disabled.
- Unknown/invalid payloads must fail deterministically (`rejected` + `VALIDATION_FAILED`).
- Duplicate `command_id` must be side-effect free.

## M2 Hardening Exit Criteria
1. Contract validation tests cover malformed timestamp and missing field cases.
2. Adapter/domain tests cover start/stop/cancel success and failure mappings.
3. Runtime service tests cover mode transitions and poll processing.
4. Snapshot updates are deterministic across success and error branches.
5. Documentation reflects runtime reality (no docs-only hold wording remains).
