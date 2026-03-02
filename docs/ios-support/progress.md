# iOS Support Progress Ledger

iOS 対応の実行記録です。将来セッションへのハンドオフを目的として、以下の運用に従います。

- append-only（履歴の書き換えは禁止。 typo 修正のみ例外）
- 各 `feat/ios/*` マージ時に 1 エントリ以上追記
- すべてのエントリに「検証コマンド + 結果」を含める

---

## 2026-02-27 - M0 Bootstrap 完了

- Date: 2026-02-27
- Branch: `feat/ios/kmp-bootstrap`
- Commit(s): `619dbe9`
- Milestone: `M0`
- Summary of changes:
  - `:shared` モジュール追加
  - KMP ターゲット（`commonMain` / `androidMain` / `iosMain`）定義
  - iOS 検証 workflow（`verify-kmp-ios.yml`）追加
  - KMP 初期警告系の整理
- Verification commands + results:
  - `./gradlew :shared:tasks :shared:compileDebugKotlinAndroid :app:assembleDebug`
  - 結果: Android 側 compile/assemble が通ることを確認
  - CI: macOS の verify workflow が通過（PR #9）
- Known gaps / next action:
  - ドメインロジックの `commonMain` への本格移行は未着手
  - 次: model/parser/domain/time の shared 化（M1）
- PR link:
  - https://github.com/shgnaka/org-clock-android/pull/9

## 2026-02-27 - M1 Shared Core Migration (Phase 1)

- Date: 2026-02-27
- Branch: `feat/ios/shared-core-migration-1`
- Commit(s): `65b427c`
- Milestone: `M1`
- Summary of changes:
  - model/parser/domain/time を `shared/commonMain` へ移行
  - `ClockRepository` 導入で repository 境界の足場を整備
  - iOS コンパイル阻害要素を分離し Android 互換を維持
- Verification commands + results:
  - `./gradlew :shared:tasks :shared:compileDebugKotlinAndroid :shared:compileReleaseKotlinAndroid :app:assembleDebug`
  - 結果: Android compile/test/assemble がグリーン
  - CI: verify workflow を含む PR チェック通過（PR #10）
- Known gaps / next action:
  - `java.time` 依存の残存箇所は `kotlinx-datetime` へ段階移行が必要
  - 次: shared-core-migration-2（datetime/呼び出し面の整理）
- PR link:
  - https://github.com/shgnaka/org-clock-android/pull/10

## 2026-02-28 - M1 Shared Core Migration (Phase 2: datetime)

- Date: 2026-02-28
- Branch: `feat/ios/shared-core-migration-2-datetime`
- Commit(s): `ebc777b`, `37d12c0`, `b96e246`
- Milestone: `M1`
- Summary of changes:
  - shared model/contract を `kotlinx-datetime` へ移行
  - parser/clock service の `commonMain` 集約を拡張
  - テスト呼び出しを新 API に追随させ JVM 互換 shim を除去
- Verification commands + results:
  - `./gradlew :shared:compileDebugKotlinAndroid :shared:compileReleaseKotlinAndroid :app:assembleDebug`
  - 結果: ローカル検証グリーン
  - CI: PR チェック通過（Android Instrumentation の一時失敗は再実行で解消）
- Known gaps / next action:
  - M2 として repository 境界の platform-neutral 化を明文化・実装する
  - iOS host 側の最小接続ポイント定義が次の主要課題
- PR link:
  - https://github.com/shgnaka/org-clock-android/pull/11

## 2026-02-28 - M2 Repository Boundary (Phase 1)

- Date: 2026-02-28
- Branch: `feat/ios/repository-boundary-m2-1`
- Commit(s): `67b4ac7`
- Milestone: `M2`
- Summary of changes:
  - `OrgRepository` / `RootAccess` を削除し、共通契約を `ClockRepository` に一本化
  - Android 固有責務として `RootAccessGateway` を新設
  - `SafOrgRepository` を `ClockRepository` + `RootAccessGateway` 実装へ分離
  - `AppGraph`/`OrgClockRoute`/`OrgClockViewModel` の `openRoot` 契約を `Result<Unit>` に統一
  - 関連ユニットテストを `ClockRepository` ベースへ移行
- Verification commands + results:
  - `./gradlew :shared:compileDebugKotlinAndroid :shared:compileReleaseKotlinAndroid :app:assembleDebug :app:testDebugUnitTest`
  - 結果: BUILD SUCCESSFUL
- Known gaps / next action:
  - iOS 向け file access adapter 実装は未着手（M4 スコープ）
  - 本ブランチをコミット後、PR で M2 境界固定方針をレビュー
- PR link:
  - https://github.com/shgnaka/org-clock-android/pull/12

## 2026-03-01 - M2 Repository Boundary (Phase 2: finalization)

- Date: 2026-03-01
- Branch: `feat/ios/repository-boundary-m2-2`
- Commit(s): `16b21b0`
- Milestone: `M2`
- Summary of changes:
  - Android 側 repository 実装（`SafOrgRepository` / `RootAccessGateway`）を `shared/src/androidMain` へ移設
  - 補助ロジック（`OrgPaths` / `BackupPolicyConfig` / `BackupPolicyDecisions`）を `shared/src/androidMain` へ移設
  - `BackupPolicyDecisionsTest` を `shared/src/androidUnitTest` へ移設し、モジュール境界と可視性を整合
  - `shared` に Android 実装依存を移管し、`app` から `documentfile` 依存を削除
