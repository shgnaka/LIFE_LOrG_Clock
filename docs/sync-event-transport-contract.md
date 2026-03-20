# Event Sync Transport Contract (Draft)

このドキュメントは、Phase 2 で導入する event-sync の送受信契約を固定するための草案です。

目的は transport 実装ではなく、shared で使う wire semantics を先に固めることです。

cursor の意味論は [`docs/sync-event-wire-protocol-cursor-semantics.md`](/home/shgnaka/LIFE_LOrG_Clock/docs/sync-event-wire-protocol-cursor-semantics.md)
に分離して整理しています。

## 1. Goals

- batch ベースで event を交換できること
- fetch / push / ack の意味がぶれないこと
- cursor / last-seen の扱いが一意であること
- duplicate / retry を no-op に寄せやすいこと

## 2. Shared Wire Schema

Phase 2 transport の schema 名:

- `clock.event.transport.v1`

この schema は event payload そのものとは別に、transport envelope の互換性を表す。

Shared code では、この wire schema を使う transport contract として
[`EventSyncTransport.kt`](/home/shgnaka/LIFE_LOrG_Clock/shared/src/commonMain/kotlin/com/example/orgclock/sync/EventSyncTransport.kt)
を定義する。

## 3. Roles

### Source peer

- event を送る側
- local event log の一部を batch 化して送信する

### Target peer

- event を受ける側
- `event_id` と cursor に基づいて idempotent に取り込む

## 4. Fetch Contract

`fetch` は target peer から source peer の event を取りに行く操作。

Request:

- `sourcePeerId`
- `targetPeerId`
- `sinceCursor` optional
- `batchLimit`

Semantics:

- `sinceCursor` は exclusive
- null の場合は最初から取得する
- `batchLimit` は 1 以上
- response は cursor 順でソートされる

Response:

- `events`
- `nextCursor`
- `hasMore`

`nextCursor` は response に含まれた最後の event cursor を指す。
response が空なら `nextCursor` は null。

## 5. Push Contract

`push` は source peer が自分の local batch を target peer に送る操作。

Request:

- `sourcePeerId`
- `targetPeerId`
- `events`

Semantics:

- events は cursor 順で送る
- `event_id` による duplicate は no-op
- target は取り込み済み cursor を progress として返してよい

Response:

- `acceptedCursor`
- `duplicateEventIds`
- `rejectedEventIds`
- `rejectReason`

## 6. Ack Contract

`ack` は target peer が source peer に「ここまで見た / 受け取った」を返す操作。

Fields:

- `seenCursor`
- `acknowledgedEventIds`
- `acknowledgedAt`

Semantics:

- ack は retry の終点を示す
- cursor ベースの再送制御に使う
- `seenCursor` は source 側の cursor に対する last-seen を意味する

## 7. Cursor Rules

- cursor は単調増加
- cursor は fetch / push の batch boundary と ack の progress indicator を兼ねる
- `sinceCursor` は exclusive
- receiver は duplicate event を既に見た cursor 以前として扱ってよい
- `lastSeenCursor` は受信済み batch の最後の cursor
- `seenCursor` は ack で示す inclusive progress

## 8. Error Model

transport 側で区別したい失敗:

- unknown peer
- revoked peer
- schema mismatch
- invalid batch order
- duplicate batch
- transient network failure

基本方針:

- local event log の保存失敗は operation failure
- remote delivery failure は pending/retry で扱う
- invalid remote event は reject / quarantine に寄せる

## 9. Relationship To Phase 2

この契約は Phase 2 の runtime に向けた shared input であり、実装順は次の通り:

1. peer trust storage
2. transport contract
3. cursor persistence
4. Android runtime
5. Desktop runtime
