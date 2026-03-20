# Viewer Delivery Architecture (Initial Decision)

このドキュメントは、Org Clock の初期 viewer/read-only 配信のアーキテクチャを固定するための決定メモです。

## Decision

初期 viewer path は `read-only snapshot delivery` を採用する。

viewer は full peer にならず、event push / ack / cursor catch-up を持たない。
viewer が読むのは host 側の event-derived projection である。

## Why this choice

- write semantics を viewer に持ち込まなくてよい
- full peer の trust / cursor / retry と分離できる
- active clock / history の read-only 表示にそのままつながる
- viewer が support / dashboard / monitor の役割に寄る

## What the viewer reads

viewer に渡す最小 read model:

- active clock projection
- history snapshot
- projection issue summary
- last update time

viewer は local org projection の直接編集を行わない。

## Role boundary

### Full peer

- event receive
- event send
- sync cursor 更新
- recovery / retry

### Viewer peer

- read-only state fetch
- active clock projection の取得
- history snapshot の取得

## Consequences

- viewer delivery は event-sync runtime の上に乗せなくてよい
- host 側で projection を作り、viewer はそれを読む
- viewer は full peer の quarantine / ack ルールに従わない

## Relationship to current implementation

現在の実装では、Android の `SyncIntegrationService` が trusted viewer peer の存在を検知したときに、local `ClockEventStore` を `ClockEventProjector` で投影し、`SyncIntegrationSnapshot.viewerProjection` として公開する。

これは初期 viewer delivery として十分であり、将来 read-only API や push-based viewer を追加する場合も、この決定を壊さずに拡張できる。

