# Backends

Intaglio compiles a plot once, into a renderer-neutral `Scene`. A backend serializes or draws that
scene and nothing else: no backend contains plot, scale, statistic, guide, or layout semantics. Every
backend consumes the same `DeviceScene` — the numeric, y-down flattening produced by
`DeviceScene.fromScene` — so the y-flip, unit resolution, and font resolution happen once, in core.

Five of the six entries below are renderers. The sixth, `intaglio-notebook`, is an adapter over the
SVG renderer; it is listed here because it is how most people get a plot onto a screen.

## The table

| | `intaglio-svg` | `intaglio-canvas` | `intaglio-java2d` | `intaglio-pdf` | `intaglio-javafx` | `intaglio-notebook` |
|---|---|---|---|---|---|---|
| Platform | JVM + Scala.js | Scala.js | JVM | JVM | JVM | JVM |
| Extra dependency | none | none | none | Apache PDFBox | OpenJFX (`Provided`) | `intaglio-svg` |
| Entry object | `SvgRenderer` | `CanvasRenderer` | `Java2DRenderer` | `PdfRenderer` | `JavaFxRenderer` | `NotebookRenderer` |
| Output | `SvgDocument` (a `String`) | draws into a `CanvasRenderingContext2D` | `BufferedImage`, PNG `Array[Byte]`, or draws into a `Graphics2D` | `PdfDocument` (bytes, one page) | draws into a `JavaFxGraphicsContext` | `NotebookMimeBundle` |
| Accepts a bare `Scene` | yes | yes | yes | **no** | yes | yes |
| Accepts a `RenderPlan` | yes | yes | yes | yes | yes | yes (`displayPlan`) |
| Inspectable command program | no | `CanvasProgram` | `Java2DProgram` | no | `JavaFxProgram` | no |
| Error enum | `SvgRenderError` | `CanvasRenderError` | `Java2DRenderError` | `PdfRenderError` | `JavaFxRenderError` | `NotebookRenderError` |

Every one of those error enums extends `IntaglioError`, and every *renderer*'s enum has a
`Graphics(error: GraphicsError)` case — the notebook adapter names its equivalent
`Compiler(error: GraphicsError)`. A compile-then-render pipeline therefore needs one `Either` channel
rather than a hand-written union.
Each companion also provides an `orThrow` extension on its own `Either`.

## What every backend can express

These are not per-backend features. They are properties of the shared scene, and the renderer
conformance contract (`RendererConformance`, `RendererHarness`) makes each backend prove it draws
them the same way.

- **Primitives**: point batches, polylines, segments, polygons, compound polygons with holes,
  rectangles, circles, text, raster images, and groups.
- **Style**: stroke, solid fill, alpha, line width (device pixels or typographic points), line type,
  line cap, line join, font family, font size.
- **Pattern fills**: angled hatch, cross hatch, parallel rules, and stipple, from the same
  `PatternRecipe` and the same deterministic tile generator. SVG emits `<pattern>` resources and PDF
  emits tiling patterns containing paths — both stay vector. Java2D, Canvas, and JavaFX install the
  shared RGBA tile as a native repeating paint. Every one anchors the tile at device `(0, 0)`,
  follows enclosing transforms, and applies the mark's alpha once to the composited result.
- **Raster images**: top-row-first ARGB pixels with explicit nearest or smooth interpolation and
  grob-level alpha. SVG embeds a deterministic base64 PNG; PDF writes a lossless image XObject;
  the three live-context backends upload a cached native image.
- **Rounded rectangle corners**: `Grob.rect` takes a `cornerRadius: ExtentExpr`. SVG emits `rx`/`ry`,
  Java2D builds a `RoundRectangle2D` (doubling the radius into AWT's arc width and height, which AWT
  halves back), PDF emits four Bezier
  corners, and Canvas and JavaFX use the same four-`arcTo` recipe. The circular corners agree across
  all five.
