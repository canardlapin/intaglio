# Data in, SVG out

This is the shortest complete path through Intaglio: a case class, a plot program, a `Scene`, an SVG
document, a file. Nothing here is a helper reserved for the tutorial.

## Depend on the core and one backend

`intaglio-core` builds and compiles plots. It draws nothing. Rendering lives in a separate artifact
so a portable consumer never acquires a platform renderer transitively.

```scala
libraryDependencies ++= Seq(
  "io.github.canardlapin" %%% "intaglio-core" % "@VERSION@",
  "io.github.canardlapin" %%% "intaglio-svg" % "@VERSION@"
)
```

Both cross-compile to the JVM and Scala.js, which is why they take `%%%`. The
[backend guide](../backends.md) lists what each artifact can express.

## Your rows are your rows

`plot` takes any `IterableOnce[Row]` and keeps the row type. There is no data frame, no column
registry, and no string-keyed column lookup: an aesthetic is a Scala function from your row.

```scala mdoc:silent
import intaglio.*

final case class Reading(minute: Double, signal: Double, channel: String)

val readings: Vector[Reading] =
  Vector(
    Reading(0.0, 1.20, "left"),
    Reading(1.0, 1.85, "left"),
    Reading(2.0, 2.40, "left"),
    Reading(3.0, 2.15, "left"),
    Reading(0.0, 0.80, "right"),
    Reading(1.0, 1.10, "right"),
    Reading(2.0, 1.75, "right"),
    Reading(3.0, 2.05, "right")
  )
```

## Build the program

```scala mdoc:silent
val program =
  plot(readings)
    .aes(_.minute, _.signal)
    .group(_.channel)
    .scaleColorDiscrete(_.channel, name = "channel")
    .geomPoint()
    .geomLine()
    .title("Signal over time")
    .axisTitles("Minute", "Signal")
    .build
```

`aes(x, y)` changes the builder's type, not just its contents. `geomPoint` and `geomLine` require
evidence that both position aesthetics are mapped, so calling them on an unmapped builder is a
compile error rather than a runtime failure:

```scala mdoc:fail
plot(readings).geomPoint()
```

`build` returns `Either[GraphicsError, PlotProgram[Reading]]`. Checked failures from scales,
coordinates, and layer validation accumulate there; no exception crosses the API boundary.

A `PlotProgram` still holds the renderer-neutral `Plot` and the compiler options, so the concise
surface hides nothing:

```scala mdoc
program.map(_.plot.layers.map(_.geom))
```

## Compile it to a scene

`scene` runs the compiler: mapping resolution, statistics, plot-wide scale training, row evaluation,
position adjustment, geom lowering, layout, guides. The result is a `Scene` — an immutable tree of
renderer-neutral grobs with no SVG, Java2D, or Canvas value anywhere in it.

```scala mdoc:silent
val scene: Either[GraphicsError, Scene] = program.flatMap(_.scene)
```

`resolve` stops one step earlier and hands back a `TrainedPlot`, which is the plot as the compiler
understands it — trained scales, resolved rows, guides, dropped-row diagnostics:

```scala mdoc
program.flatMap(_.resolve).map(_.trainedScales.map(_.aesthetic))
```

## Render and write it

```scala mdoc:silent
import intaglio.svg.*

val document: Either[IntaglioError, SvgDocument] =
  scene.flatMap(compiled => SvgRenderer.render(compiled))
```

The two `Either`s carry different error enums — `GraphicsError` from the compiler and
`SvgRenderError` from the backend — and both extend `IntaglioError`. That shared root is what lets
one `flatMap` chain compile-then-render without a hand-written error union.

`SvgDocument.value` is the markup. The document also reports the target it was serialized for:

```scala mdoc
document.map(svg => (svg.width, svg.height, svg.pixelsPerInch, svg.deviceScale))
```

Writing it is ordinary Java IO. Intaglio performs no file access of its own.

```scala mdoc:compile-only
import java.nio.file.{Files, Path}

document
  .map(svg => Files.write(Path.of("signal.svg"), svg.value.getBytes("UTF-8")))
  .fold(error => sys.error(error.message), path => println(s"wrote $path"))
```

## Where the size came from

`scene` compiles against a default 640 by 480 target at 96 DPI, and `SvgOptions.default` is the same
640 by 480 at 96 DPI. The two agree, which is the only reason the defaults line up.

They stop agreeing as soon as you pick a size. Layout measures text and allocates axis strips,
legend columns, and title bands against a concrete target, so a plot laid out for one size and
serialized at another gets the wrong margins. Bind the target once with a `RenderContext` and carry
it through:

```scala mdoc:silent
val context = RenderContext.unsafe(width = 1000, height = 600, pixelsPerInch = 144.0)

val large: Either[IntaglioError, SvgDocument] =
  program.flatMap(_.renderPlan(context)).flatMap(plan => SvgRenderer.render(plan))
```

`renderPlan` returns a `RenderPlan`, which is the pair of a `Scene` and the exact `RenderContext`
that produced it. `SvgRenderer.render(plan)` takes its width, height, DPI, and device scale from that
context instead of a second options value, so the two cannot drift.

Prefer the `RenderPlan` overloads in anything you intend to keep. The `(Scene, SvgOptions)` overloads
remain, but they build a *second* `RenderContext` from the options — 96 DPI and device scale 1 unless
you say otherwise — so keeping it in step with the target the plot was laid out for is on you.

## Next

- [Scales and guides](02-scales-and-guides.md) — domains, transforms, limits, legends, colorbars.
- [Statistics](03-statistics.md) — histograms, densities, summaries, ECDFs.
- [Saving output](07-saving-output.md) — PNG, PDF, notebooks, and the device-scale story for each.
