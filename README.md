# Org Clock Android

Android 端末上で org ファイルの見出しに対して clock 記録を行うアプリです。

## Docs

- 実機インストール手順（WSL2 + USB デバッグ）  
  `docs/install-debug-guide.md`
- Firebase App Distribution 手順（GitHub Actions 配布）  
  `docs/firebase-app-distribution-guide.md`
- 手動スモークテスト手順  
  `docs/manual-smoke-test.md`
- CI android-test ログ管理  
  `docs/ci-android-test-log-management.md`
- パフォーマンス計測手順  
  `docs/performance-benchmark.md`
- 通知機能仕様  
  `docs/features/clock-in-notification-spec.md`
- 通知機能実装状況  
  `docs/features/clock-in-notification-status.md`
- 改善チケット運用（Kaizen）  
  `docs/kaizens/README.md`
- iOS 対応ロードマップ（Milestone 定義）  
  `docs/ios-support/roadmap.md`
- iOS 対応進捗ログ（Progress Ledger）  
  `docs/ios-support/progress.md`
- sync-core 統合計画（M2 hardening）  
  `docs/synccore-integration/overview.md`  
  `docs/synccore-integration/contract.md`  
  `docs/synccore-integration/execution-plan-m1.md`  
  `docs/synccore-integration/test-acceptance.md`

## Quick Start

```bash
./gradlew installDebug
```

`installDebug` の詳細は `docs/install-debug-guide.md` を参照してください。

## Kotlin Multiplatform Bootstrap

- 共有モジュール `:shared` を追加しています（`commonMain` / `androidMain` / `iosMain`）。
- Linux 環境では iOS アプリの実行・署名はできません。iOS ターゲットのコンパイル検証は macOS CI (`.github/workflows/verify-kmp-ios.yml`) で実行します。

ローカルでの確認例:

```bash
./gradlew :shared:tasks
./gradlew :shared:compileDebugKotlinAndroid
./gradlew :app:assembleDebug
```

## Local sync-core Integration (Composite Build)

`lanonly-p2p-cmdsync-core` をローカルソースのまま依存解決するには、`SYNC_CORE_DIR`（または `-Psynccore.dir`）を指定してビルドします。

```bash
export SYNC_CORE_DIR=/absolute/path/to/lanonly-p2p-cmdsync-core
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:assembleDebug
```

別リポジトリ側で `:sync-core-engine` モジュールを定義し、`io.github.shgnaka.synccore:sync-core-engine` 座標と対応づけてください。

`sync` 実行パス自体はデフォルト無効です。手動検証時のみ `-Psynccore.integration.enabled=true` を付けてビルドしてください。

```bash
./gradlew :app:assembleDebug -Psynccore.integration.enabled=true
```

`sync-core` 実Adapter（Engine API）は `SYNC_CORE_DIR` が有効なときだけ有効化されます。
有効時は `settings.gradle.kts` の composite build で `sync-core-api` / `sync-core-engine` / `sync-core-android` を参照します。

デバッグ用の単発コマンド実行（manual slice）は `sync_command_payload` extra 付きで Activity を起動すると実行できます。

```bash
adb shell am start -n com.example.orgclock/.MainActivity \
  --es sync_command_payload '{"schema":"clock.command.v1","command_id":"cmd-1","kind":"clock.start","target":{"file_name":"2026-03-01.org","heading_path":"Work/Project A"},"requested_at":"2026-03-01T12:34:56Z","from_device_id":"device-a"}'
```

Debug build では Settings 画面に `Sync Debug` セクションが表示され、以下を操作できます。

- `Flush now`
- `Standard mode`（WorkManager 15分周期 + 起動時 flush）
- `Active mode`（Foreground Service 5秒 tick）
- `Stop sync`

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
- `GCP_WORKLOAD_IDENTITY_PROVIDER`  
  Workload Identity Provider のリソース名  
  例: `projects/123456789/locations/global/workloadIdentityPools/github/providers/org-clock`
- `GCP_SERVICE_ACCOUNT_EMAIL`  
  OIDC 連携で使用するサービスアカウントのメールアドレス  
  例: `github-actions-firebase-distributor@org-clock-android.iam.gserviceaccount.com`
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

## Multi-Agent Security Loop (Sync)

sync 境界向けの攻撃者/防御者ループは `security-loop/` 配下で実行できます。

```bash
security-loop/run.sh --module sync-core-transport-lan --iterations 3
```

主なオプション:

- `--out <dir>`: 成果物ディレクトリ指定
- `--skip-gates`: Gradleゲート (`testDebugUnitTest`, `lintDebug`) をスキップ
- `--no-fail-on-high`: High/Critical 残存時でも終了コードを失敗にしない

成果物:

- `iteration-N-attacker.json`
- `iteration-N-defender.json` (High/Critical がある場合)
- `summary.json`
