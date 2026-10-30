# Scientific heatmaps and compact guides

Use `geomRaster` for a dense regular grid. It draws one image, so there are no independently
antialiased tile edges through which the background can show. `geomTile` and `geomHeatmap` draw
separate rectangles; fractional shared edges can show seams in Canvas, JavaFX and Java2D. They
remain useful for individually styled cells, strokes, irregular grids and vector output. Enlarging
tiles to cover seams changes their geometry and can blend neighboring values.

Field-native raster and heatmap builders accept a prepared fill scale. `scale = Some(...)` takes
precedence over the palette, name, transform and out-of-bounds convenience arguments. For a fixed
scientific comparison, set the domain explicitly rather than training it separately on each map:

```scala mdoc
import intaglio.*

val fill = ContinuousScale.diverging(
  "dB", 13.0, DivergingPalette.BlueRust,
  oob = OobPolicy.Squish, labeler = Labeler.typographic
).orThrow

val field = ScalarField2D.unsafe(
  RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
  RegularGridAxis.cellCenteredUnsafe(0, 2, 2),
  Vector(-2.6, 0.0, 1.0, 2.0, 3.0, 4.6)
)

val plan = plot(field)
  .geomRaster(scale = Some(fill))
  .guides(GuidePolicy.Derived().withoutAxes(Set(AxisSide.Bottom, AxisSide.Left)))
  .renderPlan(RenderContext.unsafe(width = 400, height = 300))
  .orThrow
```

Hidden axes emit no line, tick, label or title and reserve no axis strip. Derived legends and
colorbars remain enabled. The same `withoutAxes` policy carries through facets; sides refer to the
physical sides after coordinate flipping.

Derived axes thin tick labels using the target's measured text extents and a half-em gap. They
retain the largest fitting stride across the original breaks, preferring zero when equally dense
subsets fit. A shorter regular run is used only if no complete stride can fit two labels. Axis
endpoints are not forced into the subset. Explicit axis overrides
retain their caller-supplied ticks. Colorbars retain the true endpoints of fixed
domains and thin labels to fit their height. A single continuous image replaces separate colorbar
swatches, eliminating internal antialiasing seams. `Labeler.typographic` uses the Unicode minus
sign for numbers and exponents; `Labeler.default` retains ASCII labels for compatibility.

## Nearest-neighbour sample coverage

Canvas, JavaFX and Java2D use the same nearest-neighbour rule in physical device pixels.
A device column `p` is covered when its centre `p + 0.5` lies in the original image's
half-open interval `[x, x + width)`. Its source column is
`floor((p + 0.5 - x) / width * sourceWidth)`. Rows use the same top-down rule. The output rectangle
has integer edges, so opaque images have no partial-alpha outer edges. This rule applies to
axis-aligned images; a rotated image still passes through the backend's rotation rasterizer.
Canvas also accounts for a positive axis-aligned drawing transform, such as a host's CSS/DPR
scale, before sampling. Independent clipping can still antialias a fractional clip boundary.

`RasterSampling.nearestGrid(image.dimensions, x, y, width, height)` exposes the covered integer
bounds and the source row/column for each covered device pixel. Use the original image rectangle
from `RenderPlan.deviceScene`; the plan retains original source dimensions for inspection and
picking. The renderer's temporary sampled image does not replace the scene image.

Temporary nearest-neighbour output is limited to 4096 pixels on either axis, matching the
conservative JavaFX resource policy. Larger destinations return a checked error before allocation.

Compile for the actual physical device size and render scale. A scale-one plan enlarged by a
scale-two host is a different rasterization target; recompile when the target size or scale changes.
For a Canvas host that deliberately scales a fixed plan, apply that transform to the image bounds
before calling `nearestGrid` to describe its actual backing pixels.
Smooth interpolation retains the backend's image scaler and does not promise discrete sample
identity.

Canvas uploads each source image once while it remains in `CanvasRasterCache`. It realizes the
mapping with two passes of integer-aligned strip blits, and retains the resulting native canvas
for repeated draws at the same geometry. Resizing or panning an unchanged image does not upload
its pixels again. Sources exceeding 4096 pixels on either axis retain the bounded CPU-prepared
upload path, so a very tall or wide source does not exceed a browser's native canvas limit.
Custom `CanvasRasterFactory` implementations can opt into strip blits by implementing
`createSurface`; otherwise they retain CPU sampling, including in OffscreenCanvas environments.
`CanvasDrawProfile` counts actual raster uploads and upload reuse; scratch allocations and blits
are not uploaded bytes. JavaFX and Java2D retain the CPU-prepared image path.
