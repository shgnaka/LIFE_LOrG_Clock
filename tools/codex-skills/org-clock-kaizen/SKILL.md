---
name: org-clock-kaizen
description: Identify hardcoded values and implementation smells in the org-clock-android repository, convert findings into implementation-ready kaizen tickets, and maintain docs/kaizens dashboards. Use when requests involve technical debt discovery, hardcoded constant audits, improvement backlog grooming, or preparing Codex-ready tasks.
---

# Org Clock Kaizen

Use this skill to run a repeatable workflow that turns vague improvements into implementation-ready tickets.

## Workflow

1. Run candidate scan:
   - `tools/codex-skills/org-clock-kaizen/scripts/scan_candidates.sh`
2. Classify each candidate into one category:
   - `ui`, `domain`, `data`, `infra-ci`, `tests-quality`, `security-privacy`, `docs-ops`
3. Create or update ticket files under `docs/kaizens/items`.
4. Enforce DoR before marking a ticket `Ready`.
5. Regenerate dashboards:
   - `tools/codex-skills/org-clock-kaizen/scripts/update_index.sh`

## Rules

- Treat `docs/kaizens/items/KZN-*.md` as canonical source.
- Keep generated files (`docs/kaizens/_index.md`, category dashboards) script-generated.
- Use `Impact x Effort` as default prioritization.
- Implement only tickets with `Status: Ready`.

## Required Ticket Fields

Use the exact required fields from `docs/kaizens/README.md` and `references/entry-template.md`.

## References

- `references/workflow.md`: end-to-end process and DoR checks.
- `references/taxonomy.md`: category definitions.
- `references/scoring.md`: Impact/Effort scoring model.
- `references/entry-template.md`: canonical ticket template.
