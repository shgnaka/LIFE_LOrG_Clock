#!/usr/bin/env bash
set -euo pipefail

: "${MODULE:?MODULE is required}"
: "${OUT_FILE:?OUT_FILE is required}"

MODULE_ROOT="${MODULE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ROOT_DIR="${ROOT_DIR:-$(cd "$MODULE_ROOT/../../.." && pwd)}"

if [[ ! -f "$MODULE_ROOT/manifest.json" ]]; then
  echo "manifest not found: $MODULE_ROOT/manifest.json" >&2
  exit 1
fi

mapfile -t TARGET_PATHS < <(jq -r '.targets[]' "$MODULE_ROOT/manifest.json")
if [[ ${#TARGET_PATHS[@]} -eq 0 ]]; then
  echo "module has no targets: $MODULE" >&2
  exit 1
fi

findings_json='[]'
add_finding() {
  local finding="$1"
  findings_json="$(jq -c --argjson finding "$finding" '. + [$finding]' <<<"$findings_json")"
}

has_path() {
  local pattern="$1"
  local hit=1
  for path in "${TARGET_PATHS[@]}"; do
    if [[ -e "$ROOT_DIR/$path" ]] && rg -n "$pattern" "$ROOT_DIR/$path" >/dev/null 2>&1; then
      hit=0
      break
    fi
  done
  return $hit
}

# NOTIF-001: Mutable PendingIntent can be modified by other actors.
if has_path 'PendingIntent\.(getActivity|getService|getBroadcast)\(' && has_path 'FLAG_MUTABLE'; then
  evidence="$(rg -n 'PendingIntent\.(getActivity|getService|getBroadcast)\(|FLAG_MUTABLE' "${TARGET_PATHS[@]/#/$ROOT_DIR/}" | head -n 8 | tr '\n' '; ')"
  add_finding "$(jq -nc \
    --arg finding_id "NOTIF-001" \
    --arg module "$MODULE" \
    --arg title "Mutable PendingIntent detected in notification surface" \
    --arg severity "high" \
    --arg category "intent-integrity" \
    --arg evidence "$evidence" \
    --arg repro_steps "Send crafted extras to a mutable PendingIntent target and verify altered action execution." \
    --argjson affected_files '["app/src/main/java/com/example/orgclock/notification/ClockInNotificationService.kt"]' \
    --arg confidence "high" \
    --arg recommended_fix "Use FLAG_IMMUTABLE unless mutability is strictly required and validated." \
    '{finding_id:$finding_id,module:$module,title:$title,severity:$severity,category:$category,evidence:$evidence,repro_steps:$repro_steps,affected_files:$affected_files,confidence:$confidence,recommended_fix:$recommended_fix}')"
fi

# NOTIF-002: Service action handling should be an explicit allowlist.
if has_path 'onStartCommand\(' && ! has_path 'when \(intent\?\.action\)'; then
  evidence="$(rg -n 'onStartCommand\(' "${TARGET_PATHS[@]/#/$ROOT_DIR/}" | head -n 4 | tr '\n' '; ')"
  add_finding "$(jq -nc \
    --arg finding_id "NOTIF-002" \
    --arg module "$MODULE" \
    --arg title "Service start action handling may lack explicit allowlist" \
    --arg severity "medium" \
    --arg category "input-validation" \
    --arg evidence "$evidence" \
    --arg repro_steps "Invoke service with unexpected action and observe fallback behavior." \
    --argjson affected_files '["app/src/main/java/com/example/orgclock/notification/ClockInNotificationService.kt"]' \
    --arg confidence "medium" \
    --arg recommended_fix "Use an explicit allowlist for accepted actions and reject unknown values early." \
    '{finding_id:$finding_id,module:$module,title:$title,severity:$severity,category:$category,evidence:$evidence,repro_steps:$repro_steps,affected_files:$affected_files,confidence:$confidence,recommended_fix:$recommended_fix}')"
fi

if [[ "$(jq 'length' <<<"$findings_json")" -eq 0 ]]; then
  findings_json='[
    {
      "finding_id": "NOTIF-000",
      "module": "'"$MODULE"'",
      "title": "No actionable finding from static heuristics",
      "severity": "low",
      "category": "analysis",
      "evidence": "No high-confidence notification-surface pattern matched in configured targets.",
      "repro_steps": "N/A",
      "affected_files": [],
      "confidence": "low",
      "recommended_fix": "Run runtime intent-injection and notification action abuse tests."
    }
  ]'
fi

highest="$(jq -r '
  def score: if .=="critical" then 4 elif .=="high" then 3 elif .=="medium" then 2 else 1 end;
  (sort_by(.severity|score) | reverse | .[0])
' <<<"$findings_json")"

jq -n \
  --arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg module "$MODULE" \
  --arg scanner "attacker_codex_static_v1" \
  --argjson highest "$highest" \
  --argjson findings "$findings_json" \
  '{
    generated_at: $generated_at,
    scanner: $scanner,
    module: $module,
    finding_id: $highest.finding_id,
    severity: $highest.severity,
    title: $highest.title,
    category: $highest.category,
    evidence: $highest.evidence,
    repro_steps: $highest.repro_steps,
    affected_files: $highest.affected_files,
    confidence: $highest.confidence,
    recommended_fix: $highest.recommended_fix,
    findings: $findings
  }' > "$OUT_FILE"
