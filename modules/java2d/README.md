# intaglio-java2d

`intaglio-java2d` is the JVM raster backend for the renderer-neutral
Intaglio scene. It compiles scenes into deterministic Java2D commands
and interprets them against `java.awt.Graphics2D`.

Tests combine the shared renderer conformance contract with real
`BufferedImage` pixel assertions. The backend owns Java2D-specific stroke,
font-metric, clipping, transform, and alpha-compositing behavior; it contains no
plot, scale, guide, or layout semantics.

`Java2DTextMetrics()` is an opt-in `TextMetrics` provider for callers that want
layout to use the installed Java2D font environment. Inject it explicitly with
`LayoutPolicy(metrics = Java2DTextMetrics())`; the shared default remains the
portable `TextMetrics.estimate`. Requested families are matched against the
local `GraphicsEnvironment`; missing names fall back to the provider's
configured family (Java's logical `SansSerif` by default).

`Java2DRenderer.renderImage` and `renderPng` are the public in-memory export
helpers. Prefer their `RenderPlan` overloads: one target then owns actual pixel
size, DPI, layout metrics, and font resolution from compilation through image
encoding. Pair a `Java2DTextMetrics(fallbackFamily)` value with its
`fontRegistry` when installed-font measurements are required. `Java2DExportOptions`
makes transparent versus solid backgrounds and geometry/text antialiasing
explicit; PNG export preserves the ARGB pixels produced by `renderImage`.
For controlled output, the target-bound overloads also accept a
`Java2DFontResolver`; `fixed(font)` uses supplied immutable AWT font bytes
without consulting the host family registry. Intaglio's committed perceptual
golden exercises that path; see the
[visual regression guide](../../docs/visual-regression.md).

```scala
val metrics = Java2DTextMetrics("SansSerif")
val context = RenderContext.unsafe(
  width = 1200,
  height = 800,
  pixelsPerInch = 144.0,
  textMetrics = metrics,
  fontRegistry = metrics.fontRegistry
)
val png: Either[Java2DRenderError, Array[Byte]] =
  Java2DRenderer.renderPng(RenderPlan(scene, context))
```

Shared raster images are materialized as cached ARGB `BufferedImage` values and
drawn with explicit nearest-neighbor or bilinear interpolation. Image-level
tests independently pin top-left row order, source alpha, grob alpha, and
z-order with later vector marks.

Pattern fills are materialized from the shared deterministic RGBA tile and
installed as reusable `TexturePaint` values. Structurally equal
`PatternPaint` values share one texture within a draw; `Java2DDrawProfile`
exposes requests, hits, and misses. The texture anchor is device `(0, 0)` and
follows enclosing transforms, while mark alpha is applied once to the
composited ink/background tile. Raster pattern axes are capped at 1,024 device
pixels, with oversized requests rejected through `Java2DRenderError` during
compilation instead of silently becoming solid fills.

Two visual-review runners live in the JVM test sources, so they never ship in
the backend. `GalleryRender` writes the full renderer-conformance gallery as
PNGs, and `PositionVisualQa` renders canonical scatter, line, histogram,
density, summary, ribbon, tile, count, facet, dodge, stack, and seeded-jitter
scenes through Java2D beside separately generated ggplot2 references, emitting a
side-by-side comparison page:

```sh
sbt 'java2dJVM / Test / runMain intaglio.java2d.GalleryRender target/graphics-java2d-gallery'
sbt 'java2dJVM / Test / runMain intaglio.java2d.PositionVisualQa target/graphics-position-qa'
```

`tools/render_position_adjustment_qa.sh` also generates the independent ggplot2
references and writes the paired comparison gallery in one step.

Both outputs are review artifacts; the semantic laws remain the automated
JVM/Scala.js gate.
