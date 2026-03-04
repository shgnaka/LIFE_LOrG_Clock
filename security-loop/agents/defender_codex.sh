#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/security-loop/use-codex-cli.sh" >/dev/null

: "${MODULE:?MODULE is required}"
: "${IN_REPORT:?IN_REPORT is required}"
: "${OUT_FILE:?OUT_FILE is required}"

MODULE_ROOT="$ROOT_DIR/security-loop/modules/$MODULE"
MANIFEST="$MODULE_ROOT/manifest.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "manifest not found: $MANIFEST" >&2
  exit 1
fi

DEFENDER_ENTRY="$(jq -r '.defender.entry // empty' "$MANIFEST")"
if [[ -z "$DEFENDER_ENTRY" ]]; then
  echo "manifest.defender.entry is required: $MANIFEST" >&2
  exit 1
fi

DEFENDER_PATH="$MODULE_ROOT/$DEFENDER_ENTRY"
if [[ ! -f "$DEFENDER_PATH" ]]; then
  echo "defender entry not found: $DEFENDER_PATH" >&2
  exit 1
fi
if [[ ! -x "$DEFENDER_PATH" ]]; then
  echo "defender entry is not executable: $DEFENDER_PATH" >&2
  exit 1
fi

MODULE="$MODULE" MODULE_ROOT="$MODULE_ROOT" ROOT_DIR="$ROOT_DIR" IN_REPORT="$IN_REPORT" OUT_FILE="$OUT_FILE" \
  CODEX_MODEL="$CODEX_MODEL" "$DEFENDER_PATH"
