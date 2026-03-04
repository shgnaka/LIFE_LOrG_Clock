# UX Heuristics (Org Clock UI)

Use this checklist when evaluating findings from the scanner.

## Task Flow

- Primary task (clock in/out and edit) should be obvious within one screenful.
- Critical actions should avoid unnecessary dialog chaining.
- After a user action, provide immediate confirmation (in-UI or notification).

## Message Quality

- Avoid ambiguous labels like "Done" or "Failed" without context.
- Prefer actionable error messages with a next step.
- Ensure user-facing strings are localizable where practical.

## Cognitive Load

- Keep simultaneous choices low when user is mid-task.
- Avoid presenting advanced options before basic flow is complete.
- Group related controls with consistent naming.

## State Feedback

- Loading, empty, success, and error states should be visually distinguishable.
- Long-running operations should present status updates.
- Time-sensitive state should reflect current status without requiring manual refresh.
