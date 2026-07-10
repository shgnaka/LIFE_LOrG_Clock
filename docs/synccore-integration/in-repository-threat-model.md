# In-repository sync-core Threat Model

## 1. Status

- Status: Threat Model v1
- Method: asset/trust-boundary analysis with STRIDE-style threat categories
- Scope: LAN ingress/egress, envelope verification, trust records, queue/inbox,
  result routing, key storage, logs, and migration

## 2. Assets

- local signing private key
- trusted peer public keys, roles, and endpoints
- pairing invitation token and persistent pairing secret
- queued and incoming payloads
- command/result routing metadata
- delivery and replay history
- local clock/domain integrity
- availability of local app operations

## 3. Trust Boundaries

1. Untrusted LAN to TLS listener.
2. TLS session to signed envelope verifier.
3. Verified envelope to durable inbox.
4. Durable inbox to application/domain adapter.
5. Core engine to Room/JDBC store.
6. Core engine to outbound transport.
7. Application process to Android Keystore/Desktop key storage.
8. Existing v2 database to migration code.

Transport acceptance never crosses boundary 4; it only proves persistence after
boundaries 1-3.

## 4. Attacker Capabilities

Assume an attacker may:

- connect from the same LAN;
- scan ports and send arbitrary/malformed/oversized requests;
- replay, reorder, delay, or drop observed traffic;
- possess a revoked or viewer credential;
- obtain a displayed QR before its intended recipient;
- cause process termination at any storage/network boundary;
- control remote HTTP status/body/timing;
- inject sensitive strings into payload/error fields;
- provide a corrupt or crafted legacy database.

Do not assume protection against a fully compromised local OS or unlocked
device with administrator/root access.

## 5. Threats and Required Controls

| ID | Threat | Control | Verification |
|---|---|---|---|
| TM-01 | LAN spoofing | TLS pinning plus ES256 envelope signature | SEC-01, SEC-03, SEC-06 |
| TM-02 | Unknown/revoked peer | active trust lookup before persistence | SEC-02 |
| TM-03 | Viewer performs mutation | topic/role authorization before persistence | SEC-13, ING-09 |
| TM-04 | Replay causes duplicate side effect | durable sender/message dedupe plus domain command ID store | ING-03, ID-01, ID-02 |
| TM-05 | Same ID with altered content | content hash conflict rejection | ING-04, ENG-03 |
| TM-06 | Old/future envelope replay | signed envelope timestamp window and nonce | SEC-04 |
| TM-07 | Payload/domain timestamp confusion | transport validates envelope time only | SEC-15 |
| TM-08 | Oversized/flood request exhaustion | body/payload/rate/inbox/queue limits | SEC-07..09, ENG-29 |
| TM-09 | Slow observer blocks dispatch | buffered typed observation isolated from engine | ENG-25 |
| TM-10 | Crash loses accepted message | success only after durable inbox commit | ING-01, ING-02 |
| TM-11 | Crash duplicates outbound message | dispatch lease and idempotent receiver | ENG-19, ING-03 |
| TM-12 | Result sent to wrong peer | durable route bound to verified sender | ING-06, ING-07 |
| TM-13 | Secret leakage | secure key storage, redaction, bounded detail | SEC-10, INS-01, INS-02, INS-04 |
| TM-14 | Certificate substitution | SHA-256 certificate pin from pairing | SEC-06 |
| TM-15 | Pairing invitation theft | one-time token, 2-minute TTL, explicit trust review | existing pairing tests/manual review |
| TM-16 | Migration destroys queue | transaction, pre-v3 backup, no destructive fallback | DB-04..06 |
| TM-17 | Corrupt state transition | engine-owned state machine and transactional checks | ENG-13, ENG-14, ENG-24 |
| TM-18 | Malicious response controls retries/logs | status classification and response sanitization | ENG-09, SEC-10 |
| TM-19 | Unsigned debug route exposed | absent in release; loopback + opt-in in debug | SEC-14 |
| TM-20 | Downgrade/schema ambiguity | explicit envelope version and legacy decoder isolation | SEC-12, INT-03 |

## 6. Pairing Decision

Pairing remains directional.

- A trust record authorizes one remote peer on one local host.
- Bidirectional command sync requires both directions to be paired.
- QR invitation is one-use and expires after two minutes.
- Persistent pairing secret authenticates the TLS application session but does
  not replace envelope signature verification.
- `peerId` is the trust/routing key; `deviceId` is metadata and may differ.
- Role is fixed at pairing and may be changed only by explicit re-pair/repair.

The concrete v2 exchange and migration from the overloaded legacy
`publicKeyBase64` field are defined in
`in-repository-identity-migration.md`.

## 7. Cryptographic Baseline

- Envelope signatures: ES256 (P-256 / ECDSA / SHA-256) is the standard. Ed25519 is optional for capable hosts and legacy peer compatibility only.
- Certificate pin: SHA-256 of DER certificate.
- Payload integrity in new canonical form: SHA-256 payload digest.
- Random tokens/secrets: cryptographically secure, at least 256 bits for
  persistent pairing credentials.
- No custom encryption algorithm.
- Private keys are non-exportable on Android where Keystore supports it.

Legacy envelope v1 remains accepted during migration. It is verified using its
legacy canonical form and may not be reinterpreted as the new schema.

## 8. Security Failure Behavior

- Authentication/authorization/schema failures are terminal and not retried.
- Rate limit/inbox/store temporary capacity failures return retry guidance.
- Rejection does not create inbox or route rows.
- Local clock operation remains available when sync security/storage fails.
- Security errors expose stable codes, not peer key/payload/credential details.

## 9. Residual Risks

- QR capture before intended pairing can register an attacker first.
- A compromised trusted full peer may send validly signed malicious domain
  payloads; upper-layer validation remains required.
- Root/admin compromise can bypass local storage protections.
- At-least-once delivery can duplicate transport delivery at crash boundaries.
- Seven-day transport dedupe is finite; command expiration and 90-day domain
  command-ID retention reduce late duplicate side effects.

These risks are accepted for v1 and documented for operational guidance.

## 10. Security Review Checklist

- [x] Assets and trust boundaries are enumerated.
- [x] Attacker capabilities and out-of-scope compromise are defined.
- [x] Each identified threat has a control and verification reference.
- [x] Pairing direction, identity key, and role behavior are fixed.
- [x] Cryptographic algorithms and legacy handling are fixed.
- [x] Failure behavior and residual risks are documented.
