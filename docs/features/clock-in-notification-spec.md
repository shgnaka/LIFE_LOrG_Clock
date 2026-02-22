# Clock-In Notification Spec

## 1. 目的
- org-clock の `clock-in` 状態を通知で継続可視化し、アプリを閉じた後でも進行中タスクを見失わないこと。
- 初見の Codex / エンジニアが本書だけで要件・設計・実装方針を把握できること。

## 2. スコープ
- 対象: Android アプリ本体 (`app/`)。
- 対象機能:
  - Foreground Service による常駐通知
  - 通知表示モード設定（常時表示 / 稼働中のみ）
  - 全 org ファイル走査による open CLOCK 集約
  - 権限拒否時の設定導線
- 非対象:
  - 端末再起動後の自動復帰
  - 通知アクションからの Stop/Cancel（将来拡張）

## 3. 要件
### 3.1 機能要件
- 通知機能は設定で ON/OFF できる。
- 表示モードは以下を選択できる。
  - `ActiveOnly`: open CLOCK が 1 件以上のときのみ通知表示
  - `Always`: open CLOCK が 0 件でも通知を表示
- 監視対象はルート配下の全 `.org` ファイル。
- 複数稼働時は通知展開で全件列挙する。
- 通知タップでアプリを開ける。

### 3.2 非機能要件
- 整合性戦略は「イベント駆動 + 定期再走査（5分）」。
- 通知更新失敗時はサービスを即停止せず、エラー状態を通知に表示する。
- 通知権限未許可時は UI に設定導線を出す。

## 4. 決定事項（2026-02-22）
- 表示条件: 設定で切替。
- 監視範囲: 全orgファイル。
- 複数稼働: 全件列挙。
- 通知操作: 今回はアプリ起動のみ（Stop/Cancelは未実装）。
- Android 13+ 権限拒否時: 設定導線を表示。
- 同期戦略: イベント駆動 + 定期再走査。
- 通知文言: 日本語固定（例: `記録中 2件`）。

## 5. アーキテクチャ
- `ClockInNotificationService`
  - Foreground Service 本体。
  - 設定値に応じて起動/停止。
  - 定期再走査と通知更新。
- `ClockInScanner`
  - 全 org ファイルを走査し、L2見出しの open CLOCK を抽出。
- `OrgClockViewModel`
  - 通知設定状態の保持。
  - 権限要求フローの制御。
- `OrgClockRoute` / `MainActivity`
  - 権限ランチャー、通知設定画面遷移、Service 同期実行。

## 6. 追加・変更インターフェース
- 追加型:
  - `NotificationDisplayMode`
  - `ClockInEntry`
- `OrgClockUiState` 追加項目:
  - `notificationEnabled`
  - `notificationDisplayMode`
  - `notificationPermissionGranted`
  - `notificationPermissionRequestPending`
  - `pendingEnableNotificationAfterPermission`
  - `openAppNotificationSettingsPending`
- `OrgClockUiAction` 追加項目:
  - `ToggleNotificationEnabled`
  - `ChangeNotificationDisplayMode`
  - `NotificationPermissionResult`
  - `RequestNotificationPermissionHandled`
  - `OpenAppNotificationSettings`
  - `AppNotificationSettingsOpened`

## 7. 通知UX仕様
- チャネル:
  - ID: `clock_in_ongoing`
  - Importance: LOW
- タイトル:
  - 稼働あり: `記録中 N件`
  - 稼働なし: `記録中 0件`
- 本文:
  - 先頭1件サマリ（見出し / 開始時刻 / 経過分）
  - 展開時は `InboxStyle` で複数件を列挙
- タップ:
  - `MainActivity` を開く

## 8. 同期戦略
- イベント駆動:
  - アプリ状態変化（設定変更、見出し状態更新）で Service に sync シグナルを送る。
- 定期再走査:
  - Service 内で 5 分おきに全org再走査。
- 失敗時:
  - 直ちに停止せず、通知上で `更新失敗` を表示。

## 9. テスト受け入れ条件
- Unit:
  - 通知設定 ON/OFF と権限フローが期待どおり遷移する。
  - 走査結果（複数ファイル/複数見出し）が正しく抽出される。
- Manual:
  - 権限許可/拒否の分岐が機能する。
  - `ActiveOnly` で open CLOCK 0件時に通知停止する。
  - `Always` で 0件通知を維持する。

## 10. リスクと緩和策
- リスク: org ファイル数増加時の走査コスト。
  - 緩和: 再走査間隔を 5 分に固定、イベント駆動で即時更新。
- リスク: 通知権限拒否による機能不達。
  - 緩和: 設定導線を明示し、状態メッセージで案内。

## 11. 未決事項
- 端末再起動後の自動復帰要件（必要なら BroadcastReceiver 追加）。
- 通知アクション（Stop/Cancel）追加タイミング。
