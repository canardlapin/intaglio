# Changelog

Intaglio follows [early SemVer](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html):
before 1.0 a `0.y.0` release may break the public API, and a `0.y.z` release may
not. Every breaking change is named here and, when a call site has to move, in
[MIGRATION.md](MIGRATION.md). What "breaking" means in each of the three courts
— binary, TASTy, and source — is defined in [docs/compatibility.md](docs/compatibility.md).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Added

- **The compile pipeline splits at the data/device seam.** `PlotCompiler.train`
  runs the data-dependent phases — mapping, statistics, scale training, row
  resolution, layer geometry, and guide specification — once, and returns a
  reusable `TrainedPlotData`; `PlotCompiler.place` adds the device-dependent
  remainder (layout solve and lowering) for one `RenderContext`; `resolve` is
  exactly their composition, and `TrainPlaceSuite` asserts scene equality. A
  pure resize therefore re-runs no statistical work: five placements of an
  unchanged 30-panel trellis cost ~4 ms against ~800 ms for five full
  recompiles on the receipt hardware.

- **Opt-in, caller-owned memoization of compiles.** `PlotCompileCache.bounded()`
  retains trained data by plot-and-options reference identity and placements by
  context value, with explicit LRU capacities and a `PlotCacheProfile` that
  reports hits, misses, and evictions. Pass it to `PlotCompiler.resolve`,
  `train`, `place`, or `PlotProgram.resolve`; `PlotCompileCache.Disabled` is
  the default everywhere and is exactly the uncached path. Errors are never
  cached, and nothing is cached behind your back.

- **A non-gating measured-time receipt.** `performance/timings/v1.tsv` records
  elapsed time, allocation, and per-phase medians for interactive-rate
  workloads — faceted trellises, resize sweeps, dense picking — on named
  hardware, produced by a timing harness in the performance module and
  summarized in `docs/limits.md`. Wall-clock time remains outside CI gates.

- **Per-grob titles, descriptions, classes, and data attributes.**
  `Grob.annotated(child, meta)` attaches a `GrobMeta` — an optional `title`, an
  optional `description`, an optional `CssClass`, and an insertion-ordered
  vector of `DataKey`/value pairs — to any grob. The SVG backend emits it as a
  wrapping `<g class="…" data-…="…">` whose first children are `<title>` and
  `<desc>`, so a static document carries hover text without script and a host
  stylesheet can address a mark. Canvas, Java2D, JavaFX, and PDF draw the child
  exactly as if the wrapper were absent.

  `CssClass` accepts whitespace-separated ASCII identifier tokens and
  normalises them to single-space separation; `DataKey` accepts
  `[a-z][a-z0-9-]*` and refuses the suffix `name`, because `data-name` is every
  backend's channel for a grob's `GraphicsName`. Annotation text is validated at
  the SVG boundary for XML-legal code points only, and escaped on output — a
  caller feeding human text (a transcript, a pasted note) should strip control
  characters itself rather than rely on that check to sanitise its data.

- **`PointShape.Diamond`.** A square rotated 45 degrees whose area equals the
  `Circle` drawn at the same size (half-diagonal `r * sqrt(pi / 2)`), so a
  size-by-value encoding reads as the same quantity of ink across the two
  shapes. It therefore extends past the `[-r, r]` box the other shapes keep;
  `PointShapeLaws` pins both facts. `PointShape.diamondHalfDiagonal` is the
  single source of the constant.

- **Rounded rectangles.** `Grob.rect(..., cornerRadius: ExtentExpr)` resolves
  the radius the way a circle radius resolves — the smaller of the two axis
  resolutions — and lowering clamps it to half the shorter resolved side. That
  is the SVG `rx`/`ry` rule, applied once in `DeviceScene`, so SVG, Canvas,
  Java2D, JavaFX, and PDF all round the same corner. A negative or non-finite
  radius is unrepresentable, because `ExtentExpr` refuses one.

