# Sync Failure and Recovery Plan (Draft)

このドキュメントは、同期機能を実運用に耐えられる設計へ寄せるために、想定障害と復旧方法を整理するものです。

対象:

- local event log
- local projection
- org projection
- sync transport
- peer/trust 状態

## 1. Objective

目的:

- 壊れたときにどう戻すかを先に決める
- local-first を維持したまま recoverable な構造にする
- event-log-first への移行時に運用不能になることを防ぐ

## 2. Recovery Principles

基本原則:

- 真は local event log に置く
- org file は projection / artifact として扱う
- 壊れた projection は再生成できるようにする
- duplicate / retry は成功 no-op に寄せる
- remote sync failure と local write failure を分けて扱う

## 3. Failure Categories

### 3.1 Local write failure

例:

- clock operation 時に event append に失敗
- local storage I/O error

扱い:

- local event が保存できなければ、その clock 操作は成功扱いにしない
- local truth を失う成功は許容しない

UX:

- 操作失敗として即時表示
- retry を促す

### 3.2 Pending sync failure

例:

- local event append は成功
- remote delivery だけ失敗

扱い:

- local operation 自体は成功
- pending local event として保持
- 後で自動 retry

UX:

- `pending sync`
- `last sync failed`
- `sync now`

### 3.3 Projection failure

例:

- event replay 中に invalid sequence を検出
- materialized state 構築が失敗

扱い:

- projection failure は local event log corruption と同一視しない
- event log が有効なら、projection は再生成対象

初期対応:

1. projection cache を破棄
2. event log から再構築

### 3.4 Org projection divergence

例:

- event-derived state と local org file の内容が一致しない
- org file が破損した

扱い:

- org file を真とはみなさない
- event log と projection の結果から再生成可能にする

初期対応:

1. divergence を検出
2. local org projection を再生成
3. 必要ならバックアップを保持して置換

### 3.5 Incoming invalid event

例:

- schema mismatch
- duplicate event
- invalid sequence
- trust/signature 不正

扱い:

- invalid event は local truth に適用しない
- reject reason を記録する
- quarantine 可能な構造を将来持てるようにする

### 3.6 Peer/trust failure

例:

- unknown peer
- revoked peer
- public key mismatch

扱い:

- sync delivery は拒否
- local event は保持
- trust 修復後に再同期可能にする

## 4. Recovery Scenarios

## Scenario A: Remote sync is down but local app must keep working

期待動作:

- local clock operation は成功
- local event log に append
- pending 状態で蓄積
- 後で送信 retry

これは local-first の最重要要件。

## Scenario B: Local org file is corrupted

期待動作:

- local event log から projection を再構成
- org file を再生成
- 必要なら破損ファイルを backup/quarantine へ退避
- UI では `Reload from disk` を最初の recovery action として出す

## Scenario C: Projection code changed and old cache became invalid

期待動作:

- cache / materialized snapshot を破棄
- event replay により再生成

## Scenario D: Duplicate event received from another peer

期待動作:

- `event_id` で no-op
- エラー扱いにしない

## Scenario E: Invalid event sequence received

例:

- open clock がないのに `clock_stopped`

期待動作:

- local truth へ適用しない
- reject / quarantine / warning を記録
- 他の valid event 処理を止めすぎない

## 5. Operational Signals

最低限観測できるようにしたいもの:

- pending local event count
- last successful sync timestamp
- last sync error
- projection replay error count
- invalid remote event count
- org divergence detected flag

## 6. UI States

ユーザーに見せる最小状態:

- `Synced`
- `Pending local changes`
- `Sync error`
- `Recovery required`

platform をまたいで同じ意味になるようにする。

## 7. Recovery Actions

ユーザーまたは運用側が実行できるようにしたいアクション:

- `Sync now`
- `Retry failed sync`
- `Rebuild local projection`
- `Rebuild org files from event log`
- `Reload from disk`
- `Reset peer trust for a device`

注意:

- destructive reset は最終手段にする
- local event log の削除は簡単に exposed しない

## 8. Backup and Rebuild Policy

最低限必要な方針:

- event log は durable に保存
- projection cache は再生成可能にする
- org projection の再生成前に旧ファイルを backup できるとよい

長期的な理想:

- event log は primary durable store
- projection / org files は rebuildable

## 9. Incident Handling Flow

重大な不整合が見つかった場合の基本手順:

1. local event log の読み取り可否を確認
2. projection replay を実行
3. org projection を再生成
4. still failing なら offending event / peer を isolate
5. trust / transport / storage のどこが原因か切り分ける

## 10. Design Implications

この recovery plan から逆算すると、実装には以下が必要:

- idempotent event apply
- replayable event store
- rebuildable projection
- org projection 再生成手段
- pending/error state model
- invalid event の reject 記録

## 11. Open Decisions

- invalid event を quarantine store に落とすか
- org divergence の自動修復と手動修復の境界
- projection cache を persistent にするか ephemeral にするか
- rebuild action を一般ユーザー向けに出すか debug/support 限定にするか

## 12. Related Docs

- `docs/sync-architecture-next.md`
- `docs/sync-event-schema-v1-draft.md`
- `docs/sync-migration-plan.md`
