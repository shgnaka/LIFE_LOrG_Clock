#!/usr/bin/env bash

set -euo pipefail

BASE_DIR="${1:-artifacts/ci/android-test}"
KEEP_RUNS="${2:-20}"

if [ ! -d "${BASE_DIR}" ]; then
  exit 0
fi

if ! [[ "${KEEP_RUNS}" =~ ^[0-9]+$ ]]; then
  echo "KEEP_RUNS must be a non-negative integer: ${KEEP_RUNS}" >&2
  exit 1
fi

mapfile -t run_dirs < <(find "${BASE_DIR}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)

if [ "${#run_dirs[@]}" -le "${KEEP_RUNS}" ]; then
  exit 0
fi

for run_dir in "${run_dirs[@]:${KEEP_RUNS}}"; do
  rm -rf "${BASE_DIR}/${run_dir}"
done
