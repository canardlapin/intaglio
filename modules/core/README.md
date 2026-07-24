# intaglio-core

`intaglio-core` is the renderer-neutral plotting core.

It is not a Java2D, JavaFX, Canvas, SVG, or ggplot renderer. It is the shared
typed algebra those backends can interpret later:

- grammar-of-graphics plot, layer, geom, stat, coordinate, and aesthetic specs;
- `scales`-inspired transforms with explicit open/closed domains, trained
  ranges, out-of-bounds policies, palettes, breaks, labels, and discrete
  domains;
- `grid`-inspired immutable scene trees with units, viewports, graphical
  parameters, grob primitives, and axis/tick scene helpers;
- a device-resolution layer (`DeviceContext`, `DeviceScene`) that flattens
  scenes into numeric, y-down device primitives any backend can serialize;
- a plot layout solver (`PlotLayoutSolver`, `LayoutPolicy`, `TextMetrics`)
  that allocates named panel, facet-strip, axis-strip, and guide regions;
- derived discrete color legends and continuous colorbars whose ticks and
  labels follow the scale transform, lowered to ordinary portable grobs;
- typed `Position` values for identity, dodge, stack, and seeded jitter,
  compiled as a distinct transformation between statistics and geometry;
- a finite immutable `Theme` value for geometry defaults, typography,
  palettes, guides, and optional panel decoration;
- a renderer conformance contract (`RendererConformance`, `RendererHarness`)
  that backend modules run to prove deterministic, marker-preserving output.

The module has no dependencies and cross-compiles to JVM and Scala.js.
Consumers should export plot specifications or scenes into this module;
platform renderers should consume `DeviceScene` values at a boundary. The
artifact matrix and design commitments are in the [root README](../../README.md).

## Plotting DSL

The ordinary entry point is a small immutable Scala DSL. Position mappings
change the builder's type, so `geomPoint`, `geomLine`, and `geomSummary` are not
callable until both x and y exist; histogram and density need only x. Checked
scale and coordinate failures stay in the final `Either`.

```scala
import intaglio.*

final case class Observation(time: Double, signal: Double, condition: String, arm: String)

val program = plot(rows)
  .aes(_.time, _.signal)
  .group(_.condition)
  .scaleColorDiscrete(_.condition, name = "condition")
  .geomLine()
  .geomPoint()
  .title("Activation over time")
  .axisTitles("Time (s)", "Signal")
  .theme(Theme.minimal)
  .build

val trained = program.flatMap(_.resolve)
val scene = program.flatMap(_.scene)
```

`PlotProgram` retains the renderer-neutral `Plot` and `PlotCompilerOptions`, so
the concise surface does not hide the value being compiled. Canonical plots
are single inspectable expressions:

```scala
val points = plot(rows).aes(_.time, _.signal).geomPoint().resolve
val lines = plot(rows).aes(_.time, _.signal).geomLine().resolve
val histogram = plot(rows).aes(_.signal).geomHistogram().resolve
val summary = plot(rows).aes(_.time, _.signal).geomSummary().resolve
val facets = plot(rows)
  .aes(_.time, _.signal)
  .facetWrap(_.condition, columns = 2)
  .geomPoint()
  .resolve
```

The equivalent ggplot2 inspection pattern requires a plot statement followed
by `ggplot_build` for each case:

```r
p <- ggplot(rows, aes(time, signal)) + geom_line()
trained <- ggplot_build(p)
```

The Scala examples above are compiled as JVM and Scala.js tests in
`PlotDslSuite`; this is executable syntax, not documentation-only sugar.

Layers may also own a different row type. Independent layers supply their own
data and mapping, and must state what happens if the plot is faceted:

```scala
final case class Reference(time: Double, limit: Double)

val references = Vector(Reference(0.0, 2.5), Reference(10.0, 2.5))
val mixed = plot(rows)
  .aes(_.time, _.signal)
  .geomPoint()
  .independentLayer(
    references,
    Layer.line[Reference](_.time, _.limit, inheritMapping = false),
    LayerFacetPolicy.Repeat
  )
  .resolve
```

