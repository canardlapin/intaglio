# Performance and limits

Intaglio does not publish throughput numbers, and its CI does not fail on elapsed time. What it
publishes instead is a receipt: a set of deterministic work and output-cardinality measurements that
are reproduced identically on the JVM and Scala.js, and a small set of hard constructor limits. This
page says exactly what is measured, what is bounded, and what is not bounded at all.

## Why not wall-clock

From [`performance/README.md`](../performance/README.md):

> This module runs representative Intaglio workloads on both the JVM and Scala.js. It deliberately
> does not fail CI on elapsed time: shared runners, JIT warm-up, garbage collection, and
> hosted-runner contention make wall-clock thresholds noisy. Instead, the gates measure stable work
> and output cardinality that tracks the severe regressions this repository needs to stop

and, on refreshing a baseline:

> Use a profiler or a proper benchmark runner for exploratory wall-clock work; do not convert timing
> observations into hosted-CI pass/fail assertions.

Take that at face value when planning your own capacity work. Nothing in this repository tells you
how many marks per second your machine will draw.

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

The raster limit is a formula rather than a fixed pixel count. `RasterDimensions` computes
`scanlineBytes = pixels * 4 + height`, adds five bytes per 65,535-byte stored deflate block plus a
constant, and rejects anything whose encoded size would exceed `Int.MaxValue`. The block framing
comes from the PNG encoder, so the constructor rejects exactly the images the encoder could not
produce.

The palette limit has one number most people meet: the default theme's discrete palette holds
**six** colours, and the default overflow policy is `Reject`. A seventh level is a typed error, not a
reused colour. `PaletteOverflowPolicy.Cycle` wraps instead.

## What is not limited

Stated plainly, because absence is easy to mistake for a promise:

- **There is no cap on scene element count.** `SvgRenderError` has four cases —
  `InvalidDocumentSize`, `InvalidXmlCharacter`, `DuplicateDataKey`, and `Graphics` — and none of them
  is an element-count or byte-size guard. The `svg/mark_elements` and `svg/bytes` rows above are CI
  receipt ceilings, not runtime limits; the renderer will emit a larger document.
- **There is no documented maximum row count** and no "does not scale beyond N" statement anywhere in
  the repository.
- **There is no streaming or incremental render path.** A `Scene` is a fully materialized immutable
  tree, and `DeviceScene.fromScene` walks all of it.
- **There is no caching of compiled plots.** Compilation is pure, so the same inputs give the same
  output, but nothing memoizes it for you.

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
