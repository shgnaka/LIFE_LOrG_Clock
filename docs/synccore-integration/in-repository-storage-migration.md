# In-repository sync-core Storage and Migration Plan

## 1. Status

- Status: Storage Baseline v1
- Current database: `orgclock_sync_queue.db`, Room schema version 2
- Target database: same filename, Room schema version 3
- Migration policy: additive/non-destructive; no fallback-to-destructive

Keeping the filename allows installed Android clients to migrate in place.

## 2. Current Data That Must Survive

Current v1/v2 fixtures are stored under:

```text
app/src/test/resources/synccore/legacy-v1/
```

Required preserved fields:

- outgoing command ID, topic, payload, peer, creation/expiration
- state, retry count, next retry, last error
- processed result rows
- delivery event history
- replay key and registration time

## 3. Target Schema v3

### `sync_outgoing_queue`

Existing columns remain. Add:

| Column | Type | Default/backfill |
|---|---|---|
| `contentSha256` | TEXT NOT NULL | SHA-256 of normalized stored message fields |
| `sequence` | INTEGER NOT NULL | deterministic row order assigned during migration |
| `leaseUntilEpochMs` | INTEGER NULL | null |
| `cancelRequested` | INTEGER NOT NULL | 0 |
| `terminalAtEpochMs` | INTEGER NULL | inferred from terminal state, else null |
| `lastAttemptAtEpochMs` | INTEGER NULL | null |

State backfill:

- `PENDING` -> `pending`
- `SENT` -> `dispatching`, with expired lease at migration time so it is safely
  recovered
- `ACKED` -> `acked`
- unknown state -> migration failure; do not coerce silently

Existing rows with null expiration remain null for legacy compatibility.

### `sync_incoming_inbox`

```text
receiptId TEXT PRIMARY KEY
senderPeerId TEXT NOT NULL
messageId TEXT NOT NULL
topic TEXT NOT NULL
payloadJson TEXT NOT NULL
contentSha256 TEXT NOT NULL
receivedAtEpochMs INTEGER NOT NULL
processingState TEXT NOT NULL
processingLeaseUntilEpochMs INTEGER NULL
deliveryCount INTEGER NOT NULL
processedAtEpochMs INTEGER NULL
lastErrorCode TEXT NULL
lastErrorDetail TEXT NULL
UNIQUE(senderPeerId, messageId)
```

### `sync_result_routes`

```text
commandMessageId TEXT PRIMARY KEY
senderPeerId TEXT NOT NULL
incomingReceiptId TEXT NOT NULL
createdAtEpochMs INTEGER NOT NULL
resolvedAtEpochMs INTEGER NULL
expiresAtEpochMs INTEGER NOT NULL
```

### `sync_metrics`

Key/value rows persist monotonic counters. Per-peer last success uses a separate
`sync_peer_metrics(peerId PRIMARY KEY, lastSuccessfulDispatchAtEpochMs)`.

### `sync_delivery_events`

Retain existing fields and add:

- `sequence INTEGER NOT NULL`
- `topic TEXT NOT NULL`
- `attempt INTEGER NOT NULL DEFAULT 0`

### Required indexes and constraints

```text
UNIQUE sync_outgoing_queue(commandId)
INDEX sync_outgoing_due(state, nextRetryAtEpochMs, sequence)
INDEX sync_outgoing_peer_order(targetPeerId, sequence)
INDEX sync_outgoing_terminal(terminalAtEpochMs)

UNIQUE sync_incoming_inbox(senderPeerId, messageId)
INDEX sync_incoming_claim(processingState, processingLeaseUntilEpochMs, receivedAtEpochMs)
INDEX sync_incoming_terminal(processedAtEpochMs)

UNIQUE sync_result_routes(commandMessageId)
INDEX sync_result_routes_expiry(expiresAtEpochMs, resolvedAtEpochMs)

INDEX sync_delivery_events_sequence(sequence)
INDEX sync_delivery_events_retention(occurredAtEpochMs)
INDEX sync_incoming_replay_retention(registeredAtEpochMs)
```

