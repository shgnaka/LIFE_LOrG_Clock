# Sync Next Implementation Issue Prep (Draft)

このドキュメントは、現在残っている重要な同期設計論点を、GitHub issue として登録しやすい実装単位へ落とし込むための準備メモです。

各項目には、現時点でのおすすめの選択を明記する。

## 1. Decide local event store backend

### Recommended choice

- Phase 1 は SQLite 系で始める
- Android は Room
- Desktop は SQLite 系の JVM 実装
- 契約は shared に置く

### Why

- durable
- replay しやすい
- event_id index を持ちやすい
- pending / cursor / reject reason を同居させやすい

### Draft issue title

- `Decide and document the Phase 1 local event store backend`

### Draft issue body

## Summary
Decide the storage backend for the first local event-log implementation and document the choice as the Phase 1 default.

## Recommended choice
- Use SQLite-family storage in Phase 1
- Android: Room
- Desktop: SQLite-compatible JVM implementation
- Keep contracts shared even if implementations differ per platform

## Scope
- Compare SQLite-family storage against JSONL and other simple alternatives
- Decide the Phase 1 backend per platform
- Define the minimum schema needs for events, idempotency, pending sync, and cursor metadata

## Done Criteria
- A clear backend choice is documented
- The rationale is recorded in docs
- Phase 1 implementers have a stable storage default to target

## References
- `docs/sync-event-schema-v1-draft.md`
- `docs/sync-migration-plan.md`
- `docs/sync-failure-recovery.md`
- `docs/sync-local-event-store-backend.md`

## 2. Define atomicity between event append and org projection

### Recommended choice

- event append を先に必須化する
- org update / org projection はその後に行う
- event append 失敗は操作失敗
- event append 成功後の org 更新失敗は recoverable error

### Why

- 真を event log に置く方針と一致する
- org 側は rebuild 可能
- local-first の操作保証と recovery 設計が一致する

### Draft issue title

- `Define atomicity and failure handling between event append and org projection`

### Draft issue body

## Summary
Define the exact ordering and failure handling between local clock operations, event append, projection updates, and org projection updates.

## Recommended choice
- Event append must succeed before the operation is considered durable
- Org projection happens after event append
- Event append failure means the local operation fails
- Org projection failure after append is treated as recoverable and repairable

## Scope
- Define the local write sequence
- Define success/failure semantics
- Define which failures require rollback vs recovery-required state
- Align the result with local-first and event-log-first principles

## Done Criteria
- The write order is explicitly documented
- Success and failure semantics are fixed
- Recovery expectations are aligned with the recovery plan

## References
- `docs/sync-migration-plan.md`
- `docs/sync-failure-recovery.md`
- `docs/sync-org-direct-edit-policy.md`

## 3. Define projection boundary and service split

### Recommended choice

- projection を 2 層に分ける
- active clock / history projection
- org projection

### Why

- event-derived read model と org artifact を分離できる
- rebuild や viewer 提供がしやすい
- 既存 `ClockService` の責務整理がしやすい

### Draft issue title

- `Define projection boundaries between active/history state and org projection`

### Draft issue body

## Summary
Define how projection responsibilities are split between event-derived active/history state and org file projection.

## Recommended choice
- Keep active clock and history projection separate from org file projection
- Treat org output as a derived artifact, not the primary read model

## Scope
- Define projection layers
- Define the boundary with existing `ClockService`
- Define which consumers read which projection

## Done Criteria
- Projection layers are documented
- The boundary with existing services is clear
- Viewer and UI consumers have a stable read-model direction

## References
- `docs/sync-architecture-next.md`
- `docs/sync-migration-plan.md`
- `docs/sync-failure-recovery.md`

## 4. Define event-sync wire protocol and cursor semantics

### Recommended choice

- cursor ベースの fetch + push 併用
- batch 送信
- ack は cursor / last_seen ベース
- fetch は exclusive sinceCursor
- ack の seenCursor は inclusive
- batch 内 cursor は strict increasing

### Why

- offline catch-up に強い
- duplicate/no-op と相性がよい
- full peer と viewer を分けても発展させやすい

### Draft issue title

- `Define the event-sync wire protocol and cursor semantics`

### Draft issue body

## Summary
Define the event-sync wire contract for batch event exchange, fetch/push, and cursor-based acknowledgment.

## Recommended choice
- Use batch-oriented event exchange
- Support fetch + push
- Use cursor/last-seen semantics for acknowledgment
- Make cursor progression rules explicit

