# Sync Platform Runtime Matrix (Draft)

このドキュメントは、Org Clock の同期機能を各プラットフォームでどのような runtime で動かすかを整理するための設計草案です。

対象:

- Android
- Desktop
- iOS
- macOS
- viewer/read-only runtime

## 1. Purpose

目的:

- 共通 sync core と platform-specific runtime の境界を明確にする
- 「何を共通化し、何を各プラットフォームで分けるか」を決める
- 実装優先度を誤らないようにする

## 2. Shared vs Platform-Specific

### Shared sync core に置きたいもの

- event model
- event store contract
- projection rules
- pending sync state model
- event transport contract and cursor semantics
- peer/trust semantics

### Platform-specific runtime に置くもの

- app lifecycle hook
- background execution
- periodic sync scheduling
- foreground/active sync policy
- local secure key storage implementation
- network reachability integration

## 3. Runtime Modes

全 platform で共通に持ちたい論理モード:

- `Off`
- `ForegroundPreferred`
- `Periodic`
- `ManualOnly`

platform によって、この論理モードの実装方法は異なる。

## 4. Platform Matrix

## Android

### Strong points

- foreground service が使える
- periodic background work が比較的扱いやすい
- 現行 sync runtime の土台がすでにある

### Constraints

- background 制約は OS version に依存
- aggressive vendors で periodic work が不安定になることがある

### Recommended runtime

- foreground 中:
  - operation 直後 sync
  - app resume sync
  - short interval flush optional
- background:
  - WorkManager periodic sync
- active mode:
  - foreground service は debug / explicit opt-in 向け

### Initial priority

- 最初に full peer として対応

## Desktop

### Strong points

- 常時起動しやすい
- local filesystem と相性がよい
- viewer host / sync host の両方になりやすい

### Constraints

- package 形態や常駐の作り込みは OS 差がある
- mobile ほど lifecycle が一様ではない

### Recommended runtime

- app 起動中は foreground-preferred sync
- operation 直後 sync
- periodic timer sync
- optional host mode では listener 常駐

### Initial priority

- Android と並ぶ最優先 full peer

## iOS

### Strong points

- mobile peer として持ち歩き用途に向く

### Constraints

- 長時間 background sync に厳しい
- foreground / resume ベースの同期設計が中心になる

### Recommended runtime

- operation 直後 sync
- app 起動時 sync
- foreground resume sync
- background は OS が許す範囲の opportunistic sync

### Initial priority

- 最初は limited full peer
- always-on host を期待しない

## macOS

### Strong points

- Desktop に近い常駐性
- host / viewer の両方に向く

### Constraints

- sandbox / distribution 方式で権限事情が変わる

### Recommended runtime

- Desktop とほぼ同じ設計
- app 起動中 periodic sync
- host mode の常駐 listener

### Initial priority

- iOS より先に Desktop runtime の近縁として考えてよい

## Viewer / Read-only runtime

### Strong points

- write semantics を持たないため単純
- host state の可視化に集中できる

### Constraints

- full peer と同じ transport/trust を使うか別 read-only API を使うかで複雑さが変わる

### Recommended runtime

- host 由来の read-only snapshot subscribe
- 必要なら short-interval pull を補助に使う

### Initial priority

- full sync core 完成前でも先行可能
- viewer は full peer の event-sync runtime から切り離して進めてよい

## 5. Recommended Capability Matrix

### Phase 1

- Android: full peer
- Desktop: full peer
- iOS: out of scope or local-only
- macOS: out of scope or Desktop follow-up
- Viewer: local-only or host-only prototype

### Phase 2

- Android: full peer + event transport
- Desktop: full peer + event transport + host mode
- iOS: limited full peer
- macOS: full peer follow-up
- Viewer: read-only snapshot client

### Phase 3

- Android / Desktop / iOS / macOS で共通 sync semantics
- platform ごとに runtime だけ差し替え

## 6. Sync Trigger Matrix

共通候補:

- local clock operation succeeded
- app launched
- app resumed / became active
- manual `Sync now`
- periodic timer

推奨:

- Android: operation, resume, periodic, optional active ticker
- Desktop/macOS: operation, launch, periodic
- iOS: operation, launch, resume
- Viewer: periodic pull or explicit refresh

## 7. Operational Expectations

ユーザー期待値を platform ごとに揃える:

- local operation は常に即時成功/失敗がわかる
- sync は遅延してもよいが pending 状態は見える
- `Sync now` は全 platform にあるとよい

## 8. Design Implications

この matrix から逆算すると必要なのは:

- shared sync core
- pluggable runtime controller
- pluggable transport binding
- per-platform secure identity storage
- pending/error UX の共通 state

## 9. Open Decisions

- iOS の background sync をどこまで目標にするか
- Desktop/macOS host mode を常時 listener 前提にするか
- viewer を full peer ベースで作るか、read-only API で作るか

## 10. Related Docs

- `docs/sync-architecture-next.md`
- `docs/sync-peer-trust-model.md`
- `docs/sync-migration-plan.md`
