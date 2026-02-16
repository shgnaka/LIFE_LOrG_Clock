## Summary
- `MainActivity` に集中していた画面状態管理とイベント処理を `ViewModel` に分離
- UIを `Route + Screen` 構成へ再編し、Activity を薄く保守しやすい形に変更
- ViewModel の Unit Test と Compose UI Test の土台を追加
- `benchmark` モジュールを新設し、Macrobenchmark による継続的なUI計測基盤を追加
- パフォーマンス計測手順ドキュメントを追加

## Main Changes

### Architecture / Refactor
- `MainActivity` を依存組み立て + ルート呼び出しのみに縮小
- 追加:
  - `app/src/main/java/com/example/orgclock/ui/app/OrgClockRoute.kt`
  - `app/src/main/java/com/example/orgclock/ui/app/OrgClockScreen.kt`
  - `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiState.kt`
  - `app/src/main/java/com/example/orgclock/ui/state/OrgClockUiAction.kt`
  - `app/src/main/java/com/example/orgclock/ui/viewmodel/OrgClockViewModel.kt`

### Testing
- 追加:
  - `app/src/test/java/com/example/orgclock/ui/viewmodel/OrgClockViewModelTest.kt`
  - `app/src/androidTest/java/com/example/orgclock/ui/app/OrgClockScreenTest.kt`
- `app/build.gradle.kts` に ViewModel/Compose test 用依存を追加

### Performance
- 追加:
  - `benchmark/build.gradle.kts`
  - `benchmark/src/main/AndroidManifest.xml`
  - `benchmark/src/androidTest/java/com/example/orgclock/benchmark/StartupAndScrollBenchmark.kt`
- シナリオ:
  - `coldStartup`
  - `filePickerFrameTiming`
  - `headingListScrollFrameTiming`

### Docs
- 追加: `docs/performance-benchmark.md`
- 更新: `README.md` に計測手順リンク追加

## Verification
- `./gradlew testDebugUnitTest --no-daemon` ✅
- `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :benchmark:assembleBenchmark --no-daemon` ✅
- 実機インストールは `installDebug` 経路が環境依存で不安定だったため、`assembleDebug + adb install` で確認 ✅

## Notes
- 振る舞い互換を意識してリファクタしており、Repository/Domain API は変更なし
- `benchmark` は導入済みだが、`connectedBenchmarkAndroidTest` 実行は端末接続状態に依存
