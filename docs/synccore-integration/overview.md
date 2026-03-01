# Sync-core Integration Overview (M1 Hold State)

## Purpose
This document defines the integration architecture between `org-clock` and external `sync-core` while `sync-core` is still under development. The current branch (`feat/synccore-integration-m1`) intentionally does **not** implement runtime sync behavior; it prepares stable boundaries so implementation can start with low risk once `sync-core` is ready.

## Scope (Current Branch)
- In scope:
  - Integration boundary definition
  - Responsibility split
  - Data flow and failure flow design
  - Acceptance criteria and PR sequencing
- Out of scope:
  - mDNS discovery implementation
  - Key exchange and transport implementation
  - Background workers for sync execution
  - Production UI changes for sync operations

## Fixed Constraints
- LAN-only communication for v1
- No NAT traversal / no STUN/TURN/relay in v1
- Per-device keys for peer trust
- Source of truth remains local org files on each device
- Offline behavior is store-and-apply-later

## Current Architecture Anchors
- App composition root and dependency injection:
  - `app/src/main/java/com/example/orgclock/di/AppGraph.kt`
- Domain clock operations:
  - `shared/src/commonMain/kotlin/com/example/orgclock/domain/ClockService.kt`
- Repository contracts:
  - `shared/src/commonMain/kotlin/com/example/orgclock/data/RepositoryContracts.kt`
- Android SAF-backed persistence:
  - `shared/src/androidMain/kotlin/com/example/orgclock/data/SafOrgRepository.kt`
- Existing notification service:
  - `app/src/main/java/com/example/orgclock/notification/ClockInNotificationService.kt`

## Responsibility Boundary

### sync-core responsibilities
- Peer identity and key lifecycle
- LAN peer discovery and secure transport
- Command envelope validation and signature verification
- Delivery queue, retry policy, and backoff
- Idempotency (command de-duplication by command id)
- Delivery and processing state events

### org-clock responsibilities
- Convert inbound command payloads into clock domain calls
- Resolve `file_name + heading_path` to actionable targets
- Apply clock mutations via existing `ClockService`
- Persist org changes via existing repository and SAF permissions
- Map domain failures to standardized sync result codes
- Surface sync status in app-level UX (later phase)

## Data Flow (Target for Future Implementation)
1. Inbound command arrives from `sync-core` as domain-agnostic payload.
2. org-clock adapter parses payload as `clock.command.v1`.
3. Target resolver finds file and heading context.
4. Adapter executes one of:
   - `startClockInFile`
   - `stopClockInFile`
   - `cancelClockInFile`
5. Domain/repository returns success/failure.
6. Adapter maps result to standardized `sync-core` result status + error code.
7. Result is returned to `sync-core` for remote delivery/observability.

## Failure Handling Model
- Validation failures must be terminal and explicit (`FAILED` + code).
- Duplicate command id must be non-fatal (`DUPLICATE`) and side-effect free.
- Repository conflicts should follow existing retry behavior inside domain service.
- Non-recoverable IO errors should return explicit failure with stable error code.

## M1 Integration Strategy
M1 remains in hold state until `sync-core` is available. When unblocked, implementation starts with a minimal vertical slice:
- Receive single command
- Execute domain mutation
- Return result
- No autonomous background runtime in first pass

## Non-goals to Prevent Scope Creep
- Full LAN transport or key management inside this repository
- Replacing existing notification loop with sync transport
- Building generic sync UI in M1

## Compatibility Expectations
- All existing local clock operations must remain unchanged.
- Notification behavior should not regress as part of sync integration.
- Sync integration should be feature-flag or no-op by default until externally enabled.

## Exit Criteria for Hold State
Integration coding may begin only when all are true:
1. `sync-core` publishes an agreed contract (command/result/state APIs)
2. Payload schema versioning policy is fixed
3. Error code taxonomy is fixed
4. A minimal SDK or stub package is consumable from this app
