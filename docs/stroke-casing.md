# Stroke casing

A casing is a wider contrasting stroke painted immediately before a line, segment, or contour's
ordinary stroke. It keeps a route legible over image content without duplicating the grob.

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

For image backgrounds, use a translucent light casing around a saturated route on dark imagery,
or a dark casing around a light route on pale imagery. The focused renderer fixtures exercise the
light/dark contrast pattern with a solid casing under a dashed primary stroke.
