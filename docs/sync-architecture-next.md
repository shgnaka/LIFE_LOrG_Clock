# Cross-Platform Sync Architecture (Draft)

このドキュメントは、Org Clock の長期的な同期機能をどのように設計していくかを整理するための母艦ドキュメントです。

既存の `docs/synccore-integration/` は、現在の Android 中心の command-sync 実装の現状整理を扱います。
このドキュメントはそれとは別に、Desktop / Android / 将来の iOS / macOS を含むクロスプラットフォーム前提の長期アーキテクチャを扱います。

## 1. Purpose

目標:

- 複数端末で clock 記録を同期できること
- 表示同期だけでなく、記録操作そのものを同期できること
- Android / Desktop / 将来の iOS / macOS で整合した意味論を持つこと
- offline-first / local-first な体験を壊さないこと
- viewer / read-only mode を同じ基盤の上で実現できること

この設計の対象は「同期の最終形」です。
現行の sync 実装をすぐ捨てることは目的ではなく、将来どこへ寄せるべきかを明確にすることを目的とします。

## 2. Current State

現状の sync 実装は、以下の性質を持つ command-sync です。

- 同期単位は `clock.start / clock.stop / clock.cancel`
- 送信側が command payload を送る
- 受信側が local org file に対して command を実行する
- 実行結果を `clock.result.v1` で返す

この方式は短期的にはわかりやすく、既存の Android 実装にも接続しやすいです。
一方で、長期的には以下の弱点があります。

- 状態同期ではなく remote command execution に寄っている
- viewer/read-only 用の状態配信と相性が弱い
- 将来的な多端末収束モデルが弱い
- platform 差分を吸収する共通 sync core に育てにくい

## 3. Design Direction

長期的な同期の中心は、command ではなく event log に寄せる。

基本方針:

- 各端末は local-first に動作する
- 各端末はローカルにイベントログを保持する
- 端末間ではイベントを交換し、最終的に収束させる
- `org` ファイルは同期の真ではなく、ローカル projection / materialized artifact として扱う

重要:

- `org` ファイルを真にしないことは、local-first を捨てることではない
- 真が「中央サーバ」になるわけではなく、「各端末が保持する同期済みイベント集合」が真になる

## 4. Source of Truth

### 4.1 Current

現在:

- local org file が真
- 他端末はその端末に command を送って local org file を更新させる

### 4.2 Target

長期目標:

- event log が真
- 各端末は event log を local storage に保持する
- materialized state を event log から再構成する
- org file は materialized state から生成・更新される local projection とみなす

## 5. What Becomes Event-Sourced First

最初に event-log 真へ移行する対象:

- `clock-in`
- `clock-out`
- `clock-cancel`

理由:

- 操作単位が明確
- append-only event と相性がよい
- viewer/read-only 表示と直接つながる
- 履歴監査と復旧に強い

初期イベント候補:

- `clock_started`
- `clock_stopped`
- `clock_cancelled`

各イベントが最低限持つべき情報:

- `event_id`
- `event_type`
- `device_id`
- `created_at`
- `target_file_id` または `logical_day`
- `target_heading_id` または移行期の `heading_path`
- 必要な causal metadata

## 6. Non-Clock Operations

### 6.1 Heading add / rename

見出し追加と見出し名変更は、長期的には event 化した方がよい。

候補:

- `heading_created`
- `heading_renamed`

ただし、これは clock 系より難しい。
理由は `heading_path` が rename に弱く、安定識別子にならないため。

そのため、将来的には `heading_id` 導入を前提に設計する。

暫定方針:

- Phase 1 では org 真のままでもよい
- Phase 2 以降で heading operation の event 化を検討する

### 6.2 Template update

テンプレート更新は、clock event と同じ core event stream に混ぜない。

理由:

- 性質が「履歴イベント」より「設定・資産」に近い
- 競合解決方針が clock と異なる
- 即時同期の重要度が相対的に低い

暫定方針:

- template は別系統の設定同期として扱う
- core clock sync とは責務を分ける

### 6.3 Auto-generation and generated daily files

テンプレート反映による自動ファイル生成は、真そのものではなく派生物として扱う。

真:

- template definition
- generation rule
- schedule/config

派生物:

- 生成された `.org` file

必要であれば、監査用途で `daily_file_generated` のようなイベントは残してよいが、生成ファイル自体を真にしない。

## 7. Org File Semantics in the Target Model

長期設計での org file の扱い:

- 各端末の local storage に存在してよい
- A / B / C それぞれの端末に保存されてよい
- ただし、同期の正しさの根拠は org file ではなく event log に置く

### 7.1 Temporary divergence

未同期の間は、端末間の org file に一時的な差が出ることは許容する。

これは矛盾ではなく未収束状態であり、イベント同期が進めば最終的に収束する想定にする。

### 7.2 Repair model

org file が壊れた、または他端末と一致しない場合は、event log から再投影して復旧できる構造にする。

復旧手順の基本形:

1. local event log を検証する
2. materialized state を再構成する
3. org file を再生成または再投影する

この性質を担保するため、org file は可能な限り「再構築可能な投影物」に寄せる。

## 8. Sync UX Principles

event log を真にしても、ユーザーに毎回同期操作を要求しない。

基本 UX:

- 記録操作は常にローカルで即時保存する
- 同期は自動で試みる
- 必要時のみ `Sync now` を提供する

想定される同期トリガ:

- clock 操作直後
- app 起動時
- foreground 復帰時
- 定期 background tick
- manual retry

ユーザーに見せるもの:

- sync state
- last synced time
- pending local changes
- retry action

## 9. Peer Model

同期には、少なくとも以下が必要:

- `device_id`
- `peer_id`
- trust relationship
- endpoint / transport reachability

理想 UX:

- 初回だけ peer registration / trust
- 以後は自動同期

viewer/read-only peer は full peer より権限を狭くできるが、識別と信頼モデルは必要。
初期 viewer は host 側の event-derived snapshot を読む read-only client として扱う。

## 10. Recommended Migration Path

### Phase 0

- 現行 command-sync を現状維持しつつ観測と整理を進める
- 現行 transport / trust / runtime の問題点を明文化する

### Phase 1

- clock event schema v1 を定義する
- local event log store を導入する
- local clock operation を event append に変える
- local projection で既存 UI を維持する

### Phase 2

- event sync path を導入する
- read-only viewer は event-derived state を読むようにする
- 現行 command-sync と並行運用する

### Phase 3

- heading operation の event 化を検討する
- template/schedule sync を別系統で整理する
- command-sync を縮退させる

## 11. Open Questions

- `heading_id` をいつ導入するか
- local org direct edit をどこまで許容するか
- org projection を双方向にするのか、一方向にするのか
- event causal metadata を Lamport にするか、それ以上を要するか
- sync transport を現行資産からどう置き換えるか
- iOS background 制約下でどこまで自動同期できるか

## 12. How To Use This Document

このドキュメントは設計の母艦として扱う。

運用ルール:

- 全体方針や用語変更はまずここを更新する
- 実装可能な粒度に固まったものだけ GitHub Issue に分解する
- Issue からこのドキュメントへリンクする
- 実装後に前提が変わったら Issue だけでなくこのドキュメントも更新する
