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
| Canvas | the context's `measureText`: advance width, font bounding box above and below the baseline |
| PDF | the embedded face's advance and ascent-to-descent box |
| SVG | with an [embedded](svg-fonts.md) TrueType or OpenType face for the run's family and weight: that face's own glyph boxes and metrics (below); otherwise the render context's `TextMetrics`, the measure layout and picking use, because an SVG file cannot know the viewer's font |

A custom `JavaFxGraphicsContext` that does not override `measureText` falls back to the shared
deterministic estimate. Every backend places the plate through `TextPlate.around`, whose laws are
in `TextPlateLaws`: the measured box plus exactly the padding on every side, with the corner radius
clamped to half the shorter side.

Padding and corner radius take points or device pixels and resolve once at device lowering, like a
stroke width. The plate's fill alpha multiplies `GraphicParams.alpha`, and a rotated label rotates
its plate with it.

## SVG: when the plate is guaranteed to contain the glyphs

An SVG file is drawn by someone else's viewer, which shapes and rasterises the text itself. The SVG
backend therefore makes a containment promise only when it can read the face the viewer will use.

**With an embedded face.** When `SvgRenderer.render` is given an `SvgFonts` holding a TrueType or
OpenType face whose family and weight match the run exactly, the plate is the union of the run's
logical box (its advance by the face's ascent and descent) and the ink of every glyph the viewer may
draw, plus a one-device-pixel allowance for anti-aliasing and rounding, plus the padding. Glyph
placement is unchanged: the viewer still anchors the text with `text-anchor` and
`dominant-baseline`. The ink bound is read from the face's own tables (`cmap`, `hmtx`, `glyf`,
`OS/2`, `GSUB`, `GPOS`, `kern`) and covers italic overhangs such as DejaVu Serif Italic `j` and
`fj`, combining marks (as the composite glyph the viewer composes them into when the face has one,
otherwise unattached and at the face's mark anchors), ligatures, kerning on or off, every anchor and
rotation, and every ascent, descent and x-height a viewer may take from `hhea` or `OS/2`. A
CFF-flavoured OpenType face has no per-glyph boxes in a table this backend reads, so each of its
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
  directional controls, variation selectors, multiple substitution, cursive or mark-to-ligature
  attachment, or combining marks in a face without GPOS mark positioning;
- the face has variation axes, AAT shaping or tracking, colour or bitmap glyphs, GSUB/GPOS feature
  variations, or a table that proves malformed.

The guarantee is about the face, not every viewer: a viewer that rejects the font, hints outlines,
or rounds each glyph advance to whole pixels (some platforms do at 1x) can drift from it by a
fraction of a pixel per glyph.

## Picking

A plate is paint for its label. It carries no name of its own and is excluded from picking: a
pointer in the padding does not hit the label. Ask for the padded plate to be the label's hit
region with `pickable`:

```scala mdoc:silent
val clickable = labelStyle.withTextPlate(plate.copy(pickable = true))
```

The SVG backend marks an unpickable plate `pointer-events="none"`, so browser hit testing agrees.
