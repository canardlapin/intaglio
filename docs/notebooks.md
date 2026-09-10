# Notebooks and publication output

`intaglio-notebook` is a JVM adapter that turns a plot into a Jupyter MIME bundle. It depends on
`intaglio-core` and `intaglio-svg`; neither of those depends on a kernel, and the adapter depends on
no kernel either. There is no Almond, Jupyter, or frontend artifact on its classpath, and it reads
and mutates no global notebook state.

```scala
libraryDependencies += "io.github.canardlapin" %% "intaglio-notebook" % "@VERSION@"
```

Single `%%`: the adapter is JVM-only.

## The bundle

`NotebookMimeBundle` is the whole protocol surface — two maps and three accessors.

```scala mdoc:silent
import intaglio.*
import intaglio.notebook.*

final case class Trial(block: Double, latency: Double, condition: String)

val trials: Vector[Trial] =
  Vector(
    Trial(1.0, 412.0, "match"),
    Trial(2.0, 388.0, "match"),
    Trial(3.0, 401.0, "match"),
    Trial(1.0, 495.0, "mismatch"),
    Trial(2.0, 470.0, "mismatch"),
    Trial(3.0, 466.0, "mismatch")
  )

val program =
  plot(trials)
    .aes(_.block, _.latency)
    .group(_.condition)
    .scaleColorDiscrete(_.condition, name = "condition")
    .geomLine()
    .geomPoint()
    .axisTitles("Block", "Latency (ms)")
    .build
    .fold(error => sys.error(error.message), identity)
```

`displayPlot` compiles the plot against the notebook target and returns a bundle:

```scala mdoc:silent
val options = NotebookOptions.unsafe(
  width = 960,
  height = 540,
  pixelsPerInch = 144.0,
  title = Some("Latency by block")
)

val bundle =
  NotebookRenderer.displayPlot(program.plot, options, program.compilerOptions)
```

```scala mdoc
bundle.data.keys.toVector.sorted
```

```scala mdoc
bundle.metadata
```

Hand `data` and `metadata` to whatever display API your kernel provides. In Almond:

```scala
publish.display(almond.interpreter.api.DisplayData(bundle.data, metadata = Map.empty))
```

The constants are `NotebookMimeBundle.SvgMime` (`image/svg+xml`), `PlainTextMime` (`text/plain`), and
`HtmlMime` (`text/html`). `bundle.svg`, `bundle.plainText`, and `bundle.html` are `Option` lookups
against those keys.

A success bundle always carries both `image/svg+xml` and `text/plain`; the text entry is a one-line
description of the logical size, so a frontend without SVG still prints something meaningful.

## Pass your compiler options

`renderPlot` and `displayPlot` default their third argument to `PlotCompilerOptions.lean`, whose
`guides` field is `GuidePolicy.NoGuides`. A plot displayed with the default options therefore has no
axes and no legend. That is not a bug in the adapter — it is the core default, and the plotting DSL
overrides it.

Take the options from the program, as above, or state the policy yourself:

```scala mdoc:silent
val withGuides =
  NotebookRenderer.displayPlot(
    program.plot,
    options,
    PlotCompilerOptions(guides = GuidePolicy.Derived(), theme = Theme.minimal)
  )
```

```scala mdoc
withGuides.svg.exists(_.contains("<text"))
```

## The three entry points

| Method | Input | Layout target | Failure |
|---|---|---|---|
| `render(scene, options)` | a `Scene` | the options' width/height/DPI, applied at serialization | `Either[NotebookRenderError, NotebookMimeBundle]` |
| `renderPlot(plot, options, compilerOptions)` | an uncompiled `Plot` | a `RenderContext` built from the options, used for layout **and** serialization | `Either[NotebookRenderError, NotebookMimeBundle]` |
| `displayPlan(plan, title, errorDisplay)` | a `RenderPlan` | the plan's own `RenderContext`, unchanged | never — always a bundle |

`display` and `displayPlot` are the total counterparts of `render` and `renderPlot`.

`render` re-lowers a scene that was laid out somewhere else, so a scene compiled for one size and
displayed at another gets the margins of the first. Prefer `renderPlot` when you have a `Plot`, and
`displayPlan` when you already hold a `RenderPlan` — for instance one built for a paper figure that
you also want to see in the notebook.

