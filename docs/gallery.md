# Gallery

Every plate below is rendered by the code shown above it. `intaglio.docs.Gallery` compiles the
program, renders it through `intaglio-svg`, and writes `docs/gallery/<plate>.svg` beside this page.
`tools/check-docs.sh` re-renders all of them and fails if a checked-in file differs from what the
current library produces — so a stale image is a build failure, not a documentation bug.

The plates share one fixture and one set of compiler options.

```scala mdoc:silent
import intaglio.*
import intaglio.docs.Gallery

final case class Reading(session: Double, score: Double, group: String)
final case class Series(minute: Double, level: Double, channel: String)
final case class Envelope(minute: Double, mean: Double, lower: Double, upper: Double)

val readings: Vector[Reading] =
  Vector("control", "treated").flatMap { group =>
    val offset = if group == "treated" then 1.6 else 0.0
    Vector.tabulate(24) { index =>
      val session = (index % 6).toDouble + 1.0
      val wobble = ((index * 37) % 11).toDouble / 11.0
      Reading(session, 2.0 + session * 0.45 + offset + wobble, group)
    }
  }

val control: Vector[Reading] = readings.filter(_.group == "control")

val series: Vector[Series] =
  Vector("left", "right").flatMap { channel =>
    val gain = if channel == "left" then 1.0 else 0.65
    Vector.tabulate(13) { index =>
      val minute = index.toDouble
      Series(minute, 4.0 + gain * minute * 0.7 - minute * minute * 0.02, channel)
    }
  }

val envelopes: Vector[Envelope] =
  Vector.tabulate(13) { index =>
    val minute = index.toDouble
    val mean = 4.0 + minute * 0.7 - minute * minute * 0.02
    val halfWidth = 0.5 + minute * 0.07
    Envelope(minute, mean, mean - halfWidth, mean + halfWidth)
  }

val plateContext = RenderContext.unsafe(width = Gallery.width, height = Gallery.height)

val plateOptions = PlotCompilerOptions(
  guides = GuidePolicy.Derived(),
  theme = Theme.minimal,
  renderContext = Some(plateContext)
)
```

`plateOptions` carries the render context, so layout is solved for exactly the canvas the plate is
serialized onto. That is the general rule from [saving output](tutorial/07-saving-output.md), applied
here.

## Scatter with a discrete colour scale

```scala mdoc:silent
val scatter =
  plot(readings)
    .aes(_.session, _.score)
    .scaleColorDiscrete(_.group, levels = Vector("control", "treated"), name = "group")
    .geomPoint()
    .title("Score by session")
    .axisTitles("Session", "Score")
    .compilerOptions(plateOptions)
    .build
```

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("scatter-by-condition", scatter))
```

## Lines, one path per group

```scala mdoc:silent
val lines =
  plot(series)
    .aes(_.minute, _.level)
    .group(_.channel)
    .scaleColorDiscrete(_.channel, levels = Vector("left", "right"), name = "channel")
    .geomLine()
    .geomPoint()
    .title("Level over time")
    .axisTitles("Minute", "Level")
    .compilerOptions(plateOptions)
    .build
```

`Geom.Line` connects rows in encounter order within each group and never sorts by x. Sort your rows
if you need a sorted path.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("line-series", lines))
```

## Histogram

```scala mdoc:silent
val histogram =
  plot(readings)
    .aes(_.score)
    .geomHistogram(
      HistogramBins.countUnsafe(12),
      params = Some(
        GraphicParams.unsafe(stroke = Some(Rgba.White), fill = Some(Rgba.unsafe(70, 110, 170)))
      )
    )
    .title("Score distribution")
    .axisTitles("Score", "Count")
    .compilerOptions(plateOptions)
    .build
```

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("histogram-bins", histogram))
```

## Kernel density

```scala mdoc:silent
val density =
  plot(readings)
    .aes(_.score)
    .geomDensity(
      params = Some(
        GraphicParams.unsafe(stroke = Some(Rgba.unsafe(28, 40, 66)))
          .withStrokeWidth(StrokeWidth.pointsUnsafe(1.5))
      )
    )
    .title("Score density")
    .axisTitles("Score", "Density")
    .compilerOptions(plateOptions)
    .build
```

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("density-curve", density))
```

## Mean and standard error

```scala mdoc:silent
val summary =
  plot(control)
    .aes(_.session, _.score)
    .geomSummary(SummaryInterval.StandardError)
    .title("Mean and standard error")
    .axisTitles("Session", "Score")
    .compilerOptions(plateOptions)
    .build
```

