# Kaizen Workflow

This directory tracks hardcoded values and improvement opportunities as implementation-ready tickets.

## Directory Layout

- `_index.md`: cross-category dashboard (auto-generated).
- `ui.md`, `domain.md`, `data.md`, `infra-ci.md`, `tests-quality.md`, `security-privacy.md`, `docs-ops.md`: category dashboards (auto-generated).
- `items/KZN-YYYYMMDD-NNN.md`: canonical kaizen ticket documents.

## Source of Truth

- Canonical record: `docs/kaizens/items/KZN-*.md`
- Generated views: `_index.md` and category files.

## Ticket Contract (v1)

Each ticket must include these fields:

- `ID`
- `Title`
- `Category`
- `Status` (`Open | Ready | In Progress | Blocked | Done`)
- `Priority` (`P1 | P2 | P3`)
- `Impact` (`High | Medium | Low`)
- `Effort` (`S | M | L`)
- `Updated` (`YYYY-MM-DD`)
- `Problem`
- `Evidence` (at least 2 path+line references)
- `Hardcoded/Smell`
- `Proposed Change`
- `Acceptance Criteria`
- `Test Plan`
- `Out of Scope`
- `Dependencies`

## Definition of Ready (DoR)

A ticket can be moved to `Ready` only if all of the following are true:

1. Evidence has at least two concrete file references (`path:line`).
2. Acceptance criteria are objectively testable.
3. Test plan has at least one executable scenario.
4. Out-of-scope is explicit.

## Workflow

1. Scan candidates:
```bash
./tools/codex-skills/org-clock-kaizen/scripts/scan_candidates.sh
```
2. Create ticket:
```bash
./tools/codex-skills/org-clock-kaizen/scripts/new_entry.sh --category ui --title "Extract notification permission gate"
```
3. Fill required sections in the created item file.
4. Regenerate dashboards:
```bash
./tools/codex-skills/org-clock-kaizen/scripts/update_index.sh
```
5. Implement only tickets whose `Status` is `Ready`.

## Operating Cadence

- Every PR (before): add or update tickets for planned changes.
- Every PR (after): update status, test result, and references.
- Weekly: prioritize backlog by `Impact x Effort`.

## Skill Installation

The Codex skill source is `tools/codex-skills/org-clock-kaizen`.

Install to your local Codex home as needed:

```bash
mkdir -p "$CODEX_HOME/skills"
cp -R tools/codex-skills/org-clock-kaizen "$CODEX_HOME/skills/"
```
