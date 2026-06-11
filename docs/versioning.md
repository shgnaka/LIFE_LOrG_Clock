# Versioning policy

Org Clock follows [Semantic Versioning 2.0.0](https://semver.org/) for product
releases.

## Product version

The canonical release version is the Git tag:

```text
vMAJOR.MINOR.PATCH
```

For example, tag `v1.4.2` represents product version `1.4.2`. Android, iOS, and
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

## Pre-releases

Unstable release candidates use SemVer pre-release identifiers:

```text
v1.3.0-alpha.1
v1.3.0-beta.1
v1.3.0-rc.1
```

Increment the numeric suffix for each candidate. A stable release removes the
suffix. Build metadata such as `+sha.abc1234` may identify local or CI builds,
but it does not change release precedence and should not be used for public
release tags.

Pushing a pre-release tag runs the `Release Desktop Installers` workflow. The
workflow builds the Windows and Linux installers and publishes the associated
GitHub Release with its **Pre-release** flag enabled.

For example, create and push the first `1.0.0` release candidate with:

```bash
git tag -a v1.0.0-rc.1 -m "Org Clock v1.0.0-rc.1"
git push origin v1.0.0-rc.1
```

Do not move or reuse a published pre-release tag. Fix the problem and increment
the suffix, for example from `rc.1` to `rc.2`.

## Platform build numbers

Store-facing build numbers are not part of SemVer:

- Android `versionName` is the product version; `versionCode` is a
  monotonically increasing integer.
- iOS `CFBundleShortVersionString` is the product version;
  `CFBundleVersion` is a monotonically increasing integer.
- Desktop package versions are derived from the Git tag without the leading
  `v`. Windows MSI metadata uses the numeric `MAJOR.MINOR.PATCH` portion because
  MSI does not accept SemVer pre-release identifiers; the Git tag, GitHub
  Release, and installer filename retain the complete pre-release version.

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

1. Select the next version from changes since the latest release.
2. Update platform product versions and monotonically increasing build numbers.
3. Verify release builds and migration/sync compatibility.
4. Create an annotated `vMAJOR.MINOR.PATCH` tag, or a permitted pre-release tag.
5. Push the tag. The desktop release workflow publishes generated release notes,
   installers, and checksums; pre-release tags are marked as GitHub
   Pre-releases.
