# Embedding fonts in SVG

An SVG file names its fonts; the viewer supplies them. A plot whose screen rendering uses a face
the reader's machine does not have falls back to whatever that machine substitutes, and its
labels change width and shape. Embedding the face makes the export draw what the screen drew.

Intaglio bundles no typeface, so it cannot choose one for you or vouch for one's licence. You
supply the bytes of a face you are licensed to embed, and the SVG backend writes it into the
document as an `@font-face` rule with a data URI:

```scala mdoc:compile-only
import intaglio.*
import intaglio.svg.*
import java.nio.file.{Files, Paths}

val face = SvgFontFace(
  family = "Inter",
  bytes = Files.readAllBytes(Paths.get("fonts/Inter-Regular.woff2")),
  weight = FontWeight.Regular
)
val label = Grob.text(
  "Embedded",
  Point.npcUnsafe(0.5, 0.5),
  gp = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black), fontFamily = Some("Inter"))
)
val svg: Either[IntaglioError, String] =
  for
    regular <- face
    fonts <- SvgFonts(regular)
    text <- label
    document <- SvgRenderer.render(
      Scene(Vector(text)),
      SvgOptions.unsafe(width = 320, height = 120),
      fonts
    )
  yield document.value
```

Both `SvgRenderer.render` overloads, for a `Scene` and for a `RenderPlan`, take an `SvgFonts`.
The overloads without it are unchanged and embed nothing.

## What is written

- One rule per face, in the order given, inside a `<style>` element ahead of the drawing:
  `@font-face { font-family: "Inter"; font-weight: 400; src: url(data:font/woff2;base64,…) format("woff2"); }`.
- Only faces whose family some text run names, compared case-insensitively as CSS compares them.
  Supplying a face costs nothing in a document that does not use it, and a document without
  matching text is byte-identical to one rendered without fonts.
- The bytes exactly as given. The output is deterministic and identical on the JVM and Scala.js.
- Text that uses an embedded face names it as a quoted CSS family, including names with numeric
  words or punctuation and names that would otherwise select a generic family such as `serif`.
  Rendering without supplied faces keeps its existing font-family output.

## What is checked

`SvgFontFace` returns `Either[SvgFontError, SvgFontFace]`. It recognises WOFF2, WOFF, TrueType and
OpenType from the file's own signature, refuses an empty file, a file over 16 MiB, and a family
name that cannot be written safely into CSS and XML, and refuses a TrueType or OpenType face whose
OS/2 `fsType` declares *restricted-licence embedding*. WOFF and WOFF2 compress their tables, so that
flag is not read for them: the licence remains your responsibility. `SvgFonts` refuses a family that
repeats a weight.

## Text plates

A [text plate](text-plates.md) on a run that uses an embedded TrueType or OpenType face at the
run's exact weight is sized from an ink bound computed from that face's own tables under default
(HarfBuzz-style) shaping, trusting each glyph's `glyf` header box and the `cmap` subtable HarfBuzz
would choose. Without such a face (including WOFF/WOFF2 faces), or for a run the model does not
cover, the plate is sized from the estimate and containment is not guaranteed;
[text plates](text-plates.md#svg-when-the-plate-is-guaranteed-to-contain-the-glyphs) states the
readings it relies on and every exclusion.

## Size

The face is embedded whole; there is no glyph subsetting, so prefer WOFF2 and a face cut to the
scripts you need. [ADR 0009](adr/0009-svg-fonts-are-caller-supplied.md) records why.

## Browser verification

The browser fixture exercises the real exporter with a caller-supplied, licensed regular font.
It uses an unusual family alias containing a numeric word to detect CSS parsing and fallback
errors. Choose a face with different metrics from Chromium's default serif fallback; Liberation
Sans Regular is suitable. The output directory must not already exist.

```bash
sbt 'svgJVM/Test/runMain intaglio.svg.SvgFontBrowserFixture /path/to/font.ttf /tmp/font-proof'
node tools/check-svg-font-browser.cjs /tmp/font-proof
```

The script requires Playwright and its Chromium installation. A separately installed Playwright
Chromium binary can be selected with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`; do not point it at a
user browser or profile. Follow the repository browser-ownership audit procedure before and after
running it. Pages, contexts, and the browser are closed even if an assertion fails.

The court opens the exported SVG as a standalone document, verifies its embedded face loads,
and compares text width against the original bytes loaded independently through `FontFace` and
Canvas. A second export without font bytes must fall back and have a different width. HTTP requests
are blocked and asserted absent. Screenshots and `browser-report.json` retain the browser version,
input hashes, loaded faces, and measured widths. This checks the selected font in Chromium;
it does not certify every font format, viewer, weight, or script.
