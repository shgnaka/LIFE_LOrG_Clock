#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$repo_root"

in_dir="/tmp/ui_ticket_drafts"
status_override=""
dry_run=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in-dir)
      in_dir="${2:-}"
      shift 2
      ;;
    --status)
      status_override="${2:-}"
      shift 2
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ ! -d "$in_dir" ]]; then
  echo "Input directory not found: $in_dir" >&2
  exit 1
fi

extract_field() {
  local file="$1"
  local key="$2"
  rg --no-line-number --no-heading "^${key}:" "$file" | head -n1 | sed -E "s/^${key}:[[:space:]]*//"
}

valid_status() {
  local value="$1"
  case "$value" in
    Open|Ready|In\ Progress|Blocked|Done) return 0 ;;
    *) return 1 ;;
  esac
}

valid_priority() {
  local value="$1"
  case "$value" in
    P1|P2|P3) return 0 ;;
    *) return 1 ;;
  esac
}

valid_impact() {
  local value="$1"
  case "$value" in
    High|Medium|Low) return 0 ;;
    *) return 1 ;;
  esac
}

valid_effort() {
  local value="$1"
  case "$value" in
    S|M|L) return 0 ;;
    *) return 1 ;;
  esac
}

created=0
skipped=0

shopt -s nullglob
for draft in "$in_dir"/*.md; do
  title="$(extract_field "$draft" "Title")"
  status="$(extract_field "$draft" "Status")"
  priority="$(extract_field "$draft" "Priority")"
  impact="$(extract_field "$draft" "Impact")"
  effort="$(extract_field "$draft" "Effort")"
  updated="$(extract_field "$draft" "Updated")"

  if [[ -n "$status_override" ]]; then
    status="$status_override"
  fi

  if [[ -z "$title" || -z "$status" || -z "$priority" || -z "$impact" || -z "$effort" ]]; then
    echo "Skipping malformed draft (missing header fields): $draft" >&2
    skipped=$((skipped + 1))
    continue
  fi

  if ! valid_status "$status" || ! valid_priority "$priority" || ! valid_impact "$impact" || ! valid_effort "$effort"; then
    echo "Skipping invalid enum values in: $draft" >&2
    skipped=$((skipped + 1))
    continue
  fi

  if [[ -z "$updated" ]]; then
    updated="$(date +%Y-%m-%d)"
  fi

  body="$(sed -n '/^## Problem/,$p' "$draft")"
  if [[ -z "$body" ]]; then
    echo "Skipping draft without sections from '## Problem': $draft" >&2
    skipped=$((skipped + 1))
    continue
  fi

  if [[ "$dry_run" -eq 1 ]]; then
    echo "DRY RUN: would promote '$draft' with title '$title'"
    created=$((created + 1))
    continue
  fi

  out="$(
    tools/codex-skills/org-clock-kaizen/scripts/new_entry.sh \
      --category ui \
      --title "$title" \
      --status "$status" \
      --priority "$priority" \
      --impact "$impact" \
      --effort "$effort"
  )"
  file="$(printf '%s\n' "$out" | sed -n 's/^Created:[[:space:]]*//p' | head -n1)"
  if [[ -z "$file" || ! -f "$file" ]]; then
    echo "Failed to resolve created file for draft: $draft" >&2
    skipped=$((skipped + 1))
    continue
  fi

  id="$(extract_field "$file" "ID")"
  cat > "$file" <<EOF
ID: $id
Title: $title
Category: ui
Status: $status
Priority: $priority
Impact: $impact
Effort: $effort
Updated: $updated

$body
EOF

  echo "Promoted: $draft -> $file"
  created=$((created + 1))
done

echo "Promotion complete. promoted=$created skipped=$skipped dry_run=$dry_run"

