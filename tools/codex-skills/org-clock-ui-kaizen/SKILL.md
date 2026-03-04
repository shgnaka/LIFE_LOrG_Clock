---
name: org-clock-ui-kaizen
description: Discover UI/UX and accessibility improvement opportunities in org-clock-android, convert them into implementation-ready kaizen tickets under docs/kaizens/items, and keep dashboards updated.
---

# Org Clock UI Kaizen

Use this skill to run a repeatable UI-focused improvement discovery workflow.

## When To Use

- You need UI/UX improvement candidates from Compose screens and notification UX.
- You need accessibility-focused findings (labels, touch targets, state feedback).
- You want implementation-ready kaizen tickets in `docs/kaizens/items`.

## Workflow

1. Run UI candidate scan:
   - `tools/codex-skills/org-clock-ui-kaizen/scripts/scan_ui_candidates.sh --out /tmp/ui_scan_report.md`
2. Review and convert findings into ticket drafts:
   - `tools/codex-skills/org-clock-ui-kaizen/scripts/review_ui_candidates.sh --in /tmp/ui_scan_report.md --out-dir /tmp/ui_ticket_drafts`
3. Promote reviewed drafts into canonical kaizen tickets:
   - `tools/codex-skills/org-clock-ui-kaizen/scripts/promote_ui_drafts.sh --in-dir /tmp/ui_ticket_drafts`
4. Optionally force a common status during promotion:
   - `tools/codex-skills/org-clock-ui-kaizen/scripts/promote_ui_drafts.sh --in-dir /tmp/ui_ticket_drafts --status Open`
5. Regenerate dashboards (already triggered by `new_entry.sh`, but can be run explicitly):
   - `tools/codex-skills/org-clock-kaizen/scripts/update_index.sh`

## Rules

- Canonical records remain `docs/kaizens/items/KZN-*.md`.
- Generated dashboards remain managed by `org-clock-kaizen/scripts/update_index.sh`.
- Prioritize by `Impact x Effort`.
- Initial focus is UX + accessibility before architecture-only refactors.

## Classification Labels

Use one primary label per finding:

- `UX_TEXT`: user-facing message clarity/localization concerns.
- `UX_FLOW`: friction in user task flow (excess steps, missing confirmation/feedback).
- `A11Y_SEMANTICS`: missing/ambiguous labels and semantics.
- `A11Y_TOUCH_TARGET`: likely small hit area or dense interaction points.
- `STATE_FEEDBACK`: weak loading/empty/error/running-state communication.
- `ERROR_GUIDANCE`: error is shown without actionable next step.

## References

- `references/ux-heuristics.md`: practical UX checks for this app.
- `references/a11y-checklist.md`: accessibility checks for Compose and notification UX.
- `references/ticket-template-ui.md`: UI ticket drafting template aligned with Kaizen contract.
