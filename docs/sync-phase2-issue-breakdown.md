# Sync Phase 2 Issue Breakdown (Draft)

このドキュメントは、event transport と peer/runtime を導入する Phase 2 の issue 分解メモです。

Phase 2 の目的:

- local event truth を端末間で交換できるようにする
- Android / Desktop 間の event sync を成立させる
- viewer/read-only state を event-derived state に寄せる

## Issue 1: Define peer registration and trust record model

目的:

- `device_id` / `peer_id` / public key / role を含む trust record を正式定義する

Done:

- trust record schema が確定
- full peer / viewer peer の role が定義される
- pair / revoke / repair の流れが整理される

関連:

- `docs/sync-peer-trust-model.md`

## Issue 2: Define the event-sync wire protocol and cursor semantics

目的:

- event push / fetch / ack / cursor semantics を shared 契約として定義する

Done:

- transport contract が確定
- `last_seen` または cursor の扱いが確定
- duplicate / retry の扱いが整理される
- batch boundary と ack semantics が共有される

依存:

- Phase 1 schema 安定化

関連:

- `docs/sync-platform-runtime-matrix.md`
- `docs/sync-event-transport-contract.md`
- `docs/sync-event-wire-protocol-cursor-semantics.md`

## Issue 3: Implement peer trust storage and pairing flow

目的:

- peer 登録情報と公開鍵を durable に保存し、pairing の最小 UI/flow を作る
- manual pairing の最小 UX を固定する

Done:

- trusted peer record を保存できる
- public key を含む登録ができる
- revoke と repair が可能
- role を pair 時に確定できる

依存:

- Issue 1

関連:

- `docs/sync-minimum-pairing-ux.md`

## Issue 4: Implement Android event-sync runtime

目的:

- Android で event sync の送受信 runtime を動かす

Done:

- operation-triggered sync
- resume sync
- periodic sync
- pending/error state 更新
- peer ごとの fetch / push / ack orchestration
- peer checkpoint への last seen / last sent 保存
- app start / resume / maintenance tick からの flush 連携

依存:

- Issue 2
- Issue 3

## Issue 5: Implement Desktop event-sync runtime

目的:

- Desktop で event sync の送受信 runtime を動かす

Done:

- operation-triggered sync
- periodic sync
- optional host/listener mode
- pending/error state 更新
- local event store snapshot の再公開
- root open 時の即時 sync
- Desktop settings からの `Sync now`

依存:

- Issue 2
- Issue 3

## Issue 6: Add sync cursor / last-seen persistence

目的:

- peer ごとの event catch-up 状態を保存できるようにする

Done:

- peer ごとの cursor が保存される
- restart 後も catch-up を継続できる
- trust record とは別の checkpoint store に progress を保持する

依存:

- Issue 2
- Issue 3

## Issue 7: Add invalid-event reject and quarantine reporting

目的:

- invalid remote event を記録し、recoverable に扱う

Done:

- reject reason を記録できる
- quarantine 相当の保留先がある、または導入方針が確定
- UI/support で確認できる最小情報が出る
- incoming invalid event を device mismatch / viewer peer / batch order の粒度で quarantine できる
- local sync 状態と settings から quarantine 件数と最後の reject reason を確認できる

依存:

- Issue 2

## Issue 8: Add event-derived sync status UX

目的:

- pending / synced / error / recovery-required を UI で見せる

Done:

- Android / Desktop で同じ意味の sync status が出る
- `Sync now` と retry が使える
- quarantine 後は recovery-required として扱える

依存:

- Android runtime
- Desktop runtime
- cursor persistence

## Issue 9: Add viewer/read-only state delivery path

目的:

- viewer が active clock / history snapshot を event-derived state から読めるようにする

Done:

- viewer 向け state delivery の最小 path が決まる
- full peer と viewer peer の role 境界が守られる
- viewer は read-only projection snapshot を受け取れる
- 初期 viewer は host 側 snapshot subscribe として扱う

依存:

- peer/trust model
- event transport

## Recommended Order

実装順:

1. Issue 1
2. Issue 2
3. Issue 3
4. Issue 6
5. Issue 4
6. Issue 5
7. Issue 7
8. Issue 8
9. Issue 9

## Related Docs

- `docs/sync-peer-trust-model.md`
- `docs/sync-platform-runtime-matrix.md`
- `docs/sync-migration-plan.md`
