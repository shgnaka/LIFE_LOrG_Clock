# Versioning policy

Org Clock follows [Semantic Versioning 2.0.0](https://semver.org/) for product
releases.

## Product version

The canonical product version is `orgclock.version` in `gradle.properties`.
Release tags add the `v` prefix:

```text
vMAJOR.MINOR.PATCH
```

For example, property value `1.4.2` is released as tag `v1.4.2`. Android, iOS, and
desktop builds released from the same commit use the same product version.

- `MAJOR`: incompatible changes to user data, sync/network compatibility, or
  documented external behavior that require users or integrations to migrate.
- `MINOR`: backward-compatible features or meaningful capability additions.
- `PATCH`: backward-compatible bug, security, performance, documentation, and
  internal maintenance changes.

When several change types are present, use the highest required increment.
Version numbers are never reused after publication.

## Before 1.0.0

Versions below `1.0.0` indicate that compatibility is not yet guaranteed.

- Breaking changes increment `MINOR`.
- Backward-compatible features increment `MINOR`.
- Backward-compatible fixes increment `PATCH`.

The first release that promises stable user-data and sync compatibility is
`1.0.0`.

## Release versions

New releases use only stable `MAJOR.MINOR.PATCH` versions. Pre-release suffixes
such as `-alpha`, `-beta`, and `-rc` are not used or accepted by the release
workflow. Existing `v1.0.0-rc.1` through `v1.0.0-rc.5` tags remain immutable
historical records; the first release under this policy is `v1.0.0`.

## Platform build numbers

Store-facing build numbers are not part of SemVer:

- Android `versionName` is the product version; `versionCode` is a
  monotonically increasing integer stored as `orgclock.versionCode` in
  `gradle.properties`. Increment it for every Android release build, including
  pre-releases and rebuilds.
- iOS `CFBundleShortVersionString` is the product version;
  `CFBundleVersion` is a monotonically increasing integer.
- Desktop package versions use `orgclock.version`, including Windows MSI
  metadata.

Rebuilding an existing product version for a store may increment only the
platform build number. It must not replace or move an existing Git tag or
GitHub Release.

## Compatibility scope

The product version covers the shipped application as a whole. Internal
database migrations, serialized payloads, and sync protocol schemas retain
their own schema versions. Changing an internal schema does not by itself
require a product `MAJOR` increment when the app migrates old data and remains
compatible with supported peers.

Use a `MAJOR` increment when a release intentionally drops that compatibility,
requires manual migration, or cannot interoperate with supported released
versions.

## Release procedure

1. Confirm each included PR's `major`, `minor`, `patch`, or `none` classification.
2. Select the next version from changes recorded under `CHANGELOG.md`'s
   `Unreleased` section.
3. Update `orgclock.version` and increment `orgclock.versionCode` in
   `gradle.properties`.
4. Rename the changelog's `Unreleased` entries to `VERSION - YYYY-MM-DD` and add
   a new empty `Unreleased` section.
5. Verify release builds and migration/sync compatibility using the checklist below.
6. Commit the release as `chore(release): VERSION`.
7. Create an annotated `vMAJOR.MINOR.PATCH` tag.
8. Push the tag. The desktop release workflow verifies that the tag matches
   `orgclock.version`, then publishes generated release notes, installers, and
   checksums as a stable GitHub Release.

## Compatibility checklist

Before selecting the increment, check whether the release remains compatible
with every supported released version across:

- Android/Desktop sync protocol and old-peer communication
- pairing invitations, envelopes, signatures, and other wire formats
- Room/SQLite schemas, automatic migrations, and rollback or backup behavior
- org-file parsing and writing
- credentials, identities, trust state, and settings persisted on disk
- CLI and documented user-visible behavior

If old data can be migrated automatically and supported old peers still
interoperate, the change can normally be `MINOR` or `PATCH`. Requiring users to
migrate manually, invalidating stored identity/credentials, or intentionally
dropping old-peer interoperability is a `MAJOR` change after 1.0.0.
