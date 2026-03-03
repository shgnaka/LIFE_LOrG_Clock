#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/security-loop/use-codex-cli.sh" >/dev/null

usage() {
  cat <<USAGE
Usage: security-loop/run.sh --module <name> [options]

Options:
  --module <name>        Required. Module under security-loop/modules.
  --iterations <n>       Max iterations (default: 3).
  --out <dir>            Output directory (default: security-loop/out/<timestamp>-<module>).
  --model <name>         Model name override (default: \$CODEX_MODEL).
  --fail-on-high         Return non-zero when high/critical findings remain (default: enabled).
  --no-fail-on-high      Return zero even if high/critical findings remain.
  --skip-gates           Skip Gradle validation gates.
  --help                 Show this help.
USAGE
}

MODULE=""
ITERATIONS=3
OUT_DIR=""
FAIL_ON_HIGH=1
SKIP_GATES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --module) MODULE="${2:-}"; shift 2 ;;
    --iterations) ITERATIONS="${2:-}"; shift 2 ;;
    --out) OUT_DIR="${2:-}"; shift 2 ;;
    --model) CODEX_MODEL="${2:-}"; shift 2 ;;
    --fail-on-high) FAIL_ON_HIGH=1; shift ;;
    --no-fail-on-high) FAIL_ON_HIGH=0; shift ;;
    --skip-gates) SKIP_GATES=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$MODULE" ]]; then
  echo "--module is required" >&2
  usage
  exit 2
