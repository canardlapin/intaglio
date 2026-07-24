# Intaglio

A grammar-of-graphics library for Scala 3, cross-compiled to the JVM and
Scala.js.

In intaglio printmaking an image is incised into a plate; the plate is inked
and pressed, and every impression it yields is identical. That is this
library's architecture. One renderer-neutral `Scene` is the plate, and SVG,
Canvas, Java2D, and JavaFX are impressions of it — held to a shared conformance
contract that proves they agree.

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

## Artifacts

| Artifact | JVM | Scala.js | Depends on |
|---|:---:|:---:|---|
| `intaglio-core` | yes | yes | nothing |
| `intaglio-svg` | yes | yes | core |
| `intaglio-canvas` | no | yes | core |
| `intaglio-java2d` | yes | no | core |
| `intaglio-javafx` | yes | no | core; OpenJFX is `Provided` |

The backends are separately selectable, so a portable consumer never acquires a
platform renderer or toolkit transitively. A boundary test enforces this.

## What the core owns

- Grammar-of-graphics plot, layer, geom, stat, coordinate, and aesthetic specs,
  with a typed DSL where position mappings change the builder's type — so
  `geomPoint` and `geomLine` are not callable until both `x` and `y` exist.
- Scale transforms with explicit open/closed domains, trained ranges,
  out-of-bounds policies, palettes, breaks, labels, and discrete domains.
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
sbt testAll      # 511 tests
sbt coreJVM/test svgJS/test   # or one at a time
```

Render the visual gallery:

```sh
sbt 'svgJVM / Test / runMain intaglio.svg.GalleryRender target/gallery'
```

## Status

`0.1.0-SNAPSHOT`, unpublished. The core and all four backends are green on both
platforms. The library began inside a neuroimaging system and was extracted once
it outgrew it; neuroimaging is one consumer, not the design center.

## License

Apache-2.0