```scala mdoc:silent
val context = RenderContext.unsafe(width = 1440, height = 810, pixelsPerInch = 216.0)

val figure =
  NotebookRenderer.displayPlan(
    program.renderPlan(context).fold(error => sys.error(error.message), identity),
    title = Some("Latency by block")
  )
```

## Errors are displayable

`display*` never throws and never returns a `Left`. A checked failure becomes a bundle according to
`NotebookOptions.errorDisplay`:

- `NotebookErrorDisplay.PlainText` (the default) emits only `text/plain`.
- `NotebookErrorDisplay.AccessibleHtml` emits `text/plain` plus a `text/html` entry containing
  `<pre role="alert">` with the message HTML-escaped.

The typed error remains available through `render*` if you would rather branch on it.
`NotebookRenderError` has exactly two cases, `Compiler(GraphicsError)` and `Svg(SvgRenderError)`, and
extends `IntaglioError`.

```scala mdoc
NotebookRenderer
  .renderPlot(program.plot, options, program.compilerOptions)
  .map(_.data.contains(NotebookMimeBundle.SvgMime))
```

## Device scale

`NotebookOptions` width and height are **actual backing pixels**. `deviceScale` says how many of
them make one CSS pixel, and the SVG document's logical size is `width / deviceScale`. That logical
size is what the `image/svg+xml` metadata reports, and what a frontend uses to lay the image out.

For a 640 by 480 figure at 2x:

```scala mdoc:silent
val retina = NotebookOptions.unsafe(
  width = 1280,
  height = 960,
  pixelsPerInch = 192.0,
  deviceScale = 2.0
)
```

```scala mdoc
NotebookRenderer.displayPlot(program.plot, retina, program.compilerOptions).metadata
```

DPI and `deviceScale` scale together, which is what preserves physical typography: a 10 pt axis label
stays 10 pt. Doubling `pixelsPerInch` without doubling `deviceScale` instead makes a physically
larger figure at the same pixel count.

`NotebookOptions.apply` validates width, height, DPI, and device scale through `SvgOptions` and
returns `Either[NotebookRenderError, NotebookOptions]`. `unsafe` throws on invalid input; it is for
notebook cells, where a throw is a visible failure rather than a lost one.

## Publication output

SVG in a notebook and a figure in a manuscript are different targets, and the notebook adapter is
only the first. For print, render the same `RenderPlan` through `intaglio-pdf`, which writes a
one-page PDF directly from the shared `DeviceScene` — no converter, no headless browser.

```scala mdoc:compile-only
import java.nio.file.Path
import intaglio.pdf.*

val figurePath =
  for
    font <- PdfFont.load("Source Sans 3", Path.of("fonts/SourceSans3-Regular.ttf"))
    catalog = PdfFontCatalog.single(font)
    printContext <- RenderContext(
      width = 1800,
      height = 1200,
      pixelsPerInch = 300.0,
      fontRegistry = catalog.fontRegistry
    )
    plan <- program.renderPlan(printContext)
    document <- PdfRenderer.render(plan, catalog, PdfOptions(title = Some("Figure 1")))
    path <- document.writeTo(Path.of("figure-1.pdf"))
  yield path
```

Three things about that snippet are load-bearing.

**Page size comes from pixels and DPI.** PDF points are `devicePixels * 72 / pixelsPerInch`, so
1800 by 1200 at 300 DPI is an exact 432 by 288 point MediaBox — 6 by 4 inches. There is no separate
page-size argument. Choosing a physical figure size means choosing that ratio.

**Fonts are fail-closed.** PDF text needs embedded glyphs, so you supply font bytes you are permitted
to embed, and you register the same catalog for layout (`fontRegistry`) and for rendering. A missing
Unicode glyph or malformed font data is a typed `PdfRenderError`, not a host-dependent substitution.
There is no default font and no fallback to an installed family.

**Vectors stay vectors.** `PdfRasterPolicy.ExplicitImagesOnly` is fixed: only an explicit Intaglio
`RasterImage` becomes an image XObject. Marks, text, clips, rotations, and hatch/stipple fills are
native PDF operators — patterns are tiling resources containing paths, not bitmap tiles. The renderer
never rasterizes a page as a fallback.

If you want layout to reflect installed fonts rather than the portable estimator, pair
`Java2DTextMetrics` with its own registry and pass both to the `RenderContext`; see
[saving output](tutorial/07-saving-output.md#java2d-bufferedimage-and-png). Details of the print
backend are in the [PDF renderer guide](../modules/pdf/README.md), and the per-backend capability
table is in the [backend guide](backends.md).
