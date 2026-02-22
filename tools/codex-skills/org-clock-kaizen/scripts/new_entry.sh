#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$repo_root"

category=""
title=""
status="Open"
priority="P2"
impact="Medium"
effort="M"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --category)
      category="${2:-}"
      shift 2
      ;;
    --title)
      title="${2:-}"
      shift 2
      ;;
    --status)
      status="${2:-}"
      shift 2
      ;;
    --priority)
      priority="${2:-}"
      shift 2
      ;;
    --impact)
      impact="${2:-}"
      shift 2
      ;;
    --effort)
      effort="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$category" || -z "$title" ]]; then
  echo "Usage: $0 --category <ui|domain|data|infra-ci|tests-quality|security-privacy|docs-ops> --title <text> [--status ... --priority ... --impact ... --effort ...]" >&2
  exit 1
fi

valid_categories=(ui domain data infra-ci tests-quality security-privacy docs-ops)
valid=0
for c in "${valid_categories[@]}"; do
  if [[ "$category" == "$c" ]]; then
    valid=1
  fi
done
if [[ "$valid" -ne 1 ]]; then
  echo "Invalid category: $category" >&2
  exit 1
fi

today="$(date +%Y%m%d)"
mkdir -p docs/kaizens/items

last_num="$(ls docs/kaizens/items/KZN-"$today"-*.md 2>/dev/null | sed -E 's#.*-([0-9]{3})\.md#\1#' | sort | tail -n1)"
if [[ -z "$last_num" ]]; then
  next_num="001"
else
  next_num="$(printf "%03d" $((10#$last_num + 1)))"
fi

id="KZN-$today-$next_num"
file="docs/kaizens/items/$id.md"
updated="$(date +%Y-%m-%d)"

cat > "$file" <<TEMPLATE
ID: $id
Title: $title
Category: $category
Status: $status
Priority: $priority
Impact: $impact
Effort: $effort
Updated: $updated

## Problem
[Describe the behavior gap and business/dev impact]

## Evidence
- path/to/file.ext:123
- path/to/other.ext:45

## Hardcoded/Smell
- [List literals, tight coupling, or anti-patterns]

## Proposed Change
- [Implementation approach with scope]

## Acceptance Criteria
- [ ] [Observable criterion]
- [ ] [Observable criterion]

## Test Plan
- [Unit/integration/manual scenarios]

## Out of Scope
- [Explicit non-goals]

## Dependencies
- [PRs, migrations, teams, or none]
TEMPLATE

echo "Created: $file"

if [[ -x tools/codex-skills/org-clock-kaizen/scripts/update_index.sh ]]; then
  tools/codex-skills/org-clock-kaizen/scripts/update_index.sh >/dev/null
  echo "Updated dashboards"
fi
