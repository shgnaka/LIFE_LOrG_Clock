# Execution Plan for `feat/synccore-integration-m1` (M2 Hardening)

## Objective
Stabilize and harden the existing sync runtime baseline without expanding feature scope.

## Branch Intent
- Branch: `feat/synccore-integration-m1`
- Intent: contract/test hardening and runtime safety improvements
- Excluded: transport feature expansion, pairing UX, default-on rollout

## Current Baseline
Already implemented in this branch:
- Sync command executor (`ClockCommandExecutor`)
- Manual execution path and polling path (`SyncIntegrationService`)
- Runtime mode controls (`Off/Standard/Active`)
- Optional sync-core engine binding via `app/src/synccore`

## PR Sequence

### PR-1: Documentation alignment
- Remove legacy "docs-only hold" language.
- Align overview/acceptance with actual code paths.
- Keep boundaries and non-goals explicit.

### PR-2: Contract and behavior test completion
- Add missing unit tests:
  - `clock.stop` success
  - `clock.cancel` success
  - invalid heading level mapping
  - malformed `requested_at` validation
  - polling and runtime mode transition behavior
- Keep schema and error vocabulary unchanged.

### PR-3: Runtime safety and observability hardening
- Ensure snapshot updates are deterministic in success/failure branches.
- Add bounded in-memory retention constants where state accumulates.
- Add debug logging points for reject/failure/reporting issues.

## Implementation Boundaries to Enforce
- No schema-breaking changes to `clock.command.v1` / `clock.result.v1`.
- Do not bypass `ClockService` for org mutations.
- Do not move transport concerns into this repository.
- Keep sync path feature-flag-safe by default.

## Rollback Strategy
If hardening introduces regression:
1. Keep feature flag disabled in runtime environments.
2. Revert only hardening delta while preserving contract docs.
3. Re-enable after regression test is added and green.

## Done Criteria
- Docs and code behavior are consistent.
- Sync unit tests pass for command execution and runtime service.
- No regressions in existing non-sync local flows.
