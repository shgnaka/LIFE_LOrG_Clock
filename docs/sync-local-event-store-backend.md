# Sync Local Event Store Backend (Phase 1 Default)

このドキュメントは、Phase 1 の local event log をどの storage backend で始めるかを固定するための決定メモです。

## Decision

Phase 1 の既定 backend は SQLite 系とする。

- Android: Room
- Desktop: SQLite 互換の JVM 実装
- shared: storage contract と event model のみを持つ

重要:

- Phase 1 では storage 実装を完全共有しない
- ただし contract は shared に置き、Android / Desktop 実装が同じ意味論を守る

## Why SQLite Family

SQLite 系を既定にする理由:

- durable store として扱いやすい
- append-only event log を素直に表現できる
- `event_id` の unique index を張りやすい
- replay / `listSince(cursor)` を実装しやすい
- pending sync / cursor / reject reason のような周辺 metadata を同居させやすい
- Android / Desktop の両方で実績のある実装手段がある

## Why Not JSONL First

JSONL は初期実装が軽い一方で、Phase 1 の時点でも不足が大きい。

- `event_id` idempotency の保証が弱い
- restart-safe な cursor 管理を別ファイルで持ちたくなりやすい
- pending sync 件数や checkpoint 更新が file rewrite に寄りやすい
- quarantine / reject reason の追跡を後付けしにくい
- 後続の event-sync metadata を載せ始めた時に分割管理が増えやすい

検証用・一時実装としてはあり得るが、Phase 1 の既定 backend にはしない。

## Minimum Phase 1 Storage Shape

最低限必要な保存対象:

- event 本体
- local append 順序を示す cursor
- `event_id` の idempotency 情報
- pending sync 判定に必要な sync checkpoint

最低限必要な読み書き:

- `append(event)`
- `contains(eventId)`
- `readAllForReplay()`
- `listSince(cursor)`
- `readSnapshot()`
- `updateSyncCheckpoint(cursor)`

## Suggested Table Shape

実テーブル名は platform ごとに違ってよいが、意味論は以下を満たす。

### `clock_events`

- `seq` or `cursor`: append 順の単調増加キー
- `event_id`: unique
- `schema`
- `event_type`
- `device_id`
- `created_at`
- `logical_day`
- `file_name`
- `heading_path`
- `causal_kind`
- `causal_counter`

### `clock_event_sync_state`

- singleton または peer-scope 付き metadata
- `last_synced_cursor`
- 将来の `last_seen_remote_cursor` や reject counters を追加できる形

Phase 1 では peer ごとの詳細 cursor までは必須ではない。
まずは local pending state を安定して持てることを優先する。

## Platform Notes

### Android

Room を使う。

- schema migration を扱いやすい
- index / unique constraint を明示しやすい
- test support がある

### Desktop

SQLite 互換の JVM 実装を使う。

- platform file I/O より query/metadata 管理を優先する
- Android と同じ SQL 的な設計感を保つ

Phase 1 では Android と Desktop で ORM や driver が一致していなくてもよい。

## Non-Goals

この決定でまだ固定しないもの:

- Android / Desktop の完全共通 DB layer
- peer ごとの full sync cursor schema
- remote reject quarantine の最終 table 設計
- event signature / encrypted payload 保存形式

## Follow-up

この決定の次に着手する項目:

1. `clock.event.v1` と shared event store contract を shared code に置く
2. Android / Desktop の local store 実装を追加する
3. projection と pending sync state を event log から導出する
