# Desktop Settings Implementation Plan

このドキュメントは、desktop settings 画面を `desktopApp/src/main/kotlin/com/example/orgclock/desktop/Main.kt` に実装するための具体設計です。

目的は「設計の迷いをなくし、あとは実装するだけ」の状態にすることです。

## 1. Scope

今回の settings 画面は、既存の desktop 実装に存在する機能だけを扱います。

対象:

- `Org Root`
- `File Monitoring`
- `About Desktop`

非対象:

- Android notifications
- notification permission flow
- foreground service / WorkManager
- sync runtime controls
- desktop 未対応の advanced settings controls

## 2. Source of Truth

表示内容は以下の既存実装に揃えます。

- root 保存/読込: `DesktopSettingsStore`
- root open / external change flow: `DesktopAppGraph`
- settings 遷移と action: `Main.kt`
- shared state: `OrgClockUiState`

## 3. Settings Screen Structure

`SettingsPane` は縦スクロールの 1 カラム構成にします。

上から順:

1. Settings header
2. `OrgRootSettingsCard`
3. `FileMonitoringSettingsCard`
4. `AboutDesktopSettingsCard`

将来 2 カラム化する余地は残しますが、初期実装は単列にします。

## 4. Main.kt Composable Split

### 4.1 Replace existing `SettingsPane`

既存の簡易 `SettingsPane` は置き換えます。

新シグネチャ:

```kotlin
@Composable
private fun SettingsPane(
    state: OrgClockUiState,
    onChangeRoot: () -> Unit,
    onBack: () -> Unit,
    onReloadFiles: () -> Unit,
)
```

`DesktopHostCard` 側の呼び出しは次の形に変更します。

```kotlin
Screen.Settings -> SettingsPane(
    state = state,
    onChangeRoot = onPickRoot,
    onBack = { onAction(OrgClockUiAction.BackFromSettings) },
    onReloadFiles = { onAction(OrgClockUiAction.RefreshFiles) },
)
```

### 4.2 New composables

`Main.kt` に以下の private composable を追加します。

```kotlin
@Composable
private fun SettingsHeader(
    onBack: () -> Unit,
)

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
)

@Composable
private fun SettingsFieldRow(
    label: String,
    value: String,
)

@Composable
private fun SettingsMessageBlock(
    text: String,
    tone: SettingsMessageTone = SettingsMessageTone.Info,
)

@Composable
private fun SettingsActionRow(
    content: @Composable RowScope.() -> Unit,
)

@Composable
private fun OrgRootSettingsCard(
    state: OrgClockUiState,
    onChangeRoot: () -> Unit,
    onReloadFiles: () -> Unit,
)

@Composable
private fun FileMonitoringSettingsCard(
    state: OrgClockUiState,
    onReloadFiles: () -> Unit,
)

@Composable
private fun AboutDesktopSettingsCard()
```

必要な enum:

```kotlin
private enum class SettingsMessageTone {
    Info,
    Warning,
    Success,
}
```

## 5. Card Content Specification

### 5.1 Org Root

タイトル:

- `Org Root`

説明文:

- `Choose and review the local org directory used by the desktop host.`

表示項目:

1. `Current root`
   - `state.rootReference?.rawValue ?: "No root selected"`
2. `Status`
   - root あり: `Configured`
   - root なし: `Not configured`
3. `Persistence`
   - root あり: `This root will be restored on the next launch.`
   - root なし: `No saved root is available yet.`

アクション:

- `Change root`
- `Reload files`
  - enabled: `state.rootReference != null`

フッター:

- `Desktop uses a local directory instead of Android's document tree permission flow.`

### 5.2 File Monitoring

タイトル:

- `File Monitoring`

説明文:

- `Desktop watches the selected root for external .org file changes and surfaces reload guidance.`

表示項目:

1. `Monitoring`
   - root あり: `Watching for .org changes`
   - root なし: `Waiting for a root directory`
2. `Watched directory`
   - `state.rootReference?.rawValue ?: "No directory selected"`
3. `Reload guidance`
   - `state.externalChangePending == true` -> `Reload recommended`
   - else -> `No external changes detected`
4. `Changed files`
   - `state.externalChangeChangedFileIds.isNotEmpty()`:
     - `joinToString(", ")`
   - else:
     - `No recent file changes`

状態メッセージ:

- `state.externalChangeAffectsSelectedFile == true`
  - `The selected file changed on disk. Reload to refresh headings and history.`
