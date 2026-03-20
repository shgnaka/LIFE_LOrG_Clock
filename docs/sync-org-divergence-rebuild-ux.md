# Org Divergence Detection and Rebuild UX

このドキュメントは、event-derived state と local org file がずれたときの検知と復旧 UX を固定するための決定メモです。

## Decision

org divergence は warning / recovery-required として扱う。
merge は第一選択にせず、`Reload from disk` を最初の復旧導線にする。

## Detection signals

最初の実装で使うシグナル:

- external change detection
- selected file の外部更新
- projection / replay の失敗
- local org file が再読込後に不一致を示す状態

## UX

ユーザーに見せる文言の方向性:

- `Org files changed on disk`
- `Reload recommended`
- `Reload from disk`

## Recovery actions

初期の recovery actions:

- `Reload from disk`
- `Sync now`

## Current implementation

現在の desktop 実装では、`ExternalChangeNotice` が来たら `OrgClockStore` が `externalChangePending` を立てる。
同時に Desktop settings と external change banner で `Reload from disk` を出す。

Android 側も settings の sync/recovery section から同じ recovery action を出す。

## Why this choice

- event log を真として保てる
- manual edit と projection の不一致を merge で複雑化しない
- support/debug で説明しやすい
- 将来の automatic rebuild に拡張しやすい
