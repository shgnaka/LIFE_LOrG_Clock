# iOS Distribution / Testing Flow (M5)

このドキュメントは、M5 の完了条件である iOS 配布/検証フローを定義します。  
M5 時点では `Firebase App Distribution` を一次配布経路として採用し、対象は内部テスターのみに限定します。

## 1. Scope

- 配布経路: `GitHub Actions -> Firebase App Distribution`
- 対象: 内部テスター (`ios-internal-testers` などの Firebase group alias)
- トリガー: 手動実行 (`workflow_dispatch`)
- 対象コミット: `main` の最新コミットのみ
- 配布頻度: 必要時のみ

## 2. Decision Notes

### 2.1 採用方針

- 採用: Firebase App Distribution
- 非採用（現時点）: TestFlight

### 2.2 理由

- 既存 Android 側で Firebase 配布運用の知見がある
- 手動トリガー CI で監査可能な実行履歴を残しやすい
- M5 の目的である「再実行可能手順」と「運用責任の明確化」を短期間で達成できる

### 2.3 TestFlight を見送る理由（M5 時点）

- App Store Connect のロール/運用設計まで含めると M5 のスコープを超過しやすい
- まずは Firebase 経路で iOS 配布の再現性を確立し、その後に TestFlight 併用を評価する

## 3. Required GitHub Secrets

`Settings -> Secrets and variables -> Actions -> Repository secrets` に登録します。

- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_SERVICE_ACCOUNT_EMAIL`
- `FIREBASE_APP_ID_IOS`
- `IOS_SIGNING_CERT_BASE64` (`.p12` 証明書を Base64 化した文字列)
- `IOS_SIGNING_CERT_PASSWORD`
- `IOS_PROVISIONING_PROFILE_BASE64` (`.mobileprovision` を Base64 化した文字列)
- `IOS_TEAM_ID`

## 4. Workflow

対象 workflow: `.github/workflows/distribute-ios-firebase.yml`

1. `Actions` タブで `Distribute iOS (Firebase)` を選択
2. `Use workflow from` は `main` を選択
3. `Run workflow` を実行
   - `tester_group` (default: `ios-internal-testers`)
   - `release_notes` (任意)
4. workflow は次を実施
   - `main` 最新コミットであることを検証
   - `iosApp` を archive/export して `.ipa` を生成
   - Firebase App Distribution REST API へ upload/distribute

## 5. Acceptance Criteria (M5)

受け入れ完了判定は以下をすべて満たすことです。

- CI 実行が成功し、配布サマリに `Head SHA` / `Release` / `Tester group` が出力される
- 内部テスターが配布ビルドを受信し、インストールできる
- 最小動作確認（起動 + M4 core flow の基本操作）が完了している

## 6. Ownership

M5 時点の運用責任は開発チームが一元管理します。

- workflow と secrets 設定の保守
- Runbook 更新
- 配布失敗時の一次調査と再実行
- 継続失敗時の incident 起票

## 7. Failure Handling

1. workflow ログで失敗ステップを特定
2. secret/署名/Firebase 認可を確認
3. 同一条件で 1 回再実行
4. 解消しない場合は incident を起票し、実行 URL と `Head SHA` を記録

関連:
- `docs/incidents/2026-02-22-firebase-oidc-auth-investigation.md`

