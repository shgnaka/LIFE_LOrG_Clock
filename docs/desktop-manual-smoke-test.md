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

## 5. Root persistence placeholder check
Desktop root 永続化は今後の実装対象です。MVP では以下を確認します。

1. desktop host 上で root persistence UI が未実装であることを認識する
2. README の limitation 記述と食い違いがないことを確認する

## 6. File browsing placeholder check
Desktop file browsing は今後の実装対象です。MVP では以下を確認します。

1. file browsing UI が未実装であることを確認する
2. 起動時に未実装箇所が原因でクラッシュしないことを確認する

## 7. Clock operations placeholder check
Desktop clock start / stop / history editing / heading creation は今後の共有 UI 実装対象です。MVP では以下を確認します。

1. それらの操作 UI がまだ存在しない、または案内文どおりであることを確認する
2. プレースホルダ表示が崩れず、ウィンドウ操作で例外が発生しないことを確認する

## 8. Packaging output sanity check
1. `desktopApp/build/compose/binaries/main/app/` に起動可能ファイル群があることを確認する
2. 生成された Linux 配布物名が `org-clock-desktop` ベースであることを確認する
3. 必要なら別の Linux マシンでも起動可否を確認する

## 9. MVP limitations to keep in mind
- Android notifications / services / WorkManager は対象外
- Android Storage Access Framework ベースの権限永続化は対象外
- Android と同等の file editing workflow は未提供
- この smoke test は「desktop host が配布可能な足場として壊れていないか」を確認する
