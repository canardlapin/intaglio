# intaglio-pdf

`intaglio-pdf` is Intaglio's JVM print-output backend. It writes a one-page PDF
directly from the shared `DeviceScene`; it does not invoke a converter or
browser.

```scala
libraryDependencies += "io.github.canardlapin" %% "intaglio-pdf" % "0.1.0"
```

## Export a plot

PDF text is fail-closed. Supply font bytes that you are permitted to embed,
register the same family for layout and rendering, and retain the target-bound
`RenderPlan` through export:

```scala
import java.nio.file.Path
import intaglio.*
import intaglio.pdf.*

val result =
  for
    font <- PdfFont.load("Source Sans 3", Path.of("fonts/SourceSans3-Regular.ttf"))
    catalog = PdfFontCatalog.single(font)
    context = RenderContext.unsafe(
      width = 1800,
      height = 1200,
      pixelsPerInch = 300.0,
      fontRegistry = catalog.fontRegistry
    )
    document <- PdfRenderer.render(
      RenderPlan(scene, context),
      catalog,
      PdfOptions(title = Some("Figure 1"))
    )
    path <- document.writeTo(Path.of("figure-1.pdf"))
  yield path
```

The example is a 6 by 4 inch page: PDF points are computed as
`devicePixels * 72 / pixelsPerInch`, giving an exact 432 by 288 point MediaBox.
HiDPI contexts preserve the same physical result because backing dimensions and
resolution scale together.

`PdfFont.fromBytes` is the in-memory alternative to `PdfFont.load`. TrueType
font data (including OpenType fonts with TrueType outlines) is copied on entry.
Families are matched case-insensitively, an absent family uses the catalog
default, and an explicitly requested unknown family does not silently fall
back. During export every used font is loaded in embedded-subset mode; malformed
data and missing Unicode glyphs produce a typed `PdfRenderError` instead of
host-dependent substitution.

## Vector and raster contract

The renderer emits these as native PDF vector operations:

- discs, point shapes, polylines, compound polygons, and rectangles;
- clipping paths and group rotations;
- text backed by embedded subset fonts; and
- solid, hatch, cross-hatch, parallel-rule, and stipple fills. Patterns are PDF
  tiling resources containing paths, not bitmap tiles.

`PdfRasterPolicy.ExplicitImagesOnly` is the fixed raster boundary. Only an
explicit Intaglio `RasterImage` becomes a lossless PDF image XObject. Its source
alpha, grob alpha, top-row orientation, and nearest/smooth interpolation choice
are preserved. The renderer never rasterizes a page, vector mark, text run, or
pattern as a fallback.

Each `PdfDocument` exposes the physical page dimensions, raster policy, and a
`PdfRenderProfile` counting vector shapes, text runs, explicit image placements,
unique raster payloads, vector patterns, and embedded subset fonts. Tests reopen
the produced bytes with PDFBox to verify the MediaBox, embedded font resources,
extractable text, vector operators, tiling patterns, and image boundary. A
content-derived trailer ID also makes repeated exports byte-identical.
