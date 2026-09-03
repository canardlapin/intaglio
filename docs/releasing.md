# Releasing Intaglio

A release is an explicit act. Nothing publishes on a merge to `main`: the
release workflow runs only when a maintainer pushes a `v*` tag or dispatches it
by hand, and it rehearses the release on a clean clone before it signs
anything.

## What names a version

`build.sbt` does not set `version`. sbt-dynver derives it from the git state,
so the tag is the version: `v0.2.0` publishes `0.2.0`, and an untagged commit
is a `-SNAPSHOT` that the workflow never uploads. `versionScheme` is
`early-semver`, which is what tells a downstream build's eviction check how to
read `0.y.z`.

The published artifact is built with the Scala 3 LTS alone. Every Scala 3
artifact carries the same `_3` suffix, so cross-publishing the feature release
would overwrite the LTS build at identical coordinates; the release job
therefore sets `CI_RELEASE=publishSigned` rather than sbt-ci-release's default
`+publishSigned`. TASTy is forward- but not backward-compatible, so the LTS
build is the one every later compiler can read. The feature release exists in
`crossScalaVersions` as a CI court, nothing more.

## Before tagging

Run each gate and read its output. None of these is a formality.

```
sbt scalafmtCheckAll testAll         # every module, JVM and Scala.js
sbt "++3.9.0" testAll                # the feature-release court
tools/check-docs.sh                  # examples compile, gallery is current, links resolve
tools/check-compatibility.sh         # the compatibility gate
tools/release-rehearsal.sh           # the release rehearsal, described below
```

Then confirm the paperwork:

- [CHANGELOG.md](../CHANGELOG.md) has an entry for everything in this release,
  and its **Breaking** list is complete.
- [MIGRATION.md](../MIGRATION.md) has a section for every breaking change,
  naming the compiler error and the edit that fixes it.
- `compatibility/baseline.conf` points at a commit that is a permitted
  boundary. Moving it is governed by
  [compatibility.md](compatibility.md#moving-the-baseline) and belongs in its
  own commit, after the change it accommodates.
- The `Unreleased` heading in the changelog becomes the version being released.

## The rehearsal

`tools/release-rehearsal.sh` answers a question a normal build cannot: would
these artifacts be complete and resolvable for someone who has never seen this
machine?

It clones the repository at `HEAD` into a scratch directory, then builds with
its own `user.home`, ivy home, coursier cache and sbt boot directory, so a
stale local artifact cannot stand in for a missing dependency. It publishes
every module to a throwaway local Maven repository and then reads the generated
POM closure back, checking that:

- every module the build aggregates and does not skip produced a POM, a jar, a
  sources jar and a javadoc jar — including both platform variants of each
  cross-built module. Scaladoc runs nowhere else in the build, so the javadoc
  jar is the artifact a change can silently stop producing;
- each POM carries the metadata the Central Portal requires: `name`,
  `description`, `url`, `licenses`, `developers`, `scm`;
- nothing in the closure depends on a SNAPSHOT, which Central refuses;
- every third-party coordinate in the closure resolves from Maven Central;
- the modules marked `publish / skip` — `intaglio-performance-gates`,
  `intaglio-docs`, and the aggregate root — were not published.

Nothing is signed and nothing leaves the machine. The release workflow runs the
rehearsal as a job that must pass before the publishing job starts, so a tag on
a repository whose POMs are incomplete fails before it can sign an artifact.

## Tagging

```
git tag -s v0.2.0 -m "Intaglio 0.2.0"
git push origin v0.2.0
```

The tag triggers `.github/workflows/release.yml`: the rehearsal job, then
`sbt ci-release`, which signs with the repository's PGP key and uploads a
bundle to the Sonatype Central Portal.

## Credentials

Four repository secrets, all held by the maintainer and none present in this
repository:

| Secret | What it is |
| --- | --- |
| `PGP_SECRET` | The signing key, `gpg --armor --export-secret-keys <key-id> \| base64` |
| `PGP_PASSPHRASE` | That key's passphrase |
| `SONATYPE_USERNAME` | The username half of a Central Portal user token |
| `SONATYPE_PASSWORD` | The password half of that token |

Use a user token rather than an account password, and a signing key created for
this purpose rather than a personal identity key. Publish the public half to a
keyserver before the first release, or Central will reject the signature.

## If a release goes wrong

Maven Central is immutable: a published version cannot be replaced. If a
release is broken, tag the fix as the next patch version and say so in the
changelog. Do not delete and re-push a tag that has already published — the
coordinates are already taken, and a consumer may already have resolved them.

If the workflow fails before uploading, fix the cause and re-dispatch; the tag
is still the version, so no new tag is needed.
