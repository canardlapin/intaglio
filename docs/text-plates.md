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
| SVG | the render context's `TextMetrics`, the measure layout and picking use, because an SVG file cannot know the viewer's font |

A custom `JavaFxGraphicsContext` that does not override `measureText` falls back to the shared
deterministic estimate. Every backend places the plate through `TextPlate.around`, whose laws are
in `TextPlateLaws`: the measured box plus exactly the padding on every side, with the corner radius
clamped to half the shorter side.

Padding and corner radius take points or device pixels and resolve once at device lowering, like a
stroke width. The plate's fill alpha multiplies `GraphicParams.alpha`, and a rotated label rotates
its plate with it.

## Picking

A plate is paint for its label. It carries no name of its own and is excluded from picking: a
pointer in the padding does not hit the label. Ask for the padded plate to be the label's hit
region with `pickable`:

```scala mdoc:silent
val clickable = labelStyle.withTextPlate(plate.copy(pickable = true))
```

The SVG backend marks an unpickable plate `pointer-events="none"`, so browser hit testing agrees.
