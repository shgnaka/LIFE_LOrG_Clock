# Sync-core Integration Test and Acceptance Criteria (M1 Definition)

## Purpose
Define objective test scenarios for future sync-core integration while implementation is on hold.

## Test Layers
1. Contract validation tests
2. Adapter/domain unit tests
3. Repository interaction tests
4. End-to-end manual scenarios (post-integration)

## Contract Validation Scenarios

### CV-01 Valid command payload
- Input: valid `clock.command.v1`
- Expectation:
  - parser accepts payload
  - adapter routes to correct clock operation

### CV-02 Unknown schema
- Input: `schema = clock.command.v0` or unknown
- Expectation:
  - result status: `rejected`
  - code: `VALIDATION_FAILED`

### CV-03 Missing required fields
- Input: missing `command_id` or `target.heading_path`
- Expectation:
  - status `rejected`
  - no domain call executed

### CV-04 Unknown kind
- Input: `kind = clock.pause`
- Expectation:
  - status `rejected`
  - code: `VALIDATION_FAILED`

## Adapter and Domain Scenarios

### AD-01 Start success
- Given: target heading exists and not running
- When: `clock.start`
- Then:
  - domain start called once
  - result status `applied`

### AD-02 Start already running
- Given: open clock exists
- When: `clock.start`
- Then:
  - result status `failed`
  - code `ALREADY_RUNNING`

### AD-03 Stop success
- Given: open clock exists
- When: `clock.stop`
- Then: status `applied`

### AD-04 Stop with no open clock
- Given: no open clock
- When: `clock.stop`
- Then:
  - status `failed`
  - code `NO_OPEN_CLOCK`

### AD-05 Cancel success
- Given: open clock exists
- When: `clock.cancel`
- Then: status `applied`

### AD-06 File target not found
- Given: `target.file_name` not present
- When: any command
- Then:
  - status `failed`
  - code `TARGET_FILE_NOT_FOUND`

### AD-07 Heading target not found
- Given: file exists, heading path not found
- When: any command
- Then:
  - status `failed`
  - code `TARGET_HEADING_NOT_FOUND`

### AD-08 Invalid heading level
- Given: heading resolves to non-L2
- When: start/stop/cancel
- Then:
  - status `failed`
  - code `INVALID_HEADING_LEVEL`

### AD-09 Repository I/O failure
- Given: repository read/write failure
- When: command execution
- Then:
  - status `failed`
  - code `IO_FAILURE`

### AD-10 Conflict exhaustion
- Given: repeated conflict path not recoverable
- When: command execution
- Then:
  - status `failed`
  - code `CONFLICT_RETRY_EXHAUSTED`

## Idempotency Scenarios

### ID-01 Duplicate command id
- Given: same `command_id` delivered twice
- When: second delivery processed
- Then:
  - no additional side effects
  - status `duplicate`

### ID-02 Duplicate after app restart
- Given: processed id persisted
- When: app restarts and receives same command
- Then:
  - still `duplicate`
  - no extra mutation

## Regression Guard Scenarios

### RG-01 Local manual clock operations unchanged
- start/stop/cancel from UI still behave exactly as before

### RG-02 Notification service behavior unchanged
- existing clock-in notification scanning and display unaffected when sync integration is disabled

### RG-03 No sync code path in release runtime for M1 hold
- branch build contains docs-only changes

## Manual End-to-End (Post-unblock)
These are not executable in current hold state; they define acceptance for future merge:

1. Device A sends `clock.start` to Device B target heading.
2. Device B applies and returns `applied`.
3. Device A receives result and marks delivered.
4. Repeat with invalid target; observe deterministic failure code.
5. Repeat duplicate command id; observe `duplicate` without side effects.

## Exit Acceptance for Future Implementation
All must pass before enabling integration by default:
1. All contract validation tests pass.
2. All adapter/domain mapping tests pass.
3. Duplicate handling is deterministic.
4. No regression in existing clock and notification flows.
