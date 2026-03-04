#!/usr/bin/env bash
set -euo pipefail

: "${MODULE:?MODULE is required}"
: "${IN_REPORT:?IN_REPORT is required}"
: "${OUT_FILE:?OUT_FILE is required}"

if [[ ! -f "$IN_REPORT" ]]; then
  echo "IN_REPORT not found: $IN_REPORT" >&2
  exit 1
fi

jq -e '.finding_id and .module and .severity' "$IN_REPORT" >/dev/null

select_finding='
  . as $root
  | ((.findings // []) | map(select(.finding_id == $root.finding_id)) | .[0])
    // ((.findings // []) | map(select(.severity=="critical" or .severity=="high")) | .[0])
    // (.findings // [.] | .[0])
'
finding="$(jq -c "$select_finding" "$IN_REPORT")"

finding_id="$(jq -r '.finding_id' <<<"$finding")"
severity="$(jq -r '.severity' <<<"$finding")"
category="$(jq -r '.category' <<<"$finding")"
recommended_fix="$(jq -r '.recommended_fix // ""' <<<"$finding")"

case "$finding_id" in
  NOTIF-001)
    fix_strategy="Remove mutable PendingIntent usage from notification action flows."
    patch_plan='[
      "Replace mutable PendingIntent flags with FLAG_IMMUTABLE in notification actions.",
      "Use explicit action constants and validate extras before dispatch.",
      "Add regression tests that assert immutable PendingIntent flags are used."
    ]'
    tests='[
      "Notification PendingIntent uses FLAG_IMMUTABLE",
      "Intent extras tampering does not alter privileged action behavior"
    ]'
    ;;
  NOTIF-002)
    fix_strategy="Enforce allowlisted service actions and reject unknown action values."
    patch_plan='[
      "Add explicit allowlist branch for supported actions in onStartCommand.",
      "Return safe default for unknown actions and avoid executing side effects.",
      "Add tests that unknown action is rejected without state change."
    ]'
    tests='[
      "Unknown notification service action is ignored safely",
      "Only allowlisted actions trigger side effects"
    ]'
    ;;
  *)
    fix_strategy="Apply targeted defensive changes with tests before merge."
    patch_plan='["Harden the identified code path", "Add regression tests for the repro steps"]'
    tests='["Regression test for finding reproduction"]'
    ;;
esac

jq -n \
  --arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg module "$MODULE" \
  --arg finding_id "$finding_id" \
  --arg severity "$severity" \
  --arg category "$category" \
  --arg fix_strategy "$fix_strategy" \
  --arg recommended "$recommended_fix" \
  --argjson patch_plan "$patch_plan" \
  --argjson tests "$tests" \
  --argjson source_finding "$finding" \
  '{
    generated_at: $generated_at,
    module: $module,
    finding_id: $finding_id,
    severity: $severity,
    category: $category,
    fix_strategy: $fix_strategy,
    recommended_fix_from_attacker: $recommended,
    patch_plan: $patch_plan,
    tests_to_add_or_update: $tests,
    expected_risk_reduction: (if $severity == "critical" or $severity == "high" then "high" else "medium" end),
    residual_risk: "Requires runtime validation and notification abuse tests before default-on rollout.",
    source_finding: $source_finding
  }' > "$OUT_FILE"