`LayerFacetPolicy.Select` partitions the independent row type with a typed
`(FacetCell, Row) => Boolean` function; `Exclude` omits it from facet panels.
The compiler retains each layer's hidden `Row` member through statistics,
dropped-row provenance, and `TrainedLayer` inspection while training shared
scale declarations over observations from every layer.

## Core laws

- Scene composition is a monoid: `Scene.empty` is identity and `++` is
  associative while preserving grob order.
- Primitive grobs own complete graphic parameters. Groups compose children and
  viewports only; there is no ambient or backend-dependent style inheritance.
- Stroke geometry is part of that complete value: `LineCap` and `LineJoin`
  have explicit butt/miter defaults, survive `DeviceScene` lowering, and are
  translated exhaustively by every backend rather than inherited from toolkit
  state.
- Scene coordinates are y-up (the grid convention): npc and native y increase
  toward the top of the device. Orientation is explicit — a `Viewport` carries
  a `YDirection` (default `Up`; `Down` is available for raster-style spaces) —
  and the device lowering flips exactly once, so backends never guess.
- Lengths resolve to numbers before rendering: `LengthResolver` evaluates
  npc/native/absolute expressions (including `Add`/`Sub`/`Mul` mixtures and
  typed location-plus-extent offsets)
  against a `DeviceContext`, so backends emit numeric geometry only — no CSS
  `calc()`, no percentages.
- Trained continuous ranges are immutable unions over finite observations; a
  later training pass cannot shrink a range.
- Transforms define explicit open/closed domains and must round-trip through
  their inverse on valid values within tolerance.
- Continuous scales retain both raw data domains and transformed domains:
  palette mapping uses transformed coordinates, while breaks and labels remain
  in the raw data domain.
- Scale training is plot-global: every layer bound to an aesthetic contributes
  observations to one shared scale before rows are mapped. Reusing one scale
  declaration across layers therefore gives one coordinate or palette
  language; conflicting declarations are a typed error instead of silently
  normalizing each layer independently. `ScaleTraining.Fixed` is the explicit
  limits contract when a domain must not expand.
- Facets partition each layer before statistics run. `facetWrap` and
  `facetGrid` compile to typed `FacetCell` values, row-major panel groups named
  `panel-r-c`, and renderer-neutral strip text named `strip-r-c`. Position
  scales are shared by default; `FreeX`, `FreeY`, and `Free` retrain only the
  requested panel positions, while color and fill remain plot-global so
  legends never drift between panels.
- Default continuous breaks use a deterministic zero-anchored 1/2/5 grid with
  an approximate target count. Use `Breaks.count` when an exact number of
  equally spaced breaks is part of the caller's contract.
- Aesthetic mappings are row-aware typed values: direct, constant, and scaled
  mappings share one `AesValue` algebra. `AesSpec` is the single canonical
  storage model: its precise fields are the public API, while typed lookup and
  declaration-order iteration use the same value through `Aesthetic[A]`.
  `AesEnv` is only a source-compatible alias, not a normalized copy.
  Continuous scales consume `Double`, discrete scales consume `String`, and
  the aesthetic they bind to determines the rendered value type.
- `PlotLayer` packages each layer's row type with its data, mapping, statistic,
  and facet policy. Same-row layers inherit plot data and mappings as before;
  independent layers do neither implicitly. Plot-global scales consume the
  union of their observations without erasing row sources in trained output.
- Layer constructors for common geoms require their essential aesthetics in the
  Scala signature. Generic `fromMapping` allows inheritance; `Plot.addLayer`
  validates the effective layer mapping before a renderer ever sees the layer.
- Scene sizes and radii use `ExtentExpr`, not raw `LengthExpr`, so negative
  extents cannot enter primitive grobs through checked constructors.
- Raster images use checked `RasterDimensions`, opaque packed `Rgba32` pixels,
  and immutable `RasterImage` storage. Pixel rows are top-to-bottom; image
  placement still follows the scene's y-up `Point`/`Anchor` rules. Nearest and
  smooth interpolation are explicit, and file IO or platform image objects
  never enter the shared grammar.
