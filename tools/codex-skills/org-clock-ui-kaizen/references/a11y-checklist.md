# Accessibility Checklist (Compose + Notifications)

## Semantics

- Interactive icons should have meaningful labels (`contentDescription` or semantics).
- Screen reader output should describe intent, not only visual shape.
- Decorative visuals should be silent to assistive technology.

## Touch Targets

- Tap targets should be large enough for reliable operation.
- Dense rows should not create accidental taps.
- Adjacent destructive and non-destructive actions should be clearly separated.

## Status & Errors

- Important state changes should be announced or clearly exposed in text.
- Errors should be paired with recovery actions.
- Notification text should include context, not only generic status.

## Keyboard/Focus

- Dialogs should have deterministic focus progression.
- Dismiss/confirm controls should be reachable and labeled.
- Dynamic content updates should not trap focus.