Each mark is a group mean; each segment spans one standard error either side. The y scale trains on
the bounds, so an interval is never clipped by its own panel.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("summary-intervals", summary))
```

## Ribbon with a fitted line

```scala mdoc:silent
val ribbon =
  plot(envelopes)
    .aes(_.minute, _.mean)
    .geomRibbon(
      _.lower,
      _.upper,
      params = Some(
        GraphicParams.unsafe(
          stroke = None,
          fill = Some(Rgba.unsafe(120, 150, 200)),
          alpha = 0.45
        )
      )
    )
    .geomLine(
      params = Some(
        GraphicParams.unsafe(stroke = Some(Rgba.unsafe(28, 40, 66)))
          .withStrokeWidth(StrokeWidth.pointsUnsafe(1.5))
      )
    )
    .title("Interval and centre")
    .axisTitles("Minute", "Level")
    .compilerOptions(plateOptions)
    .build
```

`geomRibbon` takes `yMin` and `yMax` accessors and derives its `y` as the midpoint. It is an identity
layer, not a statistic — the interval comes from your data.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("ribbon-band", ribbon))
```

## Heatmap over a scalar field

```scala mdoc:silent
val heatmap =
  for
    xAxis <- RegularGridAxis.cellCentered(0.0, 6.0, 24)
    yAxis <- RegularGridAxis.cellCentered(0.0, 4.0, 16)
    field <- ScalarField2D.tabulate(xAxis, yAxis) { (x, y) =>
      (x - 3.0) * (x - 3.0) * 0.2 + (y - 2.0) * (y - 2.0) * 0.4
    }
    program <- plot(field)
      .geomHeatmap(name = "value")
      .title("Scalar field")
      .axisTitles("x", "y")
      .compilerOptions(plateOptions)
      .build
  yield program
```

`plot(field)` is field-native: coordinates, tile extents, the continuous fill scale, and the colorbar
all come from the checked `ScalarField2D`, with no untyped `z` aesthetic.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("heatmap-field", heatmap))
```

## Contour lines

```scala mdoc:silent
val contours =
  for
    axis <- RegularGridAxis.vertexCentered(-2.0, 2.0, 41)
    field <- ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y * y)
    levels <- ContourLevels.at(Vector(0.5, 1.0, 2.0, 3.0))
    extracted <- ContourSet.extract(field, levels)
    program <- plot(extracted)
      .geomContour(
        params = Some(
          GraphicParams.unsafe(stroke = Some(Rgba.unsafe(28, 40, 66)))
            .withStrokeWidth(StrokeWidth.pointsUnsafe(1.0))
        )
      )
      .title("Level sets of x^2 + y^2")
      .axisTitles("x", "y")
      .compilerOptions(plateOptions)
      .build
  yield program
```

Contours are extracted before plotting, by deterministic marching squares with a bilinear asymptotic
decider for ambiguous saddles. `plot(contours)` then draws them through the ordinary grouped-line
grammar.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("contour-lines", contours))
```

## Faceted panels

```scala mdoc:silent
val facets =
  plot(readings)
    .aes(_.session, _.score)
    .facetWrap(_.group, columns = 2, levels = Vector("control", "treated"))
    .geomPoint()
    .title("Score by session and group")
    .axisTitles("Session", "Score")
    .compilerOptions(plateOptions)
    .build
```

Panels are named `panel-0-0` and `panel-0-1`, their strips `strip-0-0` and `strip-0-1`. Position
scales are shared by default; colour and fill stay plot-global under every `FacetScales` policy.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plot("facet-panels", facets))
```

## Two plots, one figure

```scala mdoc:silent
val composed =
  for
    left <- plot(readings)
      .aes(_.session, _.score)
      .scaleColorDiscrete(_.group, levels = Vector("control", "treated"), name = "group")
      .geomPoint()
      .axisTitles("Session", "Score")
      .compilerOptions(plateOptions)
      .resolve(plateContext)
    right <- plot(control)
      .aes(_.session, _.score)
      .geomSummary(SummaryInterval.StandardError)
      .axisTitles("Session", "Mean")
      .compilerOptions(plateOptions)
      .resolve(plateContext)
    options <- CompositionOptions(
      guides = CompositionGuidePolicy.CollectCompatible,
      layoutPolicy = Theme.minimal.layout,
      theme = Theme.minimal,
      columnGapPt = Some(14.0)
    )
    figure <- PlotComposition.row(Vector(left, right), plateContext, options)
  yield figure.scene
```

Both plots are resolved against the same `RenderContext`, which is what lets the composition align
their panels despite different axis-label widths. `CollectCompatible` moves the legend into one
composition-owned guide column; axes stay with their panels.

```scala mdoc:passthrough
println(intaglio.docs.Gallery.plate("composed-figure", composed))
```

## Reading the plates

Every plate is an SVG file, so it is text and it diffs. That is deliberate: the gallery doubles as a
rendering regression court. If you change how a mark, a scale, or the layout solver behaves, the
plate that shows it will change in the diff.

The programs above use only the public API. Nothing in this page reaches into a backend, and the
`Gallery` helper itself is documentation tooling, not part of Intaglio's published surface.
