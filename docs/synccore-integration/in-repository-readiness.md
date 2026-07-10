# In-repository sync-core Readiness Index

## Status

**IMPLEMENTED / GATE 5 COMPLETE**

This status means the in-repository `:sync-core` implementation, host adapters,
acceptance evidence, and no-external build gate are complete for the command-sync
scope defined in `in-repository-requirements.md`.

## Authoritative Inputs

| Artifact | Status |
|---|---|
| `in-repository-requirements.md` | Requirements Baseline v1 |
| `in-repository-api-contract.md` | API Baseline v1 |
| `in-repository-test-spec.md` | Test Specification v1 |
| `in-repository-storage-migration.md` | Storage Baseline v1 |
| `in-repository-threat-model.md` | Threat Model v1 |
| `in-repository-identity-migration.md` | Identity Baseline v1 |
| `in-repository-implementation-plan.md` | Ready |
| legacy wire/schema fixtures | Present and validated |
| test traceability verifier | Green |
| implementation readiness verifier | Green |
| acceptance audit | Green, unresolved owner evidence = 0 |
| no-external build gate | Green |
| Android connected adapter inspection | Green on suffix package |

## Frozen Decisions

- one KMP `:sync-core` module;
- Android/JVM initial targets;
- at-least-once transport;
- peer-scoped FIFO, one in-flight per peer, four globally;
- bounded retry with ten total attempts;
- durable outgoing queue and incoming inbox;
- directional trust using `peerId` as routing primary key;
- separate signing identity and transport credential with pairing v2 migration;
- TLS certificate pinning plus standard ES256 envelope signatures, with Ed25519 optional/legacy compatibility;
- Room database v3 in-place migration;
- `OrgSyncCoreClient` retained as app migration boundary;
- Android command-sync parity before external dependency removal.
- external sync-core provider removed from normal build/runtime.

## Non-blocking Deferred Scope

- iOS target timing;
- migration of event/template transports;
- capability negotiation;
- multiple published artifacts;
- public ordering key;
- long-term event-log-first cutover decisions.

## Verification

Run:

```powershell
pwsh -File scripts/verify-synccore-test-spec.ps1
pwsh -File scripts/verify-synccore-readiness.ps1
./gradlew generateSyncCoreAcceptanceAuditReport
./gradlew verifyNoExternalSyncCoreBuild -PsyncCoreDeterminismRuns=1
```

Implementation evidence is complete only when these commands pass and
`build/reports/sync-core/acceptance-audit.md` reports
`unresolved_owner_evidence=0`.
