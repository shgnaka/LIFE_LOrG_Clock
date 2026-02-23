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
  adb -s "${EMULATOR_SERIAL}" shell pm list instrumentation > "artifacts/android-test/pm-list-instrumentation-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell cmd package list packages com.example.orgclock > "artifacts/android-test/package-list-orgclock-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys activity processes > "artifacts/android-test/dumpsys-activity-processes-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys package com.example.orgclock > "artifacts/android-test/dumpsys-package-orgclock-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys package com.example.orgclock.test > "artifacts/android-test/dumpsys-package-orgclock-test-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell logcat -d -v time > "artifacts/android-test/logcat-${suffix}.txt" || true
  if adb -s "${EMULATOR_SERIAL}" shell pm list instrumentation | grep -q "com.example.orgclock.test/androidx.test.runner.AndroidJUnitRunner"; then
    adb -s "${EMULATOR_SERIAL}" shell am instrument -w -m com.example.orgclock.test/androidx.test.runner.AndroidJUnitRunner > "artifacts/android-test/instrumentation-smoke-${suffix}.txt" || true
  else
    echo "Instrumentation not installed for com.example.orgclock.test" > "artifacts/android-test/instrumentation-not-installed-${suffix}.txt"
  fi
}

wait_for_device_ready() {
  local boot_ok=0
  local dev_boot_ok=0
  local ce_ok=0
  local pm_ok=0
  local package_list_ok=0
  local last_boot_completed=""
  local last_dev_boot_completed=""
  local last_ce_available=""

  for _ in $(seq 1 120); do
    boot_completed="$(adb -s "${EMULATOR_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tr -d '\n')"
    dev_boot_completed="$(adb -s "${EMULATOR_SERIAL}" shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r' | tr -d '\n')"
    ce_available="$(adb -s "${EMULATOR_SERIAL}" shell getprop sys.user.0.ce_available 2>/dev/null | tr -d '\r' | tr -d '\n')"
    last_boot_completed="${boot_completed}"
    last_dev_boot_completed="${dev_boot_completed}"
    last_ce_available="${ce_available}"

    if adb -s "${EMULATOR_SERIAL}" shell pm path android >/dev/null 2>&1; then
      pm_ok=1
    else
      pm_ok=0
    fi
    if adb -s "${EMULATOR_SERIAL}" shell cmd package list packages >/dev/null 2>&1; then
      package_list_ok=1
    else
      package_list_ok=0
    fi

    if [ "${boot_completed}" = "1" ]; then
      boot_ok=1
    fi
    if [ "${dev_boot_completed}" = "1" ]; then
      dev_boot_ok=1
    fi
    if [ "${ce_available}" = "true" ]; then
      ce_ok=1
    fi

    if [ "${boot_ok}" -eq 1 ] &&
      [ "${dev_boot_ok}" -eq 1 ] &&
      [ "${ce_ok}" -eq 1 ] &&
      [ "${pm_ok}" -eq 1 ] &&
      [ "${package_list_ok}" -eq 1 ]; then
      return 0
    fi
    sleep 5
  done

  echo "Readiness check failed: sys.boot_completed=${last_boot_completed} dev.bootcomplete=${last_dev_boot_completed} sys.user.0.ce_available=${last_ce_available} pm_ok=${pm_ok} package_list_ok=${package_list_ok}"
  return 1
}

ensure_instrumentation_installed() {
  if adb -s "${EMULATOR_SERIAL}" shell pm list instrumentation | grep -q "com.example.orgclock.test/androidx.test.runner.AndroidJUnitRunner"; then
    return 0
  fi
  return 1
}

run_gradle_logged() {
  local suffix="$1"
  shift
  local log_path="artifacts/android-test/gradle-${suffix}.log"
  set +e
  ./gradlew --stacktrace --info "$@" 2>&1 | tee "${log_path}"
  local status=${PIPESTATUS[0]}
  set -e
  return "${status}"
}

prepare_test_apks() {
  if ! run_gradle_logged "prepare" :app:assembleDebug :app:assembleDebugAndroidTest :app:installDebug :app:installDebugAndroidTest; then
    return 1
  fi
  if ! ensure_instrumentation_installed; then
    echo "Instrumentation package not installed after prepare phase"
    return 1
  fi
  return 0
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

if ! prepare_test_apks; then
  collect_diagnostics "prepare-failure"
  exit 1
fi

test_exit=0
run_gradle_logged "connected-first" :app:connectedDebugAndroidTest || test_exit=$?

if [ "${test_exit}" -ne 0 ]; then
  echo "First instrumentation run failed, retrying once after adb reconnect"
  collect_diagnostics "after-first-failure"
  adb -s "${EMULATOR_SERIAL}" reconnect offline || true
  adb -s "${EMULATOR_SERIAL}" wait-for-device || true
  wait_for_device_ready || true
  adb -s "${EMULATOR_SERIAL}" shell am force-stop com.example.orgclock || true
  adb -s "${EMULATOR_SERIAL}" shell pm clear com.example.orgclock.test || true
  prepare_test_apks || true
  test_exit=0
  run_gradle_logged "connected-second" :app:connectedDebugAndroidTest || test_exit=$?
fi

if [ "${test_exit}" -ne 0 ]; then
  collect_diagnostics "after-second-failure"
fi

collect_diagnostics "final"

exit "$test_exit"