fi
if ! [[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "--iterations must be positive integer" >&2
  exit 2
fi

MODULE_DIR="$ROOT_DIR/security-loop/modules/$MODULE"
if [[ ! -d "$MODULE_DIR" ]]; then
  echo "module not found: $MODULE" >&2
  exit 2
fi

if [[ -z "$OUT_DIR" ]]; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  OUT_DIR="$ROOT_DIR/security-loop/out/${stamp}-${MODULE}"
fi
mkdir -p "$OUT_DIR"

ATTACKER_SCHEMA="$ROOT_DIR/security-loop/schemas/attacker-report.schema.json"
DEFENDER_SCHEMA="$ROOT_DIR/security-loop/schemas/defender-report.schema.json"

validate_attacker_json() {
  local report="$1"
  jq -e '.finding_id and .module and .severity and (.findings|type=="array")' "$report" >/dev/null
  jq -e --arg mod "$MODULE" '.module == $mod' "$report" >/dev/null
}

validate_defender_json() {
  local report="$1"
  local attacker="$2"
  jq -e '.finding_id and .module and .fix_strategy and (.patch_plan|type=="array")' "$report" >/dev/null
  jq -e --arg mod "$MODULE" '.module == $mod' "$report" >/dev/null
  jq -e --arg fid "$(jq -r '.finding_id' "$attacker")" '.finding_id == $fid' "$report" >/dev/null
}

run_gate() {
  local name="$1"
  shift
  local log_file="$OUT_DIR/${name}.log"
  if "$@" >"$log_file" 2>&1; then
    jq -n --arg name "$name" --arg status "passed" --arg log "$log_file" '{name:$name,status:$status,log:$log}'
  else
    jq -n --arg name "$name" --arg status "failed" --arg log "$log_file" '{name:$name,status:$status,log:$log}'
  fi
}

attack_reports='[]'
defense_reports='[]'
iteration_summaries='[]'
remaining_high=0

for iter in $(seq 1 "$ITERATIONS"); do
  attacker_out="$OUT_DIR/iteration-${iter}-attacker.json"
  defender_out="$OUT_DIR/iteration-${iter}-defender.json"

  MODULE="$MODULE" OUT_FILE="$attacker_out" CODEX_MODEL="$CODEX_MODEL" \
    "$ROOT_DIR/security-loop/agents/attacker_codex.sh"
  validate_attacker_json "$attacker_out"

  high_count="$(jq '[.findings[] | select(.severity=="high" or .severity=="critical")] | length' "$attacker_out")"
  remaining_high="$high_count"

  defender_status="skipped"
  if [[ "$high_count" -gt 0 ]]; then
    MODULE="$MODULE" IN_REPORT="$attacker_out" OUT_FILE="$defender_out" CODEX_MODEL="$CODEX_MODEL" \
      "$ROOT_DIR/security-loop/agents/defender_codex.sh"
    validate_defender_json "$defender_out" "$attacker_out"
    defender_status="generated"
    defense_reports="$(jq -c --arg file "$defender_out" '. + [$file]' <<<"$defense_reports")"
  fi

  gates='[]'
  if [[ "$SKIP_GATES" -eq 0 ]]; then
    gates="$(jq -c --argjson item "$(run_gate "iter-${iter}-unit" ./gradlew --stacktrace testDebugUnitTest)" '. + [$item]' <<<"$gates")"
    gates="$(jq -c --argjson item "$(run_gate "iter-${iter}-lint" ./gradlew --stacktrace lintDebug)" '. + [$item]' <<<"$gates")"
    gates="$(jq -c --argjson item "$(run_gate "iter-${iter}-sync-tests" ./gradlew --stacktrace :app:testDebugUnitTest --tests 'com.example.orgclock.sync.*')" '. + [$item]' <<<"$gates")"
  fi

  attack_reports="$(jq -c --arg file "$attacker_out" '. + [$file]' <<<"$attack_reports")"

  iter_summary="$(jq -nc \
    --argjson iteration "$iter" \
    --arg attacker_report "$attacker_out" \
    --arg defender_report "$defender_out" \
    --arg defender_status "$defender_status" \
    --argjson high_count "$high_count" \
    --argjson gates "$gates" \
    '{iteration:$iteration,attacker_report:$attacker_report,defender_report:$defender_report,defender_status:$defender_status,high_or_critical_count:$high_count,gates:$gates}')"
  iteration_summaries="$(jq -c --argjson item "$iter_summary" '. + [$item]' <<<"$iteration_summaries")"

  if [[ "$high_count" -eq 0 ]]; then
    break
  fi
done

all_gates_ok="true"
if [[ "$SKIP_GATES" -eq 0 ]]; then
  all_gates_ok="$(jq -r 'all(.[]; (.gates // []) | all(.status == "passed"))' <<<"$iteration_summaries")"
fi

latest_attacker="$(jq -r '.[-1]' <<<"$attack_reports")"
remaining_findings='[]'
if [[ -n "$latest_attacker" && "$latest_attacker" != "null" ]]; then
  remaining_findings="$(jq -c '.findings | map(select(.severity=="high" or .severity=="critical"))' "$latest_attacker")"
  remaining_high="$(jq 'length' <<<"$remaining_findings")"
fi

if [[ "$all_gates_ok" != "true" ]]; then
  final_status="failed_gates"
elif [[ "$remaining_high" -gt 0 ]]; then
  final_status="needs_manual_intervention"
else
  final_status="passed"
fi

summary_file="$OUT_DIR/summary.json"
jq -n \
  --arg run_id "sync-loop-$(date -u +%Y%m%dT%H%M%SZ)" \
  --arg module "$MODULE" \
  --arg started_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg model "$CODEX_MODEL" \
  --arg attacker_schema "$ATTACKER_SCHEMA" \
  --arg defender_schema "$DEFENDER_SCHEMA" \
  --argjson configured_iterations "$ITERATIONS" \
  --argjson iterations "$iteration_summaries" \
  --argjson attack_reports "$attack_reports" \
  --argjson defense_reports "$defense_reports" \
  --argjson remaining_findings "$remaining_findings" \
  --arg final_status "$final_status" \
  --argjson skip_gates "$( [[ "$SKIP_GATES" -eq 1 ]] && echo true || echo false )" \
  '{
    run_id:$run_id,
    module:$module,
    started_at:$started_at,
    model:$model,
    configured_iterations:$configured_iterations,
    iterations:$iterations,
    gates:{
      skipped:$skip_gates,
      required:["testDebugUnitTest","lintDebug",":app:testDebugUnitTest --tests com.example.orgclock.sync.*"]
    },
    schemas:{attacker:$attacker_schema,defender:$defender_schema},
    artifacts:{attacker_reports:$attack_reports,defender_reports:$defense_reports},
    remaining_findings:$remaining_findings,
    final_status:$final_status
  }' > "$summary_file"

cat "$summary_file"

if [[ "$FAIL_ON_HIGH" -eq 1 && "$remaining_high" -gt 0 ]]; then
  exit 1
fi
if [[ "$final_status" == "failed_gates" ]]; then
  exit 1
fi
