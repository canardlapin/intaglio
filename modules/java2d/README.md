# intaglio-java2d

`graphics-java2d` is the JVM raster backend for the renderer-neutral
`graphics` device scene. It compiles scenes into deterministic Java2D commands
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

Shared raster images are materialized as cached ARGB `BufferedImage` values and
drawn with explicit nearest-neighbor or bilinear interpolation. Image-level
tests independently pin top-left row order, source alpha, grob alpha, and
z-order with later vector marks.

`tools/render_position_adjustment_qa.sh` renders canonical scatter, line,
histogram, density, summary, ribbon, tile, count, facet, dodge, stack, and
seeded-jitter scenes through Java2D beside independently generated ggplot2
references. The resulting comparison page is a review artifact, while semantic
laws remain the automated JVM/Scala.js gate.
