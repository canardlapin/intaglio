# intaglio-javafx

`intaglio-javafx` is the JVM JavaFX backend for the renderer-neutral
Intaglio scene. It compiles scenes into deterministic JavaFX Canvas
commands and interprets them against a toolkit-free drawing contract
(`JavaFxGraphicsContext`); `JavaFxCanvasContext` adapts that contract onto a
live `javafx.scene.canvas.GraphicsContext`.

```scala
val canvas = new javafx.scene.canvas.Canvas(640, 480)
JavaFxRenderer.render(
  scene,
  JavaFxCanvasContext(canvas.getGraphicsContext2D()),
  JavaFxOptions.unsafe(width = 640, height = 480)
)
```

Drawing must happen on the JavaFX application thread like any other `Canvas`
access; compilation (`JavaFxRenderer.compile`) is pure and thread-free. The
OpenJFX dependency is `Provided`: host applications supply their own
platform-specific JavaFX runtime.

For layout that matches what JavaFX draws, measure with `JavaFxTextMetrics()`
(as `LayoutPolicy.metrics` or a render context's `textMetrics`): AWT and JavaFX
advances differ for the same family and size. See
[Text measurement](../../docs/backends.md#text-measurement).

Tests combine the shared renderer conformance contract with a recording
implementation of the drawing contract, so the full interpreter runs headless
without starting the JavaFX toolkit. The one behavior that cannot be pinned
headless is font sizing: resolved pixel sizes are passed to `Font.font`, which
on desktop treats size as pixel-equivalent; if text renders visibly larger
than the Java2D backend at the same device size, that assumption is the place
to look. The backend owns JavaFX-specific stroke,
dash, font, text-anchor, clipping, transform, and alpha behavior; it contains
no plot, scale, guide, or layout semantics.

JavaFX applications that want installed-font-aware layout can also depend on
`intaglio-java2d` and inject `Java2DTextMetrics()` into `LayoutPolicy`. Font
measurement is a caller-selected JVM capability; the JavaFX renderer itself
does not change the deterministic shared `TextMetrics.estimate` default.

Shared raster images are converted per adapter into cached ARGB
`WritableImage` values and drawn with explicit nearest-neighbor or bilinear
smoothing plus grob-level alpha.

Images and pattern tiles share a least-recently-used cache with a default
64 MiB budget. `new JavaFxCanvasContext(graphics, cacheByteLimit)` selects a
non-negative byte budget; zero disables retention. `cachedResourceBytes` and
`cachedResourceCount` expose current accounting. Raster entries count eight
bytes per pixel (packed source plus native ARGB), pattern entries four, with a
256-byte allowance per entry. Toolkit objects, pending Canvas commands, and
driver textures are outside this accounting. Resources larger than the budget
are drawn without being cached.

Call `release()` (or the compatible `clearCaches()`) on the FX application
thread after balanced drawing when detaching a controller. It drops cached
references and clears an adapter-owned current pattern fill; the adapter can
then be reused. The interaction host calls this hook during `dispose()`.
JavaFX controls reclamation after pending drawing completes; its
[Image API](https://openjfx.io/javadoc/21/javafx.graphics/javafx/scene/image/Image.html)
has no explicit image disposal operation. Saved graphics states and resources
retained by application code must also be released by their owners.

Raster sources have a conservative maximum of 4,096 pixels on each axis,
published as `JavaFxCanvasContext.MaxRasterDimension`. This is a backend policy,
not a query of the active driver's texture limit. Both `compile` and `render`
reject larger sources with `JavaFxRenderError.RasterTooLarge`, carrying the
requested width, height, and limit. Low-level `drawImage` assumes validated
input and throws for a source beyond this limit before allocating a native image.

Pattern fills use that same shared deterministic RGBA tile as an absolute
`ImagePattern`. A `JavaFxCanvasContext` caches one native resource per complete
`PatternPaint`, and `JavaFxDrawProfile` exposes requests, hits, and misses. The
pattern starts at device `(0, 0)`, follows enclosing transforms, and applies
mark alpha once after ink/background composition. Raster pattern axes are
bounded at 1,024 device pixels; oversized requests fail through the typed
`JavaFxRenderError` compilation boundary rather than degrading to solid fill.

The [interaction host](../../docs/javafx-interaction.md) adds pointer and keyboard input,
tooltips, selection gestures, data-window navigation, linked hosts, cached overlay redraw and
explicit disposal using `intaglio-interaction`, with a capability matrix that refuses what it
lacks. Compile a `JavaFxInteractionView` from a typed interaction plan, then call
`JavaFxInteractionHost.mount` (or `attach` for the default behaviour) on the FX application thread
and mount its `node`. `sbt javafxExample/run` opens a desktop example.
Its overlay colours, widths, casings and offsets come from a `JavaFxOverlayStyle` the host can
replace at run time, and outlines can follow each mark's geometry instead of its bounds.
