# intaglio-svg

`intaglio-svg` is the SVG backend for the renderer-neutral Intaglio scene.

It renders `Scene` values to deterministic SVG strings without platform IO,
browser APIs, Java2D, or mutable device state. Like every backend, it carries no
plot, scale, guide, or layout semantics; it only serializes a resolved scene.

Current scope:

- root SVG document options with explicit canvas size and optional title;
- points, lines, segments, rectangles, circles, text, raster images, and groups;
- basic graphical parameters: stroke, fill, alpha, line width/type, font family,
  and font size;
- resolved device-space groups with clip paths and optional rotation;
- deterministic embedded RGBA PNG images with explicit nearest/smooth
  interpolation policy;
- renderer-neutral `Axis` output: baselines, tick marks, and labels.

Unsupported units, oversized device attributes, and XML-illegal text return
typed `SvgRenderError` values.
JVM tests additionally parse every conformance document with the platform XML
parser; shared JVM/Scala.js tests pin identical serialization behavior.
That is intentional: a backend should expose missing layout semantics instead of
silently inventing device-specific behavior.

## Visual gallery

The reproducible gallery runner lives in JVM test sources, so it never ships in
the backend. From the repository root:

```sh
sbt 'svgJVM / Test / runMain intaglio.svg.GalleryRender target/gallery'
```

It writes the complete renderer-conformance gallery plus a derived log10 scatter
to `target/gallery/` as SVGs, `manifest.tsv`, and `index.html`.