- **Step interpolation for lines.** `Grob.lines(..., interpolation:
  LineInterpolation)` adds `StepAfter` and `StepBefore` beside the default
  `Linear`. Lowering expands a step line into exactly the corner points an
  author would have written, so a step track is one grob with one name and is
  byte-identical to its explicit form in every backend.
  `LineInterpolation#transposed` exchanges the two step forms, and
  `CoordinateTransform` applies it when it flips a scene.

- **Executable documentation.** Every fenced block marked `mdoc` under `docs/`
  is compiled against the real modules by `tools/check-docs.sh`, and the
  gallery renders its own plates into `docs/gallery/*.svg`, which are checked
  in so an unintended rendering change appears as a diff. Architecture decision
  records live in `docs/adr/`, extension-author guides in `docs/extending/`,
  and the task-oriented path in `docs/tutorial/`.

- **Release automation.** `sbt-ci-release` publishes signed artifacts to the
  Sonatype Central Portal from `.github/workflows/release.yml`, which runs only
  on a `v*` tag or a manual dispatch. `tools/release-rehearsal.sh` rehearses a
  release on a clean clone in an isolated home: it publishes every module to a
  throwaway local repository and then checks the generated POM closure for
  required metadata, absent SNAPSHOT dependencies, and third-party
  resolvability, without signing or uploading anything. See
  [docs/releasing.md](docs/releasing.md).

- **A supported-version matrix in CI.** Every module is tested on Scala 3.3.8
  and 3.9.0, on JDK 17 and 21, for the JVM and for Scala.js.

### Changed

- **Shared-scale facets compile in one resolution pass.** With
  `FacetScales.Shared` (the default), each panel's rows are now resolved once
  against the globally trained plans instead of once globally plus once per
  panel, and per-panel position training, range scans, and guide specification
  are computed once and shared. A 30-panel grid compiles ~1.9× faster and a
  half-empty grid ~2.2× faster, proportional to its occupied cells; rendered
  output is unchanged, verified panel by panel against the general path, which
  free-scale facets still use.

- **Picking is spatially indexed.** `Picking.compile` builds a uniform grid
  over target bounds, so `hits` and `nearest` queries track local density
  instead of total mark count (~24 µs against ~6 ms per query on 20,000
  marks), with results identical to the former exhaustive scan.

- **The default Scala version is now the LTS line, 3.3.8** (from 3.4.2), and
  `crossScalaVersions` adds the current feature release, 3.9.0, as a CI court.
  TASTy is forward- but not backward-compatible, so an LTS-built artifact can
  be read by any later 3.x consumer while a feature-release build would lock
  out every earlier compiler. Every Scala 3 artifact carries the same `_3`
  suffix, so a release publishes the default version alone.

- **`build.sbt` no longer sets `version`.** sbt-dynver derives it from the git
  state, so a tag is the only thing that names a release.

### Breaking

- `Grob.rect` and `Grob.rectUnsafe` take `cornerRadius` between `anchor` and
  `gp`; `Grob.lines` takes `interpolation` between `points` and `gp`. A call
  that passed `gp` positionally must name it. See
  [MIGRATION.md](MIGRATION.md).

- `Grob.Rect`, `Grob.Lines`, and `DevicePrimitive.RectShape` each gained a
  field, and `Grob` and `DeviceElement` each gained a case (`Annotated`). A
  backend or a consumer that matches exhaustively on `DeviceElement`, or that
  destructures `DevicePrimitive.RectShape`, must be updated. This is a binary
  and TASTy break as well as a source break.

- `PointShape` gained `Diamond`. An exhaustive match on `PointShape` — every
  backend has one — must handle it.

## 0.1.0

Unreleased. Intaglio has not been published to Maven Central; the version
recorded in `compatibility/baseline.conf` is a local pre-release API baseline
for the compatibility gate, not a published artifact.
