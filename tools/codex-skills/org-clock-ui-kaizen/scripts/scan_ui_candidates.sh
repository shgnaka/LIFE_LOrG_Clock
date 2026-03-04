#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$repo_root"

output=""
scope="all"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      output="${2:-}"
      shift 2
      ;;
    --scope)
      scope="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "$scope" != "all" && "$scope" != "ui" && "$scope" != "notification" ]]; then
  echo "--scope must be one of: all, ui, notification" >&2
  exit 1
fi

if [[ -n "$output" ]]; then
  : > "$output"
fi

emit() {
  local text="$1"
  if [[ -n "$output" ]]; then
    printf "%s\n" "$text" >> "$output"
  else
    printf "%s\n" "$text"
  fi
}

declare -a targets=()
if [[ "$scope" == "all" || "$scope" == "ui" ]]; then
  targets+=("app/src/main/java/com/example/orgclock/ui")
  targets+=("app/src/main/java/com/example/orgclock/MainActivity.kt")
fi
if [[ "$scope" == "all" || "$scope" == "notification" ]]; then
  targets+=("app/src/main/java/com/example/orgclock/notification")
fi

if [[ "${#targets[@]}" -eq 0 ]]; then
  echo "No scan targets resolved" >&2
  exit 1
fi

run_hit() {
  local label="$1"
  local query="$2"
  emit "### $label"
  local hits
  hits="$(rg -n "$query" "${targets[@]}" --glob '!**/build/**' || true)"
  if [[ -n "$hits" ]]; then
    emit '```'
    emit "$hits"
    emit '```'
  else
    emit "No hits"
  fi
  emit ""
}

emit "# UI Kaizen Scan Report"
emit ""
emit "Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
emit "Scope: $scope"
emit ""

run_hit "UX_TEXT" "Text\\(\"|setContentText\\(|Toast\\.makeText|SnackbarHostState\\.showSnackbar"
run_hit "A11Y_SEMANTICS" "Icon\\(|Image\\(|contentDescription\\s*=\\s*null"
run_hit "A11Y_TOUCH_TARGET" "Modifier\\.clickable|IconButton\\(|size\\([0-9]{1,2}\\.dp\\)"
run_hit "STATE_FEEDBACK" "isLoading|loading|Error|error|empty|Empty|CircularProgressIndicator|LinearProgressIndicator"
run_hit "UX_FLOW" "AlertDialog|Dialog|onDismissRequest|remember\\s*\\{"
run_hit "ERROR_GUIDANCE" "catch\\s*\\(|Result\\.failure|IllegalStateException|setSubText\\(|setStyle\\("

if [[ -n "$output" ]]; then
  echo "Wrote report to $output"
fi
