# 0009. SVG fonts are caller-supplied and embedded whole

Status: Accepted
Date: 2026-10-02

## Context

SVG export named fonts but did not carry them, so a figure drawn on screen in one face rendered in
another wherever that face was not installed (bd-01M3YZT8BWJ7D5ZX2YDCWRBK6E). The request was to
"embed or subset the bundled UI face".

The repository bundles no typeface. No font file is tracked in any module; the only font bytes in
the build are LiberationSans inside the PDFBox test dependency, used to exercise the PDF backend.
The "UI face" belongs to a consuming application. A library cannot embed a face it does not ship,
and must not ship a face whose licence it has not verified.

Subsetting a face to the glyphs a document uses means parsing and rewriting `glyf`/`loca` (or CFF),
`cmap`, `hmtx` and composite-glyph references, and for WOFF2 a Brotli codec, on both the JVM and
Scala.js. Every existing library for it is JVM-only or native.

## Decision

- **The caller supplies the bytes** through `SvgFontFace(family, bytes, weight)`, and answers for
  the licence. Intaglio bundles no face.
- **The backend checks what it can.** The format comes from the file signature; size is bounded;
  family names are restricted to text that is safe in CSS and XML; a TrueType/OpenType face whose
  OS/2 `fsType` declares restricted-licence embedding is refused.
- **Embedding is opt-in and use-driven.** Only the `SvgRenderer.render` overloads that take
  `SvgFonts` embed, and only faces whose family a text run names.
- **No subsetting.** Faces are embedded whole, as `@font-face` data URIs, byte for byte.

## Consequences

Documents that embed a face grow by the face's size; WOFF2 keeps that small. Output is
deterministic and identical on both platforms because no codec runs. The existing render overloads
are unchanged, so no existing document changes. A consumer that wants its UI face in exports
passes that face's bytes once.

## Alternatives considered

- *Bundle an open face (e.g. an OFL sans).* It would add a binary asset and a licence to every
  artifact, and would still not be the consumer's UI face.
- *Subset in Intaglio.* A hand-written sfnt subsetter on two platforms is a large, security-relevant
  parser for a size optimisation; a WOFF2 subsetter additionally needs Brotli. Rejected until a
  consumer shows the size matters.
- *Add a font library dependency.* None is cross-platform, and the backends otherwise have no
  runtime dependencies.
- *Convert text to paths.* Exact, but loses selectable and accessible text, which the SVG backend
  deliberately keeps.
