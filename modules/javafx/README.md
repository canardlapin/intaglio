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

Shared raster images are converted once per adapter into cached ARGB
`WritableImage` values and drawn with explicit nearest-neighbor or bilinear
smoothing plus grob-level alpha.
