#!/usr/bin/env bash
set -euo pipefail

# Shared helper for future Codex CLI integration.
# Current scripts can run without a remote model, but this file centralizes
# environment defaults so callers can switch to model-backed execution later.

: "${CODEX_MODEL:=gpt-5}"
export CODEX_MODEL

if command -v codex >/dev/null 2>&1; then
  export CODEX_CLI_BIN="$(command -v codex)"
else
  export CODEX_CLI_BIN=""
fi