- **Clipping and rotation**: resolved device-space groups carry clip rectangles and rotations.

## What only SVG can express

**Per-grob `GrobMeta` reaches SVG only.** `Grob.annotated(child, meta)` attaches an optional title,
description, CSS class, and ordered `data-*` pairs to a single mark. The SVG renderer emits that as a
wrapping group:

```svg
<g class="mark decode-filled" data-kind="anchor">
  <title>Recall unit 7</title>
  <desc>Decode-filled anchor, mass 0.42</desc>
  <circle data-name="unit-7" ... />
</g>
```

Canvas, Java2D, PDF, and JavaFX match `DeviceElement.Annotated(_, children)` and draw the children.
They emit no wrapper, no group, and no save/restore, so their command streams are identical whether
or not the scene carries annotations. They also perform no validation: an XML-illegal character in a
title, or a repeated `DataKey`, is a typed `SvgRenderError` at the SVG boundary and simply invisible
everywhere else. `GrobMeta.duplicateDataKey` is the pre-check if you want to catch it earlier.

Document-level accessibility is broader. `SvgRenderer` emits a root `id`, `role="img"`, ARIA
`labelledby`/`describedby`, and escaped `<title>`/`<desc>`; `PdfRenderer` maps the scene's accessible
title and description onto the PDF's Title and Subject metadata. The rest is in the
[accessibility guide](accessibility.md).

## What each backend cannot do

**SVG** has no `compile` step and no command program to inspect — it goes straight to markup. It has
no `hidpi` constructor on `SvgOptions`; reach HiDPI through `RenderContext.hidpi` and a `RenderPlan`.
Unsupported units, oversized device attributes, and XML-illegal text are `SvgRenderError` values
rather than silently repaired markup.

**Canvas** is Scala.js only and is not on this documentation's classpath, so no example on this site
is compiled against it. It draws into a live `CanvasRenderingContext2D`, so it produces no artifact
you can write to disk. Raster and pattern uploads need a `CanvasRasterFactory`, supplied as a
contextual capability (`CanvasRasterFactory.browser` is the default given); a test or OffscreenCanvas
environment provides its own. A pattern tile axis is bounded at 1024 device pixels, and a failed
native `createPattern` is a typed `CanvasRenderError.PatternResourceFailure` — never a silent fall
back to a solid fill. It is the only backend whose Options type has `hidpi`/`hidpiUnsafe`.

**Java2D** has no document type. `renderImage` returns a `BufferedImage` and `renderPng` returns PNG
bytes; `render` draws into a `Graphics2D` you own. The `Scene`-taking `renderImage`/`renderPng`
overloads hardwire `Java2DFontResolver.system` — only the `RenderPlan` three-argument forms accept a
resolver, which is what `Java2DFontResolver.fixed(font)` is for when output must not depend on
installed fonts.

**PDF** takes a `RenderPlan` and nothing else. There is no `Scene` overload, because page geometry
and font resolution have to come from the bound context: PDF points are
`devicePixels * 72 / pixelsPerInch`, so the page size *is* the context. Text is fail-closed — you
supply embeddable font bytes through a `PdfFontCatalog`, an unknown requested family does not fall
back, and a missing Unicode glyph is `PdfRenderError.UnsupportedGlyph`. `PdfRasterPolicy` has exactly
one case, `ExplicitImagesOnly`: the renderer never rasterizes a page, mark, text run, or pattern.

**JavaFX** produces no artifact either — it drives the toolkit-free `JavaFxGraphicsContext` trait,
which `JavaFxCanvasContext` adapts onto a live `javafx.scene.canvas.GraphicsContext`. Compilation is
pure and thread-free; drawing must happen on the FX application thread. OpenJFX is a `Provided`
dependency, so the host application supplies its own platform runtime.