- Verification commands + results:
  - `./gradlew :shared:compileDebugKotlinAndroid :shared:testDebugUnitTest :app:assembleDebug :app:testDebugUnitTest :shared:compileIosMainKotlinMetadata`
  - 結果: BUILD SUCCESSFUL（`compileIosMainKotlinMetadata` は SKIPPED）
- Known gaps / next action:
  - macOS CI 上で `verify-kmp-ios.yml` の通過を最終確認する
  - 次: M3（iOS app host skeleton）
- PR link:
  - N/A (to be created)

## 2026-03-01 - M3 iOS App Host Skeleton (Phase 1)

- Date: 2026-03-01
- Branch: `feat/ios/repository-boundary-m3`
- Commit(s): `7a43425`
- Milestone: `M3`
- Summary of changes:
  - `shared/commonMain` に iOS ホスト接続用の `IosHostFacade` と bridge 関数を追加
  - `shared/build.gradle.kts` に iOS framework (`OrgClockShared`) 設定を追加
  - `iosApp/` を新設し、XcodeGen 管理の SwiftUI 最小ホストを追加
  - iOS host skeleton 検証用 CI（`verify-ios-host-skeleton.yml`）を追加
- Verification commands + results:
  - `./gradlew :shared:compileDebugKotlinAndroid :shared:testDebugUnitTest :app:assembleDebug :app:testDebugUnitTest :shared:compileIosMainKotlinMetadata`
  - 結果: BUILD SUCCESSFUL（`compileIosMainKotlinMetadata` は SKIPPED）
  - `./gradlew :shared:tasks --all | rg "embedAndSignAppleFrameworkForXcode"`
  - 結果: Xcode 連携タスク存在を確認
- Known gaps / next action:
  - PR #17 の CI 実行結果（`verify-kmp-ios` / `verify-ios-host-skeleton`）確認待ち
  - CI グリーン確認後に M3 完了判定を行う
- PR link:
  - https://github.com/shgnaka/org-clock-android/pull/17

## 2026-03-01 - M3 iOS Host Skeleton CI Stabilization (arm64 simulator)

- Date: 2026-03-01
- Branch: `feat/ios/repository-boundary-m3`
- Commit(s): `TBD`
- Milestone: `M3`
- Summary of changes:
  - `verify-ios-host-skeleton.yml` の `xcodebuild` を arm64 simulator 固定へ変更（`-arch arm64`）
  - CI での `x86_64` 解決回避のため `EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64` を追加
  - `iosApp/project.yml` に同一設定を追加し、XcodeGen 生成物と CI 条件を一致
  - `README.md` の iOS build 例を CI 実行条件に同期
- Verification commands + results:
  - `xcodebuild -project iosApp/OrgClockiOS.xcodeproj -scheme OrgClockiOS -configuration Debug -destination 'generic/platform=iOS Simulator' -arch arm64 CODE_SIGNING_ALLOWED=NO EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64 build`
  - 結果: CI 実行待ち（`Verify iOS Host Skeleton` の再実行で確認予定）
- Known gaps / next action:
  - Intel simulator（`x86_64`）互換は M3 では対象外。必要なら M4 以降で再評価
  - 次: PR #17 上で `verify-ios-host-skeleton` / `verify-kmp-ios` の通過を確認し M3 完了判定へ進む
- PR link:
  - https://github.com/shgnaka/org-clock-android/pull/17

## 2026-03-02 - M4 iOS File Access Adapter + Core Flow (Phase 1)

- Date: 2026-03-02
- Branch: `feat/ios/repository-boundary-m4`
- Commit(s): `TBD`
- Milestone: `M4`
- Summary of changes:
  - `shared/src/iosMain` に `IosFileOrgRepository`（`ClockRepository` 実装）を追加
  - iOS sandbox（`Documents/OrgClock`）を保存先として `.org` ファイルの list/load/save を実装
  - `IosCoreFlowFacade` を追加し、iOS host から repository + `ClockService` の最小 core flow を呼び出し可能化
  - `iosApp/ContentView.swift` に M4 probe 表示（list/read-write/headings サマリ）を追加
  - `verify-kmp-ios.yml` に `:shared:iosSimulatorArm64Test` を追加
  - `shared/src/iosTest` に repository/facade の最小テストを追加
- Verification commands + results:
  - `./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test :app:assembleDebug`
  - 結果: BUILD SUCCESSFUL（Linux では iOS native tasks は SKIPPED）
  - `./gradlew :shared:compileIosMainKotlinMetadata :shared:compileCommonMainKotlinMetadata`
  - 結果: BUILD SUCCESSFUL（`compileIosMainKotlinMetadata` は SKIPPED）
- Known gaps / next action:
  - macOS CI で iOS native compile/test の実行確認が必要
  - M4 Exit Criteria の「主要シナリオ動作確認」を CI 実績で確定し、必要なら iOS 側エラー表示を補強
- PR link:
  - N/A (to be created)
