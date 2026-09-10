# intaglio-svg

`intaglio-svg` is the SVG backend for the renderer-neutral Intaglio scene.

It renders `Scene` values to deterministic SVG strings without platform IO,
browser APIs, Java2D, or mutable device state. Like every backend, it carries no
plot, scale, guide, or layout semantics; it only serializes a resolved scene.

Current scope:

- root SVG document options with explicit canvas size and optional title;
- points, lines, segments, rectangles, circles, text, raster images, and groups;
- basic graphical parameters: stroke, solid or validated pattern fill, alpha,
  line width/type, font family, and font size;
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

Pattern fills serialize as deterministic `pattern-N` resources in stable
first-use order. Structurally equal `PatternPaint` values share one definition;
marks reference it with `fill="url(#pattern-N)"`. Definitions use absolute
numeric device-pixel dimensions and `patternUnits="userSpaceOnUse"`, so there
are no percentage or object-bounding-box semantics. Pattern ink/background
alpha stays on the resource geometry, while mark alpha remains the element's
`opacity` and applies once to the composited fill and outline. Resource IDs use
`id`, never `data-name`, so definitions do not impersonate semantic marks.

`PatternFillAssuranceSuite` is the reproducible scale court for the admitted
StoryAtlas-sized workload. On both the JVM and Scala.js it renders 2,300
uniquely named marks sharing one complete pattern paint, pins the byte-identical
SHA-256 output, and checks the 10,000-element and 2 MiB structural limits. It
also proves that the emitted `data-name` sequence is exactly the input mark-name
sequence and that only one pattern definition is serialized.

## Visual gallery

The reproducible gallery runner lives in JVM test sources, so it never ships in
the backend. From the repository root:

```sh
sbt 'svgJVM / Test / runMain intaglio.svg.GalleryRender target/gallery'
```

It writes the complete renderer-conformance gallery plus a derived log10 scatter
to `target/gallery/` as SVGs, `manifest.tsv`, and `index.html`.
