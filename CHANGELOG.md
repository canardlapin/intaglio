# Changelog

Intaglio follows [early SemVer](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html):
before 1.0 a `0.y.0` release may break the public API, and a `0.y.z` release may
not. Every breaking change is named here and, when a call site has to move, in
[MIGRATION.md](MIGRATION.md). What "breaking" means in each of the three courts
— binary, TASTy, and source — is defined in [docs/compatibility.md](docs/compatibility.md).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Breaking

- `GraphicParams`, `TextStyle`, `LayoutPolicy` and `RenderRequirement.TextStyle`
  each gained a trailing defaulted field for typographic weight, changing their
  `apply`/`copy`/`unapply` descriptors. `PdfFont.fromBytes` gained a trailing
  `weight`, `PdfFontCatalog` indexes `(family, weight)`, and
  `PdfRenderError.DuplicateFontFamily` was removed because it can no longer be
  produced. See [MIGRATION.md](MIGRATION.md).

- `LineType` gained the parameterised case `Custom(DashPattern)`, so Scala 3
  emits its synthetic `values`/`valueOf` differently and four symbols move off
  `class intaglio.LineType`. Source is unaffected — `LineType.values` still
  compiles and means the same — but already-compiled callers must be recompiled.
  This is why the change lands at a `0.y.0` boundary rather than a patch. See
  [MIGRATION.md](MIGRATION.md).

### Added

- **Typographic weight, measured as well as drawn.** `GraphicParams.fontWeight`
  carries a checked `FontWeight` on the 100-to-900 scale, so a figure can
  establish hierarchy without changing size or family. Resolves the weight half
  of #3.

  Weight changes glyph advance, so it is threaded through measurement first:
  `TextStyle` carries it, `LayoutPolicy` carries one per text role beside the
  family it already had, and `Theme.layoutPolicy` feeds them. `Java2DTextMetrics`
  and `CanvasTextMetrics` now build the same font for measuring that their
  renderers build for drawing — one rule each, in one place, rather than the two
  independent copies that existed before.

  SVG emits `font-weight`; Canvas puts it in the CSS shorthand; Java2D applies
  `TextAttribute.WEIGHT`; JavaFX resolves through `FontWeight.findByWeight`. PDF
  is different in kind — it embeds font programs and cannot synthesize a face —
  so `PdfFontCatalog` now indexes `(family, weight)` and a run asking for a
  weight no registered face carries is a typed `MissingFontWeight` rather than a
  document that silently reads as regular. A new `bold-text` conformance case
  makes every backend prove it, so the channel cannot be accepted and discarded.

  Letter-spacing, the other half of #3, is not included: it has no route on
  JavaFX at all, and a channel that affects layout should not be a documented
  no-op in one backend.

- **Arbitrary dash rhythms.** `LineType` gained `Custom(DashPattern)` beside
  `Solid`, `Dashed` and `Dotted`, so a plot that encodes categorical state in
  dash rhythm is no longer limited to the two the library happened to name.
  `DashPattern` holds alternating on and off lengths in device pixels and
  refuses a rhythm no backend could draw: empty, longer than
  `DashPattern.MaximumSegments`, non-finite, negative, or all zero. The last is
  not pedantry — `java.awt.BasicStroke` throws on an all-zero dash array, so an
  unchecked value would render on three backends and fail on the fourth.

  `LineType.dash` resolves any line type to an `Option[DashPattern]`, and all
  five backends now go through it. Before, each of SVG, Canvas, Java2D, JavaFX
  and PDF carried its own copy of `6 4` and `1 3`; the two named rhythms now
  have one definition. Their rendered output is unchanged. The renderer
  conformance contract gained a `custom-dash` case, so every backend proves it
  honours a five-segment rhythm rather than only the two it used to receive.

  Resolves #2.

### Fixed

- **A faceted plot names each dimension once.** The axis title was lowered as
  part of every rendered axis, so "how many axes are drawn" silently decided
  "how many titles are drawn". A 3x2 `facetGrid` drew the y title three times
  down the left edge; under `FacetScales.FreeY`, where every panel gets an axis,
  it drew it six times. Supplying titles as explicit guides did not help — the
  title travelled with each lowered axis regardless of guide policy — so a
  consumer's only escapes were dropping axis titles or drawing them outside the
  scene, which breaks SVG and PDF self-containment.

  The title is now drawn once per position dimension, centred on the whole panel
  block in the outer strip `PlotLayoutSolver` already reserves, under every
  `FacetScales` policy and for both `facetGrid` and `facetWrap`. Ticks and tick
  labels stay panel-local, because only they say something panel-specific. The
  title is named `<base>-title` — `y-axis-title`, the same name an unfaceted
  plot uses — and is emitted as a label grob rather than a guide, so the axis
  guide count still counts drawn axes.

  Under free scales this also returns space: the inter-panel gap reserved a
  title band for a title that will no longer be drawn there, so a free-scale
  grid now gives that width back to the panels. `PlotFrames.axisViewport(side)`
  exposes the block-spanning outer strip that the single title occupies.
  Unfaceted plots are unchanged.

### Changed

