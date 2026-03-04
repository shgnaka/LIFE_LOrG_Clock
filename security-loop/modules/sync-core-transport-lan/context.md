# sync-core-transport-lan

## Goal
Run attacker/defender loop against sync ingress/egress boundaries to reduce exploitable risk before broader rollout.

## In Scope
- Inbound command acceptance (`HttpIncomingCommandSource`)
- Replay and rate-limit controls
- Peer trust and probe flow
- Result dispatch and envelope signing path
- Runtime control and sync coordination

## Out of Scope
- Pairing UX redesign
- NAT traversal, relay, and non-LAN transport features
- Non-sync features

## High-Risk Focus
1. Authenticity checks before command execution
2. Replay resistance across process restarts
3. Trust bootstrap requirements
4. Failure-safe behavior under transport errors
