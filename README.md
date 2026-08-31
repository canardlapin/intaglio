# Intaglio

A grammar-of-graphics library for Scala 3, cross-compiled to the JVM and
Scala.js.

In intaglio printmaking an image is incised into a plate; the plate is inked
and pressed, and every impression it yields is identical. That is this
library's architecture. One renderer-neutral `Scene` is the plate, and SVG,
Canvas, Java2D, JavaFX, and PDF are impressions of it — held to a shared
conformance contract that proves they agree.

```scala
libraryDependencies += "io.github.canardlapin" %%% "intaglio-core" % "0.1.0"
libraryDependencies += "io.github.canardlapin" %%% "intaglio-svg"  % "0.1.0"
```

```scala
import intaglio.*
import intaglio.svg.*

final case class Point(x: Double, y: Double, condition: String)

val observations = Vector(
  Point(1.0, 1.0, "A"),
  Point(2.0, 1.8, "B"),
  Point(3.0, 2.6, "A")
)

val svg: Either[IntaglioError, String] =
  plot(observations)
    .aes(_.x, _.y)
    .scaleColorDiscrete(_.condition)
    .geomPoint()
    .scene
    .flatMap(SvgRenderer.render(_))
    .map(_.value)
```

Position scales train themselves. When you want a particular one — a log axis,
a fixed domain, a bespoke palette — build it and `encode` it onto an aesthetic;
the plot already fixes the row type, so no scale-binding boilerplate appears:

```scala
val logScaled =
  for
    xScale <- ContinuousScale.train("x", observations.map(_.x), Palette.numeric, Transform.log10)
    scene  <- plot(observations)
                .aes(_.x, _.y)
                .encode(Aesthetic.X, _.x, xScale)
                .geomPoint()
                .scene
  yield scene
```

Typed `CalendarDate`/`UtcDateTime` scales and post-statistical coordinate
windows are covered in the [date/time and zoom guide](docs/date-time-and-zoom.md).
Histogram closure, compensated summaries, KDE normalization and strategy, and
contour topology are specified in the [numerical standards guide](docs/numerical-standards.md).

## Artifacts

| Artifact | JVM | Scala.js | Depends on |
|---|:---:|:---:|---|
| `intaglio-core` | yes | yes | nothing |
| `intaglio-laws` | yes | yes | core |
| `intaglio-svg` | yes | yes | core |
| `intaglio-canvas` | no | yes | core |
| `intaglio-java2d` | yes | no | core |
| `intaglio-pdf` | yes | no | core; Apache PDFBox |
| `intaglio-javafx` | yes | no | core; OpenJFX is `Provided` |

The backends are separately selectable, so a portable consumer never acquires a
platform renderer or toolkit transitively. A boundary test enforces this.

Extension authors can add the framework-neutral law artifact in test scope:

```scala
libraryDependencies +=
  "io.github.canardlapin" %%% "intaglio-laws" % "0.1.0" % Test
```

`ScaleLaws`, `StatLaws`, `GeomLaws`, `CoordLaws`, `PlotRecipeLaws`,
`AestheticLaws`, and `BackendLaws` exercise the public ecosystem seams. Each
returns structured `LawFailure` values, so it works with MUnit, ScalaTest,
Weaver, or a project-specific runner without making one of them transitive.
Complete examples live in [`modules/laws`](modules/laws/README.md).

For print output, `intaglio-pdf` writes PDF directly: page dimensions come from
the render context's pixel size and resolution, supplied fonts are embedded and
subset, vector marks and fill patterns remain vector, and only explicit raster
grobs become image payloads. See the [PDF renderer guide](modules/pdf/README.md).

## What the core owns

- Grammar-of-graphics plot, layer, geom, stat, coordinate, and aesthetic specs,
  with a typed DSL where position mappings change the builder's type — so
  `geomPoint` and `geomLine` are not callable until both `x` and `y` exist.
- Scale transforms with explicit open/closed domains, trained ranges,
  out-of-bounds policies, palettes, breaks, labels, discrete domains, and
  cross-platform date/time domains.
- Immutable scene trees with units, viewports, graphical parameters, and grob
  primitives.
- A device-resolution layer (`DeviceContext`, `DeviceScene`) flattening scenes
  into numeric, y-down primitives any backend can serialize.
- A layout solver (`PlotLayoutSolver`, `LayoutPolicy`, `TextMetrics`) allocating
  named panel, facet-strip, axis-strip, and guide regions.
- Derived discrete legends and continuous colorbars whose ticks follow the
  scale transform, lowered to ordinary portable grobs.
- Typed `Position` values for identity, dodge, stack, and seeded jitter.
- A renderer conformance contract (`RendererConformance`, `RendererHarness`)
  that every backend runs.

## Design commitments

**Single-point resolution.** All unit, orientation, and handedness semantics
resolve once, in `DeviceScene.fromScene`. Backends are dumb y-down
interpreters, and the y-flip exists in exactly one place.

**Determinism as a contract.** Output is byte-identical on the JVM and
Scala.js; `Breaks.pretty` and `Labeler` avoid `log10` and `Double.toString` to
get there. The conformance harness double-renders to check it.

**Typed diagnostics.** A compiled plot is inspectable before it is drawn.
Rows a renderer must skip are reported as `DroppedRow` values carrying a typed
`PlotDropReason`, not discarded silently.

**Illegal states unrepresentable.** Domain scalars are opaque types with smart
constructors, errors are a sealed ADT rather than exceptions or stringly
failures, and public entry points return `Either`. Core validation and each
backend keep their own precise error enum, but all of them extend a shared
`IntaglioError` root, so a pipeline that compiles a `Scene` and then renders it
carries one typed error channel instead of a union of unrelated types.

## Building

```sh
sbt compileAll   # every module, both platforms
sbt testAll      # every module, both platforms
sbt coreJVM/test svgJS/test   # or one at a time
```

Render the visual gallery:

```sh
sbt 'svgJVM / Test / runMain intaglio.svg.GalleryRender target/gallery'
```

Render the paired Intaglio/Java2D and ggplot2 visual QA gallery:

```sh
tools/render_position_adjustment_qa.sh
```

## Status

`0.1.0-SNAPSHOT`, unpublished. The core and all five backends are green on their
supported platforms.

## License

Apache-2.0