## Scope
- Define payload shape
- Define batch limits
- Define cursor progression
- Define exclusive/inclusive cursor boundaries
- Define duplicate and retry expectations

## Done Criteria
- A concrete wire contract is documented
- Cursor semantics are unambiguous
- The contract is suitable for Android/Desktop runtime work

## References
- `docs/sync-event-schema-v1-draft.md`
- `docs/sync-platform-runtime-matrix.md`
- `docs/sync-event-transport-contract.md`
- `docs/sync-event-wire-protocol-cursor-semantics.md`
- `docs/sync-phase2-issue-breakdown.md`

## 5. Define minimum pairing UX and trust handoff

### Recommended choice

- まずは manual pairing
- peer code または QR
- 公開鍵登録を含める
- full peer と viewer peer の role を pair 時に確定する
- endpoint は v1 では optional
- probe 成功後に trust record を保存する

### Why

- 誤接続を減らせる
- trust record を明示化できる
- platform をまたいでも運用しやすい

### Draft issue title

- `Define the minimum pairing UX and trust handoff flow`

### Draft issue body

## Summary
Define the initial pairing UX and trust handoff for full peers and viewer peers.

## Recommended choice
- Start with manual pairing
- Use peer code or QR
- Include public key registration in the flow
- Fix peer role at pairing time

## Scope
- Define the first pairing UX
- Define trust handoff data
- Define full peer vs viewer peer registration differences

## Done Criteria
- Pairing UX is documented at a minimum practical level
- Trust handoff data is defined
- The model is compatible with the trust record design

## References
- `docs/sync-peer-trust-model.md`
- `docs/sync-platform-runtime-matrix.md`
- `docs/sync-minimum-pairing-ux.md`

## 6. Decide viewer delivery architecture

### Recommended choice

- 最初は read-only snapshot delivery
- viewer を full peer にしない
- host 側の event-derived projection を viewer に渡す

### Why

- write semantics を持たせずに済む
- trust/cursor/event apply を viewer 側へ持ち込まなくてよい
- dashboard / monitor 用途に素直

### Draft issue title

- `Decide the initial viewer delivery architecture`

### Draft issue body

## Summary
Decide whether the initial viewer path should use read-only snapshot delivery or a full-peer event-sync model.

## Recommended choice
- Start with read-only snapshot delivery
- Do not make the initial viewer a full peer

## Scope
- Compare snapshot delivery with full-peer viewer sync
- Fix the initial architecture choice
- Define what data the viewer reads

## Done Criteria
- The initial viewer architecture is documented
- The role boundary with full peers is explicit
- The choice is aligned with the runtime matrix and trust model
- A concrete read-only snapshot path is chosen

## References
- `docs/sync-platform-runtime-matrix.md`
- `docs/sync-peer-trust-model.md`
- `docs/sync-phase2-issue-breakdown.md`
- `docs/sync-viewer-delivery-architecture.md`

## 7. Define org divergence detection and rebuild UX

### Recommended choice

- external change を検知する
- divergence は warning
- merge ではなく rebuild を優先
- `Reload from disk` を最小 recovery action にする

### Why

- event truth と整合する
- support/debug の運用がしやすい
- direct edit の完全双方向同期を避けられる

### Draft issue title

- `Define org divergence detection and rebuild UX`

### Draft issue body

## Summary
Define how the app detects divergence between event-derived state and local org files, and how rebuild/recovery is exposed.

## Recommended choice
- Detect external changes
- Treat divergence as warning/recovery-needed state
- Prefer rebuild over merge
- Provide `Reload from disk` as a core recovery action

## Scope
- Define divergence signals
- Define warning/recovery UX
- Define rebuild entry points

## Done Criteria
- Divergence handling is documented
- Rebuild UX is documented
- The behavior is aligned with direct-edit policy and failure recovery

## References
- `docs/sync-failure-recovery.md`
- `docs/sync-org-direct-edit-policy.md`
- `docs/sync-migration-plan.md`
- `docs/sync-org-divergence-rebuild-ux.md`

## 8. How To Use This Document

使い方:

- ここから必要な issue を GitHub に登録する
- issue を作成したらこの docs へのリンクを入れる
- 判断が変わったら issue と docs を両方更新する

優先度の高い順:

1. local event store backend
2. atomicity between event append and org projection
3. projection boundary and service split
4. event-sync wire protocol
5. minimum pairing UX
6. viewer delivery architecture
7. org divergence detection and rebuild UX
