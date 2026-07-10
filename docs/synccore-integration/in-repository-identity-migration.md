# In-repository sync-core Identity and Pairing Migration

## 1. Status

- Status: Identity Baseline v1
- Problem: current `PeerTrustRecord.publicKeyBase64` may contain either a
  legacy Ed25519 public key or an encoded HTTPS transport credential
- Decision: signing identity and transport authentication are separate fields

The overloaded current field must not be copied directly into the internal
sync-core trust model.

## 2. Target Trust Record

Application/platform storage owns:

```text
peerId
deviceId
displayName
role
signingPublicKeyBase64
signingAlg
endpoint
transportCredentialRef
certificateSha256
registeredAt
lastSeenAt
revokedAt
active
capabilities
```

Rules:

- `signingPublicKeyBase64` is an X.509 SubjectPublicKeyInfo encoded public key.
- `signingAlg` is `ES256` for the standard P-256/ECDSA/SHA-256 path. `Ed25519`
  is allowed only for optional/legacy compatibility.
- `transportCredentialRef` points to platform-protected secret storage; the
  secret itself is not passed to sync-core.
- certificate fingerprint is not a signing key.
- `peerId` is the trust/routing primary key.
- `deviceId` is stable device metadata and may differ from `peerId`.

## 3. Local Identity

Each full peer owns:

- stable `deviceId`;
- stable `peerId` (v1 defaults to `deviceId`);
- ES256 signing key pair;
- TLS server identity if it accepts ingress;
- display name and supported capabilities.

Android signing private key uses Android Keystore. Desktop signing private key
uses a dedicated identity file/OS secret facility and is separate from the TLS
private key.

## 4. Pairing Protocol v2

Pairing remains a short-lived HTTPS exchange pinned by the invitation
certificate fingerprint.

Invitation fields:

```text
schema = orgclock.invite.v2
token
hostPeerId
hostDeviceId
hostDisplayName
hostSigningPublicKeyBase64
hostSigningAlg
certificateSha256
endpoint
expiresAtEpochMs
capabilities
```

Request fields:

```text
schema = orgclock.pairing.exchange.v2
invitationToken
requesterPeerId
requesterDeviceId
requesterDisplayName
requesterSigningPublicKeyBase64
requesterSigningAlg
requestedRole
capabilities
```

Response fields:

```text
schema = orgclock.pairing.exchange.result.v2
hostPeerId
hostDeviceId
hostDisplayName
hostSigningPublicKeyBase64
hostSigningAlg
grantedRole
encodedTransportCredential
certificateSha256
capabilities
```

The host stores the requester's signing public key, signing algorithm, and newly generated
transport credential. The requester stores the host signing public key, signing algorithm,
credential, endpoint, and certificate fingerprint.

## 5. Direction and Authorization

- One successful exchange establishes trust records on both participating
  hosts for that connection, but each record remains locally revocable.
- Requested role may be reduced by the host but not elevated silently.
- Full role is required for `clock.command.v1`.
- Viewer role cannot submit mutation topics.
- Revoke disables signing trust and transport credential.
- Repair does not reactivate a changed public key; key change requires re-pair.

## 6. Legacy Classification

On migration, inspect `publicKeyBase64`:

1. `orgclock-https-v1:` prefix:
   - classify as `TransportCredentialOnly`;
   - extract credential into platform-protected transport storage;
   - signing key remains missing;
   - peer may use existing event/template transport;
   - peer cannot send mutation command until v2 re-pair.
2. Valid ES256 or legacy Ed25519 X.509 public key:
    - classify as `SigningKeyOnly`;
    - preserve as signing key;
    - record `signingAlg=ES256` for P-256 keys and `signingAlg=Ed25519` for legacy Ed25519 keys;
   - existing endpoint may be retained;
   - transport credential must be paired/configured separately.
3. Pairing invitation prefix or malformed value:
   - classify as `Incomplete`;
   - do not trust for ingress;
   - require re-pair.

No heuristic may treat an HTTPS credential as a signing key.

## 7. Compatibility UX

Settings exposes:

- `Ready`: signing key and transport credential present;
- `Transport only - re-pair required`;
- `Signing key only - transport setup required`;
- `Incomplete - re-pair required`;
- `Revoked`.

The migration does not automatically revoke existing event/template transport.
It blocks only capabilities whose required credential is missing.

## 8. Tests

Add these cases to the relevant acceptance owners:

- classify each legacy credential type without ambiguity;
- preserve valid legacy Ed25519 key with `signingAlg=Ed25519`;
- move transport credential to protected storage and remove plaintext duplicate;
- reject command ingress from transport-only peer;
- v2 exchange stores both peers' signing identities;
- role reduction and mutation authorization;
- changed key requires re-pair;
- revoke disables both transport and signing authorization.

These cases are tracked as `IDN-01..08` in the requirements and test
specification.

## 9. Identity Review Checklist

- [x] Signing key and transport credential are separate.
- [x] Pairing v2 invitation/request/response fields are fixed.
- [x] Directional/local revocation semantics are fixed.
- [x] Legacy credential classification is deterministic.
- [x] Capability behavior for incomplete legacy peers is fixed.
- [x] Re-pair and key-change policy is fixed.
