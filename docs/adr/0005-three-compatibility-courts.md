# 0005. Three compatibility courts

Status: Accepted
Date: 2026-09-03

## Context

"Is this release compatible?" is three questions in Scala 3, and answering one does not answer the
others.

*Binary* compatibility asks whether a class file compiled against the old artifact still links
against the new one. *TASTy* compatibility asks whether the Scala 3 compiler can re-typecheck a
downstream source file against the new artifact's TASTy — which is a stronger and different question,
because TASTy carries inline bodies, opaque type definitions, given priorities, and type members
that class files do not. *Source* compatibility asks whether downstream source still compiles, which
no tool decides exactly.

Intaglio makes the gap between these unusually wide. It publishes `opaque type`s (`GraphicsName`,
`BandPadding`, `CoordinateRatio`, `BinCount`, `DodgeWidth`), refinement type aliases
(`StatResult.Aux`, `PlotRecipe.Aux`, `TrainedLayer.Aux`, `CategoryIdentity.Aux`), abstract type
members inside traits (`Stat`, `PlotRecipe`, `TrainedLayer`), and `given`-resolved typeclasses.
Every one of those is invisible to MiMa and load-bearing for a consumer.

Three further pressures shape the gate. The library cross-compiles to Scala.js, whose artifacts have
no JVM class files for MiMa to read. It is pre-1.0, so `0.y.0` may break deliberately. And it is not
yet published to Central, so at the time this decision was taken there was no immutable artifact to
compare against.

## Decision

Run all three courts, report them separately, and never let a green result in one be quoted as
evidence for another.

*Binary*: sbt-version-policy drives MiMa over every JVM module with
`mimaReportSignatureProblems := true`, so generic signature changes are failures rather than notes.
*TASTy*: TASTy-MiMa (`tastyMiMaReportIssues`) runs over every JVM **and** Scala.js artifact.
*Source*: sbt-version-policy's forward-MiMa approximation, plus the compiled examples in `docs/` —
`docs/mdoc` type-checks every fenced `mdoc` block against the real modules, so a guide cannot
document an API that no longer exists.

The baseline is exact and lives in the repository. `compatibility/baseline.conf` is two lines — a
full commit SHA and the version that commit's API is published under:

```
sha=<40 hex characters>
version=<major>.<minor>.<patch>
```

Its current values are not repeated here; the file is the only place they live, and a record that
quoted them would be wrong from the next boundary onward.

`tools/check-compatibility.sh` validates that the SHA is a real commit in this history, `git archive`s
it into a temporary directory, and writes a `version.sbt` naming the baseline version — an archive
has no Git history, so sbt-dynver has nothing to derive a version from and the build must be told. It publishes
every module from that commit into an isolated Ivy repository (isolated `user.home`,
`sbt.boot.directory`, `sbt.global.base`, `sbt.ivy.home`, and `COURSIER_CACHE`, all under one
`mktemp -d` removed on exit), then runs `compatibilityCheck` in the working tree.

Two values are set for that second run, and both are derived from `baseline.conf` rather than fixed
in the script. `INTAGLIO_COMPAT_BASELINE_VERSION` is the switch: `mimaPreviousArtifacts` and
`tastyMiMaPreviousArtifacts` are empty without it, and `versionPolicyIntention` is
`Compatibility.None` without it and `BinaryAndSourceCompatible` with it. And the working tree is
built as `0.2.1-SNAPSHOT` — the patch release that follows the baseline — because sbt-version-policy
reads the version under test to decide what compatibility it must demand. Deriving that from the
baseline keeps the two in step when the baseline moves. Ordinary development is therefore an
explicitly breaking boundary; the strong court is opt-in and self-contained.

MiMa is skipped for Scala.js. `jsSettingsBase` sets `versionPolicyCheck / skip := true` and
`versionCheck / skip := true`, and the reason is written beside the setting: MiMa reads JVM class
files. A `.sjsir` artifact has none. The shared `intaglio-core`, `intaglio-laws`, and `intaglio-svg`
sources are compiled for both platforms from one source tree, so their public API is covered by the
JVM twin's MiMa run; `intaglio-canvas` is JavaScript-only and has no twin, and it receives the TASTy
court, which is the one that actually runs on Scala.js artifacts.

