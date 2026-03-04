#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$repo_root"

in_file=""
out_dir=""
mode="normal"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in)
      in_file="${2:-}"
      shift 2
      ;;
    --out-dir)
      out_dir="${2:-}"
      shift 2
      ;;
    --mode)
      mode="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$in_file" || ! -f "$in_file" ]]; then
  echo "Usage: $0 --in <scan_report.md> [--out-dir <dir>] [--mode <normal|strict>]" >&2
  exit 1
fi

if [[ "$mode" != "normal" && "$mode" != "strict" ]]; then
  echo "--mode must be normal or strict" >&2
  exit 1
fi

if [[ -z "$out_dir" ]]; then
  out_dir="/tmp/ui_ticket_drafts"
fi
mkdir -p "$out_dir"

extract_hits() {
  local label="$1"
  awk -v target="### ${label}" '
    $0 == target { in_block=1; in_code=0; next }
    in_block && /^### / { in_block=0 }
    in_block && /^```$/ { in_code = !in_code; next }
    in_block && in_code && NF > 0 { print }
  ' "$in_file"
}

title_for() {
  local label="$1"
  case "$label" in
    UX_TEXT) echo "Clarify and localize ambiguous UI/notification text" ;;
    UX_FLOW) echo "Reduce dialog friction in primary clock flow" ;;
    A11Y_SEMANTICS) echo "Improve UI semantics and screen reader labels" ;;
    A11Y_TOUCH_TARGET) echo "Increase tap target reliability in dense controls" ;;
    STATE_FEEDBACK) echo "Strengthen loading/empty/error state feedback" ;;
    ERROR_GUIDANCE) echo "Provide actionable recovery guidance on UI errors" ;;
    *) echo "Improve UI behavior" ;;
  esac
}

impact_for() {
  local label="$1"
  case "$label" in
    UX_FLOW|A11Y_SEMANTICS|STATE_FEEDBACK) echo "High" ;;
    *) echo "Medium" ;;
  esac
}

effort_for() {
  local label="$1"
  case "$label" in
    A11Y_TOUCH_TARGET|UX_TEXT) echo "S" ;;
    UX_FLOW|STATE_FEEDBACK) echo "M" ;;
    ERROR_GUIDANCE|A11Y_SEMANTICS) echo "M" ;;
    *) echo "M" ;;
  esac
}

priority_for() {
  local impact="$1"
  local effort="$2"
  if [[ "$impact" == "High" && "$effort" != "L" ]]; then
    echo "P1"
  elif [[ "$impact" == "Medium" ]]; then
    echo "P2"
  else
    echo "P3"
  fi
}

updated="$(date +%Y-%m-%d)"

labels=(UX_TEXT UX_FLOW A11Y_SEMANTICS A11Y_TOUCH_TARGET STATE_FEEDBACK ERROR_GUIDANCE)
created=0

for label in "${labels[@]}"; do
  mapfile -t lines < <(extract_hits "$label")

  uniq_refs=()
  declare -A seen=()
  for line in "${lines[@]}"; do
    ref="${line%%:*}:${line#*:}"
    ref="${ref%%:*}:${ref#*:}"
    file_part="${line%%:*}"
    rest="${line#*:}"
    line_no="${rest%%:*}"
    key="${file_part}:${line_no}"
    if [[ -n "$file_part" && -n "$line_no" && -z "${seen[$key]:-}" ]]; then
      seen[$key]=1
      uniq_refs+=("$key")
    fi
    if [[ "${#uniq_refs[@]}" -ge 4 ]]; then
      break
    fi
  done
  unset seen

  if [[ "${#uniq_refs[@]}" -lt 2 ]]; then
    continue
  fi

  if [[ "$mode" == "strict" && "${#uniq_refs[@]}" -lt 3 ]]; then
    continue
  fi

  title="$(title_for "$label")"
  impact="$(impact_for "$label")"
  effort="$(effort_for "$label")"
  priority="$(priority_for "$impact" "$effort")"

  slug="$(printf '%s' "$label" | tr '[:upper:]' '[:lower:]')"
  out_file="$out_dir/ui-${slug}.md"

  {
    echo "Title: $title"
    echo "Category: ui"
    echo "Status: Open"
    echo "Priority: $priority"
    echo "Impact: $impact"
    echo "Effort: $effort"
    echo "Updated: $updated"
    echo ""
    echo "## Problem"
    echo "- UI finding classified as \"$label\" with user-visible quality risk."
    echo ""
    echo "## Evidence"
    for ref in "${uniq_refs[@]}"; do
      echo "- $ref"
    done
    echo ""
    echo "## Hardcoded/Smell"
    echo "- Classification: $label"
    echo "- Current implementation likely depends on local ad-hoc behavior rather than a shared UX/a11y rule."
    echo ""
    echo "## Proposed Change"
    echo "- Introduce a small UI guideline abstraction or reusable UI helper for this pattern."
    echo "- Move user-visible behavior to explicit state and accessible labeling rules."
    echo ""
    echo "## Acceptance Criteria"
    echo "- [ ] At least two evidence points are resolved by concrete code changes."
    echo "- [ ] Resulting UI behavior is consistent for the classified finding ($label)."
    echo "- [ ] Manual verification confirms improved UX/a11y behavior."
    echo ""
    echo "## Test Plan"
    echo "- Add/update UI or unit tests around the touched behavior where feasible."
    echo "- Run existing android/unit test targets related to changed files."
    echo "- Manual talkback/readability check for user-visible changes."
    echo ""
    echo "## Out of Scope"
    echo "- Full visual redesign unrelated to the finding set."
    echo ""
    echo "## Dependencies"
    echo "- none"
  } > "$out_file"

  created=$((created + 1))
done

echo "Created $created ticket draft(s) in $out_dir"
