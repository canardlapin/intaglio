# Several plots in one figure

Faceting splits one plot into panels of the same plot. Composition puts *different* plots — different
data, different geoms, different scales — into one figure with their panels aligned. This page is the
route from two plot programs to one rendered figure; the [composition guide](../composition.md)
specifies the mechanism, the alignment contract, and the guide-compatibility rules.

```scala mdoc:silent
import intaglio.*
import intaglio.svg.*

final case class Recording(minute: Double, rate: Double, unit: String)
final case class Tuning(angle: Double, response: Double, unit: String)

val recordings: Vector[Recording] =
  Vector(
    Recording(0.0, 12.0, "u1"),
    Recording(1.0, 18.5, "u1"),
    Recording(2.0, 22.0, "u1"),
    Recording(0.0, 8.0, "u2"),
    Recording(1.0, 11.5, "u2"),
    Recording(2.0, 16.0, "u2")
  )

val tuning: Vector[Tuning] =
  Vector(
    Tuning(0.0, 4.0, "u1"),
    Tuning(45.0, 9.5, "u1"),
    Tuning(90.0, 14.0, "u1"),
    Tuning(0.0, 3.0, "u2"),
    Tuning(45.0, 6.5, "u2"),
    Tuning(90.0, 8.0, "u2")
  )
```

## Compile every input against one context

This is the rule that makes everything else work. A composition aligns panels, measures text, and
resolves physical gaps — all of which need one target. Build a `RenderContext` first, resolve each
plot against it, and let the composition keep it.

```scala mdoc:silent
val context = RenderContext.unsafe(width = 1100, height = 460)

val timeCourse =
  plot(recordings)
    .aes(_.minute, _.rate)
    .group(_.unit)
    .scaleColorDiscrete(_.unit, name = "unit")
    .geomLine()
    .geomPoint()
    .title("Firing rate")
    .axisTitles("Minute", "Spikes/s")
    .theme(Theme.minimal)
    .resolve(context)

val tuningCurve =
  plot(tuning)
    .aes(_.angle, _.response)
    .group(_.unit)
    .scaleColorDiscrete(_.unit, name = "unit")
    .geomLine()
    .title("Orientation tuning")
    .axisTitles("Angle (deg)", "Response")
    .theme(Theme.minimal)
    .resolve(context)
```

`resolve(context)` returns a `TrainedPlot` that carries a solved layout. A plot compiled without a
layout cannot be composed:

```scala mdoc
Plot(recordings)
  .addLayer(Layer.point[Recording](_.minute, _.rate))
  .flatMap(bare => PlotCompiler.resolve(bare))
  .flatMap(trained => PlotComposition.row(Vector(trained), context))
  .left
  .map(_.message)
```

The plotting DSL derives guides, so it always solves a layout. The bare core compiler defaults to
`GuidePolicy.NoGuides`, and then a layout is optional — which is exactly the case above.

## Row, column, grid

```scala mdoc:silent
val figure =
  for
    first <- timeCourse
    second <- tuningCurve
    composed <- PlotComposition.row(Vector(first, second), context)
  yield composed
```

```scala mdoc
figure.map(_.cells.map(cell => (cell.row, cell.column)))
```

`row` and `column` are `grid` with the column count pinned. All three are row-major with row zero at
the visual top, and all three align every source panel to the same cell-relative rectangle — so two
plots whose axis labels have very different widths still get panels that line up. An empty input
vector is `GraphicsError.InvalidCompositionGrid`.

## Render it

`ComposedPlot.renderPlan` pairs the composed scene with the context you supplied, so the figure goes
to any backend the same way a single plot does.

```scala mdoc
figure
  .map(_.renderPlan)
  .flatMap(plan => SvgRenderer.render(plan))
  .map(document => (document.width, document.height))
```

## Gaps, clipping, and one theme

`CompositionOptions` carries the knobs. Gaps are physical points; `None` uses the layout policy's
`panelGapPt`. A non-finite or negative gap is `GraphicsError.InvalidCompositionGap` at construction,
not a silently ignored value.

```scala mdoc:silent
val spaced =
  for
    options <- CompositionOptions(
      layoutPolicy = Theme.minimal.layout,
      theme = Theme.minimal,
      columnGapPt = Some(16.0),
      cellClip = Clip.On
    )
    first <- timeCourse
    second <- tuningCurve
    composed <- PlotComposition.row(Vector(first, second), context, options)
  yield composed
```

Pass the same `theme` you used to train the plots. The composition's own solver needs it whenever a
collected guide relies on theme defaults.

## Collect the shared legend

Two plots that use the same colour scale produce two identical legends. `CollectCompatible` moves
legends and colorbars out of the cells into one composition-owned guide column, and merges guides
with the same semantic content, authored styles, and stable name.

```scala mdoc:silent
val collected =
  for
    options <- CompositionOptions(
      guides = CompositionGuidePolicy.CollectCompatible,
      theme = Theme.minimal
    )
    first <- timeCourse
    second <- tuningCurve
    composed <- PlotComposition.row(Vector(first, second), context, options)
  yield composed
```

```scala mdoc
collected.map(_.collectedGuides.length)
```

Axes stay with their panels — they are position guides and mean different things in different cells.
Incompatible guides stay separate and keep stable first-use order, so collecting never silently
merges two legends that disagree. Placement fields are deliberately excluded from the compatibility
test, because the guide-stack solver owns the new location.

The default is `CompositionGuidePolicy.KeepPerPlot`, which leaves every plot's guides where they are.

## Add an inset

An inset is a whole plot drawn over the composition, positioned in whole-composition NPC fractions
with y increasing upward. Clipping is a required argument — there is no implicit backend default.

```scala mdoc:silent
val withInset =
  for
    composed <- figure
    detail <- plot(recordings)
      .aes(_.minute, _.rate)
      .geomPoint()
      .theme(Theme.minimal)
      .resolve(context)
    bounds <- PlotInset.npc(x = 0.62, y = 0.58, width = 0.32, height = 0.34, clip = Clip.On)
  yield composed.withInset(detail, bounds)
```

```scala mdoc
withInset.map(_.insetCount)
```

Bounds must stay inside the unit square: `x + width <= 1` and `y + height <= 1`, all finite, with
positive extents. Anything else is `GraphicsError.InvalidInsetBounds`.

```scala mdoc
PlotInset.npc(0.8, 0.8, 0.3, 0.3, Clip.On).left.map(_.message)
```

The inset's viewport and clipping policy survive unchanged into `DeviceScene`, its group is named
`composition-inset-<n>`, and its semantic sidecar is appended to the composition — so an accessible
renderer reports the inset as its own plot. `withInset` also accepts a raw `Scene` when the overlay
is not a plot.

## What composition is not

It owns no SVG, Canvas, Java2D, or JavaFX object. It applies checked `Viewport` transforms to
ordinary scene groups, and the usual `Scene` to `DeviceScene` lowering serves every backend. There is
no composition-specific renderer path and no backend feature to enable.

It also does not retrain scales. Two composed plots keep their own scales; if you want them to share
a domain, train them the same way before composing — for instance with `ContinuousScale.fixed` on
both.

## Next

- [Saving output](07-saving-output.md) — writing the composed figure to SVG, PNG, or PDF.
- [The composition guide](../composition.md) — cell transforms, panel envelopes, and the full
  guide-compatibility rules.