- `state.externalChangePending == true`
  - `Org files changed on disk. Reload to refresh the desktop view.`
- それ以外はメッセージなし

アクション:

- `Reload from disk`
  - enabled: `state.rootReference != null`

フッター:

- `Desktop monitoring is local filesystem-based and is separate from Android background sync.`

### 5.3 About Desktop

タイトル:

- `About Desktop`

説明文:

- `This desktop host focuses on shared clock flows, local file access, and cross-platform desktop validation.`

表示項目:

1. `Desktop host role`
   - `Compose Desktop host for shared Org Clock flows`
2. `Available on desktop`
   - `Root selection, file browsing, heading actions, history edit/delete, external change detection`
3. `Not available on desktop`
   - `Android notifications, notification permission flow, foreground service, WorkManager, sync runtime controls`
4. `Package`
   - `org-clock-desktop`
5. `Platform focus`
   - `Windows and Linux desktop MVP`

フッター:

- `Desktop prioritizes launchability, shared domain validation, and packaging over Android feature parity.`

## 6. Visual Style

既存 desktop theme に合わせて、settings card も dark green surface 上の sub-card とします。

推奨:

- card background: `Color(0xFF1C3A34)` 前後
- border: `Color(0xFF3B564F)`
- title: `MaterialTheme.typography.titleMedium`
- description/value: `MaterialTheme.typography.bodyMedium`
- footer/detail: `MaterialTheme.typography.bodySmall`

余白:

- card outer spacing: `12.dp`
- card inner padding: `18.dp`
- field row spacing: `6.dp`
- section spacing: `10.dp`

## 7. SettingsPane Layout Skeleton

```kotlin
@Composable
private fun SettingsPane(
    state: OrgClockUiState,
    onChangeRoot: () -> Unit,
    onBack: () -> Unit,
    onReloadFiles: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(onBack = onBack)
        OrgRootSettingsCard(
            state = state,
            onChangeRoot = onChangeRoot,
            onReloadFiles = onReloadFiles,
        )
        FileMonitoringSettingsCard(
            state = state,
            onReloadFiles = onReloadFiles,
        )
        AboutDesktopSettingsCard()
    }
}
```

## 8. SettingsCard Skeleton

```kotlin
@Composable
private fun SettingsCard(
    title: String,
    description: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C3A34),
            contentColor = Color(0xFFF8F5ED),
        ),
        border = BorderStroke(1.dp, Color(0xFF3B564F)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD3E6D6),
            )
            content()
            footer?.let {
                HorizontalDivider(color = Color(0xFF3B564F))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD3E6D6),
                )
            }
        }
    }
}
```

## 9. Field and Message Helpers

### 9.1 SettingsFieldRow

用途:

- label/value の 2 段表示

表示ルール:

- label は小さめ、value は本文
- 長い path は折り返し可

### 9.2 SettingsMessageBlock

用途:

- info/warning/success の本文メッセージ

tone と色:

- `Info`: `Color(0xFFD3E6D6)`
- `Warning`: `Color(0xFFF2D38A)`
- `Success`: `Color(0xFF8FE0A8)`

## 10. Mapping Table

| UI label | Source |
|---|---|
| Current root | `state.rootReference?.rawValue` |
| Status | `state.rootReference != null` |
| Monitoring | `state.rootReference != null` |
| Reload guidance | `state.externalChangePending` |
| Changed files | `state.externalChangeChangedFileIds` |
| Selected-file warning | `state.externalChangeAffectsSelectedFile` |

## 11. Implementation Order

1. `DesktopHostCard` の `SettingsPane` 呼び出しを差し替える
2. `SettingsPane` を `state` ベースに変更する
3. `SettingsCard` / `SettingsFieldRow` / `SettingsMessageBlock` / `SettingsActionRow` を追加する
4. `OrgRootSettingsCard` を実装する
5. `FileMonitoringSettingsCard` を実装する
6. `AboutDesktopSettingsCard` を実装する
7. 既存の簡易 `SettingsPane` を削除する

## 12. Definition of Done

以下を満たしたら完了です。

- settings が 3 枚のカードで表示される
- root 未設定時でもレイアウトが崩れない
- root 設定済み時に current root と reload action が表示される
- external change 発生時に file monitoring card が `Reload recommended` を表示する
- Android 専用機能は settings に出さない
- desktop smoke test の intent に反しない
