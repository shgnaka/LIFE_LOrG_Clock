# Org Clock Desktop MVP Manual Smoke Test

Desktop MVP は Windows / Linux の起動・配布・基本操作確認を対象にします。Android 版と違い、SAF 権限や通知系はまだ対象外です。

## 1. Compile verification
```bash
./gradlew verifyDesktopCurrentOs
```

期待値:
- `:desktopApp:compileKotlin` が成功する

## 2. Launch desktop host
```bash
./gradlew runDesktop
```

期待値:
- `Org Clock Desktop` ウィンドウが開く
- クラッシュせず初回描画が完了する

## 3. Current-OS package verification
```bash
./gradlew packageDesktopCurrentOs
```

期待値:
- `desktopApp/build/compose/binaries/main/` 配下に現在の OS 向け成果物が生成される
- 少なくとも unpackaged app directory が生成される
- Windows 環境では `.msi` が確認できる
- Linux 環境では `.deb` または `.AppImage` が確認できる

## 4. First launch behavior
1. 生成されたアプリを起動する
2. 初回表示が完了することを確認する
3. ウィンドウのリサイズ後もレイアウトが破綻しないことを確認する

## 5. Root persistence check
1. 初回起動で `Choose org root` または `Select local directory` を押す
2. org root にしたいローカルディレクトリを選ぶ
3. file list または heading list へ遷移することを確認する
4. アプリを終了して再起動する
5. 保存済み root が自動復元され、初回と同じ root で起動することを確認する

## 6. File browsing check
1. root 選択後に file list が表示されることを確認する
2. `Reload files` で一覧更新ができることを確認する
3. file を選ぶと heading list に遷移することを確認する

## 7. Navigation and settings check
1. heading list で `Back to files` が動作することを確認する
2. toolbar の `Settings` から settings 画面へ遷移できることを確認する
3. settings の `Back` で file list または heading list へ戻れることを確認する
4. settings の `Change root` で別 root を選び直せることを確認する
5. settings 画面に Android 専用の notification / permission / sync runtime controls が出ていないことを確認する

## 8. Desktop interaction check
1. heading list で `Start` / `Stop` / `Cancel` / `History` / `Add child heading` が明示ボタンで見えていることを確認する
2. top toolbar の `Add top-level heading` から create dialog を開けることを確認する
3. history dialog で `Edit` / `Delete` が long press なしで操作できることを確認する
4. edit/create/delete dialogs が mouse と keyboard で操作しやすいことを確認する

## 9. Packaging output sanity check
1. `desktopApp/build/compose/binaries/main/app/` に起動可能ファイル群があることを確認する
2. 生成された配布物名が `org-clock-desktop` ベースであることを確認する
3. Windows では `.msi` のインストールとアンインストールが完了することを確認する
4. Linux では必要なら別の Linux マシンでも起動可否を確認する

## 10. Windows-specific checks
1. `Select local directory` で `C:\\Users\\...` 配下のローカルディレクトリを選択できることを確認する
2. OneDrive 配下や日本語ディレクトリ名を含む root でも起動・再起動が破綻しないことを確認する
3. org ファイルを外部エディタで保存したときに `Reload from disk` 導線が表示されることを確認する
4. 保存済み root を削除した状態で再起動し、root setup 画面に戻れて再選択できることを確認する

## 11. MVP limitations to keep in mind
- Android notifications / services / WorkManager は対象外
- Android Storage Access Framework ベースの URI 権限永続化は対象外
- desktop ではローカル directory picker を使う
- Android と同等の detailed editing workflow は未提供
- この smoke test は「desktop host が shared store を起動・復元・遷移できるか」を確認する
