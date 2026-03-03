# Sync-core Integration Test and Acceptance Criteria (M2)

## Purpose
Define objective acceptance criteria for the current runtime baseline and hardening work.

## Test Layers
1. Contract validation tests
2. Adapter/domain unit tests
3. Runtime service unit tests
4. Regression guards (sync disabled)

## Contract Validation Scenarios

### CV-01 Valid command payload
- Input: valid `clock.command.v1`
- Expectation:
  - parser accepts payload
  - adapter routes to correct clock operation

### CV-02 Unknown schema
- Input: `schema = clock.command.v0` or unknown
- Expectation:
  - status: `rejected`
  - code: `VALIDATION_FAILED`

### CV-03 Missing required fields
- Input: missing `command_id` or `target.heading_path`
- Expectation:
  - status: `rejected`
  - no domain call executed

### CV-04 Unknown kind
- Input: `kind = clock.pause`
- Expectation:
  - status: `rejected`
  - code: `VALIDATION_FAILED`

### CV-05 Malformed timestamp
- Input: invalid `requested_at`
- Expectation:
  - status: `rejected`
  - code: `VALIDATION_FAILED`

## Adapter and Domain Scenarios

### AD-01 Start success
- Given: target heading exists and not running
- When: `clock.start`
- Then: status `applied`

### AD-02 Stop success
- Given: open clock exists
- When: `clock.stop`
- Then: status `applied`

### AD-03 Cancel success
- Given: open clock exists
- When: `clock.cancel`
- Then: status `applied`

### AD-04 Start already running
- Given: open clock exists
- When: `clock.start`
- Then:
  - status `failed`
  - code `ALREADY_RUNNING`

### AD-05 Stop with no open clock
- Given: no open clock
- When: `clock.stop`
- Then:
  - status `failed`
  - code `NO_OPEN_CLOCK`

### AD-06 Invalid heading level
- Given: heading resolves but is not level-2
- When: any command
- Then:
  - status `failed`
  - code `INVALID_HEADING_LEVEL`

### AD-07 File or heading not found
- Given: unknown file or unknown heading path
- When: any command
- Then:
  - status `failed`
  - code mapped to target failure

### AD-08 Conflict / I/O failure mapping
- Given: conflict retries exhausted or repository I/O failure
- When: command execution
- Then:
  - status `failed`
  - code mapped deterministically

## Runtime Service Scenarios

### RS-01 Feature flag disabled
- `executeManualCommand` returns `rejected` and snapshot keeps last error.

### RS-02 Poll incoming commands once
- Given: N incoming payloads
- `pollIncomingCommandsOnce()` returns N and each result is reported.

### RS-03 Runtime mode transitions
- `enableStandardMode()` sets snapshot mode `Standard`.
- `enableActiveMode()` sets snapshot mode `Active`.
- `stopRuntime()` sets snapshot mode `Off`.

### RS-04 Snapshot determinism on report failure
- If result reporting fails, snapshot still records `lastResult` and `lastError`.

## Idempotency Scenarios

### ID-01 Duplicate command id
- second delivery returns `duplicate` with no second mutation.

### ID-02 Duplicate after restart
- persisted command id still yields `duplicate`.

## Regression Guards

### RG-01 Local UI operations unchanged when sync disabled

### RG-02 Notification behavior unchanged when sync disabled

## Exit Acceptance
All scenarios above pass in unit/regression suites before any default-on decision.