Capacity checks and inserts occur in the same transaction. Indexes may optimize
the check but do not replace transactional enforcement.

### Existing tables

- `sync_processed_results` remains for migration compatibility.
- `sync_incoming_replay` remains until inbox dedupe has been deployed for one
  stable release. New ingress writes both during the compatibility release.

The separate `sync-processed-command-ids.db` remains a separate application
idempotency store during this migration. Its schema stays version 1; retention
configuration changes from 7 days/20,000 rows to 90 days/100,000 rows. It is not
merged into the transport database because it protects domain side effects,
not transport delivery.

Peer trust is currently stored outside this Room database. Its field migration
is executed by the host adapter according to
`in-repository-identity-migration.md`; queue schema migration must not
reinterpret peer credentials.

## 4. Migration 2 to 3

Migration runs in one SQLite transaction:

1. create new inbox, route, metric, and peer-metric tables;
2. add nullable/defaulted outgoing/event columns;
3. assign stable `sequence` ordered by
   `createdAtEpochMs, commandId`;
4. compute `contentSha256` in deterministic batches before commit;
5. map known state values;
6. mark legacy `SENT` leases as expired;
7. assign event sequence ordered by `occurredAtEpochMs, id`;
8. validate row counts, non-null columns, unique keys, and foreign references;
9. set Room user version to 3 and commit.

Any failure rolls back the transaction. The database file is not deleted,
renamed, or recreated.

## 5. Backup and Failure Handling

Before first production v3 open:

- close all handles;
- checkpoint WAL;
- create `orgclock_sync_queue.db.pre-v3` plus `-wal/-shm` as needed;
- fsync copied files where platform APIs permit;
- retain backup until v3 opens and health check succeeds three times or seven
  days have elapsed.

On migration failure:

- return `MIGRATION_FAILED`;
- keep sync runtime off;
- keep local clock operations available;
- retain original and backup files;
- do not retry in a tight loop;
- expose a support/debug diagnostic without payload or secret content.

## 6. Database Ownership

- One Room database instance per process.
- All queue/inbox transitions use transactions.
- The core engine owns state-transition rules.
- Room DAO owns SQL only and does not choose retry/security policy.
- No Android main-thread database access.

## 7. Retention and Capacity

Retention follows `DR-01` in the requirements baseline.

Cleanup order:

1. resolved/expired result routes;
2. terminal outgoing rows older than TTL;
3. terminal inbox/dedupe rows older than TTL;
4. delivery events older than TTL;
5. processed command IDs older than TTL;
6. enforce row caps by oldest eligible terminal row.

Pending, retry-wait, dispatching, or unprocessed inbox rows are never deleted by
retention cleanup.

## 8. Migration Tests

Required tests are `DB-04`, `DB-05`, and `DB-06`.

Fixtures must include sentinel rows with:

- null and non-null expiration;
- pending, sent, and acked state;
- retry count and future next-attempt time;
- error code/detail;
- processed result;
- delivery event;
- replay key.

After migration, tests compare every preserved field and verify v3 indexes,
constraints, state mapping, sequence ordering, and expired SENT lease recovery.

## 9. Rollback

Application rollback to a binary that only understands v2 is not supported
after v3 writes. Release rollout therefore uses:

1. internal/debug migration exercise;
2. release candidate with feature flag off by default;
3. staged distribution;
4. backup retention described above.

Operational rollback disables the new sync runtime while retaining v3 data; it
does not downgrade the database.

## 10. Storage Review Checklist

- [x] Target schema version and database filename are fixed.
- [x] Every legacy table has an explicit preservation rule.
- [x] New queue, inbox, route, lease, metric, and event fields are defined.
- [x] Required indexes and uniqueness constraints are defined.
- [x] Transactional migration order is defined.
- [x] Failure behavior and backup policy are defined.
- [x] Retention order and protected active rows are defined.
- [x] Migration and rollback tests are identified.
