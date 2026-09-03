# Contributing to Intaglio

Intaglio's bar is evidence, not assertion. A change is finished when the courts
below pass and the commit message says which ones ran and what they showed.

## Build and test

```
sbt scalafmtCheckAll testAll     # every module, JVM and Scala.js
sbt compileAll                   # compile only
tools/check-docs.sh              # compile every documented example, re-render the gallery, check links
tools/check-compatibility.sh     # the compatibility gate against the exact baseline
```

`testAll` and `compileAll` name all thirteen modules explicitly rather than
relying on aggregation, so a module cannot silently drop out of the build. The
supported versions are the Scala 3 LTS (3.3.8, the default and the published
one) and the current feature release (3.9.0); CI runs both on JDK 17 and 21.
Select one locally with `sbt "++3.9.0" testAll`.

Formatting is scalafmt's job. Do not hand-format, and do not argue with it.

## What a change must carry

**Tests that would fail without it.** A new capability needs a test that
distinguishes the new behaviour from the old, not a test that merely exercises
it. Never weaken a test to make it pass.

**A law, where there is an invariant.** `modules/laws` holds the kits that
ecosystem authors run against their own extensions: `ScaleLaws`, `StatLaws`,
`GeomLaws`, `CoordLaws`, `PlotRecipeLaws`, `BackendLaws`, `PointShapeLaws`,
`RectCornerLaws`, `LineInterpolationLaws`, and the scene, layout, transform and
position kits. A geometric or numerical invariant belongs in a kit, expressed
through the public API, with a negative test proving the kit detects breakage.
The suites live in package `external.laws` deliberately: they must work without
privileged access to `intaglio`.

**A conformance case, where a backend must react.** `RendererConformance` is the
backend contract. Adding a scene feature means adding a case to `cases` (both
the `for` bindings and the `yield Vector(...)` — they are two hand-written
lists) so that every backend harness proves it draws the thing.

**Determinism.** Two renders of one scene must be equal, and JVM and Scala.js
output must be byte-identical. Anything that formats a number, orders a
collection, or seeds a generator is a determinism risk: format doubles through
the fixed-point helpers, order maps explicitly, and seed with the SplitMix64
helpers that behave identically on both platforms. Suites under
`shared/src/test` run on both platforms, which is what makes an exact string
assertion a cross-platform check.

**Typed errors.** A public constructor returns `Either[GraphicsError, A]` and
makes illegal states unrepresentable. An `unsafe`/`orThrow` variant may exist
beside it, never instead of it. `docs/extending/unsafe.md` lists every such
boundary; a new one belongs in that table.

**Documentation that compiles.** A guide's examples are fenced `mdoc` and
compiled by `tools/check-docs.sh`; a bare ```scala fence is not checked and
should not be used for new material. A gallery plate writes its own SVG into
`docs/gallery`, and that file is committed, so the source and the image a
reader sees are the same artifact.

## Commit messages

Write them in the repository's existing form: a one-line summary in the
imperative, a body that explains the problem before the solution, then two
labelled sections:

- **Evidence:** the commands you ran and what they reported — per-module test
  counts, the exact assertions that pin the new behaviour, the digests or
  goldens involved.
- **Not established:** what the change does *not* prove. This section is not
  optional and is not a place for modesty; it is where a reviewer learns which
  claims to distrust.

`git log` has many examples. Read three before writing your first.

## Compatibility

Before 1.0, a `0.y.0` release may break the public API and a `0.y.z` release may
not. Breaking the API means: an entry in [CHANGELOG.md](CHANGELOG.md) under
**Breaking**, a section in [MIGRATION.md](MIGRATION.md) naming the compiler
error and the edit that fixes it, and — in a separate later commit — moving the
baseline in `compatibility/baseline.conf`. Never move the baseline to silence a
failing gate. The full policy, including why Scala.js is checked by TASTy-MiMa
alone, is [docs/compatibility.md](docs/compatibility.md).

## Architecture decisions

A decision that constrains future work belongs in `docs/adr/` as a numbered
record: context, decision, consequences, alternatives considered. Add it in the
same change that implements the decision, not afterwards.

## Reporting a problem

A bug report is most useful as a failing scene: the smallest `Scene` or plot
program that misbehaves, the backend, the platform, and the exact output you
got against the output you expected. For anything with a security dimension,
read [SECURITY.md](SECURITY.md) first and do not open a public issue.
