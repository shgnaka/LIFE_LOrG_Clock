# Org Direct Edit Policy (Draft)

このドキュメントは、長期同期設計において local org file への直接編集をどこまで許容するかを決めるための方針書です。

## 1. Problem

長期設計では event log を真に寄せたい。
一方で、Org Clock は `org` ファイルを扱うアプリであり、ユーザーや外部エディタによる直接編集の期待もある。

ここで決めるべきこと:

- direct edit を全面許容するか
- 一部だけ許容するか
- projection artifact として保護するか

## 2. Core Principle

長期方針:

- clock sync の真は event log に置く
- org file は local projection / materialized artifact に寄せる

このため、clock 系の意味を変える direct edit は無制限に許容しない方がよい。

## 3. Decision Summary

判断:

- Phase 1 では direct edit を全面禁止しない
- ただし、clock event-log 対象領域については「best-effort coexistence」にとどめる
- 長期的には org direct edit を 3 区分で扱う

区分:

1. 許容する direct edit
2. 制限付きで許容する direct edit
3. 許容しない direct edit

## 4. Category A: Allowed Direct Edits

基本的に許容:

- コメント行
- メモ本文の追記
- 同期対象外セクションの編集
- 表示や整理のための軽微な非構造変更

前提:

- clock event projection の意味論を壊さないこと

## 5. Category B: Conditionally Allowed Direct Edits

条件付きで許容:

- 見出しタイトル変更
- 見出し並べ替え
- template 非依存の手動ファイル整理

理由:

- 現在は org-first 運用と共存している
- ただし将来的には heading event や `heading_id` 導入と衝突する

方針:

- Phase 1 では許容してよい
- ただし sync の真として扱わない
- 長期的には heading operation event 化後に制約を再定義する

## 6. Category C: Disallowed Direct Edits

長期的に禁止または unsupported とすべきもの:

- projection された CLOCK 行の手動編集
- event-derived history を意味的に書き換える編集
- sync state と矛盾する open clock の手動作成/削除

理由:

- event log と org projection が乖離する
- 他端末と収束しなくなる
- 復旧コストが上がる

## 7. Phase-Based Policy

### Phase 1

- direct edit は基本許容
- ただし clock event-log 導入後の projection mismatch は warning 扱い
- `CLOCK:` 手編集は unsupported と明記する

### Phase 2

- event-derived clock data 領域は protected 扱いを検討する
- viewer / sync projection との整合確認を強化する

### Phase 3

- heading operation event 化後、構造 edit の取り扱いを再定義する
- 必要なら direct edit import は別機能に分離する

## 8. Recommended Product Stance

短い表現でいうと:

- `org` は読めるし編集もできる
- ただし sync の真は event log
- 特に `CLOCK:` まわりの manual edit は保証対象外

## 9. Detection and UX

必要な仕組み:

- external change detection
- org projection divergence detection
- rebuild action
- `Reload from disk` を recovery の第一導線にする

ユーザーに見せるべき文言の方向性:

- `org files changed externally`
- `clock sync state may be stale`
- `rebuild from local event log`
- `rebuild from event log`

## 10. Recovery Stance

direct edit によって org projection が壊れた場合:

- event log が健全なら rebuild を優先する
- manual file contents を真として merge しない

これは「manual edit を全面否定する」のではなく、
「clock sync の整合性では event log を優先する」という意味。

## 11. What We Should Not Do Initially

最初からやらない方がよいもの:

- 任意 org 差分を event へ自動変換する importer
- external edit を完全双方向同期する仕組み
- 自由編集と event truth を同時に完全保証すること

理由:

- 複雑さが非常に高い
- heading identity / template / generated file と絡んで設計が爆発しやすい

## 12. Final Recommendation

推奨方針:

- Phase 1 では org direct edit を残す
- ただし `CLOCK:` と event-derived sync state は event truth を優先する
- heading/title/body の direct edit は暫定共存
- 長期的には protected projection 領域を増やす

## 13. Related Docs

- `docs/sync-architecture-next.md`
- `docs/sync-failure-recovery.md`
- `docs/sync-heading-id-decision.md`
