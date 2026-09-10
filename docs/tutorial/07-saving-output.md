# Saving output

Every output route in Intaglio starts from the same place: a `RenderContext` that says what the
target is, and a `RenderPlan` that binds a compiled `Scene` to it. What differs is the last step.

```scala mdoc:silent
import intaglio.*

final case class Yield(hours: Double, grams: Double, batch: String)

val batches: Vector[Yield] =
  Vector(
    Yield(1.0, 2.1, "a"),
    Yield(2.0, 3.4, "a"),
    Yield(3.0, 4.0, "a"),
    Yield(1.0, 1.7, "b"),
    Yield(2.0, 2.9, "b"),
    Yield(3.0, 3.6, "b")
  )

val program =
  plot(batches)
    .aes(_.hours, _.grams)
    .group(_.batch)
    .scaleColorDiscrete(_.batch, name = "batch")
    .geomLine()
    .geomPoint()
    .title("Yield over time")
    .axisTitles("Hours", "Grams")
    .theme(Theme.minimal)
```

## Pick the target first

`RenderContext` fixes width, height, DPI, device scale, text metrics, and font resolution *before*
layout runs. Three numbers determine the physical result:

| Field | Meaning |
|---|---|
| `width`, `height` | **actual device pixels** in the output |
| `pixelsPerInch` | how many of those pixels make one inch — physical size is `width / pixelsPerInch` inches |
| `deviceScale` | how many device pixels make one logical (CSS) pixel; `logicalWidth = width / deviceScale` |

```scala mdoc:silent
val screen = RenderContext.unsafe(width = 900, height = 560)
val page = RenderContext.unsafe(width = 1800, height = 1200, pixelsPerInch = 300.0)
val retina = RenderContext.hidpiUnsafe(logicalWidth = 900, logicalHeight = 560, devicePixelRatio = 2.0)
```

```scala mdoc
(retina.width, retina.height, retina.pixelsPerInch, retina.deviceScale, retina.logicalWidth)
```

`hidpi` is the constructor to prefer when you know the logical size and the ratio: it multiplies
pixels *and* DPI by the ratio together, which is what preserves physical typography. A 10 pt axis
label stays 10 pt. Doubling `pixelsPerInch` on its own instead makes a physically larger figure at
the same pixel count.

Everything below uses `program.renderPlan(context)`.

```scala mdoc:silent
val plan = program.renderPlan(screen)
```

## SVG string

```scala mdoc:silent
import intaglio.svg.*

val document: Either[IntaglioError, SvgDocument] =
  plan.flatMap(target => SvgRenderer.render(target))
```

```scala mdoc
document.map(svg => (svg.width, svg.height, svg.logicalWidth, svg.logicalHeight))
```

`SvgDocument.value` is the markup, and `toString` returns it too. The document also reports the
target it was serialized for.

One thing to know before you embed it: the root element's `width`, `height`, and `viewBox` all carry
the **device** pixel size. `deviceScale` governs how typographic points become device pixels and is
reported on the document; it does not shrink the emitted attributes. To show a 2× document at its
logical size, set the CSS width and height yourself from `logicalWidth` and `logicalHeight`.

`SvgRenderer.render(plan, title)` sets the accessible document title. Rendering a compiled plot also
emits a root `id`, `role="img"`, and ARIA `labelledby`/`describedby` — see
[accessibility](../accessibility.md).

## SVG file

Intaglio performs no file access. Writing is ordinary Java IO, and the encoding is yours to choose:

```scala mdoc:compile-only
import java.nio.file.{Files, Path}

document
  .map(svg => Files.write(Path.of("yield.svg"), svg.value.getBytes("UTF-8")))
  .fold(error => sys.error(error.message), path => println(s"wrote $path"))
```

The bytes are deterministic. The same plan renders byte-identically on the JVM and Scala.js, and the
conformance harness double-renders to check it — which is what makes an SVG file usable as a
regression fixture.

## Java2D: `BufferedImage` and PNG

```scala mdoc:compile-only
import intaglio.java2d.*

val image = plan.flatMap(target => Java2DRenderer.renderImage(target, Java2DExportOptions.default))
val png = plan.flatMap(target => Java2DRenderer.renderPng(target, Java2DExportOptions.default))
```

