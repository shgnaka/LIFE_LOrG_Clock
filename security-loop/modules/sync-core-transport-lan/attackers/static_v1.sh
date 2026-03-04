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

# SYNC-001: Inbound command path lacks explicit signature verification.
if has_path 'verificationMethod\s*=\s*"schema\+sender\+timestamp\+replay"'; then
  evidence="$(rg -n 'verificationMethod\s*=\s*"schema\+sender\+timestamp\+replay"' "${TARGET_PATHS[@]/#/$ROOT_DIR/}" | head -n 3 | tr '\n' '; ')"
  add_finding "$(jq -nc \
    --arg finding_id "SYNC-001" \
    --arg module "$MODULE" \
    --arg title "Inbound command acceptance lacks cryptographic verification gate" \
    --arg severity "high" \
    --arg category "authenticity" \
    --arg evidence "$evidence" \
    --arg repro_steps "Send a crafted clock.command.v1 payload to /v1/messages with valid fields but no trusted signature; observe acceptance if schema/timestamp/replay checks pass." \
    --argjson affected_files '["app/src/synccore/java/com/example/orgclock/sync/HttpIncomingCommandSource.kt"]' \
    --arg confidence "high" \
    --arg recommended_fix "Require signed envelope verification against trusted peer keys before marking IncomingVerificationState.Verified." \
    '{finding_id:$finding_id,module:$module,title:$title,severity:$severity,category:$category,evidence:$evidence,repro_steps:$repro_steps,affected_files:$affected_files,confidence:$confidence,recommended_fix:$recommended_fix}')"
fi

# SYNC-002: Replay protection memory-only.
if has_path 'class ReplayGuard' && has_path 'private val seen = linkedMapOf'; then
  evidence="$(rg -n 'class ReplayGuard|private val seen = linkedMapOf' "${TARGET_PATHS[@]/#/$ROOT_DIR/}" | head -n 6 | tr '\n' '; ')"
  add_finding "$(jq -nc \
    --arg finding_id "SYNC-002" \
    --arg module "$MODULE" \
    --arg title "Replay protection is process-memory only" \
    --arg severity "high" \
    --arg category "replay" \
    --arg evidence "$evidence" \
    --arg repro_steps "Restart app/service then resend previous command_id; in-memory replay cache is reset." \
    --argjson affected_files '["app/src/synccore/java/com/example/orgclock/sync/HttpIncomingCommandSource.kt"]' \
    --arg confidence "high" \
    --arg recommended_fix "Persist replay nonce/command ids with TTL in Room and enforce duplicate rejection across restarts." \
    '{finding_id:$finding_id,module:$module,title:$title,severity:$severity,category:$category,evidence:$evidence,repro_steps:$repro_steps,affected_files:$affected_files,confidence:$confidence,recommended_fix:$recommended_fix}')"
fi

# SYNC-003: Trust onboarding based on reachability only.
if has_path 'addTrustedPeer\(' && has_path 'if \(probe\.reachable\) \{[[:space:]]*peerTrustStore\.trust'; then
  evidence="$(rg -n 'addTrustedPeer\(|peerTrustStore\.trust\(' "${TARGET_PATHS[@]/#/$ROOT_DIR/}" | head -n 6 | tr '\n' '; ')"
  add_finding "$(jq -nc \
    --arg finding_id "SYNC-003" \
    --arg module "$MODULE" \
    --arg title "Peer trust grant is tied to health reachability" \
    --arg severity "medium" \
    --arg category "trust-management" \
    --arg evidence "$evidence" \
    --arg repro_steps "Make a host respond 200 on /v1/health and add it as peer; trust is granted without key confirmation." \
    --argjson affected_files '["app/src/main/java/com/example/orgclock/sync/SyncIntegrationService.kt","app/src/main/java/com/example/orgclock/sync/PeerHealthChecker.kt"]' \
    --arg confidence "medium" \
    --arg recommended_fix "Require explicit key fingerprint confirmation before persisting trust, and decouple health probe from trust grant." \
    '{finding_id:$finding_id,module:$module,title:$title,severity:$severity,category:$category,evidence:$evidence,repro_steps:$repro_steps,affected_files:$affected_files,confidence:$confidence,recommended_fix:$recommended_fix}')"
fi

if [[ "$(jq 'length' <<<"$findings_json")" -eq 0 ]]; then
  findings_json='[
    {
      "finding_id": "SYNC-000",
      "module": "'"$MODULE"'",
      "title": "No actionable finding from static heuristics",
      "severity": "low",
      "category": "analysis",
      "evidence": "No high-confidence pattern matched in configured targets.",
      "repro_steps": "N/A",
      "affected_files": [],
      "confidence": "low",
      "recommended_fix": "Run deeper dynamic analysis and protocol-level tests."
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
