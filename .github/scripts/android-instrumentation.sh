#!/usr/bin/env bash

set -euo pipefail

chmod +x ./gradlew
mkdir -p artifacts/android-test

EMULATOR_SERIAL="$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1; exit}')"
if [ -z "${EMULATOR_SERIAL}" ]; then
  EMULATOR_SERIAL="emulator-5554"
fi

collect_diagnostics() {
  local suffix="$1"
  adb devices -l > "artifacts/android-test/adb-devices-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell getprop > "artifacts/android-test/getprop-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys activity processes > "artifacts/android-test/dumpsys-activity-processes-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys package com.example.orgclock > "artifacts/android-test/dumpsys-package-orgclock-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell logcat -d -v time > "artifacts/android-test/logcat-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell am instrument -w -m com.example.orgclock.test/androidx.test.runner.AndroidJUnitRunner > "artifacts/android-test/instrumentation-smoke-${suffix}.txt" || true
}

wait_for_device_ready() {
  local boot_ok=0
  local boot_anim_ok=0
  local pm_ok=0

  for _ in $(seq 1 120); do
    boot_completed="$(adb -s "${EMULATOR_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tr -d '\n')"
    boot_anim_state="$(adb -s "${EMULATOR_SERIAL}" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r' | tr -d '\n')"

    if adb -s "${EMULATOR_SERIAL}" shell pm path android >/dev/null 2>&1; then
      pm_ok=1
    else
      pm_ok=0
    fi

    if [ "${boot_completed}" = "1" ]; then
      boot_ok=1
    fi
    if [ "${boot_anim_state}" = "stopped" ] || [ -z "${boot_anim_state}" ]; then
      boot_anim_ok=1
    fi

    if [ "${boot_ok}" -eq 1 ] && [ "${boot_anim_ok}" -eq 1 ] && [ "${pm_ok}" -eq 1 ]; then
      return 0
    fi
    sleep 5
  done

  return 1
}

adb kill-server || true
adb start-server
adb -s "${EMULATOR_SERIAL}" wait-for-device
adb -s "${EMULATOR_SERIAL}" reconnect offline || true
adb -s "${EMULATOR_SERIAL}" wait-for-device

if ! wait_for_device_ready; then
  echo "Emulator failed readiness checks"
  collect_diagnostics "before-fail"
  exit 1
fi

adb -s "${EMULATOR_SERIAL}" shell settings put global window_animation_scale 0 || true
adb -s "${EMULATOR_SERIAL}" shell settings put global transition_animation_scale 0 || true
adb -s "${EMULATOR_SERIAL}" shell settings put global animator_duration_scale 0 || true

test_exit=0
./gradlew --stacktrace connectedDebugAndroidTest || test_exit=$?

if [ "${test_exit}" -ne 0 ]; then
  echo "First instrumentation run failed, retrying once after adb reconnect"
  collect_diagnostics "after-first-failure"
  adb -s "${EMULATOR_SERIAL}" reconnect offline || true
  adb -s "${EMULATOR_SERIAL}" wait-for-device || true
  adb -s "${EMULATOR_SERIAL}" shell am force-stop com.example.orgclock || true
  adb -s "${EMULATOR_SERIAL}" shell pm clear com.example.orgclock.test || true
  test_exit=0
  ./gradlew --stacktrace connectedDebugAndroidTest || test_exit=$?
fi

if [ "${test_exit}" -ne 0 ]; then
  collect_diagnostics "after-second-failure"
fi

collect_diagnostics "final"

exit "$test_exit"
