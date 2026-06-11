# Desktop版のインストール

Desktop版のインストーラは [GitHub Releases](https://github.com/shgnaka/LIFE_LOrG_Clock/releases/latest) から取得できます。

## Windows

1. Latest Release のAssetsから `org-clock-desktop-*.msi` をダウンロードする
2. `.msi` を実行してインストールする
3. Windowsの警告が表示された場合は、ReleaseのSHA-256と `SHA256SUMS-windows.txt` を照合してから続行する

## Linux

Debian/Ubuntu系では `.deb`、それ以外ではportable `.tar.gz`を利用できます。

```bash
sha256sum -c SHA256SUMS-linux.txt
sudo apt install ./org-clock-desktop_*.deb
```

portable版の場合:

```bash
tar -xzf org-clock-desktop-*-linux-portable.tar.gz
./bin/org-clock-desktop
```

## リリース方法

`v1.2.3`形式のタグをpushすると、GitHub ActionsがWindowsとLinuxのインストーラを生成し、同じGitHub Releaseへチェックサム付きで公開します。`v1.2.3-rc.1`のようなプレリリースタグも利用でき、その場合はGitHub ReleaseのPre-releaseフラグが自動的に有効になります。既存タグは`Release Desktop Installers` workflowの手動実行でも再公開できます。
