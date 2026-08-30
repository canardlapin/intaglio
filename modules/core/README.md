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

## Render contexts

Use `RenderContext` when plot layout must match a concrete output target. It
binds device-pixel width and height, pixel density, text metrics, and immutable
font-family resolution before `PlotCompiler` allocates any panel, axis, guide,
or title region. Compilation returns a `RenderPlan`, which carries the same
context through device lowering and into the backend:

```scala
val context = RenderContext.unsafe(
  width = 1280,
  height = 960,
  pixelsPerInch = 192.0,
  textMetrics = platformMetrics,
  fontRegistry = FontRegistry {
    case Some(requested) => installedFamilyFor(requested)
    case None            => Some("Platform Sans")
  }
)

val plan = program.flatMap(_.renderPlan(context))
val svg = plan.flatMap(SvgRenderer.render)
```

`RenderContext(…)` is the checked constructor. The convenience `unsafe`
constructor throws on non-positive dimensions or density. A `FontRegistry`
must be deterministic for the lifetime of a context: its result is used both
by family-aware `TextMetrics` during layout and by `DeviceScene` during text
lowering. Recompile when the target changes; targets with the same physical
size but proportionally scaled pixels and DPI preserve physical typography and
spacing.

The older `PlotCompiler.compile(plot)` and backend `(Scene, Options)` methods
remain 96-DPI compatibility entry points. They create a default render context
internally. New target-aware code should pass the `RenderPlan` directly so a
backend cannot accidentally lower a scene with dimensions or font resolution
different from those used for layout.

## Pattern fills

`PatternRecipe` is the renderer-neutral fill-pattern contract. Its checked
constructors admit only angled hatch, cross-hatch, horizontal or vertical
parallel rules, and stipple recipes. A `PatternPaint` pairs one recipe with an
explicit ink color and optional solid background; it never stores SVG, CSS,
callbacks, or backend objects.

```scala
val recipe = PatternRecipe.angledHatch(
  angleDegrees = 45.0,
  spacing = 8.0,
  lineWidth = 1.25
)
val params = recipe.map { value =>
  GraphicParams
    .unsafe(stroke = Some(Rgba.Black), alpha = 0.8)
    .withPatternFill(PatternPaint(value, Rgba.unsafe(30, 80, 120), Some(Rgba.White)))
}
```

Pattern spacing, line width, and stipple radius are device pixels. Hatch angles
are clockwise degrees from a vertical rule in the y-down device coordinate
system. The repeated tile starts at `(0, 0)` in the current device coordinate
system, does not restart at each mark's bounding box, and follows enclosing
viewport transforms. Ink and background keep their own RGBA values; the
`GraphicParams.alpha` value is then applied once to the composited mark.

Ordinary `GraphicParams.unsafe(fill = Some(color))` and `checked` calls retain
their existing signatures and behavior. `withPatternFill` replaces that solid
fill channel, while `withSolidFill` explicitly switches back. Patterns affect
only primitives that already have a fill channel: discs, closed polygons,
compound polygons, and rectangles. Text, images, and open lines are unchanged.

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

Statistical layers retain their typed output rows in `StatFrame`. Count, bin,
summary, and density results are distinct `StatRow` subtypes, so fields required
by a statistic are total Scala values:

```scala
val bins = histogram.orThrow.layers.head.statFrame.rows.collect {
  case row: StatRow.Binned[?] =>
    (row.count, row.binLower, row.binUpper, row.binWidth, row.binMidpoint)
}
```

`StatRow.Counted`, `Binned`, `Summarized`, and `Density` expose their own
required fields directly. The compiler maps and lowers those fields from the
typed subtype; a mismatched output variant is a checked mapping rejection, not
a missing value replaced by zero. `row.computed` and
`frame.computedAesthetics` remain generic inspection views derived from typed
rows (with declared keys retained for an empty frame), rather than storage used
to drive compilation.

`Stat` is an open public transform contract. Its single compiler entry point is
polymorphic in the current input subtype:

```scala
def compute[Input <: Row](
  batch: StatBatch[Input],
  context: StatContext
): Either[StatError, StatResult[Input]]
```

`StatBatch` supplies stable indexed inputs and the effective input mapping;
`StatContext` distinguishes plot-level from concrete `FacetCell` execution.
`StatResult` is an existential package: an extension's exact `StatRow` subtype
stays attached to its exact `AesSpec`, so output mappings can be total functions
over required fields. The compiler validates that output mapping against the
selected geom, but has no registry or match statement for stat implementations.
External stats normally select `StatLowering.Geom` and map their result to an
ordinary geom.

