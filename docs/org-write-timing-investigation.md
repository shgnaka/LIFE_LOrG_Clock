# org clock ローカル書き込みタイミング調査

## 結論
org ファイルへの実書き込みは、`SafOrgRepository.saveFile(...)` 内で
`ContentResolver.openOutputStream(target.uri, "wt")` を開き、
`writer.write(outputText)` と `writer.flush()` を実行したタイミングで発生する。

- 実行地点: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:159`
- 書き込み処理: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:162`
- flush: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:163`

## 呼び出し経路（UI -> Service -> Repository）

- UI アクション定義
  - `StartClock`: `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiAction.kt:17`
  - `StopClock`: `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiAction.kt:18`
  - `CancelClock`: `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiAction.kt:19`
  - `SaveEdit`: `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiAction.kt:26`
  - `SubmitCreateHeading`: `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiAction.kt:30`

- ViewModel ハンドラ
  - `startClock(item)`: `app/src/main/java/com/example/orgclock/ui/viewmodel/OrgClockViewModel.kt:316`
  - `stopClock(item)`: `app/src/main/java/com/example/orgclock/ui/viewmodel/OrgClockViewModel.kt:344`
  - `cancelClock(item)`: `app/src/main/java/com/example/orgclock/ui/viewmodel/OrgClockViewModel.kt:367`
  - `saveEdit()`: `app/src/main/java/com/example/orgclock/ui/viewmodel/OrgClockViewModel.kt:390`
  - `submitCreateHeading()`: `app/src/main/java/com/example/orgclock/ui/viewmodel/OrgClockViewModel.kt:440`

- MainActivity での Service 配線
  - `startClockInFile`: `app/src/main/java/com/example/orgclock/MainActivity.kt:34`
  - `stopClockInFile`: `app/src/main/java/com/example/orgclock/MainActivity.kt:37`
  - `cancelClockInFile`: `app/src/main/java/com/example/orgclock/MainActivity.kt:40`
  - `editClosedClockInFile`: `app/src/main/java/com/example/orgclock/MainActivity.kt:46`
  - `createL1HeadingInFile`: `app/src/main/java/com/example/orgclock/MainActivity.kt:49`
  - `createL2HeadingInFile`: `app/src/main/java/com/example/orgclock/MainActivity.kt:52`

## 操作別タイミング

| 操作 | Service 側保存呼び出し | 実書き込みタイミング | リトライ |
|---|---|---|---|
| Start clock | `saveFile(..., ClockMutation)` | 操作直後に保存。`saveFile` 内 `openOutputStream("wt")` で実書き込み。 | 競合時のみ最大 2 回 (`ClockService.startClockInFile`) |
| Stop clock | `saveFileWithRetry(..., ClockMutation)` -> `saveFile` | 操作直後に保存。`openOutputStream("wt")` で実書き込み。 | 競合時のみ最大 2 回 |
| Cancel clock | `saveFileWithRetry(..., ClockMutation)` -> `saveFile` | 操作直後に保存。`openOutputStream("wt")` で実書き込み。 | 競合時のみ最大 2 回 |
| 履歴編集 SaveEdit | `saveFileWithRetry(..., UserEdit)` -> `saveFile` | 保存ボタン押下直後に保存。`openOutputStream("wt")` で実書き込み。 | 競合時のみ最大 2 回 |
| 見出し作成 L1/L2 | `saveFileWithRetry(..., UserEdit)` -> `saveFile` | 作成 submit 直後に保存。`openOutputStream("wt")` で実書き込み。 | 競合時のみ最大 2 回 |

### 参照箇所（Service）

- `startClockInFile` の 1 回目保存: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:84`
- `startClockInFile` の競合再保存: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:98`
- `stopClockInFile`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:113`
- `cancelClockInFile`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:130`
- `editClosedClockInFile`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:169`
- `createL1HeadingInFile`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:184`
- `createL2HeadingInFile`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:199`
- 競合リトライ実装: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:339`

## 非書き込み操作（読み取りのみ）

以下は `saveFile` を呼ばないためローカル org ファイルへ書き込まない。

- 見出し一覧取得 `listHeadings`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:60`
- 履歴一覧取得 `listClosedClocksInFile`: `app/src/main/java/com/example/orgclock/domain/ClockService.kt:140`

## 補足: バックアップ作成タイミング

本体書き込み前にバックアップを作る場合がある。

- 判定: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:145`
- ルール:
  - `UserEdit` は毎回バックアップ作成を試行: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:173`
  - `ClockMutation` は初回または 15 分経過時のみ: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:177`
  - 間隔定数 15 分: `app/src/main/java/com/example/orgclock/data/SafOrgRepository.kt:265`

バックアップ作成有無に関係なく、実ファイル更新そのものは `openOutputStream("wt")` の書き込みで発生する。

## 備考

- 現行 UI 配線（`MainActivity`）は `*InFile` 系 API を使用しており、調査対象の主経路は `saveFile`。
- `saveDaily` 経路は存在するが、現行 UI からは直接使われていない。
