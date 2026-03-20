# Sync Migration Plan (Draft)

このドキュメントは、現在の command-sync / org-file-first 実装から、長期的な event-log-first 同期設計へどのように移行するかを整理するための計画書です。

## 1. Objective

移行の目的:

- 現行アプリを壊さずに同期基盤を置き換える
- `clock-in / out / cancel` を local event log 真へ寄せる
- viewer/read-only state 配信を event-derived state の上に載せられるようにする
- Android / Desktop / 将来の iOS / macOS で共通の sync core に近づける

重要:

- いきなり全面移行しない
- command-sync と event-sync の並行期間を前提にする
- org file-first から event-log-first への切り替えは、clock 系から段階的に行う

## 2. Current Baseline

現状の前提:

- local org file が操作の真
- remote peer は command を送る
- 受信側が local org file を更新する
- Android 実装が先行している

現状の資産として保持したいもの:

- `ClockService` の domain semantics
- 既存 UI の操作フロー
- local-first の体験
- Desktop / Android の基本的な root/file handling

## 3. Target State

移行完了後の理想形:

- clock 系操作の真は local event log
- 各端末は local event log を持つ
- local projection が active clock / history / org projection を生成する
- event sync により端末間で収束する
- viewer は projection された read model を読む

ただし、初期フェーズでは以下を許容する:

- heading operation は org-file-first のまま
- template/schedule は別系統
- org projection は完全再生成ではなく段階的導入

## 4. Migration Principles

### 4.1 Clock-first migration

最初に移行するのは clock 系だけに限定する。

対象:

- `clock-in`
- `clock-out`
- `clock-cancel`

非対象:

- heading add / rename
- template update
- generated daily file rules

### 4.2 Double-run transition

移行中は一時的に二重系を許容する。

意味:

- local operation 実行時に event append を導入する
- 既存の org update path も当面残す
- projection の結果と既存の local state を比較しながら移行する

### 4.3 No big-bang replacement

`ClockService` や UI を一度に置き換えない。

置換順:

1. event append
2. local projection
3. pending sync state
4. event transport
5. command-sync 縮退

### 4.4 Docs-first decisions

移行中の前提変更は、実装より先に docs を更新する。

最低限更新対象:

- `docs/sync-architecture-next.md`
- `docs/sync-event-schema-v1-draft.md`
- このドキュメント

## 5. Phased Plan

## Phase 0: Stabilize current sync as legacy baseline

目的:

- 現行 command-sync を legacy baseline として扱えるようにする
- transport / trust / runtime の問題点を明文化する

やること:

- 現行 sync の制約を docs に固定する
- command-sync を long-term solution と誤認しないよう整理する

完了条件:

- 現行 sync の責務と限界が docs 上で明確

## Phase 1: Introduce local event truth for clock operations

目的:

- local clock operation を event append ベースにする
- local projection で active clock / history を再構成できるようにする

やること:

- `clock.event.v1` 確定
- shared event model / event store contract 追加
- Android / Desktop local event store 追加
- projection 追加
- local pending sync state 追加

完了条件:

- local clock operation が event として保存される
- event replay で active clock と history が再構成できる
- UI が pending local events を表示できる

## Phase 2: Add event-sync transport alongside legacy command-sync

目的:

- 端末間で event を交換できるようにする
- viewer/read-only state を event-derived state で提供できるようにする

やること:

- event push/fetch/ack contract 定義
- peer trust / sync cursor / last-seen 実装
- event delivery runtime 導入
- viewer の read model を event projection 由来に切り替え

完了条件:

- Android と Desktop の間で event 同期できる
- command-sync なしでも active clock / history が追随する

## Phase 3: Deprecate legacy command-sync for clock operations

目的:

- clock 系について command-sync を縮退させる

やること:

- clock command publish を optional / legacy に落とす
- event-sync を primary path に昇格する
- debug/runtime docs を更新する

完了条件:

- clock sync の primary path が event-sync になる

## Phase 4: Extend beyond clock operations

候補:

- heading create / rename
- template/schedule sync
- generated file governance

これは clock sync が安定してから扱う。

## 6. Coexistence Strategy

並行期間の扱い:

- local write path は当面「event append + existing org update」
- remote sync path は当面「legacy command-sync + new event-sync」
- viewer は event-derived state へ先行移行してよい

重要:

- command-sync と event-sync が同じ clock 操作を重複適用しない設計が必要
- 並行運用中は source-of-truth の優先順位を docs で固定する

暫定優先順位:

1. local event log
2. local projection
3. local org projection
4. legacy command-sync path は互換レイヤ

## 7. Cutover Criteria

clock 系を legacy command-sync から切り替える条件:

- local event append が安定
- projection が既存 UI と一致
- event transport が Android / Desktop 間で安定
- failure recovery が運用可能
- pending/error 状態が UI で追える

## 8. Rollback Strategy

もし event-sync 導入で問題が起きた場合:

1. event transport を無効化する
2. local event append は保持してよいが remote sync は止める
3. 既存 command-sync を fallback path に戻す
4. projection mismatch や replay failure を調査する

rollback で守るべきもの:

- local clock operation が失われないこと
- org file が読めること
- 既存 UI が最低限動くこと

## 9. Open Decisions

- 並行期間中に org direct edit をどう扱うか
- event append と org mutation の atomicity をどう担保するか
- local event log をどの storage で始めるか
- iOS/macOS での Phase 1 参加タイミング

## 10. Recommended Next Documents

この移行計画とセットで読むべきもの:

- `docs/sync-architecture-next.md`
- `docs/sync-event-schema-v1-draft.md`
- `docs/sync-failure-recovery.md`
