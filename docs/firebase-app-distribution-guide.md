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
5. `FIREBASE_SERVICE_ACCOUNT_JSON`
6. `FIREBASE_APP_ID_ANDROID`

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

### 2.3 サービスアカウント JSON の登録

Google Cloud で CI 用サービスアカウントを作成し、JSON キーを発行して
ファイル内容をそのまま `FIREBASE_SERVICE_ACCOUNT_JSON` に登録します。

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

### Firebase 認証エラー

- `FIREBASE_SERVICE_ACCOUNT_JSON` が正しい JSON か確認
- サービスアカウント権限を見直し

### グループ未検出エラー

- workflow の `tester_group` と Firebase 側 group alias の一致を確認

## 6. 運用メモ

- 現在は手動実行トリガー（`workflow_dispatch`）のみ
- 将来 `main` push を配布スイッチにする場合は、workflow トリガーを追加する