Every implementation also publishes a `StatContract` with explicit
`inputPreservation`, `grouping`, `summarization`, `rejection`, input-`mapping`,
`geometry`, and `lowering` policies. Built-in identity, count, bin, summary, and
density stats execute through this same interface. Expected accessor and
precondition failures are `StatError` values; the compiler adds layer
provenance when translating them to `GraphicsError`. The shared
`external.stat.OpenStatSuite` is an executable consumer-package example that
defines a new stat and output row without package-private access.

`Geom` and `Coord` are open public lowering contracts too. An ecosystem geom
publishes a checked `GeomAestheticContract` and implements one method over a
`GeomBatch` of typed `ResolvedRow` values:

```scala
case object CrossGeom extends Geom:
  val label = "cross"
  val contract = GeomAestheticContract.checked(
    Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
    Vector(Aesthetic.Color, Aesthetic.Alpha, Aesthetic.Size)
  ).orThrow

  def lower[Row](batch: GeomBatch[Row]) =
    // construct portable Grob values from batch.rows
    Right(Vector.empty)
```

The compiler validates the declared mapping, supplies `GeomContext`, and calls
that method directly; there is no built-in-geom registry or fallback cast.
Coordinates similarly implement `transform(CoordInput)` and return a
`CoordResult`, while also declaring guide placement, optional panel aspect,
clipping, and facet compatibility. `CoordinateTransform.identity`,
`transpose`, and checked `translate` are reusable logical-output transforms.
Built-in Cartesian, flipped, and fixed coordinates use the same methods. The
shared `external.geometry.OpenGeometrySuite` is an executable consumer-package
court for both extension points.

Ecosystem code can define a typed aesthetic without registering a string or
editing core. The key has reference identity, so retain and reuse the same
value for insertion and lookup:

```scala
val Confidence = Aesthetic.unsafe[Double]("confidence")

val mapping = AesSpec.empty[Observation]
  .updated(Confidence, AesValue.total(_ => 0.95))

val confidence: Option[AesValue[Observation, Double]] =
  mapping.get(Confidence)
```

`AestheticMap` is the immutable heterogeneous storage behind `AesSpec`. Core
keys remain in their stable declaration order and extension keys follow in
insertion order. Generic position encodings also carry the DSL prerequisite:

```scala
val x = ContinuousScaleSpec.numeric("time").orThrow
val y = ContinuousScaleSpec.numeric("signal").orThrow

val points = plot(rows)
  .encode(Aesthetic.X, _.time, x)
  .encode(Aesthetic.Y, _.signal, y)
  .geomPoint()
```

Categorical scales retain the caller's category type instead of requiring a
`String` projection. Define its stable lookup identity and display label once:

```scala
enum Arm(val code: Int):
  case Control extends Arm(10)
  case Treatment extends Arm(20)

given CategoryIdentity[Arm] =
  CategoryIdentity.by(
    _.code,
    {
      case Arm.Control   => "control arm"
      case Arm.Treatment => "treatment arm"
    }
  )

val domain: DiscreteDomain[Arm] =
  DiscreteDomain.ordered(Vector(Arm.Control, Arm.Treatment)).orThrow
val scale: DiscreteScale[Arm, Rgba] =
  DiscreteScale("arm", domain, palette).orThrow
val positions: BandScale[Arm] =
  BandScale("arm-position", domain).orThrow
```

`DiscreteDomain[A]` keeps its ordered `Vector[A]` and an immutable stable-key
index. `DiscreteScale[A, Out]`, `BandScale[A]`, and their specs accept `A`
directly. Guides and descriptors use the explicit label function; lookup,
grouping, and palette selection use the explicit identity, so equal labels do
not collapse distinct factors or enum cases. `String` has a built-in identity
for source-level convenience. `external.category.TypedCategorySuite` is the
JVM/Scala.js consumer court.

Application and scientific model types can remain independent of Intaglio.
Define a `PlotRecipe` beside the model to convert it to an immutable,
renderer-neutral `PlotSpec`:

```scala
final case class TimeSeries(samples: Vector[Observation])

given PlotRecipe.Aux[TimeSeries, Observation] =
  PlotRecipe.checked { series =>
    plot(series.samples)
      .aes(_.time, _.signal)
      .geomLine()
      .build
      .map(PlotSpec.fromProgram)
  }

val spec: Either[GraphicsError, PlotSpec[Observation]] =
  TimeSeries(rows).toPlotSpec
```

`PlotRecipe` is a Scala typeclass with an associated row type, not a base class,
implicit conversion, or mutable plugin registry. Normal lexical `given`
resolution selects the recipe; a missing or ambiguous recipe fails at compile
time. Conversion and compilation are pure over immutable inputs, so resolving
the same recipe result is deterministic. The shared
`external.recipe.PlotRecipeSuite` defines two unrelated consumer-package models
and runs their recipes on both JVM and Scala.js.

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

