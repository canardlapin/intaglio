# Aligned plot composition

Intaglio composes trained plots as ordinary renderer-neutral `Scene` groups. The composition owns no
SVG, Canvas, Java2D, or JavaFX object: it applies checked `Viewport` transforms, then the usual
`Scene` to `DeviceScene` lowering serves every backend.

Compile every input against the same `RenderContext` passed to the composition. The returned
`ComposedPlot` retains that context in its `renderPlan`, keeping text measurement, physical gaps,
font resolution, and final device lowering bound to one target.

```scala
val context = RenderContext.unsafe(width = 1200, height = 700)

val first  = firstProgram.resolve(context).orThrow
val second = secondProgram.resolve(context).orThrow

val composed = PlotComposition
  .row(Vector(first, second), context)
  .orThrow

val renderPlan = composed.renderPlan
```

`row`, `column`, and `grid` use row-major order with row zero at the visual top. Their affine cell
transforms map every source panel envelope to the same cell-relative panel rectangle. Axis labels,
titles, or legends may therefore give the source plots different margins without leaving their
composed panels misaligned. `composed.cells` exposes both the whole-plot transform and the resulting
panel frame for inspection.

Composition gaps are physical points. `None` uses `LayoutPolicy.panelGapPt`; explicit gaps are
checked before layout.

```scala
val options = CompositionOptions.unsafe(
  columnGapPt = Some(12.0),
  rowGapPt = Some(16.0),
  cellClip = Clip.On
)

val grid = PlotComposition.grid(plots, columns = 2, context, options).orThrow
```

## Rows of unequal height

Rows split the height equally unless `CompositionOptions.withRowHeights` sizes them. A
`RowHeight.points` row takes a fixed physical height; `RowHeight.weight` rows share what the fixed
rows and gaps leave. Explicitly sized rows reserve only their own plots' top and bottom strips, so a
thin strip without an x axis is not charged its neighbour's axis. Left and right strips stay shared,
so every row's panel has the same horizontal edges. `withSharedXFrame(true)` additionally refuses
plots whose x range or x scale domain differs, so aligned columns also mean the same categories.

```scala mdoc:silent
import intaglio.*

final case class Effect(contrast: String, value: Double)
val effects = Vector("A", "B", "C", "D").zipWithIndex.map((c, i) => Effect(c, i - 1.5))
val stackContext = RenderContext.unsafe(width = 480, height = 600)

def stripPlot(xAxis: Boolean) =
  for
    x <- BandScaleSpec("contrast", Vector("A", "B", "C", "D"), BandPadding.unsafe(0.1))
    y <- ContinuousScaleSpec("effect", Palette.numeric)
    trained <- plot(effects)
      .encode(Aesthetic.X, _.contrast, x)
      .encode(Aesthetic.Y, _.value, y)
      .geomPoint()
      .guides(
        if xAxis then GuidePolicy.Derived()
        else GuidePolicy.Explicit(Vector(GuideSpec.Axis(AxisSide.Left)))
      )
      .resolve(stackContext)
  yield trained

val stacked =
  for
    strip <- stripPlot(xAxis = false)
    matrix <- stripPlot(xAxis = false)
    contrasts <- stripPlot(xAxis = true)
    top <- RowHeight.points(45.0)
    middle <- RowHeight.weight(1.0)
    bottom <- RowHeight.points(90.0)
    composed <- PlotComposition.column(
      Vector(strip, matrix, contrasts),
      stackContext,
      CompositionOptions.default
        .withRowHeights(Vector(top, middle, bottom))
        .withSharedXFrame(true)
    )
  yield composed
```

A wrong number of row heights is `GraphicsError.InvalidCompositionRowHeights`; fixed rows taller
than the composition are a `LayoutOverflow`, as is a row too short for its own plot's strips.

## Collect guides

`CompositionGuidePolicy.CollectCompatible` leaves position axes with their own panels and moves
legends and colorbars into one solver-owned guide column. Guides with the same semantic content,
authored styles, and stable name share one entry; incompatible guides remain separate and retain
stable first-use order. Placement fields are deliberately excluded from compatibility because the
composition's guide-stack solver owns their new location.

Pass the theme used to train the plots when collected guides rely on theme defaults:

```scala
val options = CompositionOptions.unsafe(
  guides = CompositionGuidePolicy.CollectCompatible,
  layoutPolicy = publicationPolicy,
  theme = publicationTheme
)
```

## Add an inset

Inset bounds are explicit whole-composition NPC fractions in the usual y-up coordinate system.
Clipping is a required argument rather than an implicit backend default.

```scala
val inset = PlotInset.npcUnsafe(
  x = 0.62,
  y = 0.58,
  width = 0.32,
  height = 0.34,
  clip = Clip.On
)

val withInset = composed.withInset(detailPlot, inset)
```

The inset plot's semantic sidecar is appended to the composition, and its viewport and clipping
policy survive unchanged into `DeviceScene`.
