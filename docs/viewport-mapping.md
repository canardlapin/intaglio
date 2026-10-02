# Resolved viewport mapping

`DeviceScene.fromScene` records every named viewport it lowers in `DeviceScene.frames`. A host can
look up one panel by its stable scene identity and convert overlay or pointer coordinates without
recompiling the plot:

```scala mdoc:silent
import intaglio.*

final case class Observation(x: Double, y: Double)
def checked[A](value: Either[IntaglioError, A]): A =
  value.fold(error => throw new IllegalArgumentException(error.message), identity)

val plot = checked(
  Plot(Vector(Observation(0.0, 0.0), Observation(10.0, 2.5)))
    .addLayer(Layer.point[Observation](_.x, _.y))
)
val context = RenderContext.unsafe(640, 480)
val trained = checked(
  PlotCompiler.resolve(
    plot,
    PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
  )
)
val deviceScene = checked(DeviceScene.fromScene(trained.scene, context))
val panel = checked(deviceScene.frame(GraphicsName.unsafe("plot-panel")))
val devicePoint = checked(panel.nativeToDevice(DevicePoint(10.0, 2.5)))
val nativePoint = checked(panel.deviceToNative(devicePoint))
```

`ResolvedViewportFrame.frame` is the final device-pixel rectangle used by all renderers. Its mapping
includes the panel's final physical range, so ordinary expansion, coordinate zoom, nested composition
viewports, and the device y direction are already accounted for. Compiled continuous position scales
also retain their raw and transformed domains and transform; log and reverse scales therefore round
trip through the same encoded panel coordinates that marks use. `Coord.Flipped` swaps those scale
mappings and raw point components with the physical axes, so callers continue to supply logical
data `(x, y)` positions.

`deviceToNative` returns `ViewportFrameError.OutsideFrame` for a device point outside the panel.
Categorical, temporal, and other non-continuous position scales return `UnavailableAxis`, because a
numeric pointer coordinate has no lossless raw value for them. A viewport with its own rotation, or
inside a rotated viewport, returns `Rotated`; the frame remains available for bounds and renderer
inspection, but callers must supply their own affine handling before asking for an axis mapping.
Non-finite coordinates and zero-width device, panel, or transformed intervals are refused through
the same typed error channel.

Frame identities are the `GraphicsName` on the viewport group. The compiler names an ordinary panel
`plot-panel` and facet panels with their existing facet panel names. `DeviceScene.frame` refuses a
duplicate name instead of selecting one silently, so a host should give custom named viewports unique
identities. Compositions retain the child names and expose their nesting through
`ResolvedViewportFrame.path`; use `frame(Vector(compositionCellName, panelName))` when several child
plots each contain `plot-panel`.

## Aspect-preserving viewports

An image or a screen-sized frame must keep its aspect ratio at any host size. Give its viewport a
`ViewportAspect`: `AspectMode.Fit` letterboxes the whole content at the requested alignment, and
`AspectMode.Fill` covers the extent and clips the overflow to it. The fitted rectangle is the
viewport's frame, so `deviceScene.frame(name)` and its inverse mapping describe the drawn content,
not the extent around it.

```scala mdoc:silent
val frameImage = RasterImage.solid(RasterDimensions.unsafe(16, 9), Rgba32.unsafe(20, 30, 45))
val letterboxed =
  for
    aspect <- ViewportAspect(16.0 / 9.0, AspectMode.Fit, vertical = VJust.Top)
    viewport <- Viewport.checked(xScale = Interval.unsafe(0.0, 1920.0))
    grob <- Grob.image(
      frameImage,
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(1.0, 1.0),
      viewport = Some(viewport.withAspect(aspect)),
      name = Some(GraphicsName.unsafe("screen"))
    )
    device <- DeviceScene.fromScene(Scene(Vector(grob)), RenderContext.unsafe(800, 600))
  yield device
val screenFrame = letterboxed.toOption.flatMap(_.frame(GraphicsName.unsafe("screen")).toOption)
```

At 800 × 600 the screen frame is 800 × 450 at the top of the extent. `ViewportAspect.ofScales`
takes the ratio from native ranges measured in equal units, such as pixel coordinates.

An inset extent can be stated as a difference, such as the full width less a fixed margin:
`ExtentExpr.fromExpr(LengthExpr.npcUnsafe(1.0) - LengthExpr(Length.pointsUnsafe(12.0)))`. Its sign
depends on the frame, so it is checked when it is resolved; a negative size is
`GraphicsError.InvalidExtent` naming the expression and its pixel value, never clamped to zero.