Horizontal and vertical reference lines are a separate O(1) annotation path,
not constant mappings repeated over the plot rows:

```scala
val annotated = plot(rows)
  .aes(_.time, _.signal)
  .geomPoint()
  .hline(2.5) // Train + Repeat are the explicit defaults
  .vline(
    0.5,
    scale = AnnotationScalePolicy.Overlay,
    facets = AnnotationFacetPolicy.Exclude
  )
  .resolve
```

`Train` treats the coordinate as data-space input: it expands an unscaled
range or contributes to an existing continuous position scale and is then
mapped through that trained scale. `Overlay` leaves training unchanged and
uses a panel-native coordinate. `Repeat` emits the annotation in every facet;
`Exclude` omits it from faceted panels. Reference layers retain no data or row
accessors, expose their resolved state through `TrainedLayer.annotation`, and
render even when the base data is empty. The legacy `data` argument on
`Layer.hline` and `Layer.vline` is accepted for source compatibility but is
ignored rather than retained.

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
- `ScaleSpec` is the row-free declaration algebra used by the plotting DSL.
  Constructing a spec never evaluates a row or invents a provisional domain;
  its domain is `ScaleDomain.Unspecified` until compilation. The compiler
  collects observations from every contributing layer at the plot or facet
  scope, trains the declaration once, and installs that same concrete `Scale`
  in every layer mapping. `ContinuousScale.fixed`, `DiscreteScale.fixed`, and
  `BandScale.fixed` remain the distinct public contract for known domains that
  must not expand.
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
  mappings share one `AesValue` algebra. A `RowMapping` can declare one of three
  contracts: `total` promises a value for every row, `checked` returns a typed
  `MappingFailure`, and `throwing` explicitly admits non-fatal exceptions.
  Existing `Row => A` lambdas remain source-compatible and are treated as
  throwing mappings. Because `RowMapping` is itself a `Row => A`, the same
  constructors work in `aes`, layer, stat, facet, and scale-binding APIs. The
  compiler catches non-fatal failures: direct aesthetic failures become
  `DroppedRow` diagnostics carrying aesthetic and row index, while failures
  needed for scale training, statistics, or facet partitioning become
  `GraphicsError.MappingEvaluationFailed`. Calling `RowMapping.apply`,
  `AesValue.map`, or `ScaleBinding.map` directly is the explicit convenience
  boundary that may throw; `PlotCompiler.resolve` and `compile` do not leak
  mapping exceptions. `AesSpec` is the single canonical mapping model: its
  built-in accessors and open typed lookup are views over one `AestheticMap`.
  The map packages each `Aesthetic[A]` key with its `AesValue[Row, A]`; exact
  key identity is the only boundary at which a hidden value type is recovered.
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
  never enter the shared grammar. Explicit `Rgba32.fromPackedInt` /
  `toPackedInt` conversions and `RasterImage.unsafeFromOwnedPackedArray`
  provide allocation-free packed-buffer interop at performance boundaries.
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
- Grouping is an inspectable compiler decision. An explicit `group` mapping is
  authoritative; otherwise discrete color, fill, alpha, and size bindings form
  a structural composite `GroupKey` from their raw pre-palette categories in
  stable aesthetic order. `TrainedLayer.grouping` and each resolved row expose
  that decision and key, so a palette that maps two categories to the same
  visual value cannot accidentally merge lines, polygons, dodged marks, or
  stacks.
- Every built-in geom publishes a `GeomAestheticContract` containing its
  required, optional, and group-constant aesthetics. A bound aesthetic outside
  that contract fails before row evaluation instead of being silently ignored.
  Lines require resolved color and alpha to stay constant within a group;
  ribbons, areas, and polygons additionally require constant fill. A violation
  is a typed `VaryingGroupAesthetic` compiler error, never a first-row style
  reduction.
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
  guide styles win locally. Omitted DSL palettes and layout policies are
  resolved from that final theme during scale training and layout assembly, so
  moving `.theme(...)` before or after scale, label, or geom declarations does
  not change the plot. Explicit palettes, panel layouts, frames, and layout
  policies remain authoritative. The layout solver measures the same themed
  font families and point sizes later emitted as text, while panel backgrounds
  and tick-aligned grids are ordinary renderer-neutral grobs beneath the data.
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
same renderer-neutral machinery. A free position dimension receives a local
axis on every panel; only dimensions explicitly shared by `FacetScales`
suppress inner axes. Inter-panel gaps and representative outer strips are
measured with the active `LayoutPolicy.metrics`, so independently trained tick
labels remain legible under the target text metrics.
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
