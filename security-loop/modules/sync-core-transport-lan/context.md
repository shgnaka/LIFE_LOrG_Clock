# sync-core-transport-lan

## Goal
Run attacker/defender loop against sync ingress/egress boundaries to reduce exploitable risk before broader rollout.

## In Scope
- Inbound command acceptance through `SyncRawIngressReceiver`
- Replay, size-limit, rate-limit, and inbox capacity controls
- Peer trust and probe flow
- Result dispatch and ES256 envelope signing path; Ed25519 is optional/legacy-compatible only
- HTTPS endpoint policy and TLS certificate pinning
- Android/Desktop sync-core host adapters
- Runtime control and sync coordination

## Out of Scope
- Pairing UX redesign
- NAT traversal, relay, and non-LAN transport features
- Non-sync features

## High-Risk Focus
1. Authenticity checks before command execution
2. Replay resistance across process restarts
3. Trust bootstrap requirements
4. HTTPS-only egress and certificate pin enforcement
5. Failure-safe behavior under transport errors
