# intaglio-svg

`graphics-svg` is the first concrete backend for the renderer-neutral
`graphics` scene IR.

It renders `Scene` values to deterministic SVG strings without platform IO,
browser APIs, Java2D, or mutable device state. The module exists to exercise the
core scene contract before adding interactive Canvas or JVM raster backends.

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

The reproducible gallery runner lives in JVM test sources, so it is excluded
from the shipped backend. From the repository root:

```sh
tools/render_graphics_gallery.sh
```

It writes the complete renderer-conformance gallery and a derived log10 scatter
to `target/graphics-gallery/` as SVGs, `manifest.tsv`, and `index.html`. When the
`DesignGraphics` migration is present, the same command adds its real event
plot. Set `SCALAFIM_GALLERY_REQUIRE_DESIGN=1` to make that integration mandatory.
The shell runner combines compiled test classpaths at runtime rather than adding
a domain dependency to `graphics-svg`.
