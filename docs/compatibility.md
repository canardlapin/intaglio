# Compatibility policy

Intaglio treats compatibility as a property of its own published modules. The gate does not clone,
compile, or name a consumer repository. Binary compatibility, Scala 3 TASTy compatibility, and
source compatibility are related but distinct; a green result in one court is not reported as
proof of the others.

## Version policy

Intaglio uses early semantic versioning before 1.0:

- `0.y.0` may change public APIs. A breaking change must be intentional, documented in the
  changelog and migration guide, and accompanied by a newly reviewed baseline.
- `0.y.z` patch releases preserve backward JVM binary and Scala 3 TASTy compatibility within that
  `0.y` line. Source compatibility is required for documented APIs and checked with
  sbt-version-policy's forward-MiMa approximation plus compiled public examples.
- Deprecation is preferred within a line. A deprecated public member is removed only at a permitted
  breaking boundary.

Starting with 1.0:

- patch releases preserve backward binary, TASTy, and source compatibility;
- minor releases preserve backward binary and TASTy compatibility, while source incompatibilities
  require an explicit migration note; and
- major releases may break compatibility, with a migration guide and a newly reviewed baseline.

The current inventory deliberately retains `AesEnv` as a source alias for `AesSpec` and
`SceneConformance` as a deprecated facade for `RendererConformance`. They are explicit compatibility
surface, not accidental extraction types. The baseline freezes them until a policy-permitted removal.

## Executable gate

`compatibility/baseline.conf` names one full Git SHA and a local baseline version. The SHA is part of
this repository's history. `tools/check-compatibility.sh`:

1. validates the pinned commit;
2. exports that commit into a temporary directory and names its version there, because an export
   has no Git history for sbt-dynver to read;
3. publishes every Intaglio module at the baseline version into an isolated local Ivy repository;
4. compares every JVM module with that artifact using sbt-version-policy/MiMa, including signature
   problems, building the working tree as the patch release that follows the baseline version --- a
   version sbt-version-policy derives its expectations from, so it is computed from `baseline.conf`
   rather than fixed in the script; and
5. runs TASTy-MiMa sequentially over every JVM and Scala.js artifact under an explicit memory
   budget.

The temporary checkout, caches, and local artifacts are removed when the command exits. The ordinary
CI workflow runs this command after tests. TASTy-MiMa `InternalError` filters are narrowly scoped to
`PackedStatPlan.Aux` and `StatResult.Aux`, whose refinement aliases trigger the tool's
`Unexpected local ref` parser limitation; to eight Java2D API symbols whose `java.awt` types trigger
package-resolution failures; and to the JavaFX context constructor, whose `javafx` type triggers the
same limitation. Each occurs when comparing identical artifacts. No compatibility problem kind is
suppressed; any other report must be fixed or accompanied by a reviewed policy decision.

Run the same court locally with:

```sh
tools/check-compatibility.sh
```

MiMa checks JVM class-file compatibility; it is intentionally skipped for Scala.js artifacts. Shared
Scala.js source is covered by its JVM twin, while the JavaScript-only Canvas module receives the
Scala 3 TASTy court. sbt-version-policy uses forward MiMa as an approximation for source
compatibility, and TASTy-MiMa checks retyping compatibility for all Scala 3 artifacts. These tools
cannot prove behavioral compatibility; Intaglio's law, conformance, differential, fuzz, and golden
suites cover that separate contract.

### The baseline is compiler-bound

The baseline is built from source, not downloaded, so both sides of the comparison are compiled ---
and neither court is compiler-neutral. Comparing an artifact built with one Scala version against a
working tree built with another produces two kinds of noise that are not API changes: MiMa reports
`IncompatibleSignatureProblem` wherever the two compilers emit different generic signatures (in
Intaglio, `PlotBuilder.encodePositionX` and `encodePositionY` do this between 3.4.2 and 3.3.8), and
sbt-version-policy's dependency check reports `scala3-library_3` itself as an incompatible version
change.

Changing `ThisBuild / scalaVersion` therefore obliges a baseline move, whether or not the public API
moved with it. Treat the compiler change as part of the same breaking boundary, and pin a
replacement commit that already carries the new default.

## Moving the baseline

Do not move the baseline to silence a failure. It may change only when the version policy permits a
breaking boundary. First review the public diff and migration text, run `scalafmtCheckAll testAll`
at the replacement commit, then update both fields in `baseline.conf` in a later commit. Once a
release is available from Central, release CI should compare against that immutable published version
and run `versionCheck` before publication; the repository baseline remains the bootstrap court for
clean, unpublished development.
