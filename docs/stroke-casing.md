# Stroke casing

A casing is a wider contrasting stroke painted immediately before a line, segment, contour, point
mark, or disc's ordinary stroke. It keeps a route legible over image content without duplicating the grob.

```scala mdoc:silent
import intaglio.*

val routeStyle = GraphicParams
  .unsafe(
    stroke = Some(Rgba.unsafe(24, 94, 180)),
    lineWidth = 2.0,
    lineType = LineType.Dashed
  )
  .withCasing(
    StrokeCasing.unsafe(
      color = Rgba.unsafe(255, 255, 255, alpha = 0.85),
      width = CasingWidth.relativeUnsafe(3.0),
      alpha = 0.7
    )
  )
```

Place the route after an image grob so the image remains the background:

```scala mdoc:silent
val imagePixels = RasterImage.solid(
  RasterDimensions.unsafe(1, 1),
  Rgba32.unsafe(38, 45, 62)
)
val background = Grob.imageUnsafe(
  imagePixels,
  Point.npcUnsafe(0.5, 0.5),
  Size.npcUnsafe(1.0, 1.0)
)
val route = Grob
  .segments(
    Vector(Point.npcUnsafe(0.1, 0.25) -> Point.npcUnsafe(0.9, 0.75)),
    gp = routeStyle
  )
  .toOption
  .get
val scene = Scene(Vector(background, route))
```

`CasingWidth.relativeUnsafe(3.0)` makes the casing three times the resolved primary stroke
width. Use `CasingWidth.Absolute(StrokeWidth.pointsUnsafe(3.0))` when a physical width is more
appropriate. The casing is solid by default even when the primary stroke is dashed; supply a
`lineType` to `StrokeCasing.unsafe` to opt into a dashed casing.

The effective casing opacity is the product of the casing colour alpha, the casing alpha, and
`GraphicParams.alpha`. SVG, PDF, Java2D, Canvas, and JavaFX paint the casing first. It is not a
second scene primitive: named output, picking, and legend construction continue to see the one
line or contour.

## Points and discs

The same style cases point marks, circle grobs and point batches. A hollow point keeps its
contrasting ring over a busy background without being rebuilt as a many-segment ring path, and a
batch stays one primitive however many marks it carries:

```scala mdoc:silent
val haloStyle = GraphicParams
  .unsafe(stroke = Some(Rgba.unsafe(24, 94, 180)), lineWidth = 1.5)
  .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(3.0), alpha = 0.8))

val markers = Grob.pointBatchUnsafe(
  Vector(Point.npcUnsafe(0.3, 0.4), Point.npcUnsafe(0.5, 0.6), Point.npcUnsafe(0.7, 0.4)),
  shapes = BatchColumn.Values(Vector(PointShape.Circle, PointShape.Cross, PointShape.Diamond)),
  graphicParams = BatchColumn.Constant(haloStyle)
)
val ring = Grob.circle(Point.npcUnsafe(0.5, 0.5), ExtentExpr.pointsUnsafe(18.0), gp = haloStyle)
```

Every backend draws a point's casing with the mark's own geometry, under its stroke. Both bars of
a cross share one underlay, painted before either bar, so neither bar's casing cuts the other where
they cross; a single cased cross point lowers to a one-mark batch for that reason. As with lines,
the casing is drawn only when the mark has a stroke.

Paint order with a fill differs by backend family, as it already does for closed paths: SVG and
PDF paint the casing before the filled mark, so the fill covers its inner half; Java2D, Canvas and
JavaFX paint fill, casing, then stroke. For hollow marks, the common case over imagery, the two
orders are identical.

Picking treats a point's casing exactly as it treats a path's: as paint for the one mark. Targets,
hits and hit distances are those of the uncased mark, so turning a casing on never changes what a
pointer selects.

For image backgrounds, use a translucent light casing around a saturated route on dark imagery,
or a dark casing around a light route on pale imagery. The focused renderer fixtures exercise the
light/dark contrast pattern with a solid casing under a dashed primary stroke.
