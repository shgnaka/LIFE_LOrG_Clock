# Performance Benchmark Guide

## Goal

Track UI jank regressions and keep jank rate below 5% for core flows.

## Build benchmark APK

```bash
./gradlew :benchmark:assembleBenchmark --no-daemon
```

## Run macrobenchmarks

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest --no-daemon
```

## Scenarios

- `coldStartup`: cold startup timing
- `filePickerFrameTiming`: frame timing until root setup/file picker visible
- `headingListScrollFrameTiming`: frame timing while flinging a scrollable container

## Notes

- Benchmarks require a connected device/emulator.
- Keep device thermal state stable for comparable results.
