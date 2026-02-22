#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$repo_root"

output=""
if [[ "${1:-}" == "--out" ]]; then
  output="${2:-}"
  if [[ -z "$output" ]]; then
    echo "--out requires a file path" >&2
    exit 1
  fi
fi

emit() {
  local text="$1"
  if [[ -n "$output" ]]; then
    printf "%s\n" "$text" >> "$output"
  else
    printf "%s\n" "$text"
  fi
}

if [[ -n "$output" ]]; then
  : > "$output"
fi

emit "# Kaizen Scan Report"
emit ""
emit "Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
emit ""
emit "## UI / Presentation"
ui_hits="$(rg -n "private const val|ZonedDateTime\.now\(|ZoneId\.systemDefault\(|NotificationCompat|setContentText\(" app/src/main/java/com/example/orgclock/ui app/src/main/java/com/example/orgclock/notification app/src/main/java/com/example/orgclock/MainActivity.kt --glob '!**/build/**' || true)"
if [[ -n "$ui_hits" ]]; then
  emit '```'
  emit "$ui_hits"
  emit '```'
else
  emit "No hits"
fi

emit ""
emit "## Domain / Data"
domain_hits="$(rg -n "ZonedDateTime\.now\(|LocalDate\.now\(|error\(|IllegalStateException\(|15 \* 60 \* 1000|backupGenerations" app/src/main/java/com/example/orgclock/domain app/src/main/java/com/example/orgclock/data --glob '!**/build/**' || true)"
if [[ -n "$domain_hits" ]]; then
  emit '```'
  emit "$domain_hits"
  emit '```'
else
  emit "No hits"
fi

emit ""
emit "## Infra / CI"
infra_hits="$(rg -n "seq 1 [0-9]+|sleep [0-9]+|timeout-minutes|WORKFLOW_IMPL" .github/workflows --glob '!**/build/**' || true)"
if [[ -n "$infra_hits" ]]; then
  emit '```'
  emit "$infra_hits"
  emit '```'
else
  emit "No hits"
fi

emit ""
emit "## Tests / Quality"
quality_hits="$(rg -n "TODO|FIXME|assert|5_000|90L|Clock started|Clock stopped" app/src/test app/src/androidTest benchmark/src/androidTest docs --glob '!**/build/**' || true)"
if [[ -n "$quality_hits" ]]; then
  emit '```'
  emit "$quality_hits"
  emit '```'
else
  emit "No hits"
fi

if [[ -n "$output" ]]; then
  echo "Wrote report to $output"
fi
