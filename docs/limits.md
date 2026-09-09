# Performance and limits

Intaglio's CI does not fail on elapsed time. What CI gates is a receipt: a set of deterministic
work and output-cardinality measurements that are reproduced identically on the JVM and Scala.js,
and a small set of hard constructor limits. Alongside that, the repository publishes a second,
non-gating receipt of measured elapsed time and allocation on named hardware
([`performance/timings/v1.tsv`](../performance/timings/v1.tsv), summarized
[below](#measured-time-the-non-gating-receipt)), so capacity planning no longer starts from zero.
This page says exactly what is measured, what is bounded, and what is not bounded at all.

## Why not wall-clock

From [`performance/README.md`](../performance/README.md):

> This module runs representative Intaglio workloads on both the JVM and Scala.js. It deliberately
> does not fail CI on elapsed time: shared runners, JIT warm-up, garbage collection, and
> hosted-runner contention make wall-clock thresholds noisy. Instead, the gates measure stable work
> and output cardinality that tracks the severe regressions this repository needs to stop

and, on refreshing a baseline:

> Use a profiler or a proper benchmark runner for exploratory wall-clock work; do not convert timing
> observations into hosted-CI pass/fail assertions.

Take that at face value when planning your own capacity work. The
[measured-time receipt](#measured-time-the-non-gating-receipt) tells you what one named machine
observed; it does not tell you how many marks per second *your* machine will draw.

## The receipt

`performance/baselines/v1.tsv`, verbatim:

```text
# Intaglio deterministic performance receipt
# schema_version=1
# source_sha=5cfaceecb60bbbacb96f18d900c76f7b5bc19428
# recorded_on=2026-08-30
workload	metric	recorded	high_severity_limit	rationale
scatter	retained_rows	0	0	Lean compilation must not retain one resolved row per mark
scatter	grobs	1	1	Large point layers must remain one columnar grob
scatter	device_primitives	1	1	Device lowering must preserve one point batch
scatter	batch_coordinates	20000	20000	The batch carries exactly one coordinate per source mark
raster	packed_bytes	262144	262144	Packed rasters retain one four-byte pixel word
raster	device_primitives	1	1	One raster grob lowers to one device image
raster	svg_bytes	350260	437825	A 25 percent ceiling guards deterministic PNG and base64 growth
dodge	output_rows	10000	10000	Dodge must not duplicate adjusted rows
dodge	grobs	10000	10000	Dodge lowering remains one rectangle per input row
stack	output_rows	10000	10000	Stack must not duplicate adjusted rows
stack	grobs	10000	10000	Stack lowering remains one rectangle per input row
discrete_lookup	identity_calls	9216	9216	Every indexed lookup derives its stable identity exactly once
histogram	compiled_bins	256	256	Large generated histograms retain one output per requested bin
histogram	slow_path_penalty	0	0	Generated bins use arithmetic lookup and explicit breaks use binary search
svg	mark_elements	10000	10000	Point batches serialize exactly one SVG element per mark
svg	bytes	1336945	1671182	A 25 percent ceiling guards large-scatter serialization growth
```

`recorded` is the value observed at the named source SHA. `high_severity_limit` is the reviewed CI
ceiling. Every row where the two are equal is asserted for **exact** equality; the two byte counts
allow 25 percent growth so a formatting change does not masquerade as a performance failure.

The shared workload and baseline definitions live in
`modules/performance/shared/src/test/scala/intaglio/performance/`, and a JVM test parses this TSV and
asserts full structural equality — header, order, values, and rationale strings — against those
definitions. The receipt and the code cannot drift.

## What each workload actually does

| Workload | Input | Compiled as |
|---|---|---|
| `scatter` | 20,000 rows, `Geom.Point` | `PlotCompilerOptions.lean`, lowered to a `DeviceContext` of 800 × 600 |
| `raster` | one 256 × 256 checkerboard `RasterImage`, nearest interpolation | one image grob, rendered to SVG at 512 × 512 |
| `dodge` / `stack` | 10,000 rows = 500 categories × 20 groups, mixed signs, `Geom.Bar` | `PlotCompilerOptions.rich` with `Position.Dodge()` / `Position.Stack()` |
| `discrete_lookup` | an 8,192-level ordered `DiscreteDomain` | 8,192 hits plus 1,024 misses through `indexOf` |
| `histogram` | 50,000 samples, 256 bins | `ProvenancePolicy.CountOnly` |
| `svg` | 10,000 marks in one hand-built `Grob.pointBatch`, 200 × 50 | serialized at 800 × 600 |

Two of those metrics are strategy assertions rather than counts. `histogram/slow_path_penalty` is
`0` only when generated bins resolve through arithmetic lookup *and* explicit breaks resolve through
binary search; any other combination scores `1`. `discrete_lookup/identity_calls` counts identity
derivations after the domain has been built, so `9216` is exactly one derivation per `indexOf` —
proof that lookup does not re-derive per level.

`svg/mark_elements = 10000` is the load-bearing one for expectations: **batching does not reduce
output cardinality.** The SVG backend unrolls a point batch into one `<circle>` per mark. The saving
is in the compiler, the scene, and the device IR, not in the document.

## Measured time: the non-gating receipt

The deterministic receipt above records work and cardinality, not time. A second receipt,
[`performance/timings/v1.tsv`](../performance/timings/v1.tsv), records elapsed time and allocation
for interactive-rate workloads — many-panel trellises, repeated device-size changes over unchanged
data, and dense hover picking. It is produced manually by
`sbt "performanceJVM/Test/runMain intaglio.performance.TimingHarness"` on named hardware
(currently an Apple M3 Max, JVM 25), reports run counts with min/median/max rather than a single
run, and is deliberately **not** a CI gate, for exactly the reasons quoted above. Treat the numbers
as one machine's honest observation, not a promise.

What the receipt (2026-09-08, 12 measured runs after 4 warm-ups) establishes:

- **A 30-panel faceted compile** (6 × 5 grid, 20 ribbon+line series per panel, 24,000 rows, shared
  scales, lean provenance) takes ~169 ms median and allocates ~359 MB. The first recording measured
  ~287 ms and ~688 MB; the difference is the shared-scale fast path, which resolves each panel's
  rows exactly once instead of once globally plus once per panel, and computes the scale registry
  and axis specs once instead of per panel (`FacetScales.Shared` makes the per-panel merge a no-op,
  so the general per-panel path — still used for free scales — is provably redundant there;
  `FacetSharedScalesSuite` verifies the two paths panel by panel against each other). Free-scale
  facets are unchanged at ~307 ms because they genuinely need per-panel training.
- **A resize through `resolve` recompiles everything; through `train`/`place` it recompiles almost
  nothing.** Re-resolving that unchanged plot at five successive `RenderContext` sizes costs
  ~1.2 s and ~3.4 GB of allocation — ~240 ms and ~690 MB per resize in which not one datum
  changed. Training once with `PlotCompiler.train` and placing the trained value at the same five
  sizes (`resize-place`) costs ~4 ms and ~1.1 MB total — roughly 1 ms per resize, over two orders
  of magnitude faster — and the phase clock records no mapping, statistics, scale training, or row
  resolution during placement. The scenes are identical to full recompiles (`TrainPlaceSuite`
  asserts equality). A caller who cannot restructure onto `train`/`place` can pass a
  `PlotCompileCache` to plain `resolve`: the same five-size sweep with a warm cache
  (`resize-cached`) costs ~0.07 ms and ~0.2 MB, because every resolve is a lookup and the phase
  clock records no compile phases at all.
- **Hover picking is indexed and tracks local density, not mark count.** On a 20,000-mark faceted
  plot, one `hits` query costs ~24 µs median and one `nearest` query ~45 µs: `Picking.compile`
  builds a uniform spatial grid over target bounds (raising plan compile from ~21 ms to ~53 ms,
  paid once) and queries evaluate exact geometry only on the grid's candidate set, with results
  identical to a full scan. Before the index the same queries cost ~6.4 ms and ~5.7 ms each with
  ~9.5 MB allocated per query. A 150 × 300 px rectangle `select` over dense data still costs
  ~42 ms (down from ~460 ms) because it genuinely intersects hundreds of targets.
- **Rich provenance is a retention cost, not (here) a time cost.** The same trellis compiled rich
  retains 48,000 resolved rows (one per datum per layer) against lean's zero, at statistically
  indistinguishable elapsed time for this line/ribbon workload. The DSL's `PlotBuilder.resolve`
  defaults to rich; `PlotCompiler.compile` defaults to lean (see [The batch IR](#the-batch-ir)).
  A consumer who reaches a faceted plot through the DSL therefore pays the retention without
  asking; the receipt's `facet-grid-30-rich` row is where that cost is visible, and
  `ProvenancePolicy.None` in the compiler options is the one-line escape.
- **Empty grid cells now cost what their data costs.** The same grid with half its cells empty
  (12,000 rows, 15 occupied of 30 panels) costs ~79 ms and ~180 MB — about half the dense grid,
  i.e. proportional to the occupied cells, where it previously cost ~171 ms and ~341 MB because
  every declared cell paid per-panel scale training, a per-panel range scan, and its own guide
  specification. Empty cells still render a panel and a strip; they no longer buy compile work.

The resize and picking figures are the after-numbers for the interactive-rate work tracked in the
issue log (the before-numbers are quoted inline); the per-phase split exists precisely so the
data/device pipeline split can prove that a resize does no statistical work.

## The batch IR

The batch IR exists to stop a large point layer from allocating one grob, one device primitive, and
one retained row per mark. Two types implement it: `Grob.PointBatch` in the scene and
`DevicePrimitive.PointBatch` after lowering. Both store positions as a `Vector` and style as
`BatchColumn`, which is either one `Constant` or one `Values` entry per mark — so a uniform style
column collapses to a single value, and a varying one is still explicit about its cardinality.

There is **no row-count threshold.** Batching triggers on three conditions: the geom is exactly
`Geom.Point`, the lowering is `StatLowering.Geom`, and the compiler's `provenance` is anything other
than `ProvenancePolicy.Full`. A one-row point layer batches under `lean`; a 20,000-row point layer
does not batch under `rich`.

```scala mdoc:silent
import intaglio.*

final case class Mark(x: Double, y: Double)

val marks = Vector.tabulate(2000)(i => Mark(i.toDouble, (i % 97).toDouble))

val leanPlot =
  plot(marks).aes(_.x, _.y).geomPoint().compilerOptions(PlotCompilerOptions.lean).resolve

val richPlot =
  plot(marks).aes(_.x, _.y).geomPoint().compilerOptions(PlotCompilerOptions.rich).resolve
```

```scala mdoc
(leanPlot.map(_.layers.head.grobs.length), richPlot.map(_.layers.head.grobs.length))
```

```scala mdoc
(leanPlot.map(_.layers.head.rows.length), richPlot.map(_.layers.head.rows.length))
```

What batching costs you, exactly:

- **Per-mark grob identity.** A batched layer is one grob with one optional `GraphicsName` for the
  whole batch. Anything that addresses or wraps an individual mark — `Grob.annotated(child, meta)`
  per datum, for instance — is not available.
- **Per-row inspection.** Because the trigger is the provenance policy, `rows`, `statFrame.rows`, and
  `droppedRows` are emptied in the same step.

What it preserves: exact geometry and style. The batch's points and its per-index radii, shapes, and
`GraphicParams` equal what the unbatched path would have produced. Accessibility survives too — the
same `SceneSemantics` travels through, and datum IDs are a `DatumIdSeries` whose `valueAt(i)` returns
`<layer-id>-datum-<i>` on demand rather than one retained `String` per mark.

`ProvenancePolicy` is a five-point scale — `None`, `CountOnly`, `Representative`, `SourceIndices`,
`Full` — and each case publishes a `ProvenanceRetentionCost` naming its asymptotic growth. All four
non-`Full` policies batch point marks. Note the asymmetric defaults in the compiler:
`PlotCompiler.compile` defaults to `lean`, `PlotCompiler.resolve` defaults to `rich`.

## Hard limits

These are enforced at construction or at a render boundary, and each produces a typed error.

| Limit | Value | Where | Failure |
|---|---|---|---|
| Pattern tile axis | spacing ≤ **1024** device pixels | raster pattern lowering (`PatternTile.MaxAxisPixels`, internal) | `GraphicsError.InvalidPatternParameter` — raster backends only; SVG and PDF emit vector patterns and do not apply it |
| Break output size | ≤ **10,000** values | `Breaks.MaximumOutputSize` | `GraphicsError.BreakOutputLimitExceeded` |
| Break iteration | a deterministic internal cap | `Breaks` | `GraphicsError.BreakIterationLimitExceeded` |
| Device coordinate magnitude | \|value\| ≤ **1.0e13**, finite | `DeviceScene` lowering | `GraphicsError.InvalidDeviceValue` |
| Calendar year | **−9999** to **9999** | `CalendarDate` | checked constructor |
| PDF page side | ≤ **14400** points (200 inches) | `PdfRenderer` | `PdfRenderError.InvalidPageSize` |
| Raster dimensions | the PNG-encoded size must fit in an `Int` | `RasterDimensions` | `GraphicsError.InvalidRasterDimensions` |
| Discrete palette capacity | the palette's own length | `DiscretePalette.validateDomain` | `GraphicsError.DiscretePaletteOverflow` under `PaletteOverflowPolicy.Reject` |
| Dash pattern segments | **32**, at least one above zero | `DashPattern` | `GraphicsError.InvalidDashPattern` |

The raster limit is a formula rather than a fixed pixel count. `RasterDimensions` computes
`scanlineBytes = pixels * 4 + height`, adds five bytes per 65,535-byte stored deflate block plus a
constant, and rejects anything whose encoded size would exceed `Int.MaxValue`. The block framing
comes from the PNG encoder, so the constructor rejects exactly the images the encoder could not
produce.

The dash limit has a reason beyond tidiness: `java.awt.BasicStroke` throws on an all-zero dash
array, so a pattern that three backends would draw and Java2D would reject is refused at
construction instead. Dash segments are device pixels, which is what `Dashed` and `Dotted` have
always meant — a stroke measured in points scales with the device while its dash does not.

The palette limit has one number most people meet: the default theme's discrete palette holds
**six** colours, and the default overflow policy is `Reject`. A seventh level is a typed error, not a
reused colour. `PaletteOverflowPolicy.Cycle` wraps instead.

`DiscretePalette.okabeIto` holds **eight** and also rejects. Its capacity is not the interesting
number: every prefix clears the CIE76 floor of 10 that `ColorSeparation.SeriesFloor` names, but the
seventh and eighth clear it by about a unit rather than by a margin, so past about **six** series
the honest move is faceting or direct labelling rather than a longer palette. The default theme
palette falls below that floor from **three** series on, and the compiler now says so through
`AccessibilityDiagnostic.IndistinguishablePalette`.
[Accessible plots](accessibility.md#colour-vision-deficiency) has the per-prefix figures.

## What is not limited

Stated plainly, because absence is easy to mistake for a promise:

- **There is no cap on scene element count.** `SvgRenderError` has four cases —
  `InvalidDocumentSize`, `InvalidXmlCharacter`, `DuplicateDataKey`, and `Graphics` — and none of them
  is an element-count or byte-size guard. The `svg/mark_elements` and `svg/bytes` rows above are CI
  receipt ceilings, not runtime limits; the renderer will emit a larger document.
- **There is no documented maximum row count** and no "does not scale beyond N" statement anywhere in
  the repository.
- **There is no streaming render path.** A `Scene` is a fully materialized immutable tree, and
  `DeviceScene.fromScene` walks all of it. There *is* an incremental compile path: the pipeline is
  split at the data/device seam, so `PlotCompiler.train` runs the data-dependent phases once and
  `PlotCompiler.place` re-runs only layout and lowering per `RenderContext`.
  `resolve(plot, context, options)` is exactly the composition of the two, and the resize saving is
  quantified in [Measured time](#measured-time-the-non-gating-receipt).
- **Caching of compiled plots is opt-in and caller-owned.** Compilation is pure, so the same inputs
  give the same output; `PlotCompileCache.bounded()` memoizes that purity when you pass it to
  `PlotCompiler.resolve`/`train`/`place` or `PlotProgram.resolve`. Keys are plot and options
  *reference* identity (plot values carry user functions, so structural equality is undecidable)
  plus context value; `profile` reports hits, misses, and evictions so a cache that never hits is
  visible; `PlotCompileCache.Disabled` is the default everywhere and is exactly the uncached path.
  Nothing is ever cached behind your back, and a cache you never construct costs nothing.

If you are plotting enough marks that any of these matter, the honest advice is: measure it on your
own target with a real benchmark runner, use `PlotCompilerOptions.lean`, and prefer point layers so
the batch IR applies.

## The deterministic fuzz court

`FuzzRegressionSuite` lives in core's shared test sources, so it runs on both the JVM and Scala.js
under `testAll`. It has no wall-clock and no platform randomness.

- **256 fixed seeds**, generated in closed form from a SplitMix64 constant — the same 256 on every
  platform and every run.
- **Five categories**: `scene`, `mapping`, `transform`, `breaks`, `layout`. Each drives public
  construction and callback boundaries with a mixture of ordinary values and the ten special doubles
  (`NaN`, both infinities, `±Double.MaxValue`, `±0.0`, `Double.MIN_VALUE`, `±1.0`).
- **The property is that nothing leaks.** Every case runs inside a wrapper that fails the suite if a
  non-fatal exception escapes, so the assertion is that a checked boundary returns a typed error
  rather than throwing. Break generation is additionally asserted against
  `Breaks.MaximumOutputSize`, and throwing user callbacks must surface as typed errors: a
  `Transform` as `TransformEvaluationFailed`, a custom `Breaks` as `BreakGenerationFailed`, a
  `TextMetrics` as `LayoutMeasurementFailed`. A throwing or rejecting `RowMapping` must resolve to a
  typed drop reason rather than an escaping exception.
- **Replay is by seed.** A failure message names its category and its exact seed; reconstructing the
  generator from that seed reproduces the case.

`unsafe` convenience methods are explicit throwing boundaries and are deliberately not fuzz targets.

The law kit has its own seed court: `SeededLaw.defaultSeeds` in `intaglio-laws` is 16 fixed seeds
including `Long.MinValue` and `Long.MaxValue`, and every counterexample carries `seed=<value>` in its
`LawFailure` detail.

## The golden court

Two JVM suites pin rendered rasters. Both are perceptual, not exact: they permit small JDK
glyph-rasterizer differences while failing on geometry, clipping, pattern, and colour regressions.

**`GoldenRegressionSuite`** pins one 360 × 240 PNG of the conformance scene — a panel rect, a
cross-hatched rect, a polyline, a four-shape point batch, and a title.

| Region | Changed-pixel fraction | Mean channel error |
|---|---|---|
| full image | 0.02 | 0.5 |
| geometry (rows 48 and below, excluding the text band) | 0.001 | 0.03 |

Both metrics are dimensionless. The fraction is changed pixels over compared pixels; the mean channel
error is mean absolute per-channel difference in 0–255 ARGB units, averaged over all four channels —
so `0.03` is three hundredths of one 8-bit level.

Three things make that reproducible: the font is Liberation Sans bytes loaded from a test-scoped
artifact and installed with `Java2DFontResolver.fixed`, so the host font environment is irrelevant;
both antialiasing hints are disabled; and the background is opaque white. A mutation test blackens a
20 × 20 block and asserts the geometry threshold rejects it.

**`FeatureVisualRegressionSuite`** pins five 640 × 480 PNGs covering temporal zoom, style aesthetics,
grouped ECDF, type-7 quantile summary, and aligned composition. It uses the same font and export
settings, the same full-image threshold, and a per-case panel rectangle with a tighter threshold —
except `style-aesthetics`, which is deliberately relaxed to the full-image threshold. It additionally
requires each fixture to render more than 2,000 non-white pixels and all five signatures to differ.

A failure writes `expected.png`, `actual.png`, and `difference.png` under
`target/golden-failures/java2d/` (and `.../features/<case>/` for the second suite), with matching
pixels rendered as faint grey and differing pixels as solid red.

**Tests never rewrite their own oracle.** Updating a golden runs a separate main class that refuses
unless given `--accept` and prints the new SHA-256, driven by `tools/update-java2d-goldens.sh` and
`tools/update-feature-visual-goldens.sh`. The intended order is: render the paired Intaglio/ggplot2
gallery, review it at native size, then accept. Details are in the
[visual regression guide](visual-regression.md) and the
[recent-feature visual QA page](visual-qa/recent-features.md).

## Running the gates

```sh
sbt "performanceJVM/test" "performanceJS/test"   # the receipt, both platforms
sbt testAll                                      # everything, including the fuzz and golden courts
tools/check-docs.sh                              # compile every documented example, re-render the gallery
```

To refresh a performance baseline: first work out *why* the deterministic metric changed. Then update
the shared baseline definition and the TSV in one commit, record the production source SHA, and rerun
both platforms.
