# Performance and limits

This page answers three questions about large or unusual plots. How much time and memory does
Intaglio spend on them? Which inputs does it refuse, and with what error? And what does it leave
unbounded, so that the size of the job is yours to manage? A final section explains how the
repository's tests guard these properties.

None of the times on this page is a promise. They were measured on one machine, and hosted CI never
fails a build because something ran slowly. What CI does check is described under
[How CI guards performance](#how-ci-guards-performance). The browser widgets have separate timing
budgets, run on named hardware; [browser performance](performance.md) describes them.

## Making a large plot cheaper

Three choices make the largest differences in the measurements below. Each is explained later on
this page.

1. Compile with `PlotCompilerOptions.lean`. The lean options retain no resolved row per mark,
   and they let a point layer be drawn as one batch instead of one object per point. The DSL's
   `resolve` uses the rich options unless you ask otherwise.
2. Do not recompile when only the size changes. Train the plot once with `PlotCompiler.train`
   and place it at each new size with `PlotCompiler.place`, or pass a `PlotCompileCache` to
   `resolve`. Note that `train` defaults to the lean options.
3. Share facet scales when the panels can share them. Free scales train every panel separately;
   in the measurements below they take about 70 percent more time and nearly twice the memory.

For workloads larger than those measured here, measure on your own hardware with a benchmark runner.

## What large plots cost

The figures in this section come from
[`performance/timings/v1.tsv`](../performance/timings/v1.tsv), which a harness writes when run by
hand:

```sh
sbt "performanceJVM/Test/runMain intaglio.performance.TimingHarness"
```

The recorded run is from 8 September 2026, on an Apple M3 Max with OpenJDK 25, single-threaded.
Each workload ran 4 times to warm up and 12 times for measurement, and the table gives the median.
The source was commit `38e8c62` with uncommitted changes. Another processor, JVM, or heap size will
give other numbers.

| Workload | What it does | Median time | Allocated |
| --- | --- | ---: | ---: |
| `facet-grid-30` | compiles a 6 × 5 facet grid with shared scales under lean provenance: 20 line-and-ribbon series per panel, 24,000 rows | 182 ms | 307 MB |
| `facet-grid-30-rich` | the same plot under rich provenance | 170 ms | 310 MB |
| `facet-free-scales` | the same plot with free scales | 308 ms | 593 MB |
| `facet-grid-sparse` | the same grid with 15 of its 30 panels empty, 12,000 rows | 81 ms | 154 MB |
| `resize-only` | resolves the unchanged plot at five sizes | 807 ms | 1,534 MB |
| `train-once` | trains the plot once, for later placement | 190 ms | 307 MB |
| `resize-place` | places the trained plot at the same five sizes | 4.3 ms | 1.1 MB |
| `resize-cached` | resolves at the five sizes through a warm `PlotCompileCache` | 0.07 ms | 0.2 MB |
| `pick-compile` | builds the picking index for a 20,000-mark faceted plot | 65 ms | 100 MB |
| `pick-hits` | one `hits` query against that index | 31 µs | |
| `pick-nearest` | one `nearest` query | 32 µs | |
| `pick-select` | one 150 × 300 px rectangle `select` over dense data | 43 ms | |

### Resizing without recompiling

Resolving a plot runs every phase of the compiler: mapping, statistics, scale training, row
resolution, layout, and lowering. Only layout and lowering depend on the output size. When a window
is resized over unchanged data, the first four phases repeat work whose result cannot have changed.
That is why five resizes through `resolve` cost 807 ms, about 160 ms each.

`PlotCompiler.train` runs the data-dependent phases once, and `PlotCompiler.place` runs layout and
lowering for one `RenderContext`. Given the same options, `resolve(plot, context, options)` is
exactly `train` followed by `place`, and `TrainPlaceSuite` checks that both routes produce equal scenes. Placing the trained
plot at the same five sizes costs 4.3 ms in total, and the receipt's phase timings record no
mapping, statistics, scale training, or row resolution during placement.

If your code cannot be arranged around `train` and `place`, pass a `PlotCompileCache` to
`resolve`. With a warm cache each resolve is a lookup, and the five resizes cost 0.07 ms. The cache
is never on unless you construct one:

- `PlotCompileCache.Disabled` is the default everywhere, and it is exactly the uncached path.
- `PlotCompileCache.bounded()` memoizes compilation, which is safe because compiling is a pure
  function of its inputs. It can be passed to `PlotCompiler.resolve`, `train`, or `place`, or to
  `PlotProgram.resolve`.
- A plot holds your functions, and functions cannot be compared for equality, so the cache keys on
  the plot and the options by reference. A newly built plot is therefore a miss even when it
  describes the same picture. A `RenderContext` is keyed by its dimensions and resolution, so
  returning to an earlier size hits even through a new context; its text metrics and font registry
  are compared by reference.
- A cache is mutable and not thread-safe. Give each rendering thread its own.
- Errors are never cached.
- `profile` reports hits, misses, and evictions, so a cache that never hits can be seen.

### Facet grids

With shared scales (`FacetScales.Shared`), the compiler resolves each panel's rows once and builds
the scale registry and the axis specifications once for the whole grid. With free scales each panel
trains its own scales, so the same grid costs 308 ms instead of 182 ms. `FacetSharedScalesSuite`
checks, panel by panel, that the shared-scale route agrees with the general per-panel route.

An empty panel costs no compile work. The grid with half of its 30 cells empty costs 81 ms, about
half as much as the full grid, although each empty cell still draws its panel and strip.

### Rich provenance costs memory

Under the rich options, the 30-panel grid keeps 48,000 resolved rows after compiling, one per datum
per layer. Under the lean options it keeps none. For this line-and-ribbon workload the elapsed times
are about the same: the rich median is 170 ms and the lean median is 182 ms, and the ranges of the
twelve runs overlap.

The default depends on the entry point. `PlotBuilder.resolve`, which the DSL calls, and
`PlotCompiler.resolve` default to rich; `PlotCompiler.compile` and `PlotCompiler.train` default to
lean. A faceted plot
resolved through the DSL therefore retains its rows unless you pass other compiler options, for
example `PlotCompilerOptions.lean` or a `provenance` of `ProvenancePolicy.None`.

### Hover picking

`Picking.compile` builds a uniform spatial grid over the bounds of the hit targets. A query then
evaluates exact geometry only for the targets in the grid cells it touches, and returns the same
result as a scan of every target. The index costs 65 ms to build for 20,000 marks, once; after
that, a `hits` or `nearest` query costs about 30 µs. A rectangle `select` over dense data still
costs about 43 ms, because such a rectangle really does intersect hundreds of targets.

## Large point layers are batched

Drawn naively, a point layer allocates one scene object (a grob), one device primitive, and one
retained row for every mark. The batch representation avoids all three. In the scene, the layer
becomes one `Grob.PointBatch`; after lowering, it becomes one `DevicePrimitive.PointBatch`. Both
store the positions as a `Vector` and each style property as a `BatchColumn`, which is either one
`Constant` or `Values` with one entry per mark.

Whether a layer is batched does not depend on how many rows it has. Three conditions decide it: the
geom is exactly `Geom.Point`, the lowering is `StatLowering.Geom`, and the compiler's `provenance`
is anything other than `ProvenancePolicy.Full`. A one-row point layer is batched under the lean
options, and a 20,000-row point layer is not batched under the rich options.

```scala mdoc:silent
import intaglio.*

final case class Mark(x: Double, y: Double)

val marks = Vector.tabulate(2000)(i => Mark(i.toDouble, (i % 97).toDouble))

val leanPlot =
  plot(marks).aes(_.x, _.y).geomPoint().compilerOptions(PlotCompilerOptions.lean).resolve

val richPlot =
  plot(marks).aes(_.x, _.y).geomPoint().compilerOptions(PlotCompilerOptions.rich).resolve
```

Under the lean options the 2,000 points form one grob; under the rich options they form 2,000.

```scala mdoc
(leanPlot.map(_.layers.head.grobs.length), richPlot.map(_.layers.head.grobs.length))
```

The lean layer keeps none of its resolved rows, and the rich layer keeps all of them.

```scala mdoc
(leanPlot.map(_.layers.head.rows.length), richPlot.map(_.layers.head.rows.length))
```

Batching gives up two things:

- Identity for each mark. A batch is one grob with at most one `GraphicsName`. You cannot
  address or wrap an individual mark, for example with `Grob.annotated(child, meta)` for each datum.
- Inspection of each row. Batching is triggered by the provenance policy, and the same policy
  empties `rows`, `statFrame.rows`, and `droppedRows`.

It keeps the geometry, the style, and the accessibility information. The batch's points and its
radii, shapes, and `GraphicParams` for each index equal what the unbatched path produces. The same
`SceneSemantics` is carried through, and the datum IDs are a `DatumIdSeries` whose `valueAt(i)`
computes `<layer-id>-datum-<i>` when asked, instead of retaining one `String` per mark.

Batching does not make SVG output smaller. The SVG backend writes one `<circle>` element for each
mark in a batch, so the saving is in the compiler, the scene, and the device representation, not in
the document.

`ProvenancePolicy` has five cases, ordered from least to most retained: `None`, `CountOnly`,
`Representative`, `SourceIndices`, and `Full`. Each case publishes a `ProvenanceRetentionCost` that
names how its retention grows with the data. The four cases below `Full` all batch point marks.

## Inputs Intaglio rejects

Each limit below is checked when a value is constructed or when a scene crosses a render boundary.
A value outside the limit produces a typed error, not an exception. Errors are cases of
`GraphicsError` unless the table names `PdfRenderError`.

| Limit, and where it is checked | Value | Error |
|---|---|---|
| Pattern tile axis: raster backends only (`PatternTile.MaxAxisPixels`, internal). SVG and PDF draw vector patterns. | spacing of at most 1024 device pixels | `InvalidPatternParameter` |
| Break output size: `Breaks.MaximumOutputSize` | at most 10,000 values | `BreakOutputLimitExceeded` |
| Break iteration: `Breaks` | a fixed internal cap | `BreakIterationLimitExceeded` |
| Device coordinate: `DeviceScene` lowering | finite, with magnitude at most 1.0e13 | `InvalidDeviceValue` |
| Calendar year: `CalendarDate` | −9999 to 9999 | `InvalidCalendarDate` |
| PDF page side: `PdfRenderer` | at most 14,400 points (200 inches) | `PdfRenderError.InvalidPageSize` |
| Raster dimensions: `RasterDimensions` | the PNG-encoded size must fit in an `Int` | `InvalidRasterDimensions` |
| Discrete palette: `DiscretePalette.validateDomain` | no more levels than the palette has colours, under `PaletteOverflowPolicy.Reject` | `DiscretePaletteOverflow` |
| Dash pattern: `DashPattern` | at most 32 segments, at least one above zero | `InvalidDashPattern` |
| Font weight: `FontWeight` | 100 to 900 | `InvalidFontWeight` |
| PDF font faces: `PdfFontCatalog` | one face per family and weight | `PdfRenderError.DuplicateFontFace` or `PdfRenderError.MissingFontWeight` |

Four of these limits need more explanation than a table cell allows.

The raster limit is a formula, not a pixel count. `RasterDimensions` computes
`scanlineBytes = pixels * 4 + height`, adds five bytes for each stored deflate block of 65,535 bytes
plus a constant, and rejects any image whose encoded size would exceed `Int.MaxValue`. The block
framing is the PNG encoder's own, so the constructor rejects exactly the images the encoder could not
write.

The PDF font limit is the one most likely to surprise. Most backends can approximate a weight they lack: AWT and JavaFX use the nearest
face, and a browser synthesizes one. A PDF embeds its font programs, and PDFBox will not synthesize a
bold face from an embedded subset. A document that draws at a weight no registered face provides is
therefore a `MissingFontWeight` error, rather than a document that silently renders the text in the
regular weight. Register every weight you use.

The dash limit exists because `java.awt.BasicStroke` throws on an all-zero dash array. Intaglio refuses such a
pattern at construction, so that a pattern three backends would draw is not one that Java2D rejects
at render time. Dash segments, like a fill pattern's spacing, line width, and radius, are measured in
layout pixels of 1/96 inch by default: one device pixel at 96 ppi and two at 192 ppi, so dashes and
hatches keep their physical size at 2x and in export. `PaintLengthUnit.Point` and `Millimetre` are
the other physical units, and `PaintLengthUnit.DevicePixel` requests literal device pixels. See
[dash and hatch units](style-aesthetics.md#dash-and-hatch-units).

The palette limit is the one most people meet. The default theme's discrete palette holds six colours, and the default
overflow policy is `Reject`, so a seventh level is a typed error rather than a reused colour.
`PaletteOverflowPolicy.Cycle` wraps around instead. `DiscretePalette.okabeIto` holds eight and also
rejects overflow. Every prefix of it clears the CIE76 colour-difference floor of 10 that
`ColorSeparation.SeriesFloor` names, but the seventh and eighth colours clear it by only about one
unit. Beyond about six series, faceting or direct labelling will separate the series better than a
longer palette. The default theme's palette is the first six Okabe–Ito colours, so it clears the
floor. A palette you choose that falls below the floor is reported as
`AccessibilityDiagnostic.IndistinguishablePalette`.
[Accessible plots](accessibility.md#colour-vision-deficiency) gives the figures for each prefix.

## What Intaglio does not limit

A missing limit can be mistaken for a promise that any size will work, so the absences are listed
here.

- **Scene size.** No limit applies to the number of elements in a scene or the size of an SVG
  document. `SvgRenderError` has five cases, `InvalidDocumentSize`, `InvalidXmlCharacter`,
  `DuplicateDataKey`, `InvalidIdPrefix`, and `Graphics`, and none of them guards element count or
  byte size. The
  `svg/mark_elements` and `svg/bytes` ceilings in the [deterministic receipt](#the-deterministic-receipt)
  are CI checks on one workload, not runtime limits.
- **Row count.** The repository documents no maximum number of rows.
- **Streaming.** A `Scene` is a fully built immutable tree, and `DeviceScene.fromScene` walks all of
  it. Compilation can be split at the size boundary, as described under
  [Resizing without recompiling](#resizing-without-recompiling), but rendering cannot be streamed.

## How CI guards performance

### Why CI does not measure time

[`performance/README.md`](../performance/README.md) gives the reason:

> It deliberately does not fail CI on elapsed time: shared runners, JIT warm-up, garbage collection,
> and hosted-runner contention make wall-clock thresholds noisy. Instead, the gates measure stable
> work and output cardinality that tracks the severe regressions this repository needs to stop

The same README asks that exploratory timing be done with a profiler or a benchmark runner, and that
timing observations never become pass/fail assertions in hosted CI. The timings receipt above follows
that rule: it is produced by hand and checked by no test.

### The deterministic receipt

What CI does check is [`performance/baselines/v1.tsv`](../performance/baselines/v1.tsv), a receipt
of work and output counts that must come out identically on the JVM and Scala.js. Each row names a
workload, a metric, the value recorded at the named source commit, and the ceiling CI enforces.

<details>
<summary>The receipt, verbatim</summary>

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

</details>

Where the recorded value equals the ceiling, CI requires that exact value. The two byte counts,
`raster/svg_bytes` and `svg/bytes`, may grow by 25 percent, so that a change in formatting is not
reported as a performance failure. The workloads and baselines are defined in
`modules/performance/shared/src/test/scala/intaglio/performance/`, and a JVM test parses the TSV and
requires it to match those definitions in full: header, order, values, and rationale text. The file
and the code therefore cannot disagree.

| Workload | Input | Compiled as |
|---|---|---|
| `scatter` | 20,000 rows, `Geom.Point` | `PlotCompilerOptions.lean`, lowered to an 800 × 600 `DeviceContext` |
| `raster` | one 256 × 256 checkerboard `RasterImage`, nearest-neighbour interpolation | one image grob, rendered to SVG at 512 × 512 |
| `dodge` / `stack` | 10,000 rows (500 categories × 20 groups), mixed signs, `Geom.Bar` | `PlotCompilerOptions.rich` with `Position.Dodge()` or `Position.Stack()` |
| `discrete_lookup` | an ordered `DiscreteDomain` of 8,192 levels | 8,192 hits and 1,024 misses through `indexOf` |
| `histogram` | 50,000 samples, 256 bins | `ProvenancePolicy.CountOnly` |
| `svg` | 10,000 marks in one hand-built `Grob.pointBatch` of 200 × 50 | serialized at 800 × 600 |

Two metrics check a strategy rather than count work. `histogram/slow_path_penalty` is `0` only when
generated bins are found by arithmetic and explicit breaks by binary search; any other combination
scores `1`. `discrete_lookup/identity_calls` counts the identities derived after the domain is built,
so a value of 9,216 means one derivation for each `indexOf` call: a lookup does not derive anything
again for each level.

### Fuzz and golden tests

`FuzzRegressionSuite` runs 256 fixed seeds on both the JVM and Scala.js, with no clock and no
platform randomness. Its cases mix ordinary values with special doubles (`NaN`, both infinities,
`±Double.MaxValue`, `±0.0`, `Double.MIN_VALUE`, and `±1.0`) at the public construction and callback
boundaries. The suite fails if any non-fatal exception escapes: a checked boundary must return a
typed error. A throwing `Transform` must surface as `TransformEvaluationFailed`, a throwing custom
`Breaks` as `BreakGenerationFailed`, a throwing `TextMetrics` as `LayoutMeasurementFailed`, and a
throwing or rejecting `RowMapping` as a typed drop reason. Every built-in break run is also checked
against `Breaks.MaximumOutputSize`. The cases fall into five categories: `scene`, `mapping`,
`transform`, `breaks`, and `layout`. A failure names its category and seed, and
the seed alone reproduces the case. The `unsafe` convenience methods throw by design and are not
fuzzed.

The law kit uses fixed seeds too: `SeededLaw.defaultSeeds` in `intaglio-laws` holds 16, including
`Long.MinValue` and `Long.MaxValue`, and every counterexample records `seed=<value>` in its
`LawFailure` detail.

Two JVM suites, `GoldenRegressionSuite` and `FeatureVisualRegressionSuite`, compare rendered PNGs
against committed images with perceptual thresholds that tolerate small differences in glyph
rasterization but fail on changes to geometry, clipping, patterns, or colour. Tests never rewrite
their own reference images. The [visual regression guide](visual-regression.md) gives the
thresholds, the fixed fonts, and the review procedure for accepting a new image.

## Running the gates

```sh
sbt "performanceJVM/test" "performanceJS/test"   # the deterministic receipt, on both platforms
sbt testAll                                      # every suite, including the fuzz and golden tests
tools/check-docs.sh                              # compiles every documented example and re-renders the gallery
```

To refresh a performance baseline, first find out why the deterministic metric changed. Then update
the shared baseline definition and the TSV in one commit, record the production source commit, and
run both platforms again, together with `scalafmtCheckAll`.
