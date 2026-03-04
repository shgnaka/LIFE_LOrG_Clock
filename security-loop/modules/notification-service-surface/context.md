# notification-service-surface

## Goal
Evaluate security risk around notification-triggered actions and PendingIntent handling.

## In Scope
- Notification service entry points and intent actions
- PendingIntent mutability and update semantics
- Notification interaction paths (open app / stop action)

## Out of Scope
- Sync transport and peer trust model
- UI redesign or copy changes
- Background scheduling outside notification package

## High-Risk Focus
1. Mutable PendingIntent usage in externally triggerable paths
2. Service action handling without explicit allowlist
3. Notification actions that can be replayed or spoofed
