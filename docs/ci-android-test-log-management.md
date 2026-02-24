# CI Android-Test ログ管理

`android-test` の失敗調査を再現可能にするため、ログの命名規則と保存先を固定します。

## 対象

- GitHub Actions: `.github/workflows/android-instrumentation.yml`
- Jobs: `android-test` (API 34), `android-test-api35` (API 35)
- 収集元: `.github/scripts/android-instrumentation.sh` が出力する診断ログ

## Run ID 仕様

`<run-id>` は次の形式を使います。

`<UTC時刻>_r<run_number>_a<run_attempt>_sha<short_sha>`

例:

`20260224T123045Z_r182_a1_sha1a2b3c4`

構成要素:

- `UTC時刻`: `YYYYMMDDTHHMMSSZ`
- `run_number`: GitHub Actions `GITHUB_RUN_NUMBER`
- `run_attempt`: GitHub Actions `GITHUB_RUN_ATTEMPT`
- `short_sha`: `GITHUB_SHA` 先頭8文字

## ディレクトリ規約

保存ルート:

`artifacts/ci/android-test/<run-id>/`

APIごとのサブディレクトリ:

- API 34: `artifacts/ci/android-test/<run-id>/api34/`
- API 35: `artifacts/ci/android-test/<run-id>/api35/`

この配下に `gradle-install-*.log`、`logcat-*.txt`、`pm-list-instrumentation-*.txt` などを保存します。

## 運用ルール

- 生ログは編集しない（切り出し・再整形しない）。
- ログは Git にコミットしない（`.gitignore` で `artifacts/ci/` を除外）。
- 比較調査時は run ディレクトリ単位で扱う。

## Cleanup ルール

- 保持本数方式: 最新 N run を保持し、それより古い run を削除。
- CI の既定値: `ANDROID_TEST_LOG_KEEP_RUNS=20`
- 実体: `.github/scripts/cleanup-ci-logs.sh`

実行例:

```bash
bash .github/scripts/cleanup-ci-logs.sh artifacts/ci/android-test 20
```

## 手元調査での持ち込み

CI から取得した `artifacts/android-test` 相当のファイル群は、次のように run 単位で配置します。

1. `<run-id>` を決める。
2. `artifacts/ci/android-test/<run-id>/api34/` または `api35/` を作る。
3. 生ログをそのまま配置する。

最低限必要なファイル:

- `gradle-install-*.log`
- `logcat-*.txt`

推奨追加:

- `adb-devices-*.txt`
- `getprop-*.txt`
- `pm-list-instrumentation-*.txt`
- `dumpsys-package-orgclock*.txt`