- Regular scalar fields use checked `RegularGridAxis` values with explicit
  cell- or vertex-centered sampling and immutable x-fastest row-major storage.
  `plot(field).geomHeatmap()` derives coordinates, tile extents, a continuous
  fill scale, and a colorbar without inventing an untyped `z` aesthetic or
  leaking field storage into renderers.
- `FieldStat.Bin2D` is a pure observation-to-field transform with checked bin
  counts, optional fixed domains, explicit count/proportion values, and a
  right-closed boundary contract. Conservation and permutation laws run in
  shared tests on both JVM and Scala.js; plotting starts only after the field
  has been computed.
- `FieldStat.Kde2D` uses checked per-axis bandwidths, grid sizes, and optional
  domains. Its shared Gaussian kernel writes directly into primitive row-major
  storage; separability, translation, permutation, normalization, and
  automatic-bandwidth laws anchor the computation independently of rendering.
- `ContourSet.extract` turns a scalar field and checked `ContourLevels` into
  typed non-empty paths using deterministic shared marching squares. A
  bilinear asymptotic decider handles ambiguous saddles, exact ties follow an
  explicit policy, and `plot(contours).geomContour()` lowers the paths through
  the ordinary grouped-line grammar on every backend.
- `Geom.Line` deliberately has path semantics: within each group it connects
  rows in encounter order and never sorts by x. This differs from ggplot2
  `geom_line()`, which sorts by x; callers that want sorted lines must sort
  their rows explicitly, and any future sorted-line geom will be a distinct
  typed API rather than a silent change to `Geom.Line`.
- Filled contours clip each regular-grid triangle against checked
  `ContourBreaks`, cancel internal edges, stitch oriented rings, and assign
  clockwise holes to the smallest containing counter-clockwise outer ring.
  `ContourBandSet` retains that topology; `plot(bands).geomFilledContour()`
  maps each region and ring to the generic typed polygon `group`/`subpath`
  grammar. The shared scene lowers those rings to one winding-aware compound
  path, eliminating fragment seams while keeping statistical behavior out of
  renderers.
- Axes are scene helpers, not renderer features: `Axis` lowers to baseline
  segments, tick segments, and text labels that any backend can interpret.
- Plot text is structural data. `PlotLabels` carries title, subtitle, and x/y
  axis titles; derived axes default to their scale names, and explicit labels
  override them. The layout solver sizes dedicated title/subtitle regions and
  enlarged axis strips through `TextMetrics`, then lowering emits ordinary
  text grobs for every backend.
- Themes are values, not ambient state or a selector cascade. Compilation
  resolves one `Theme` into complete leaf `GraphicParams`; explicit layer and
  guide styles win locally. The layout solver measures the same themed font
  families and point sizes later emitted as text, while panel backgrounds and
  tick-aligned grids are ordinary renderer-neutral grobs beneath the data.
  `TextMetrics.estimate` remains the deterministic portable default. Callers
  may explicitly inject `Java2DTextMetrics` or `CanvasTextMetrics` through
  `LayoutPolicy.metrics` when layouts should reflect a fixed platform font
  environment; installed fonts never affect shared output implicitly.
- Statistics are typed data transformations, not enum flags interpreted by a
  geom. `StatFrame` exposes its aggregate rows and closed
  `ComputedAesthetic` fields; `Stat.Count` computes count/proportion before
  scale training, and its discrete category output therefore drives both bar
  positions and axis labels. Unsupported stat/geom/aesthetic combinations are
  compiler errors represented by `GraphicsError`.
- Position adjustment is likewise a typed compiler phase, not mutable geom
  state. `DodgeConfig`, `StackOrder`, and `JitterConfig` are finite immutable
  values; checked opaque widths and amounts reject invalid input at their
  construction boundary, and a `JitterSeed` makes displacement reproducible
  across JVM and Scala.js.

## Compilation pipeline

