# Sync-core Integration Contract (M1)

## Status
- Contract status: Draft-ready for implementation
- Runtime implementation status in this repo: Not implemented (by design)

## Versioning
- Contract namespace: `clock.command.v1`
- Result namespace: `clock.result.v1`
- Rule: additive changes only within `v1`; breaking changes require `v2`

## Boundary Contract

### org-clock consumes from sync-core
- Incoming command stream
- Delivery state stream (observability)

### org-clock provides to sync-core
- Command execution result callback
- Optional health/status surface (future)

## Required Interfaces (Conceptual)

### SyncCoreClient (external dependency)
- `submitCommand(...)`
- `observeIncomingCommands()`
- `reportResult(...)`
- `observeDeliveryState()`

### ClockCommandExecutor (org-clock internal adapter)
- Parse and validate command payload
- Resolve target entities
- Execute domain operation
- Return standardized result

## Command Payload Schema (`clock.command.v1`)

```json
{
  "schema": "clock.command.v1",
  "command_id": "uuid-or-ulid",
  "kind": "clock.start|clock.stop|clock.cancel",
  "target": {
    "file_name": "YYYY-MM-DD.org",
    "heading_path": "L1/L2"
  },
  "requested_at": "2026-03-01T12:34:56Z",
  "from_device_id": "device-a",
  "request_id": "opaque-id"
}
```

### Field Requirements
- `schema`: required, must equal `clock.command.v1`
- `command_id`: required, globally unique, idempotency key
- `kind`: required enum
- `target.file_name`: required string
- `target.heading_path`: required string
- `requested_at`: required RFC3339 UTC timestamp
- `from_device_id`: required string
- `request_id`: optional opaque tracing id

## Result Payload Schema (`clock.result.v1`)

```json
{
  "schema": "clock.result.v1",
  "command_id": "uuid-or-ulid",
  "status": "applied|failed|duplicate|rejected",
  "error_code": "OPTIONAL_CODE",
  "error_message": "optional human-readable detail",
  "applied_at": "2026-03-01T12:34:59Z",
  "by_device_id": "device-b"
}
```

## Error Code Mapping Contract

| org-clock condition | result.status | error_code |
|---|---|---|
| target file not found | failed | TARGET_FILE_NOT_FOUND |
| target heading not found | failed | TARGET_HEADING_NOT_FOUND |
| heading level invalid | failed | INVALID_HEADING_LEVEL |
| start requested while already running | failed | ALREADY_RUNNING |
| stop/cancel with no open clock | failed | NO_OPEN_CLOCK |
| conflict retries exhausted | failed | CONFLICT_RETRY_EXHAUSTED |
| repository I/O failure | failed | IO_FAILURE |
| invalid command payload | rejected | VALIDATION_FAILED |
| duplicate command id | duplicate | DUPLICATE_COMMAND |

## Idempotency Rules
- `command_id` is the sole deduplication key.
- Re-delivered command with the same `command_id` must not re-apply side effects.
- Duplicate handling should still return a `clock.result.v1` response.

## Target Resolution Rules
- Do not rely on line index from remote payload.
- Payload identity is `file_name + heading_path`.
- Resolver must map this identity to runtime target in local repository state.

## Time and Zone Rules
- Payload timestamps are UTC.
- Domain execution uses local app time services where required.
- Result timestamps should be emitted in UTC.

## Validation Rules
Reject (`rejected`) when:
- `schema` mismatch
- Missing required fields
- `kind` unknown
- `target` invalid or empty
- malformed timestamp

## Security Expectations (for sync-core side)
org-clock assumes sync-core already enforces:
- Peer trust (paired device keys)
- Message authenticity
- Replay protection

org-clock still validates payload shape and command semantics.

## Compatibility and Evolution
- New optional fields may be added without breaking v1.
- Existing required fields cannot change semantics in v1.
- If `kind` adds new command types, old clients must return `rejected` for unknown kinds.

## Open Decisions Deferred to sync-core
- Delivery guarantees (at least once vs effectively once contract wording)
- Max message size
- Retry schedule exposure in client API
- Peer presence event model
