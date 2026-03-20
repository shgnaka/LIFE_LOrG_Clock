# Sync Phase 1 Issue Breakdown (Draft)

このドキュメントは、`docs/sync-architecture-next.md` の Phase 1 を GitHub Issue に分解するための下書きです。

Phase 1 の目的:

- `clock-in / out / cancel` を local event log 真へ寄せる
- 既存 UI を壊さず projection で表示できるようにする
- 後続の event sync / viewer 実装に耐える最小土台を作る

## Issue 1: Define clock event schema v1

目的:

- v1 の event envelope と projection ルールを確定する

Done:

- `clock.event.v1` の field 定義が固まる
- `clock_started / clock_stopped / clock_cancelled` の意味論が固まる
- duplicate / invalid input の扱いを定義する

依存:

- なし

関連:

- `docs/sync-event-schema-v1-draft.md`
- `shared/src/commonMain/kotlin/com/example/orgclock/sync/ClockEventContracts.kt`
- `shared/src/commonMain/kotlin/com/example/orgclock/sync/ClockEventProjection.kt`

## Issue 2: Add shared event model and storage contracts

目的:

- shared 層で event 型と event store interface を定義する

Done:

- commonMain に event data model が入る
- event store contract が定義される
- append / list / replay に必要な最小 API が決まる

依存:

- Issue 1

候補 API:

- `append(event)`
- `contains(eventId)`
- `listSince(cursor)`
- `readAllForReplay()`
- `readSnapshot()`
- `updateSyncCheckpoint(cursor)`

## Issue 3: Implement local event store for Android and Desktop

目的:

- Android / Desktop でローカル event log を保存できるようにする

Done:

- Android 実装
- Desktop 実装
- idempotency を event_id ベースで担保

依存:

- Issue 2

メモ:

- 最初は platform 別の単純実装でよい
- 後で共通 DB 化できる境界を保つ
- Phase 1 の既定 backend は `docs/sync-local-event-store-backend.md` を参照

## Issue 4: Implement projection for active clock and history

目的:

- event log から active clock / closed history を再構成する projection を作る

Done:

- active clock view が event replay から作れる
- closed history が event replay から作れる
- invalid sequence を検出できる

依存:

- Issue 2
- Issue 3

## Issue 5: Route local clock operations through event append

目的:

- `clock start/stop/cancel` を org file 直接 mutation だけでなく event append 中心に寄せる

Done:

- local clock operation 実行時に event append が走る
- projection 結果と既存 UI が整合する
- 当面の org projection へのつなぎ方が決まる

依存:

- Issue 3
- Issue 4

注意:

- いきなり既存 `ClockService` を全面置換しない
- 並行期間を前提にする

## Issue 6: Add sync status model for pending local events

目的:

- UI で `未同期イベントあり` を出せるようにする

Done:

- local pending count を取得できる
- `synced / pending / error` の最小状態が出せる

依存:

- Issue 3

## Issue 7: Define event-sync transport contract

目的:

- command-sync ではなく event-sync 用の送受信 contract を定義する

Done:

- event push / fetch / ack の最小 contract が決まる
- cursor または last-seen の考え方が固まる
- transport schema と batch boundary が共有される

依存:

- Issue 1

メモ:

- 実装は Phase 2 でもよい
- 先に contract だけ切っておくと後続が楽

関連:

- `docs/sync-event-transport-contract.md`

## Recommended Order

実装順:

1. Issue 1
2. Issue 2
3. Issue 3
4. Issue 4
5. Issue 6
6. Issue 5
7. Issue 7

## Notes For GitHub Issues

Issue 化するときのルール:

- 1 issue 1責務に寄せる
- 依存先 issue を本文に書く
- このドキュメントと `docs/sync-architecture-next.md` をリンクする
- 実装前提が変わったら issue だけでなく docs も更新する
