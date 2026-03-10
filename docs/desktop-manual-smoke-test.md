# Org Clock Desktop MVP Manual Smoke Test

Desktop MVP は Linux first の起動・配布・基本操作確認を対象にします。Android 版と違い、SAF 権限や通知系はまだ対象外です。

## 1. Compile verification
```bash
./gradlew verifyDesktopCompile
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

## 3. Linux package verification
```bash
./gradlew packageDesktopLinux
```

期待値:
- `desktopApp/build/compose/binaries/main/` 配下に Linux 向け成果物が生成される
- 少なくとも unpackaged app directory が生成される
- Linux 環境では `.deb` または `.AppImage` などの配布物が確認できる

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

## 8. Packaging output sanity check
1. `desktopApp/build/compose/binaries/main/app/` に起動可能ファイル群があることを確認する
2. 生成された Linux 配布物名が `org-clock-desktop` ベースであることを確認する
3. 必要なら別の Linux マシンでも起動可否を確認する

## 9. MVP limitations to keep in mind
- Android notifications / services / WorkManager は対象外
- Android Storage Access Framework ベースの URI 権限永続化は対象外
- desktop ではローカル directory picker を使う
- Android と同等の detailed editing workflow は未提供
- この smoke test は「desktop host が shared store を起動・復元・遷移できるか」を確認する
