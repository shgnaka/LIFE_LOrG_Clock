#!/usr/bin/env bash

set -u

chmod +x ./gradlew
mkdir -p artifacts/android-test

adb kill-server || true
adb start-server
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 reconnect offline || true
adb -s emulator-5554 wait-for-device

boot_ok=0
for i in $(seq 1 120); do
  boot_completed="$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tr -d '\n')"
  if [ "$boot_completed" = "1" ]; then
    boot_ok=1
    break
  fi
  sleep 5
done

if [ "$boot_ok" -ne 1 ]; then
  echo "Emulator failed to report boot completion"
  adb devices -l > artifacts/android-test/adb-devices-before-fail.txt || true
  adb -s emulator-5554 shell getprop > artifacts/android-test/getprop-before-fail.txt || true
  adb -s emulator-5554 logcat -d -v time > artifacts/android-test/logcat-before-fail.txt || true
  exit 1
fi

./gradlew --stacktrace connectedDebugAndroidTest
test_exit=$?

if [ "$test_exit" -ne 0 ]; then
  echo "First instrumentation run failed, retrying once after adb reconnect"
  adb -s emulator-5554 reconnect offline || true
  adb -s emulator-5554 wait-for-device || true
  ./gradlew --stacktrace connectedDebugAndroidTest
  test_exit=$?
fi

adb devices -l > artifacts/android-test/adb-devices.txt || true
adb -s emulator-5554 shell getprop > artifacts/android-test/getprop.txt || true
adb -s emulator-5554 logcat -d -v time > artifacts/android-test/logcat.txt || true

exit "$test_exit"
