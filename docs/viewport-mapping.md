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
