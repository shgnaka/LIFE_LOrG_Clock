#!/usr/bin/env bash

set -euo pipefail

chmod +x ./gradlew
INSTRUMENTATION_SMOKE_TIMEOUT_SEC=120
PM_SETTLE_ATTEMPTS=10
PM_SETTLE_SLEEP_SEC=3
ARTIFACT_BASE_DIR="${ARTIFACT_BASE_DIR:-artifacts/android-test}"
ANDROID_TEST_FLAVOR="${ANDROID_TEST_FLAVOR:-unknown}"

mkdir -p "${ARTIFACT_BASE_DIR}"

EMULATOR_SERIAL="$(adb devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1; exit}')"
if [ -z "${EMULATOR_SERIAL}" ]; then
  EMULATOR_SERIAL="emulator-5554"
fi

{
  echo "android_test_flavor=${ANDROID_TEST_FLAVOR}"
  echo "artifact_base_dir=${ARTIFACT_BASE_DIR}"
  echo "emulator_serial=${EMULATOR_SERIAL}"
  echo "github_run_number=${GITHUB_RUN_NUMBER:-}"
  echo "github_run_attempt=${GITHUB_RUN_ATTEMPT:-}"
  echo "github_sha=${GITHUB_SHA:-}"
} > "${ARTIFACT_BASE_DIR}/run-context.txt"

collect_diagnostics() {
  local suffix="$1"
  adb devices -l > "${ARTIFACT_BASE_DIR}/adb-devices-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell getprop > "${ARTIFACT_BASE_DIR}/getprop-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell pm list instrumentation > "${ARTIFACT_BASE_DIR}/pm-list-instrumentation-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell cmd package list packages com.example.orgclock > "${ARTIFACT_BASE_DIR}/package-list-orgclock-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys activity processes > "${ARTIFACT_BASE_DIR}/dumpsys-activity-processes-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys package com.example.orgclock > "${ARTIFACT_BASE_DIR}/dumpsys-package-orgclock-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell dumpsys package com.example.orgclock.test > "${ARTIFACT_BASE_DIR}/dumpsys-package-orgclock-test-${suffix}.txt" || true
  adb -s "${EMULATOR_SERIAL}" shell logcat -d -v time > "${ARTIFACT_BASE_DIR}/logcat-${suffix}.txt" || true
  if adb -s "${EMULATOR_SERIAL}" shell pm list instrumentation | grep -q "com.example.orgclock.test/androidx.test.runner.AndroidJUnitRunner"; then
    local smoke_log_path="${ARTIFACT_BASE_DIR}/instrumentation-smoke-${suffix}.txt"
    set +e
    timeout "${INSTRUMENTATION_SMOKE_TIMEOUT_SEC}" adb -s "${EMULATOR_SERIAL}" shell am instrument -w -m com.example.orgclock.test/androidx.test.runner.AndroidJUnitRunner > "${smoke_log_path}" 2>&1
    local smoke_status=$?
    set -e

    if [ "${smoke_status}" -eq 124 ]; then
      echo "Instrumentation smoke timed out after ${INSTRUMENTATION_SMOKE_TIMEOUT_SEC}s" > "${ARTIFACT_BASE_DIR}/instrumentation-smoke-timeout-${suffix}.txt"
    elif [ "${smoke_status}" -ne 0 ]; then
      echo "Instrumentation smoke exited with status ${smoke_status}" > "${ARTIFACT_BASE_DIR}/instrumentation-smoke-failure-${suffix}.txt"
    fi
  else
    echo "Instrumentation not installed for com.example.orgclock.test" > "${ARTIFACT_BASE_DIR}/instrumentation-not-installed-${suffix}.txt"
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
  local log_path="${ARTIFACT_BASE_DIR}/gradle-${suffix}.log"
  set +e
  ./gradlew --stacktrace --info "$@" 2>&1 | tee "${log_path}"
  local status=${PIPESTATUS[0]}
  set -e
  return "${status}"
}

build_test_apks() {
  if ! run_gradle_logged "build" :app:assembleDebug :app:assembleDebugAndroidTest; then
    return 1
  fi
  return 0
}

install_test_apks() {
  local suffix="$1"
  if ! run_gradle_logged "${suffix}" :app:installDebug :app:installDebugAndroidTest; then
    return 1
  fi
  if ! ensure_instrumentation_installed; then
    echo "Instrumentation package not installed after install phase (${suffix})"
    return 1
  fi
  return 0
}

wait_for_package_manager_settle() {
  for _ in $(seq 1 "${PM_SETTLE_ATTEMPTS}"); do
    if adb -s "${EMULATOR_SERIAL}" shell cmd package list packages >/dev/null 2>&1 &&
      adb -s "${EMULATOR_SERIAL}" shell pm list instrumentation >/dev/null 2>&1; then
      return 0
    fi
    sleep "${PM_SETTLE_SLEEP_SEC}"
  done
  echo "Package manager did not settle in time"
  return 1
}

recover_and_retry_install() {
  collect_diagnostics "install-first-failure"
  adb -s "${EMULATOR_SERIAL}" reconnect offline || true
  adb -s "${EMULATOR_SERIAL}" wait-for-device || true
  wait_for_device_ready || true
  wait_for_package_manager_settle || true
  adb -s "${EMULATOR_SERIAL}" shell am force-stop com.example.orgclock || true
  adb -s "${EMULATOR_SERIAL}" shell pm uninstall com.example.orgclock || true
  adb -s "${EMULATOR_SERIAL}" shell pm uninstall com.example.orgclock.test || true
  if ! install_test_apks "install-second"; then
    collect_diagnostics "install-second-failure"
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

if ! build_test_apks; then
  collect_diagnostics "build-failure"
  exit 1
fi

if ! install_test_apks "install-first"; then
  echo "Install phase failed, running adb recovery then retrying once"
  if ! recover_and_retry_install; then
    echo "Install phase failed after retry"
    exit 1
  fi
fi

if ! ensure_instrumentation_installed; then
  collect_diagnostics "install-postcheck-failure"
  exit 1
fi

test_exit=0
second_run_attempted=0
run_gradle_logged "connected-first" :app:connectedDebugAndroidTest || test_exit=$?

if [ "${test_exit}" -ne 0 ]; then
  echo "First instrumentation run failed, retrying once after adb reconnect"
  collect_diagnostics "after-first-failure"
  adb -s "${EMULATOR_SERIAL}" reconnect offline || true
  adb -s "${EMULATOR_SERIAL}" wait-for-device || true
  wait_for_device_ready || true
  adb -s "${EMULATOR_SERIAL}" shell am force-stop com.example.orgclock || true
  adb -s "${EMULATOR_SERIAL}" shell pm clear com.example.orgclock.test || true
  if ! wait_for_package_manager_settle; then
    echo "Package manager did not settle before second instrumentation run"
    collect_diagnostics "pm-settle-retry-failure"
    test_exit=1
  elif ! install_test_apks "install-before-second-connected"; then
    echo "Install phase failed during retry, aborting before second instrumentation run"
    collect_diagnostics "install-before-second-connected-failure"
    test_exit=1
  else
    second_run_attempted=1
    test_exit=0
    run_gradle_logged "connected-second" :app:connectedDebugAndroidTest || test_exit=$?
  fi
fi

if [ "${test_exit}" -ne 0 ] && [ "${second_run_attempted}" -eq 1 ]; then
  collect_diagnostics "after-second-failure"
fi

collect_diagnostics "final"

exit "$test_exit"
