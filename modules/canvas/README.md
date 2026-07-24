# intaglio-canvas

`intaglio-canvas` is the Scala.js Canvas 2D backend for the renderer-neutral
Intaglio scene. It compiles a `Scene` into a deterministic
`CanvasProgram`, then interprets that program against a browser
`CanvasRenderingContext2D`.

The recorded command program is the primary conformance and debugging surface:
it makes save/restore nesting, transform-before-clip ordering, primitive
selection, paint, text alignment, and z-order testable without depending on
browser pixel rasterization. The backend contains no plot, scale, guide, or
layout semantics.

Raster images are uploaded as top-left row-major `ImageData` and scaled with
the declared interpolation and alpha. `CanvasRenderer.drawCached` accepts a
bounded `CanvasRasterCache` that retains browser-native image sources across
draws by raster identity; `CanvasDrawProfile` reports hits, misses, and uploaded
bytes. The simpler `draw` entry point keeps a cache only for that call.
`CanvasRasterFactory` is an explicit contextual capability: the companion
supplies ordinary browser canvas materialization, while OffscreenCanvas or test
environments can provide their own image source without changing shared
graphics.

`CanvasTextMetrics` is an opt-in layout provider backed by
`CanvasRenderingContext2D.measureText`. Construct it with the drawing context
and inject it through `LayoutPolicy(metrics = ...)`; portable output continues
to use `TextMetrics.estimate` unless the caller opts in. The caller supplies a
`familyAvailable` predicate for its loaded browser-font environment. A missing
requested family resolves to the explicit `fallbackFamily` (`sans-serif` by
default), and browsers without bounding-box height metrics retain the portable
height estimate.

`BrowserGallery` in the Scala.js test sources exports a real-browser review
entry point. It renders every shared conformance scene through the production
Canvas interpreter and is intended for headless-Chrome or interactive visual
QA after linking the test configuration as a `NoModule` script.
