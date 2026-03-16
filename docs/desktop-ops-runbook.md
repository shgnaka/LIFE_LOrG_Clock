# Desktop Ops Runbook

Desktop host は保存済み root を `java.util.prefs.Preferences` に保持します。実装上の保存先 node は `com/example/orgclock/desktop`、保持する key は `last_root` です。

コード上の根拠:

- `DesktopSettingsStore`: `desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSettingsStore.kt`

## 保存される内容

- 最後に開いた org root の絶対パス
- 現状の desktop host では永続設定はこの `last_root` のみ

desktop host の再起動や再インストール後も、この user-level preference が残っていれば前回の root を再利用します。

## 設定保存場所の確認

`java.util.prefs` の backing store は OS と JDK 実装に依存します。Org Clock desktop では `Preferences.userRoot()` を使っているため、いずれも「現在のユーザー」の設定として保存されます。

### Linux

Oracle の Preferences API ドキュメントでは、Linux の user preferences は通常 `~/.java/.userPrefs` 配下に保存されます。

このアプリでまず確認する場所:

```bash
~/.java/.userPrefs/com/example/orgclock/desktop/prefs.xml
```

確認例:

```bash
sed -n '1,160p' ~/.java/.userPrefs/com/example/orgclock/desktop/prefs.xml
```

### macOS

macOS では Java の user preferences をユーザーごとの Preferences 領域で管理します。JDK 実装差分があり得るため、運用上は `Library/Preferences` 配下で `orgclock` または `last_root` を検索するのが安全です。

確認例:

```bash
find ~/Library/Preferences -iname '*orgclock*' -o -iname '*java*prefs*'
```

### Windows

Oracle の Preferences API ドキュメントでは、Windows では preferences は Windows Registry に保存されます。Org Clock desktop は `userRoot()` を使うため、現在ログインしているユーザーの設定として保存されます。

確認方法:

1. `regedit` を起動する
2. `HKEY_CURRENT_USER` 配下を対象に `orgclock` または `last_root` を検索する
3. Org Clock desktop の設定 node / value を確認する

## 無効な保存済み root の reset / 再設定

### まず試す手順

1. desktop app を起動する
2. `Change root` を押す
3. 有効な org root を再選択する

この手順で復旧できる場合は、`last_root` が新しいパスで上書きされます。

### root が stale で起動時に毎回失敗する場合

`last_root` を削除してから再起動します。削除対象は `com/example/orgclock/desktop` node の `last_root` だけです。

Linux の例:

```bash
rm -f ~/.java/.userPrefs/com/example/orgclock/desktop/prefs.xml
```

削除後に app を再起動し、root を選び直します。

macOS の例:

1. `~/Library/Preferences` 配下で Org Clock desktop に対応する Java preferences エントリを探す
2. `last_root` を削除するか、Org Clock desktop の node を削除する
3. app を再起動して root を選び直す

Windows の例:

1. `regedit` で `HKEY_CURRENT_USER` 配下の Org Clock desktop に対応する Java preferences エントリを探す
2. `last_root` value または Org Clock desktop の node を削除する
3. app を再起動して root を選び直す

## uninstall / reinstall 時に残るデータ

- desktop package の uninstall だけでは、user-level preferences は消えない前提で扱う
- そのため `last_root` は reinstall 後も残る可能性がある
- org files 自体は app のインストール先とは別のユーザーデータなので、自動では削除しない
- 「完全に初期状態へ戻したい」場合は uninstall に加えて preferences の `last_root` を明示的に削除する

## サポート時の確認ポイント

1. 保存されている `last_root` が実在ディレクトリか
2. そのディレクトリに対して読み取り権限があるか
3. reinstall 直後でも古い `last_root` を引き継いでいないか
4. root を再選択後に `LoadedFile` まで進むか

## 関連資料

- `docs/project-overview.md`
- `docs/desktop-manual-smoke-test.md`
