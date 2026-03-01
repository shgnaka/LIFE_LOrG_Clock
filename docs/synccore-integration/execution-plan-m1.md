# Execution Plan for `feat/synccore-integration-m1`

## Objective
Prepare `org-clock` for sync-core integration without implementing runtime sync behavior yet.

## Branch Intent
- Branch: `feat/synccore-integration-m1`
- Intent: documentation and contract hardening only
- Explicitly excluded: transport/runtime code changes

## Development Policy for M1
1. No production code paths are changed for sync behavior.
2. Any placeholder references must be documentation-only.
3. Existing app behavior must remain byte-for-byte equivalent in runtime logic.

## Prerequisite Gates (Before Any Future Coding)
All gates must pass before switching from docs-only to code implementation:
1. sync-core API signatures are published and stable.
2. payload schema and result schema are frozen for v1.
3. error code vocabulary is agreed.
4. artifact consumption strategy is fixed (Maven/submodule/local package).

## Future PR Sequence (Once Unblocked)

### PR-1: Adapter boundary skeleton (no-op wiring)
- Add integration interfaces/ports in app layer.
- Bind no-op implementation in DI.
- Ensure behavior unchanged.

### PR-2: Manual command execution slice
- Add debug/manual trigger path.
- Receive one command payload and execute through `ClockService`.
- Report one result payload.

### PR-3: Error taxonomy and mapping hardening
- Centralize exception-to-error-code mapping.
- Add unit tests for all mapped failures.

### PR-4: Delivery/processing observability (minimal)
- Add internal state surface for last result / last error.
- Keep UI scope debug-level only initially.

### PR-5: Background model decision
- Evaluate startup-trigger vs WorkManager.
- Adopt one model with explicit battery constraints.

## Implementation Boundaries to Enforce
- Keep org mutation and conflict retry in existing domain/repository path.
- Do not introduce remote line-index coupling.
- Do not duplicate retry logic that belongs to sync-core.
- Keep transport logic out of this repository.

## Rollback Strategy
If early integration coding regresses behavior:
1. Disable sync integration via feature flag/no-op binding.
2. Retain contract docs and tests.
3. Re-enable only after failing scenario is fixed and covered.

## Traceability Matrix

| Concern | Owner | Source doc |
|---|---|---|
| Responsibility split | org-clock + sync-core | `overview.md` |
| Payload/result contract | org-clock + sync-core | `contract.md` |
| Test acceptance | org-clock | `test-acceptance.md` |

## Done Criteria for This Branch
- `overview.md` exists and is internally consistent.
- `contract.md` defines all required fields and mapping rules.
- `test-acceptance.md` covers happy-path and failure-path checks.
- README points to integration docs.
- No runtime code changes for sync behavior.
