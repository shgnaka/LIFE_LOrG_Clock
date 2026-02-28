# iOS Support Roadmap

## 1. Objective

iOS 対応の目的は、既存 Android アプリのコアロジックを Kotlin Multiplatform (KMP) で共有し、iOS 側で再利用できる基盤を段階的に整備することです。  
"Done" は以下を満たした状態と定義します。

- 主要ドメインロジックが `:shared` に集約され、Android/iOS 双方でコンパイル検証できる
- iOS 側のホスト実装を開始できる最小構成（境界・依存・配布方針）が確立している
- 継続的な検証導線（CI + 進捗台帳）が運用されている

## 2. Scope

### In-scope

- KMP shared core の段階的移行
- iOS compile validation（主に macOS CI）
- マイルストーンベースの移行運用

### Out-of-scope（現時点）

- iOS UI の Android 同等機能までの完全実装
- Web 対応
- 永続化層の全面再設計

## 3. Milestones

- [x] M0: KMP bootstrap complete
- [x] M1: shared core migration (model/parser/domain/time)
- [ ] M2: platform-neutral repository boundary
- [ ] M3: iOS app host skeleton
- [ ] M4: iOS file access adapter + core flow
- [ ] M5: distribution/testing flow (Firebase/TestFlight decision notes)

## 4. Exit Criteria (Pass/Fail)

### M0 Exit Criteria

- `:shared` モジュールが追加済みで `commonMain` / `androidMain` / `iosMain` が定義されている
- Android 側ビルド（`assembleDebug`）が通る
- iOS ターゲット検証用 workflow (`.github/workflows/verify-kmp-ios.yml`) が通る

### M1 Exit Criteria

- model/parser/domain/time が `shared/commonMain` に移行済み
- Android 固有実装は `androidMain` 側へ分離済み
- `:shared` の Android コンパイルと `:app` assemble がグリーン
- 進捗が `docs/ios-support/progress.md` に記録済み

### M2 Exit Criteria

- Repository のプラットフォーム依存境界が明確化され、interface が `commonMain` で定義される
- Android 実装は `androidMain` に閉じる
- iOS 側で実装差し替え可能な設計になっている

### M3 Exit Criteria

- iOS アプリホスト（最小 skeleton）が起動可能
- `:shared` の主要ユースケースを iOS ホストから呼び出せる
- macOS CI で iOS 側ビルド検証が継続して通る

### M4 Exit Criteria

- iOS file access adapter が実装され core flow で利用できる
- 主要シナリオ（読み取り・保存・エラー処理）の動作確認が完了
- 既知制約と次アクションが progress に記録される

### M5 Exit Criteria

- iOS 配布/検証フロー（Firebase App Distribution または TestFlight 方針）が文書化される
- チーム内で再実行可能な手順が整備される
- 受け入れテスト観点と運用責任範囲が合意される

## 5. Risk & Dependency Register

- macOS 実行環境依存: iOS ビルド・署名・配布検証は macOS が必要
- Signing/Distribution 制約: Apple Developer Program, provisioning profile, tester 管理
- Kotlin/AGP/KMP 互換性: バージョン組み合わせによる警告/不整合リスク
- CI 実行安定性: iOS/Android ジョブの flakiness と実行時間増

## 6. Branching Rule

- ベースブランチ: `feat/ios_support`
- 作業ブランチ: `feat/ios/*`
- 各作業ブランチのマージ時、`docs/ios-support/progress.md` へ 1 エントリ以上を追記する
- PR 説明には milestone ID（例: M2）と progress エントリ日付を記載する

## 7. Current Status

- M0: Completed (`feat/ios/kmp-bootstrap`)
- M1: Completed (`feat/ios/shared-core-migration-1` + 後続の datetime 移行を `feat/ios_support` へ反映済み)
- M2: In progress（`feat/ios/repository-boundary-m2-1` で repository 境界の platform-neutral 化を実施中）

## 8. Update Workflow (for future Codex sessions)

### Branch 開始時

1. `docs/ios-support/roadmap.md` を読む
2. `docs/ios-support/progress.md` の最新エントリを読む
3. roadmap の milestone から次タスクを選ぶ

### Branch 終了時

4. `progress.md` に verification command/result 付きで 1 エントリ追記する
5. milestone 状態が変化した場合のみ roadmap のチェック状態を更新する
6. PR 説明に milestone ID と progress エントリ日付を記載する
