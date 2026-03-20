# Clock Event Schema v1 (Draft)

このドキュメントは、Phase 1 で最初に導入する clock event log の最小 schema を定義するための草案です。

対象は次の 3 操作です。

- `clock-in`
- `clock-out`
- `clock-cancel`

この段階では、見出し追加・見出し rename・template 更新は含めません。

## 1. Goals

この schema の目的:

- local clock operation を append-only event として永続化できること
- 複数端末で event を交換できること
- 再適用しても同じ結果になること
- active clock / history / viewer state をこの event から再構成できること

## 2. Non-Goals

この v1 では扱わないもの:

- heading create / rename event
- template update event
- generated daily file event
- org file の全文差分同期
- rich CRDT merge

## 3. Event Envelope

全 event 共通で持つ項目:

- `schema`
- `event_id`
- `event_type`
- `device_id`
- `created_at`
- `logical_day`
- `file_name`
- `heading_path`
- `causal`

v1 草案:

```json
{
  "schema": "clock.event.v1",
  "event_id": "evt-01ARZ3NDEKTSV4RRFFQ69G5FAV",
  "event_type": "clock_started",
  "device_id": "device-a",
  "created_at": "2026-03-18T10:15:30Z",
  "logical_day": "2026-03-18",
  "file_name": "2026-03-18.org",
  "heading_path": "Work/Project A",
  "causal": {
    "kind": "lamport_v1",
    "counter": 42
  }
}
```

## 4. Field Semantics

### `schema`

- 固定値: `clock.event.v1`

### `event_id`

- 端末横断で一意
- append-only event の idempotency key
- UUIDv7 / ULID など時系列性のある ID を推奨

### `event_type`

許可値:

- `clock_started`
- `clock_stopped`
- `clock_cancelled`

### `device_id`

- event を生成した端末の stable ID
- peer_id とは分けてよい

### `created_at`

- UTC timestamp
- event 自体の生成時刻

### `logical_day`

- その clock event が属する論理日付
- `YYYY-MM-DD`
- local projection で daily org file を再構成するときに使う

### `file_name`

- 移行期の補助情報
- 当面は `YYYY-MM-DD.org`
- 将来は projection 側で再計算可能なら、v2 以降で optional 化を検討する

### `heading_path`

- 移行期のターゲット識別子
- Phase 1 では `heading_id` が未導入のため path を使う
- rename をまたぐ安定識別子ではないため、Phase 2 以降で置換前提

### `causal`

v1 では最小限の Lamport counter を採用する。

```json
{
  "kind": "lamport_v1",
  "counter": 42
}
```

含める理由:

- 同端末内順序を比較しやすくする
- 収束ロジックの将来拡張余地を持つ

## 5. Event Types

### 5.1 `clock_started`

意味:

- 対象見出しで clock が開始された

追加 payload は持たない。
開始時刻は `created_at` をそのまま使う。

### 5.2 `clock_stopped`

意味:

- 対象見出しの open clock を閉じた

追加 payload は持たない。
終了時刻は `created_at` をそのまま使う。

### 5.3 `clock_cancelled`

意味:

- 対象見出しの最新 open clock を取り消した

追加 payload は持たない。

## 6. Projection Rules (Phase 1)

Phase 1 の projection は、同一 `logical_day + heading_path` 単位で event を読む。

基本ルール:

1. `clock_started` が来たら open clock を作る
2. open clock がある状態で `clock_stopped` が来たら closed history に移す
3. open clock がある状態で `clock_cancelled` が来たら open clock を削除する

異常系:

- open clock がないのに `clock_stopped`:
  - invalid projection input として記録
  - v1 では quarantine または warning 扱いを検討
- open clock がないのに `clock_cancelled`:
  - 同上
- open clock がある状態で再度 `clock_started`:
  - v1 では invalid とみなす

## 7. Idempotency Rules

- `event_id` が同じ event は一度だけ適用する
- duplicate receive は成功 no-op として扱う

## 8. Local Persistence Shape

実装形式は未固定だが、最低限必要なのは以下:

- append-only storage
- event_id index
- replay 可能であること

候補:

- JSONL
- SQLite table
- Room / SQLDelight / multiplatform DB

## 9. Open Questions

- `file_name` を v1 で必須にするか補助情報にするか
- `heading_path` をどの Phase まで許容するか
- invalid projection input を quarantine するか単純 reject にするか
- event signature を v1 から入れるか transport trust に委ねるか

## 10. Next Step

この草案が固まったら、次の issue を切る。

- shared event model 型を追加する
- local event store interface を追加する
- local projection 実装を追加する
- `ClockService` から event append ベースへ寄せる移行方針を決める