The `BufferedImage` is `TYPE_INT_ARGB` at exactly `context.width` by `context.height` device pixels.
`renderPng` encodes those same ARGB pixels. Use `renderImage` when the image is going into a Swing or
JavaFX surface; use `renderPng` when it is going to disk or over a wire.

`Java2DExportOptions` makes the two raster decisions explicit:

- `background` is `Java2DBackground.Transparent` (the default) or `Java2DBackground.Solid(color)`.
  Transparent is right for compositing; solid is right for anything that will be pasted onto white.
- `renderingHints` sets geometry and text antialiasing independently, each
  `Java2DAntialiasing.Enabled` or `Disabled`. Both default to enabled. Disabling both is what makes a
  raster reproducible enough to pin as a golden image.

```scala mdoc:compile-only
import java.nio.file.{Files, Path}
import intaglio.java2d.*

plan
  .flatMap(target => Java2DRenderer.renderPng(target, Java2DExportOptions.default))
  .map(bytes => Files.write(Path.of("yield.png"), bytes))
```

### Fonts

By default Java2D resolves families against the host `GraphicsEnvironment`, which means the same
program can produce different pixels on different machines. Two independent controls fix that.

`Java2DTextMetrics` makes *layout* use the installed font environment, and exposes a matching
registry so measurement and drawing agree:

```scala mdoc:compile-only
import intaglio.java2d.*

val metrics = Java2DTextMetrics("SansSerif")
val bound = RenderContext.unsafe(
  width = 1200,
  height = 800,
  pixelsPerInch = 144.0,
  textMetrics = metrics,
  fontRegistry = metrics.fontRegistry
)
```

`Java2DFontResolver.fixed(font)` makes *drawing* use supplied AWT font bytes and ignore the host
registry entirely. It is available only on the three-argument `RenderPlan` overloads of `renderImage`
and `renderPng` — the `Scene`-taking overloads hardwire `Java2DFontResolver.system`.

```scala mdoc:compile-only
import java.awt.Font
import intaglio.java2d.*

def reproducible(font: Font) =
  plan.flatMap(target =>
    Java2DRenderer.renderPng(
      target,
      Java2DExportOptions(
        background = Java2DBackground.Solid(Rgba.White),
        renderingHints = Java2DRenderingHints(
          geometry = Java2DAntialiasing.Disabled,
          text = Java2DAntialiasing.Disabled
        )
      ),
      Java2DFontResolver.fixed(font)
    )
  )
```

That combination — pinned font bytes, disabled antialiasing, solid background — is exactly what
Intaglio's own perceptual golden court uses. See the
[visual regression guide](../visual-regression.md).

## PDF

`intaglio-pdf` writes a one-page PDF directly from the shared `DeviceScene`. There is no converter
and no headless browser, and `PdfRenderer.render` takes a `RenderPlan` and nothing else.

```scala mdoc:compile-only
import java.nio.file.Path
import intaglio.pdf.*

val figure =
  for
    font <- PdfFont.load("Source Sans 3", Path.of("fonts/SourceSans3-Regular.ttf"))
    catalog = PdfFontCatalog.single(font)
    context <- RenderContext(
      width = 1800,
      height = 1200,
      pixelsPerInch = 300.0,
      fontRegistry = catalog.fontRegistry
    )
    target <- program.renderPlan(context)
    document <- PdfRenderer.render(target, catalog, PdfOptions(title = Some("Figure 1")))
    path <- document.writeTo(Path.of("figure-1.pdf"))
  yield path
```

**The page size is the context.** PDF points are `devicePixels * 72 / pixelsPerInch`, so 1800 by 1200
at 300 DPI is an exact 432 by 288 point MediaBox — 6 by 4 inches. There is no page-size argument, and
a HiDPI context gives the same physical page because pixels and DPI scale together. A side over
14400 points is `PdfRenderError.InvalidPageSize`.

