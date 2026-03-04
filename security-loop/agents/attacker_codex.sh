#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/security-loop/use-codex-cli.sh" >/dev/null

: "${MODULE:?MODULE is required}"
: "${OUT_FILE:?OUT_FILE is required}"

MODULE_ROOT="$ROOT_DIR/security-loop/modules/$MODULE"
MANIFEST="$MODULE_ROOT/manifest.json"
if [[ ! -f "$MANIFEST" ]]; then
  echo "manifest not found: $MANIFEST" >&2
  exit 1
fi

ATTACKER_ENTRY="$(jq -r '.attacker.entry // empty' "$MANIFEST")"
if [[ -z "$ATTACKER_ENTRY" ]]; then
  echo "manifest.attacker.entry is required: $MANIFEST" >&2
  exit 1
fi

ATTACKER_PATH="$MODULE_ROOT/$ATTACKER_ENTRY"
if [[ ! -f "$ATTACKER_PATH" ]]; then
  echo "attacker entry not found: $ATTACKER_PATH" >&2
  exit 1
fi
if [[ ! -x "$ATTACKER_PATH" ]]; then
  echo "attacker entry is not executable: $ATTACKER_PATH" >&2
  exit 1
fi

MODULE="$MODULE" MODULE_ROOT="$MODULE_ROOT" ROOT_DIR="$ROOT_DIR" OUT_FILE="$OUT_FILE" CODEX_MODEL="$CODEX_MODEL" \
  "$ATTACKER_PATH"
