# installDebug ガイド（WSL2 + Android 実機）

このドキュメントは、`./gradlew installDebug` で  
ビルドから実機インストールまでを 1 コマンドで行うための手順書です。

## 1. 何ができるか

`installDebug` を実行すると、以下を自動で実施します。

1. `debug` APK をビルド
2. 接続中の Android 端末へインストール

実行コマンド:

```bash
./gradlew installDebug
```

## 2. 前提条件

以下が満たされている必要があります。

1. Android 端末で USB デバッグが ON
2. 端末を USB 接続済み
3. `adb devices` で端末が `device` 状態
4. `local.properties` に `sdk.dir` が設定済み

`local.properties` の例:

```properties
sdk.dir=/mnt/c/Users/<Windowsユーザー名>/AppData/Local/Android/Sdk
```

## 3. 基本手順

1. 端末接続確認

```bash
/mnt/c/Users/<Windowsユーザー名>/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
```

2. インストール実行

```bash
./gradlew installDebug
```

3. 必要なら手動起動

```bash
/mnt/c/Users/<Windowsユーザー名>/AppData/Local/Android/Sdk/platform-tools/adb.exe shell am start -n com.example.orgclock/.MainActivity
```

## 4. よく使う関連コマンド

アンインストール:

```bash
./gradlew uninstallDebug
```

ログ確認:

```bash
/mnt/c/Users/<Windowsユーザー名>/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -d AndroidRuntime:E *:S
```

## 5. よくあるトラブル

### `device unauthorized`

- 端末側に表示される USB デバッグ許可ダイアログで許可する。

### `SDK location not found`

- `local.properties` の `sdk.dir` を確認する。
- パスが存在するか `ls /mnt/c/Users/<Windowsユーザー名>/AppData/Local/Android/Sdk` で確認する。

### 複数端末が接続されている

- 対象端末のシリアルを指定して `adb` を使う。

```bash
/mnt/c/Users/<Windowsユーザー名>/AppData/Local/Android/Sdk/platform-tools/adb.exe -s <serial> shell am start -n com.example.orgclock/.MainActivity
```

---

運用の基本は `./gradlew installDebug` のみです。  
失敗したときだけ `adb` で状態確認する形にすると、日常の更新が楽になります。
