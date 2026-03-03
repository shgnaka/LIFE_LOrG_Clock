#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/security-loop/use-codex-cli.sh" >/dev/null

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
  SYNC-001)
    fix_strategy="Enforce signature verification on inbound envelopes before command acceptance."
    patch_plan='[
      "Introduce verifier interface in HttpIncomingCommandSource for envelope authenticity.",
      "Reject payloads lacking valid signature and trusted key binding.",
      "Add unit tests for forged payload and unknown key-id rejection."
    ]'
    tests='[
      "HttpIncomingCommandSource rejects unsigned / malformed signatures",
      "Only trusted key-id can produce VerifiedIncomingCommand"
    ]'
    ;;
  SYNC-002)
    fix_strategy="Persist replay registry across process restarts with bounded TTL."
    patch_plan='[
      "Create Room-backed replay nonce store with (senderDeviceId,commandId) primary key.",
      "Replace in-memory ReplayGuard with persistent adapter and retention cleanup.",
      "Add restart-simulation test to assert duplicate is rejected after relaunch."
    ]'
    tests='[
      "Replay duplicate rejected across restart",
      "Old entries pruned by TTL and max rows"
    ]'
    ;;
  SYNC-003)
    fix_strategy="Separate reachability probe from trust grant and require explicit key confirmation."
    patch_plan='[
      "Change addTrustedPeer flow to store probe result only.",
      "Add explicit trust action requiring peer fingerprint/key-id confirmation.",
      "Guard default peer selection on confirmed trust state only."
    ]'
    tests='[
      "Reachable peer is not trusted without confirmation",
      "Trusted peer persists only after explicit confirm"
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
    residual_risk: "Requires runtime validation and peer interoperability tests before default-on rollout.",
    source_finding: $source_finding
  }' > "$OUT_FILE"
