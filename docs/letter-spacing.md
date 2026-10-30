# Letter spacing

Use signed physical spacing on a text style:

```scala mdoc
import intaglio.*

val labelStyle = GraphicParams
  .unsafe(fontSize = Length.pointsUnsafe(10), fontWeight = Some(FontWeight.unsafe(500)))
  .withLetterSpacing(LetterSpacing.pointsUnsafe(0.6))
```

`LetterSpacing.points` and `LetterSpacing.devicePixels` return checked values.
Their `Unsafe` variants throw on non-finite input. Negative values tighten text;
zero or an absent value retains the existing rendering path. Points scale with
the device resolution. Device pixels remain fixed in the compiled device scene.

Apply the same parameters to a theme text role to carry spacing into its layout
policy. `TextStyle.letterSpacingPt` is the corresponding measurement value in
points. Use the metrics for the backend that draws the figure: `CanvasTextMetrics`,
`Java2DTextMetrics`, `JavaFxTextMetrics`, or `PdfFontCatalog.textMetrics`. A custom
metrics provider must implement the style-aware width method to support spacing;
the default method rejects nonzero spacing. `TextMetrics.estimate` remains an
estimate, including its Unicode-aware spacing increment.

## Unicode and shaping

Tracking keeps combining sequences, supplementary characters, variation selectors,
and emoji ZWJ sequences intact. The shared boundary implementation uses Unicode 17
extended grapheme clusters and is tested against the complete official
`GraphemeBreakTest` fixture. Nonzero tracking requires well-formed UTF-16 and a
single paragraph. Place separate lines explicitly.

Java2D, JavaFX, and PDF apply a conservative shaping policy. Latin, Greek, Cyrillic,
Hebrew, Han, Hangul, Hiragana, Katakana, and Bopomofo can receive gaps between
grapheme clusters. A whitespace-delimited word containing another script stays
whole, including its punctuation and combining marks. Arabic joins and Indic
conjuncts therefore remain intact; spacing is applied outside those protected
words. Formatting and bidi controls add no spacing opportunities. Required glyph
groups are never pulled apart. This is Unicode-aware tracking, not a promise of
every CSS text-layout feature or every font's discretionary contextual behavior.

Canvas and SVG use the browser or viewer's native `letter-spacing` implementation.
Native shaping, kerning, fallback, and end bearings can differ between platforms;
browser advance measurements can include an extra trailing spacing allowance.
Use backend-specific metrics for alignment and layout rather than comparing
advances between engines. A Canvas context without native letter-spacing support
rejects a nonzero request.

Java2D and PDF position glyphs after native shaping, preserving each group's
internal offsets. JavaFX retains its own registered fonts and paragraph bidi
layout. It snapshots the completed, positioned paragraph when drawing to its
raster Canvas, which also preserves color emoji. Draw on the FX application
thread. The snapshot follows the Canvas transform's scale and is bounded to 4096
pixels per axis; a larger tracked run fails explicitly. JavaFX layout measurement
does not require a snapshot. SVG text remains a single text element; embedded-font
plate bounds include a conservative envelope for tracking.

## PDF fonts and text

PDF shapes with the exact bytes registered in its font catalog, then writes
positioned glyph IDs with an embedded subset. Tracked text remains PDF text,
including a Unicode `/ActualText` value for the original logical string. It is
never converted to a bitmap or outlines. Missing visible glyphs fail with the
existing typed font error; a font still determines which scripts and glyphs it
can display.

PDFBox 3.0.8's text extractor can reorder bidirectional `/ActualText`; upstream
tracks the Arabic case in [PDFBOX-6281](https://issues.apache.org/jira/browse/PDFBOX-6281).
Our mixed Hebrew fixture reproduces an ordering failure with that reader. The
PDF retains the exact logical Unicode string. Poppler recovers the original
characters in order and adds U+202B/U+202C around the Hebrew run. Ordinary tests
check non-bidi extraction, exact bidi metadata, and visible rendering separately.
To repeat the independent reader check with Poppler installed on `PATH`:

```sh
sbt 'pdfJVM/Test/runMain intaglio.pdf.PdfTrackingFixture /tmp/intaglio-tracking.pdf'
python3 tools/check-pdf-tracking.py /tmp/intaglio-tracking.pdf
```

## Regeneration

`tools/generate-unicode-text-data.py` records pinned Unicode source URLs and
SHA-256 hashes. It generates the shared range tables and the embedded conformance
fixture. The original fixture is retained under
`modules/core/shared/src/test/resources/unicode/` for provenance.
