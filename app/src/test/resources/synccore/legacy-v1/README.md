# Legacy sync-core v1 fixtures

These files freeze the wire and Android queue schema consumed by the current
external sync-core integration.

- `clock-command-payload.json`: `clock.command.v1` domain payload
- `clock-result-payload.json`: `clock.result.v1` domain payload
- `command-envelope.json`: serialized legacy `Envelope`
- `command-envelope.canonical.txt`: exact legacy signature input with an empty
  signature excluded from the canonical text
- `schema-v1.sql`: queue database before replay persistence
- `migration-1-2.sql`: additive replay-registry migration
- `schema-v2.sql`: queue database after that migration

The signature in `command-envelope.json` is shape-only fixture data. Standard
signature verification tests generate real ES256 key pairs and sign canonical
input at test time. Ed25519 fixtures are retained only for legacy compatibility
coverage.