`PlotCompiler` is a facade over explicit, independently testable phases
(`CompilerPhases.scala`): mapping resolution → statistical transformation →
plot-wide scale training → row evaluation (with typed `DroppedRow`
diagnostics) → position adjustment → group-aware geom lowering → layout
resolution → guide resolution. The scale phase follows ggplot2's core build invariant: scales see
the union of each layer's stat output before they map values, but encodes the
one-scale-per-aesthetic rule directly in `PlotScaleRegistry`.
Guides read that same registry, so marks, axes, and legends cannot disagree.
Faceted plots repeat mapping and statistics per panel before the scale phase,
then either train one union scale or fresh panel position scales according to
`FacetScales`; the remaining row, geom, coordinate, and guide phases stay the
same renderer-neutral machinery.
Guides follow a `GuidePolicy`:
`Derived` produces routine axes from trained scales (transform-aware breaks
positioned in mapped space) and legends from discrete color/fill palettes,
with explicit `GuideSpec` overrides; layout comes from an explicit
`PanelLayout`, an explicit `PanelFrame` plus derived data ranges, or the
`LayoutPolicy` solver. Compiler-derived panel ranges use a typed
`RangeExpansion` (5% by default) after guide derivation so point glyphs at
trained extrema remain inside the panel; `RangeExpansion.none` restores exact
edge-centered framing, and an explicit `PanelLayout` is always authoritative.

A lower-level plot remains ordinary immutable composition when a domain adapter
needs direct control of layers:

```scala
val plot = Plot(rows)
  .withTitle("Activation over time")
  .withSubtitle("Condition means")
  .withAxisTitles("Time (s)", "Signal")
  .addLayer(Layer.line[Observation](_.time, _.signal))

val scene = PlotCompiler.compile(
  plot,
  PlotCompilerOptions(guides = GuidePolicy.Derived(), theme = Theme.minimal)
)

val counts = Plot(observations)
  .addLayer(
    Layer.count(
      _.condition,
      group = Some(_.arm),
      position = Position.Dodge(),
      padding = BandPadding.unsafe(0.2)
    )
  )

val jittered = Plot(observations)
  .addLayer(
    Layer.point(
      _.time,
      _.signal,
      position = Position.jitterUnsafe(
        seed = 2026L,
        width = 0.22,
        height = 0.12
      )
    )
  )
```

Categorical positions are represented by `BandScale`, not by an untyped scale
configuration or a geom-specific width convention. A checked `BandPadding`
trains ordered string levels into zero-based centers, while each resolved row
carries a `Band(center, width)` value that layout, axes, bars, and coordinates
consume uniformly. The same `BandScale` can bind to `Aesthetic.X` or
`Aesthetic.Y`; `Stat.Count` simply produces the categorical values it trains.

This is behavioral parity with ggplot2, not an R-shaped port. ggplot2's
one-based categorical centers and Intaglio's zero-based centers are related by
an affine translation, and both use a default width of 0.9. The public Scala
surface instead makes invalid padding unrepresentable, preserves the scale's
typed input/output relation, and exposes the trained interval semantics for
inspection before rendering.

The same distinction governs position adjustment. Intaglio adopts the useful
layout semantics of ggplot2—band-aware dodge, sign-separated stack, and seeded
jitter—without importing `ggproto`, stringly configuration, or ambient random
state. `DodgePreserve.Total` and `DodgePreserve.Single`, for example, are
exhaustive Scala alternatives and can be inspected before compilation. The
`intaglio.java2d.PositionVisualQa` runner in the `intaglio-java2d` test sources
generates a paired Java2D and ggplot2 gallery covering scatter, line,
statistical layers, bounded geoms, count, facets, and position adjustment.

## Backends

A backend implements `RendererHarness` and runs `RendererConformance.check`
in its test suite; the shared `DeviceScene` lowering is the reference
implementation. `intaglio-svg` inspects serialized markup while
`intaglio-canvas` records deterministic Canvas commands; both must satisfy the
same primitive, style, text-placement, raster-image, clipping, and rotation
requirements.
`intaglio-java2d` and `intaglio-javafx` independently adopt the same contract,
and `intaglio-java2d` adds raster-level `BufferedImage` assertions for JVM
rendering behavior.