**Notebook** is not a renderer. It calls `SvgRenderer`, so it inherits SVG's capabilities exactly, and
it adds a MIME bundle, a plain-text fallback, and a total `display*` family that converts a checked
failure into something displayable rather than returning a `Left`. See [notebooks](notebooks.md).

## Text measurement

Layout has to know how wide a tick label is before any backend runs. The portable default is
`TextMetrics.estimate`, which is deterministic and identical on the JVM and Scala.js — that is what
makes byte-identical cross-platform output possible.

Two backends offer platform providers, and both are opt-in:

```scala
LayoutPolicy(metrics = Java2DTextMetrics())          // JVM, installed AWT fonts
LayoutPolicy(metrics = CanvasTextMetrics(context))   // browser, measureText
```

Installed fonts never affect shared output implicitly. `Java2DTextMetrics` also exposes a matching
`fontRegistry`, so layout and rendering resolve the same families:

```scala mdoc:compile-only
import intaglio.*
import intaglio.java2d.*

val metrics = Java2DTextMetrics("SansSerif")
val context = RenderContext.unsafe(
  width = 1200,
  height = 800,
  pixelsPerInch = 144.0,
  textMetrics = metrics,
  fontRegistry = metrics.fontRegistry
)
```

A JavaFX application that wants installed-font-aware layout can depend on `intaglio-java2d` for
`Java2DTextMetrics` alone; the JavaFX renderer does not change the shared default.

## One plan, several backends

Because layout is bound to a `RenderContext` rather than to a renderer, the same `RenderPlan` feeds
every backend at the same physical size.

```scala mdoc:silent
import intaglio.*
import intaglio.svg.*

final case class Sample(dose: Double, response: Double)

val samples: Vector[Sample] =
  Vector(Sample(1.0, 0.4), Sample(2.0, 1.1), Sample(3.0, 1.9), Sample(4.0, 2.2))

val deviceTarget = RenderContext.unsafe(width = 900, height = 600, pixelsPerInch = 144.0)

val plan =
  plot(samples)
    .aes(_.dose, _.response)
    .geomLine()
    .geomPoint()
    .axisTitles("Dose", "Response")
    .renderPlan(deviceTarget)
```

```scala mdoc
plan.flatMap(bound => SvgRenderer.render(bound)).map(_.value.take(60))
```

```scala mdoc:compile-only
import intaglio.java2d.*

val png: Either[IntaglioError, Array[Byte]] =
  plan.flatMap(bound => Java2DRenderer.renderPng(bound, Java2DExportOptions.default))
```

`Java2DExportOptions` makes the two choices a raster export has to make explicit:
`background` is `Java2DBackground.Transparent` or `Solid(color)`, and `renderingHints` sets geometry
and text antialiasing independently. PNG export preserves the ARGB pixels `renderImage` produced.

## Conformance

A backend is not a matter of taste. Each one implements `RendererHarness` and runs
`RendererConformance.check` in its own test suite, against the same primitive, style, text-placement,
raster, clipping, rotation, and annotation cases. The harness double-renders to check determinism.
`intaglio-svg` inspects serialized markup, `intaglio-canvas` inspects recorded commands,
`intaglio-java2d` adds `BufferedImage` pixel assertions, `intaglio-pdf` reopens its own bytes with
PDFBox, and `intaglio-javafx` runs the full interpreter against a recording implementation of the
drawing contract without starting the toolkit.

`CanvasProgram.validate`, `Java2DProgram.validate`, and `JavaFxProgram.validate` return
`Option[String]` — `Some(problem)` for unbalanced save/restore or a non-finite number. They are
cheap assertions for anyone extending or embedding a command stream.

Per-backend detail lives beside each module: [SVG](../modules/svg/README.md),
[Canvas](../modules/canvas/README.md), [Java2D](../modules/java2d/README.md),
[PDF](../modules/pdf/README.md), [JavaFX](../modules/javafx/README.md),
[notebook](../modules/notebook/README.md).
