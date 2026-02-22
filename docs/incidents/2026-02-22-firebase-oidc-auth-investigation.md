# Firebase OIDC 認証失敗 調査レポート（2026-02-22）

## 1. 目的

GitHub Actions の Firebase 配布ジョブで発生した認証失敗の原因候補を整理し、
再発防止に向けた改善案を残す。

対象エラー:

```text
Error: Failed to authenticate, have you run firebase login?
Error: Process completed with exit code 1.
```

## 2. 事象サマリ

- 失敗ステップ名: `Distribute to Firebase App Distribution`
- 失敗箇所の実行内容（ログ上）:
  - `firebase appdistribution:distribute ...`
- 失敗メッセージ:
  - `Failed to authenticate, have you run firebase login?`

## 3. 背景（コード外の文脈）

- 当初は USB/adb を使わない端末反映を目的に Firebase App Distribution を導入。
- セキュリティ方針として、長期鍵（JSONキー）から OIDC（Workload Identity Federation）へ移行を実施。
- 期待アーキテクチャ:
  - GitHub Actions が OIDC トークンで Google Cloud 認証
  - 認証済みトークンで App Distribution API を呼び出し
- 一般に `firebase-tools` は環境によって ADC/OIDC 連携が安定しないことがあり、
  `firebase login` を要求する既知パターンがある。

## 4. 観測事実（Evidence）

### 4.1 リポジトリ `main` の現在の workflow 定義

現行の `.github/workflows/distribute-android.yml` は REST 実装であり、
`firebase appdistribution:distribute` を呼ばない。

主なステップ:

- `Upload APK to App Distribution (REST)`
- `Wait for upload operation (REST)`
- `Distribute to Firebase App Distribution`（REST POST）

### 4.2 ユーザー提示ログの実行内容

提示ログには以下が記録されている:

- `firebase appdistribution:distribute "$APK_PATH" ...`
- その直後に `Failed to authenticate, have you run firebase login?`

### 4.3 4.1 と 4.2 の矛盾

- コード上は REST 実装だが、実行ログは CLI 実装を示している。
- したがって「実行された workflow 定義」が期待とずれている可能性が高い。

## 5. 原因仮説（確度順）

1. **古い workflow revision を実行した（最有力）**  
   例: workflow 実行時に古い commit/ref が選択されていた。

2. **同じワークフロー名の過去 run を再実行し、古いスナップショットを踏んだ**  
   GitHub Actions の再実行は、当時の workflow 定義を使うケースがある。

3. **別ブランチ／別 ref から実行した**  
   UI 上の `Use workflow from` や `git_ref` の指定差異が原因。

4. **ブラウザの run 参照ミス**  
   最新実行ではなく、古い失敗 run のログを見ていた。

## 6. 結論（確定）

- エラー内容そのものは「CLI 認証方式の失敗」を示している。
- ただし現行コードは REST 実装であり、**コードとログが一致していない**ため、
  根本原因は「実行された workflow revision のずれ」。

### 6.1 確定した事実（run: `22270093588`）

- 実行種別: `Re-run`
- `Head branch`: `main`
- `Head SHA`: `c3e5ebb`
- 失敗ログ: `Failed to authenticate, have you run firebase login?`

`c3e5ebb` は REST 化（`3a74ec5`）より前の世代であり、CLI 実装を含む。
したがって本件は、旧 workflow 定義を再実行したことで CLI 経路が動作し、認証失敗した。

## 7. 検証チェックリスト（次回 run で必須）

GitHub Actions の実行画面で以下を確認する。

1. `Head branch` が `main` であること
2. `Head SHA` が期待コミットを指していること
3. 該当 run のステップ名に以下が出ること
   - `Upload APK to App Distribution (REST)`
   - `Wait for upload operation (REST)`
4. `Distribute to Firebase App Distribution` ステップで
   `firebase appdistribution:distribute` が表示されないこと

## 8. 改善案

### 8.1 即効策

1. 「再実行」ではなく「新規 Run workflow」を使う
2. `Use workflow from` を `main` に固定する
3. run 直後に step 名一覧で REST ステップの存在を確認する
4. `Print workflow fingerprint` の `WORKFLOW_IMPL=rest-v1` を確認する

### 8.2 恒久策

1. workflow にバージョン指紋を出力する  
   例: `echo "WORKFLOW_IMPL=rest-v1"`
2. `Print distribution summary` に `GITHUB_SHA` / `WORKFLOW_REF` を明示出力
3. 運用ガイドに「再実行時の定義ずれ注意」を追記

## 9. 追加で疑うべき要素（補助）

- サービスアカウント権限不足（403/permission エラーとして現れやすい）
- WIF provider 条件ミス（repository 条件不一致）
- `FIREBASE_APP_ID_ANDROID` の取り違え

今回の主エラーは `firebase login` 要求のため、上記より優先度は低い。

## 10. 参照

- workflow: `.github/workflows/distribute-android.yml`
- セットアップガイド: `docs/firebase-app-distribution-guide.md`
