# Firebase App Distribution ガイド（GitHub Actions 配布）

このドキュメントは、`adb` / USB 転送なしで Android 端末へ更新を反映するための実運用手順です。  
配布経路は `GitHub Actions -> Firebase App Distribution` を前提にします。

## 1. 前提

- Firebase プロジェクト作成済み
- Android アプリ登録済み（package name: `com.example.orgclock`）
- App Distribution でテスター/グループ設定済み（例: `internal-testers`）
- リポジトリに `.github/workflows/distribute-android.yml` が存在

## 2. GitHub Secrets を登録

`Settings -> Secrets and variables -> Actions -> Repository secrets` に以下を登録します。

1. `ANDROID_KEYSTORE_BASE64`
2. `ANDROID_KEYSTORE_PASSWORD`
3. `ANDROID_KEY_ALIAS`
4. `ANDROID_KEY_PASSWORD`
5. `GCP_WORKLOAD_IDENTITY_PROVIDER`
6. `GCP_SERVICE_ACCOUNT_EMAIL`
7. `FIREBASE_APP_ID_ANDROID`

### 2.1 keystore を Base64 化する

Linux (WSL 含む):

```bash
base64 -w 0 /path/to/release.keystore
```

macOS:

```bash
base64 /path/to/release.keystore | tr -d '\n'
```

出力された 1 行文字列を `ANDROID_KEYSTORE_BASE64` に登録します。

### 2.2 Firebase App ID の取得

Firebase Console の対象 Android アプリ設定で `1:...:android:...` 形式の ID を確認し、
`FIREBASE_APP_ID_ANDROID` に登録します。

### 2.3 OIDC 連携情報の登録

JSON キーは使わず、GitHub OIDC で Google Cloud に認証します。

- `GCP_WORKLOAD_IDENTITY_PROVIDER`  
  例: `projects/123456789/locations/global/workloadIdentityPools/github/providers/org-clock`
- `GCP_SERVICE_ACCOUNT_EMAIL`  
  例: `github-actions-firebase-distributor@org-clock-android.iam.gserviceaccount.com`

サービスアカウントには、Firebase 配布に必要なロール（最低でも App Distribution へ書き込み可能な権限）を付与します。
workflow 側では OIDC で発行した短命アクセストークンを使い、App Distribution REST API（upload/distribute）を呼び出します。

### 2.4 Workload Identity Federation の作成

Google Cloud 側で以下を設定します。

1. Workload Identity Pool を作成
2. Pool 内に OIDC Provider を作成（issuer: `https://token.actions.githubusercontent.com`）
3. Provider の attribute mapping を設定（`google.subject=assertion.sub` など）
4. Provider の attribute condition で対象リポジトリを制限  
   例: `assertion.repository=='<owner>/<repo>'`
5. CI 用サービスアカウントに `Workload Identity User` を付与  
   principal は上記 provider からの主体を指定
6. `projects/.../workloadIdentityPools/.../providers/...` の完全パスを
   `GCP_WORKLOAD_IDENTITY_PROVIDER` に登録

## 3. 配布実行

1. GitHub の `Actions` タブを開く
2. `Distribute Android (Firebase)` を選択
3. `Run workflow` を実行
   - `git_ref`: `main`（推奨）
   - `tester_group`: `internal-testers`（Firebase 側の group alias と一致）
   - `release_notes`: 任意（空なら自動生成）

## 4. 成功確認

- Actions ジョブが成功する
- テスターに配布通知が届く
- 端末側でアプリ更新が可能

## 5. よくある失敗と対処

### `Missing required secret: ...`

- GitHub Secrets のキー名を完全一致で再確認

### `Keystore restore failed or file is empty`

- `ANDROID_KEYSTORE_BASE64` の値が改行混入や欠損していないか確認
- keystore の再Base64化を実施

### Gradle 署名エラー

- `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` の整合性を確認

### Firebase / OIDC 認証エラー

- `GCP_WORKLOAD_IDENTITY_PROVIDER` の値が完全一致か確認
- `GCP_SERVICE_ACCOUNT_EMAIL` が正しいか確認
- Workload Identity Provider で GitHub リポジトリの principal 条件を許可しているか確認
- サービスアカウント権限を見直し

### App Distribution REST エラー

- `Upload response did not include operation name` の場合:
  - `FIREBASE_APP_ID_ANDROID` の値が正しいか確認
  - アップロード API 呼び出しのレスポンス本文を確認
- `Upload operation failed` の場合:
  - APK が対象アプリと一致しているか確認（applicationId / 署名）
- `Failed to distribute release` の場合:
  - `tester_group` が Firebase 側 group alias と一致しているか確認
  - サービスアカウントに App Distribution 書き込み権限があるか確認

### グループ未検出エラー

- workflow の `tester_group` と Firebase 側 group alias の一致を確認

## 6. 運用メモ

- 現在は手動実行トリガー（`workflow_dispatch`）のみ
- 将来 `main` push を配布スイッチにする場合は、workflow トリガーを追加する