**Text is fail-closed.** You supply embeddable font bytes through a `PdfFontCatalog`; families match
case-insensitively, an absent requested family uses the catalog default, and an explicitly requested
*unknown* family does not fall back. A missing Unicode glyph is `PdfRenderError.UnsupportedGlyph`.
`PdfFont.fromBytes(family, bytes)` is the in-memory alternative to `PdfFont.load`.

**Vectors stay vectors.** `PdfRasterPolicy.ExplicitImagesOnly` is the fixed and only policy: marks,
text, clips, rotations, and hatch/stipple fills become native PDF operators, patterns become tiling
resources containing paths, and only an explicit `RasterImage` becomes an image XObject.

`PdfDocument.bytes` gives you the encoded document, `writeTo(path)` writes it and returns the path,
and `profile` counts vector shapes, text runs, image placements, unique raster payloads, vector
patterns, and embedded subset fonts. Repeated exports are byte-identical: the trailer ID is derived
from the content.

## Canvas (Scala.js)

`intaglio-canvas` is Scala.js only and is not on this documentation's classpath, so there is no
compiled example here. The shape of it:

`CanvasRenderer.compile(plan)` produces a `CanvasProgram` — a deterministic, inspectable command
vector — and `CanvasRenderer.render(plan, context2d)` compiles and draws in one step. Drawing methods
take a `using CanvasRasterFactory`; `CanvasRasterFactory.browser` is the default given, and an
OffscreenCanvas or test environment supplies its own.

Canvas is the one backend whose Options type carries HiDPI directly:

```scala
val options = CanvasOptions.hidpiUnsafe(
  logicalWidth = 640,
  logicalHeight = 480,
  devicePixelRatio = 2.0
)
// width = 1280, height = 960, pixelsPerInch = 192, deviceScale = 2
```

Set the `<canvas>` element's `width`/`height` attributes to the backing pixels
(`options.width`, `options.height`) and its CSS size to `options.logicalWidth` and
`options.logicalHeight`. `CanvasOptions.hidpi` preserves the exact requested logical size even when a
fractional ratio forces integer backing-pixel rounding, which the plain constructors cannot do.

Two optional pieces: `CanvasRenderer.drawCached(program, context2d, cache)` retains browser-native
image sources across draws by raster identity, reporting hits, misses, and uploaded bytes through
`CanvasDrawProfile`; and `CanvasTextMetrics(context2d, fallbackFamily, familyAvailable)` is an opt-in
`TextMetrics` provider backed by `measureText`, injected through `LayoutPolicy(metrics = ...)`.
Portable output keeps `TextMetrics.estimate` unless you opt in.

## Notebook MIME bundle

```scala mdoc:silent
import intaglio.notebook.*

val bundle =
  program.build.map { built =>
    NotebookRenderer.displayPlot(
      built.plot,
      NotebookOptions.unsafe(width = 960, height = 540, pixelsPerInch = 144.0),
      built.compilerOptions
    )
  }
```

```scala mdoc
bundle.map(_.data.keys.toVector.sorted)
```

`NotebookMimeBundle` is a `data` map and a `metadata` map, in Jupyter's shape. Hand both to your
kernel's display API. Pass your program's `compilerOptions` — the adapter's default is
`PlotCompilerOptions.lean`, whose guide policy is `NoGuides`. Details, including the device-scale
metadata a frontend reads, are in the [notebook guide](../notebooks.md).

## Choosing a route

| You want | Route | Why |
|---|---|---|
| a figure in a web page or an SVG editor | `SvgRenderer` | text stays text; per-grob `GrobMeta` survives |
| a figure in a manuscript | `intaglio-pdf` | true vector, embedded subset fonts, exact page size |
| a bitmap for a slide or a README | `Java2DRenderer.renderPng` | one file, no font dependency at view time |
| a golden test fixture | SVG string, or Java2D PNG with pinned fonts | both are byte-deterministic |
| an interactive browser view | `intaglio-canvas` | draws into a live context, no DOM per mark |
| a notebook cell | `intaglio-notebook` | MIME bundle with a text fallback |

The capability differences behind that table — what each backend can and cannot express — are in the
[backend guide](../backends.md), and the size and scaling limits are in [limits](../limits.md).
