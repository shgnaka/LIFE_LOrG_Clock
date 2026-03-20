# Event Sync Wire Protocol and Cursor Semantics (Draft)

このドキュメントは、event-sync の wire level での意味論を固定するための草案です。

`docs/sync-event-transport-contract.md` が envelope と payload の形を定めるのに対して、
この文書は「cursor をいつ進めるか」「fetch / push / ack をどの順で扱うか」を明文化します。

## 1. Goals

- fetch / push / ack の進行条件を一意にする
- cursor の meaning を `exclusive fetch cursor` と `inclusive seen cursor` に分ける
- retry と duplicate を安全に扱えるようにする
- Android / Desktop runtime が同じ progress rule を使えるようにする

## 2. Terminology

- `source peer`: event を送る側
- `target peer`: event を受ける側
- `sinceCursor`: fetch の開始点。**exclusive**
- `nextCursor`: response の最後の event cursor。次の fetch では `sinceCursor` として使う
- `seenCursor`: ack で「ここまで見た」を示す。**inclusive**
- `acceptedCursor`: push で target が受理済みとして返す最後の cursor

## 3. Fetch Semantics

`fetch` は source peer の local event log から batch を取り出す操作。

### Request

- `sourcePeerId`
- `targetPeerId`
- `sinceCursor`
- `batchLimit`

### Rules

- `sinceCursor` は exclusive
- `sinceCursor = null` の場合は最初から取得する
- `batchLimit` は 1 以上
- 返却順は cursor の昇順
- cursor はギャップなく単調増加であることを前提にする

### Response

- `events`
- `nextCursor`
- `hasMore`

### Response Rules

- `events` が空なら `nextCursor` は `null`
- `events` が 1 件以上なら `nextCursor` は最後の event の cursor
- `hasMore = true` の場合、次回 fetch は `nextCursor` を `sinceCursor` として使う
- `hasMore = false` の場合でも、受信側は `lastSeenCursor` を progress として保存してよい

### Example

`sinceCursor = 10` なら、返却対象は `cursor > 10` の event だけ。

もし `11, 12, 13` が返ったら:

- `lastSeenCursor = 13`
- `nextCursor = 13`
- 次回の `sinceCursor` は `13`

## 4. Push Semantics

`push` は source peer が既に持っている batch を target peer に送る操作。

### Request Rules

- `events` は cursor 昇順
- batch 内の cursor は strictly increasing
- 同一 batch に同じ `event_id` を複数回含めない

### Target Behavior

- `event_id` が既知なら duplicate として no-op にしてよい
- `event_id` が未知なら append を試みる
- invalid sequence や trust violation は reject してよい

### Response Rules

- `acceptedCursor` は target が受理した最後の cursor
- duplicate / rejected の詳細は補助情報として返せる
- reject の場合は `acceptedCursor` を進めない

## 5. Ack Semantics

`ack` は target peer が source peer に「この cursor まで見た」ことを返す操作。

### Rules

- `seenCursor` は inclusive
- `acknowledgedEventIds` は実際に取り込んだ event の確認材料
- `acknowledgedAt` は retry / diagnostics 用のタイムスタンプ
- ack は delivery の成功確定ではなく、progress の確定として扱う

### Practical Rule

runtime は `fetch` の結果を取り込んだあと、最後に見えた cursor を `seenCursor` として ack する。

## 6. Cursor Progression Model

推奨する progress の流れ:

1. `fetch(sinceCursor = lastSeenCursor)`
2. 受信 batch を cursor 昇順で検証
3. local store に append
4. `seenCursor = response.lastSeenCursor`
5. ack を送信
6. `lastSeenCursor` を checkpoint に保存

push 側は次の流れ:

1. local store から `lastSentCursor` より後の batch を読む
2. target に push する
3. `acceptedCursor` を checkpoint に保存

## 7. Invalid Cases

以下は reject / quarantine の候補:

- batch が cursor 順でない
- trust record と peer identity が一致しない
- viewer peer が write batch を送る
- schema mismatch
- revoked peer からの delivery

## 8. Relation To Runtime

この wire semantics に従って、runtime は次の前提で実装する:

- checkpoint store は `lastSeenCursor` と `lastSentCursor` を別に持つ
- fetch は `sinceCursor` を exclusive に扱う
- ack は `seenCursor` で progress を閉じる
- duplicate は event_id で no-op に寄せる