TASTy-MiMa's suppressions are narrow, of one kind, and enumerated. Only `ProblemKind.InternalError`
is filtered — no compatibility problem kind is suppressed anywhere — and only for symbols where the
tool's own parser fails while comparing an artifact with itself: `intaglio.PackedStatPlan.Aux` and
`intaglio.StatResult.Aux`, whose refinement aliases trigger an `Unexpected local ref` limitation;
eight `intaglio.java2d` symbols whose `java.awt` parameter types trigger package resolution failures;
and `intaglio.javafx.JavaFxCanvasContext.<init>`, whose `javafx` type triggers the same limitation.

`docs/compatibility.md` states the version policy, the deliberately retained compatibility surface
(`AesEnv` as a source alias for `AesSpec`, `SceneConformance` as a deprecated facade for
`RendererConformance`), and the rule for moving the baseline. This record does not restate it.

## Consequences

A pre-1.0 project has a real compatibility gate before it has a published artifact. The baseline is
a commit rather than a version, which means the court works on day one and works offline.

Reporting the courts separately is the point. A change that passes MiMa and fails TASTy-MiMa is a
change that links but does not recompile, and quoting the MiMa result would be false. Because
TASTy-MiMa also runs on Scala.js artifacts, that is the court in which a Canvas-only regression is
caught.

The filters are the weakest part of the arrangement and are treated as such. Each one is a known
tool limitation, verified by the fact that it fires when comparing identical artifacts, and each is
scoped to one fully qualified symbol rather than a package or a pattern. A new `InternalError` on a
new symbol fails the build and requires either a fix or a reviewed decision — it does not fall
through an existing filter.

None of the three courts proves behavioral compatibility. A method that keeps its signature and
changes its answer passes all three. That contract belongs to the law, conformance, differential,
fuzz, and golden suites, and `docs/compatibility.md` says so explicitly so the gate is not
over-read.

Building the baseline from source rather than downloading it has one consequence that a published
artifact would not have: both sides of the comparison are compiled, so neither court is
compiler-neutral. Changing `ThisBuild / scalaVersion` therefore obliges a baseline move even when no
public API moved, because the two compilers emit different generic signatures for some members and
sbt-version-policy sees `scala3-library_3` itself change. `docs/compatibility.md` records the exact
symptoms; the reason they exist at all is this decision.

The baseline is a manual object. It cannot be advanced to silence a failure, and advancing it
requires reviewing the public diff and the migration text and running `scalafmtCheckAll testAll` at
the replacement commit first. That is friction by design; the failure mode it prevents is a baseline
that tracks `HEAD` and therefore checks nothing.

Once a release exists on Central, release CI should compare against that immutable published version
and run `versionCheck` before publication. The repository baseline stays as the bootstrap court for
clean, unpublished development rather than being deleted.

## Alternatives considered

**MiMa alone.** Rejected: it cannot see `opaque type` definitions, `Aux` refinements, abstract type
members, or `inline` bodies, which is most of what makes Intaglio's extension points typed.

**TASTy-MiMa alone.** Rejected: a consumer that ships compiled artifacts still needs linkage, and
TASTy-MiMa does not decide it.

**A published-version baseline from day one.** Rejected: there was no published version, and
inventing one would have meant publishing an unreviewed artifact solely to be compared against.

**A floating baseline (previous tag, or `HEAD~1`).** Rejected: it makes the gate depend on git
history shape, silently weakens on a rebase, and lets a breaking change be laundered by a second
commit.

**Running MiMa on Scala.js artifacts by unpacking `.sjsir`.** Rejected: MiMa's model is JVM class
files, and there is no supported path. The JVM twin plus the TASTy court covers the same source
without inventing tooling.

**Suppressing TASTy-MiMa `InternalError` globally.** Rejected: it would hide the same failure kind
on symbols where it is real. Per-symbol matchers cost a build.sbt edit each and make every
suppression reviewable.