- **The default theme palette is now colour-vision-measured.**
  `Theme.defaultPalettes.discrete` is the first six of
  `DiscretePalette.okabeItoColors` instead of the first six tab10 colours.
  **Every plot that does not name its own colours changes colour.** The reason
  is measured rather than aesthetic: from three series on, protanopia brought
  tab10's orange and its green to CIE76 5.6 and deuteranopia brought its blue
  and its purple to 7.2, both below the floor the new
  `AccessibilityDiagnostic.IndistinguishablePalette` reports at, while the
  replacement holds 17.0 through six series.
  `DiscretePaletteEvidenceSuite` pins both palettes so the comparison stays a
  fact.

  Three of the ten committed gallery plates moved and were reviewed at native
  size; the java2d golden images did not, because those fixtures name their
  colours explicitly. To restore the previous look in one line:

  ```scala
  Theme.default.copy(
    palettes = Theme.default.palettes.copy(
      discrete = Vector(
        Rgba.unsafe(31, 119, 180),
        Rgba.unsafe(255, 127, 14),
        Rgba.unsafe(44, 160, 44),
        Rgba.unsafe(214, 39, 40),
        Rgba.unsafe(148, 103, 189),
        Rgba.unsafe(140, 86, 75)
      )
    )
  )
  ```

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

- **A diverging palette primitive with a neutral at zero.** `DivergingPalette`
  takes `negative`, `neutral`, and `positive` colours and answers on a signed,
  symmetric domain: `-1` is the negative endpoint, `0` is exactly the neutral,
  `+1` is the positive endpoint. The sign of the argument names the arm, so two
  call sites in one application cannot silently adopt opposite conventions.
  `pixel` is `color` packed through `Rgba32.fromRgba`, so a raster overlay and a
  scene mark showing the same value are the same colour by construction rather
  than by convention, and `unitPalette` exposes the same ramp on `[0, 1]` for
  wiring into a continuous colour scale with the neutral exactly at `0.5`. The
  constructor returns `Either` and refuses a palette whose sign could not be
  read back — identical endpoints, or an arm with no gradient — as
  `GraphicsError.DegenerateDivergingPalette`.

  `DivergingColorizer` is the raster face and `ContinuousScale.diverging` the
  scene face, and both exist so the library rather than the caller owns the
  midpoint. The colorizer carries a magnitude rather than a window, so zero is
  on the neutral by construction; re-windowing it takes the enclosing symmetric
  window instead of moving the middle, and a non-finite value takes an explicit
  invalid pixel rather than rendering as a value of exactly zero. The scale
  builds the domain `[-limit, +limit]` with `ScaleTraining.Fixed`, so a later
  plot-wide training pass cannot widen one side and slide the neutral onto the
  data's midpoint.

  `docs/adr/0008-signed-color-is-a-primitive.md` records the reasoning, what was
  rejected, and what is deferred. The additive API review in
  `compatibility/interaction-additions.txt` gained a third supported problem
  kind along the way: a Scala 3 `enum` case compiles to a class, a companion,
  and a static field, and the review had no way to name the field.

- **Oklab, and interpolation through it.** `Oklab` is a checked perceptual
  colour type with `fromRgba`, `toRgba`, and `mix`. The round trip is byte-exact
  for every one of the 16 777 216 sRGB colours, encoding clamps an out-of-gamut
  coordinate into gamut rather than failing, and both endpoints of a mix are
  returned identically rather than recomputed, so a ramp reaches its own
  endpoints exactly. `Palette.oklabGradient(from, to)` is the sequential ramp
  built on it, and `DivergingPalette` interpolates each arm outward from the
  neutral in the same space. `Palette.gradient` still interpolates the stored
  sRGB bytes and is unchanged; between hue-distant endpoints that path collapses
  to grey in the middle — `#0000FF` to `#FFFF00` passes through `#808080` — and
  the doc comment now says so.

- **Named palettes chosen against a colour-vision measurement.**
  `DivergingPalette.BlueRust` keeps its two arms at least CIE76 17.9 apart at
  matched magnitude under normal vision, protanopia, and deuteranopia, and never
  reverses lightness outward along either arm. `DiscretePalette.okabeIto` is the
  eight-colour qualitative palette of Okabe and Ito, reordered so that every
  prefix is the best set of its size, with a published separation floor per
  prefix length. `DiscretePalette.okabeItoColors` is the same order as a
  `Vector[Rgba]` for `ThemePalettes`. Neither replaces a default: the default
  theme palette is unchanged, so no existing plot changes colour.

  `DivergingPaletteEvidenceSuite` and `DiscretePaletteEvidenceSuite` assert
  every published figure on each run, through the same public API a consumer
  would use. `docs/accessibility.md` covers choosing sequential against
  diverging and why no rainbow or jet ramp is shipped;
  `evidence/diverging-palette/README.md` records the method and the boundary.

- **Colour vision simulation, and a diagnostic that uses it.** `ColorVision`
  simulates protanopia, deuteranopia, and tritanopia with the two-half-plane
  construction of Brettel, Vienot and Mollon (1997); `ColorSeparation` reports
  CIE76 distance in CIELAB as a named observer sees it, finds the closest pair
  in a set, and exposes CIE L* and C*. `ColorVisionSuite` pins the simulated
  sRGB primaries and secondaries against the published reference values of
  libDaltonLens, and checks that every grey is a fixed point and that each
  separation plane contains the neutral axis. Only dichromacy is modelled;
  anomalous trichromacy, which is more common, is not, and the documentation
  states that wherever it states a figure.

  `AccessibilityDiagnostic.IndistinguishablePalette` is reported for a discrete
  colour or fill scale whose closest pair of levels falls below
  `ColorSeparation.SeriesFloor` for some observer, naming the observer, the two
  levels, and the separation. Exact RGBA collision keeps its own diagnostic.
  Continuous ramps are not checked: neighbouring samples there are supposed to
  be close.


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
