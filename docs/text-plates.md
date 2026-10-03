# Text plates

A label drawn over an image usually needs a background plate. A host cannot size one itself,
because only the backend knows how wide the label will draw in the face it actually resolves.
`TextPlate` asks the backend to do it: a filled, optionally rounded rectangle that bounds the
text it draws, plus padding on every side.

```scala mdoc:silent
import intaglio.*

val plate = TextPlate(
  fill = Rgba.unsafe(16, 24, 40, alpha = 0.78),
  padding = StrokeWidth.pointsUnsafe(3.0),
  cornerRadius = StrokeWidth.pointsUnsafe(3.0)
)

val labelStyle = GraphicParams
  .unsafe(stroke = None, fill = Some(Rgba.White))
  .withTextPlate(plate)

val label = Grob.text("Region 7", Point.npcUnsafe(0.5, 0.8), gp = labelStyle)
```

The plate is part of the text's style, not a second grob. Each backend measures the run with its
own metrics at draw time and places the plate around the box it draws the glyphs against:

| Backend | Text measure |
| --- | --- |
| Java2D | the resolved `java.awt.Font`'s logical bounds, fallback face included |
| JavaFX | `JavaFxGraphicsContext.measureText`; the live `JavaFxCanvasContext` uses JavaFX's own text layout in the face `Font.font` resolved |
| Canvas | the context's `measureText`: the advance width and font bounding box, united with the run's actual glyph bounds (`actualBoundingBox*`) so overhangs stay inside |
| PDF | the embedded face's advance and ascent-to-descent box, united with the outline bounds of the glyphs the run draws, from that same face (below) |
| SVG | with an [embedded](svg-fonts.md) TrueType or OpenType face for the run's family and weight: that face's own glyph boxes and metrics (below); otherwise the render context's `TextMetrics`, the measure layout and picking use, because an SVG file cannot know the viewer's font |

A custom `JavaFxGraphicsContext` that does not override `measureText` falls back to the shared
deterministic estimate. Every backend places the plate through `TextPlate.around`, whose laws are
in `TextPlateLaws`: the measured box plus exactly the padding on every side, with the corner radius
clamped to half the shorter side.

Padding and corner radius take points or device pixels and resolve once at device lowering, like a
stroke width. The plate's fill alpha multiplies `GraphicParams.alpha`, and a rotated label rotates
its plate with it.

## PDF: what the plate bounds

A PDF draws every run from a TrueType face in its `PdfFontCatalog`, embedded and subset in the
document; it never uses a standard-14 base font or a host font, and it has no fallback face. A run
whose family is not registered, or whose face lacks one of its code points, fails to render
(`MissingFont`, `MissingFontWeight`, `UnsupportedGlyph`) rather than drawing, or measuring, another
face.

