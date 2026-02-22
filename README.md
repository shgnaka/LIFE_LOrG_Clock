# Org Clock Android

Android 端末上で org ファイルの見出しに対して clock 記録を行うアプリです。

## Docs

- 実機インストール手順（WSL2 + USB デバッグ）  
  `docs/install-debug-guide.md`
- Firebase App Distribution 手順（GitHub Actions 配布）  
  `docs/firebase-app-distribution-guide.md`
- 手動スモークテスト手順  
  `docs/manual-smoke-test.md`
- パフォーマンス計測手順  
  `docs/performance-benchmark.md`

## Quick Start

```bash
./gradlew installDebug
```

`installDebug` の詳細は `docs/install-debug-guide.md` を参照してください。

## CI Distribution (No ADB / No USB)

`adb` や USB 転送を使わずに端末へ更新を反映する場合は、GitHub Actions から Firebase App Distribution へ配布します。

追加したワークフロー:
- `.github/workflows/distribute-android.yml`

### 1. Required GitHub Secrets

Repository Secrets に以下を登録してください。

- `ANDROID_KEYSTORE_BASE64`  
  release 用 keystore ファイルを Base64 化した文字列
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `FIREBASE_SERVICE_ACCOUNT_JSON`  
  Firebase サービスアカウント JSON（文字列としてそのまま登録）
- `FIREBASE_APP_ID_ANDROID`  
  例: `1:1234567890:android:abc123def456`

### 2. Run workflow manually

1. GitHub の `Actions` タブを開く
2. `Distribute Android (Firebase)` を選ぶ
3. `Run workflow` を実行
   - `git_ref` (default: `main`)
   - `tester_group` (default: `internal-testers`)
   - `release_notes` (任意)

実行時に release APK をビルドして Firebase App Distribution へ配布します。

詳細なセットアップとトラブルシュートは `docs/firebase-app-distribution-guide.md` を参照してください。
