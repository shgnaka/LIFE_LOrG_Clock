# Heading ID Introduction Decision (Draft)

このドキュメントは、長期同期設計で `heading_id` をいつ、どの範囲で導入するかを判断するためのメモです。

## 1. Problem

現状の Org Clock は見出しターゲットを主に `heading_path` で識別している。

例:

- `Work/Project A`
- `Projects/Backlog`

この方式は以下の操作では十分機能する。

- 既存の同一ファイル内見出しへの clock start/stop/cancel
- local UI での見出し選択
- file reload 後の再解決

しかし、長期的な sync 設計では問題がある。

主な問題:

- rename に弱い
- 同名見出しや再編成に弱い
- event log で安定識別子として使いにくい
- 他端末で path が変わったときに event の意味が揺れる

## 2. Current Position

現状:

- `HeadingPath` は広く使われている
- parser も domain も UI も path ベース
- sync の command payload も `heading_path` ベース

つまり、いきなり `heading_id` に全面移行するのは変更範囲が大きい。

## 3. Decision Summary

判断:

- Phase 1 では `heading_id` を必須にしない
- Phase 1 の clock event v1 は移行期識別子として `heading_path` を使う
- ただし Phase 2 に入る前に `heading_id` 導入方針を確定する
- heading add / rename を event 化する前に `heading_id` は必須とする

一言でいうと:

- `clock-only event sourcing` の導入は `heading_path` で開始してよい
- `structure-changing sync` に進む前には `heading_id` が必要

## 4. Why Not Introduce It Immediately

即時導入を避ける理由:

- 現在のコード全体が `HeadingPath` に強く依存している
- Phase 1 の目的は clock event log の導入であり、見出し構造の全面再設計ではない
- 一度に変更すると migration risk が上がる
- Android / Desktop / shared parser / tests への波及が大きい

## 5. Why It Is Still Necessary

長期的に `heading_id` が必要な理由:

- `heading_renamed` を event 化すると path が変わる
- `heading_moved` に相当する操作を event 化すると path はもはや識別子にならない
- viewer / sync / audit で見出しを安定して参照したい

必要になるフェーズ:

- heading create event
- heading rename event
- heading move / restructure
- template-tagged heading tracking

## 6. Recommended Timing

### Phase 1

- 導入しない
- `heading_path` を移行期ターゲット識別子として使う

### Phase 2

- `heading_id` の設計と導入を始める
- 少なくとも parser / model / projection に `heading_id` を持てる余地を追加する

### Phase 3

- heading operations を event 化する前に `heading_id` を primary identifier に昇格する

## 7. Practical Migration Strategy

推奨段階:

1. `HeadingPath` を維持したまま model に optional `headingId` の余地を作る
2. parser が local org file から stable `headingId` を取得できる仕組みを検討する
3. 新規作成 heading に対して `heading_id` を付与する
4. 既存 heading には migration 時に ID を埋める
5. sync/event 層は徐々に `heading_id` を優先し、`heading_path` を fallback にする

## 8. Candidate Approaches

### A. Hidden property inside org heading

例:

- property drawer に `ORGCLOCK_HEADING_ID`

利点:

- org file 内に自己完結する
- 他端末へ projection/export しても ID を維持しやすい

弱点:

- org file を projection artifact とみなす方針との整合が必要
- direct edit で消される可能性がある

### B. External sidecar mapping

例:

- local metadata DB
- sidecar JSON

利点:

- org file を汚さない

弱点:

- 他端末へ移すと ID 追跡が難しい
- projection 再生成時の対応づけが難しい

### C. Event-log-origin identity

考え方:

- heading create event が heading_id を配る
- local projection が org へ反映する

利点:

- event-first と相性がよい

弱点:

- 既存 org-first 見出しへの移行が難しい

## 9. Recommended Direction

現時点の推奨:

- 長期的には A と C の折衷
- すなわち:
  - 将来の heading create event は `heading_id` を生成する
  - projection された org heading には stable ID を埋め込めるようにする

ただし、Phase 1 では実装しない。

## 10. Risks If Delayed Too Long

`heading_id` 導入を遅らせすぎると:

- event schema が path 依存で固定化される
- rename sync の設計が何度もやり直しになる
- viewer/history/audit の参照整合が弱くなる

## 11. Final Recommendation

最終判断:

- `heading_id` は必要
- ただし Phase 1 にねじ込まない
- Phase 2 の必須設計課題として扱う
- heading operation を event 化する前に導入する

## 12. Related Docs

- `docs/sync-architecture-next.md`
- `docs/sync-event-schema-v1-draft.md`
- `docs/sync-migration-plan.md`