PDF text is not shaped: each code point is one glyph, placed at the sum of the advances before it,
with no kerning, ligatures or mark positioning. The plate is therefore the union of the run's
logical box (its advance by the face's ascent and descent, the box the anchor places) and the
outline bounds of exactly those glyphs, read from the same embedded face at the same size, plus the
padding. It covers italic overhangs such as `j` and `f`, combining marks, which draw at the pen
with no advance of their own, every anchor, and every rotation; glyph placement is unchanged. There
is no rasterisation allowance: the plate contains the outlines geometrically.

Checked against PDFBox's rasteriser with independently rendered ink-only and plate-only pages:
Liberation Sans, Georgia Italic, DejaVu Serif Italic and DejaVu Sans; `j`, `f`, `fj`, `A` with a
combining acute, a leading combining acute, `ÅÉ` and `Plate Wg`; nine anchors; 0°, 33°, 90° and
180°; 0 and 4 px padding; 1x and 2x. Every inked pixel lies on the plate. The guarantee is about
the embedded outlines: a viewer that hints them, or substitutes a font for one it rejects, can draw
outside them.

## SVG: when the plate is guaranteed to contain the glyphs

An SVG file is drawn by someone else's viewer, which shapes and rasterises the text itself. The SVG
backend therefore makes a containment promise only when it can read the face the viewer will use,
and only as far as that face's own tables describe what the viewer draws.

**With an embedded face.** When `SvgRenderer.render` is given an `SvgFonts` holding a TrueType or
OpenType face whose family and weight match the run exactly, the plate is the union of the run's
logical box (its widest possible advance by the face's ascent and descent) and an ink bound computed
from the face's own tables under default (HarfBuzz-style) shaping, plus one SVG user unit for
anti-aliasing and rounding, plus the padding. The document's `viewBox` is its size in device pixels,
so that allowance is one device pixel when the SVG is shown at its own size; a host that scales the
SVG scales it too. Glyph placement is unchanged: the viewer still anchors the text with
`text-anchor` and `dominant-baseline`.

The ink bound reads `cmap`, `hmtx`, `glyf`, `OS/2`, `GDEF`, `GSUB`, `GPOS` and `kern`. It covers
italic overhangs such as DejaVu Serif Italic `j` and `fj`; combining marks, both as the composite
glyph a shaper composes them into when the face has one and unattached or at the face's
mark-to-base and mark-to-mark anchors; ligatures and the substitutions of always-on features
(`ccmp`, `locl`, `rlig`, `rclt`, `calt`, `clig`, `liga`, `rand`), including those reached only
through contextual rules; kerning on or off; every anchor and rotation; and every ascent, descent
and x-height a viewer may take from `hhea` or `OS/2`. It rests on these readings of the face:

- each glyph's box is the bounding box recorded in its `glyf` header, which the format requires to
  enclose the outline; composite glyphs are not recursed into, so a face whose composite headers
  understate their components is bounded only as well as it records itself;
- the code-point mapping is the `cmap` subtable HarfBuzz would choose, by encoding record in the
  order (3,10), (0,6), (0,4), (3,1), (0,3), (0,2), (0,1), (0,0); if that subtable is not format 4
  or 12, nothing is mapped and the face is not used;
- a CFF-flavoured OpenType face has no per-glyph boxes in a table this backend reads, so each of its
  glyphs is bounded by the font-wide `head` box: contained, but loose.

Checked in Playwright's Chromium 151 at 1x and 2x against independently rendered ink-only and
plate-only images: all 104 embedded-face cases contain their ink (DejaVu Serif Italic and DejaVu
Sans; `j`, `fj`, `A` with a combining acute, mixed-width text, space and empty labels; three anchors;
0° and 33°).

**Otherwise containment is not guaranteed.** The plate falls back to the render context's
`TextMetrics` estimate, which may be narrower or wider than what the viewer draws, whenever:

- no face is embedded for the run's family, or none at its exact weight (a viewer may synthesise
  bold), or the face is WOFF or WOFF2, whose tables are compressed;
- the face does not map one of the run's code points, so the viewer draws it from a fallback font;
- the run needs shaping the model does not cover: right-to-left and complex scripts (Arabic,
  Hebrew, Indic, South-East Asian, Hangul jamo), emoji, tabs and line breaks, invisible and
  directional controls, variation selectors, the fraction slash (U+2044, which turns on fraction
  features), multiple substitution, cursive or mark-to-ligature attachment, or combining marks in a
  face without GPOS mark positioning;
- the face has variation axes, AAT shaping or tracking, colour or bitmap glyphs, GSUB/GPOS feature
  variations, a `kern` table other than plain horizontal format 0 pairs, or a table that proves
  malformed or exceeds the reader's work limits.

The guarantee is about the face, not every viewer: a viewer that rejects the font, hints outlines,
or rounds each glyph advance to whole pixels (some platforms do at 1x) can drift from it by a
fraction of a pixel per glyph, and a viewer that shapes with features beyond the defaults above
(for example through CSS `font-feature-settings`, which Intaglio does not write) is not covered.

## Picking

A plate is paint for its label. It carries no name of its own and is excluded from picking: a
pointer in the padding does not hit the label. Ask for the padded plate to be the label's hit
region with `pickable`:

```scala mdoc:silent
val clickable = labelStyle.withTextPlate(plate.copy(pickable = true))
```

The SVG backend marks an unpickable plate `pointer-events="none"`, so browser hit testing agrees.
