# Extending backends

A backend translates a `DeviceScene` into an output format. It does no unit arithmetic, no y-flip, no
DPI conversion, no text measurement, and no plot semantics: all of that happened in
`DeviceScene.fromScene` before the backend saw anything. See
[ADR 0004](../adr/0004-resolve-lengths-once-against-one-target.md).

## What you implement

Two things: a translation from `DeviceScene` to your output type, and a `RendererHarness[Out]`
adapter so the shared conformance contract can interrogate it.

```
trait RendererHarness[Out]:
  def render(scene: Scene): Either[String, Out]
  def containsMarker(out: Out, name: GraphicsName): Boolean
  def satisfies(out: Out, requirement: RenderRequirement): Boolean = false
  def validate(out: Out): Option[String] = None
```

`render` must use `RendererConformance.targetContext()` — 240 by 160 pixels at 96 ppi — or construct
your backend's options from `targetWidth`, `targetHeight`, and `targetPixelsPerInch`, because some
requirements observe resolved physical units such as point-valued stroke widths and font sizes.
`Out` must have value equality: the checker renders each case twice and compares.

`satisfies` and `validate` have defaults so a new backend compiles immediately, but the defaults are
not adequate. Leaving `satisfies` at `false` fails every requirement-bearing conformance case; a
real harness pattern-matches `RenderRequirement` against its output.

## The device scene

`DeviceScene(width: Double, height: Double, elements: Vector[DeviceElement], semantics: SceneSemantics)`.
Coordinates are device pixels, y-down, origin at the top-left. Every number is finite and of
magnitude at most `1e13`; `DeviceScene.fromScene` validates that and returns
`GraphicsError.InvalidDeviceValue` or `UnresolvableLength` rather than emitting a `NaN`.

### Every `DeviceElement` case

| Case | Fields | What a backend does |
| --- | --- | --- |
| `Mark` | `primitive: DevicePrimitive` | Draw the primitive. |
| `Group` | `name: Option[GraphicsName]`, `clip: Option[DeviceClip]`, `rotation: Option[DeviceRotation]`, `children: Vector[DeviceElement]` | Push the clip rectangle and the rotation, draw `children` in order, pop. |
| `Annotated` | `meta: GrobMeta`, `children: Vector[DeviceElement]` | Attach the metadata if the format can carry it; otherwise draw `children` as if the wrapper were absent. It has no name, no clip, and no rotation of its own. |

`DeviceClip(x, y, width, height)` is an axis-aligned rectangle in device pixels.
`DeviceRotation(degrees, pivotX, pivotY)` is already in device handedness — positive is clockwise —
so apply it directly and do not negate it.

`GrobMeta` carries an optional `title`, an optional `description`, an optional checked `CssClass`,
and an insertion-ordered `Vector[(DataKey, String)]`. The SVG backend emits a wrapping `<g>` with
`class` and `data-*` attributes and `<title>`/`<desc>` children; raster backends and PDF ignore it.
Metadata never changes geometry or paint.

### Every `DevicePrimitive` case

| Case | Fields |
| --- | --- |
| `Disc` | `centerX: Double`, `centerY: Double`, `radius: Double`, `gp: GraphicParams`, `name: Option[GraphicsName]` |
| `PointBatch` | `points: Vector[DevicePoint]`, `radii: BatchColumn[Double]`, `shapes: BatchColumn[PointShape]`, `graphicParams: BatchColumn[GraphicParams]`, `name: Option[GraphicsName]` |
| `Polyline` | `points: Vector[DevicePoint]`, `closed: Boolean`, `gp: GraphicParams`, `name: Option[GraphicsName]` |
| `CompoundPolygon` | `rings: Vector[Vector[DevicePoint]]`, `gp: GraphicParams`, `name: Option[GraphicsName]` |
| `RectShape` | `x: Double`, `y: Double`, `width: Double`, `height: Double`, `cornerRadius: Double`, `gp: GraphicParams`, `name: Option[GraphicsName]` |
| `TextRun` | `label: String`, `x: Double`, `y: Double`, `horizontal: HJust`, `vertical: VJust`, `rotationDegrees: Double`, `fontSizePx: Double`, `fontFamily: Option[String]`, `gp: GraphicParams`, `name: Option[GraphicsName]` |
| `Image` | `image: RasterImage`, `x: Double`, `y: Double`, `width: Double`, `height: Double`, `interpolation: RasterInterpolation`, `alpha: Double`, `name: Option[GraphicsName]` |

Four things about that table are load-bearing.

**`RectShape.cornerRadius` is already clamped.** Lowering resolves the grob's axis-neutral
`ExtentExpr` corner radius and then clamps it to half the shorter resolved side — the SVG `rx`/`ry`
rule, applied once so raster and vector backends agree. A non-finite or non-positive request becomes
`0.0`, which is the sharp rectangle every backend emitted before corners existed. **Draw the radius
you are given.** Do not re-derive a limit, and do not treat a large value as an error: it cannot
occur.

**Step interpolation is expanded before you see it.** `Grob.Lines` carries a `LineInterpolation`, but
`DevicePrimitive.Polyline` does not. `DeviceScene` expands `StepAfter` and `StepBefore` into exactly
the corner points an author would have written by hand, so a step line and its explicit form lower to
the same polyline. A backend implements one polyline routine and never sees a step.

**`PointBatch` is one primitive covering many marks.** Read `shapes.valueAt(index)` and
`graphicParams.valueAt(index)` per mark; do not read index zero and draw everything the same way.
`BatchColumn.isConstant` tells you when it is safe to hoist state-setting out of the loop. Each shape
is centred on its point and sized by the resolved radius: `Circle` is the disc of radius `r`;
`Square` and `Triangle` span `[-r, r]` on both axes; `Cross` is two strokes of length `2r`; `Diamond`
is a square rotated 45 degrees whose half-diagonal is `PointShape.diamondHalfDiagonal(r)`, chosen so
its area equals the circle's. See [ADR 0006](../adr/0006-columnar-marks-stay-one-grob.md).

**`Polyline` carries `closed`, not a separate polygon case.** `closed = true` is a filled, stroked
polygon; `closed = false` is an open path. `CompoundPolygon` is the multi-ring form, where ring
winding carries outer/hole semantics — fill it with one winding-aware path so interior rings become
holes rather than seams.

`GraphicParams` reaching a backend has `lineWidth` in device pixels and `lineWidthUnit` normalized to
`StrokeUnit.DevicePixel`; a point-valued stroke was converted once during lowering. `LineCap` and
`LineJoin` are explicit and must be translated exhaustively rather than inherited from toolkit state.
`fillPattern` spacing, hatch line width, and stipple radius are literal device pixels; hatch angles
are clockwise degrees from a vertical rule in the y-down device system; the tile starts at `(0, 0)`
in the current device coordinate system and follows enclosing viewport transforms. `alpha` multiplies
the composited result once.

## A worked harness

```scala mdoc:compile-only
import intaglio.*
import intaglio.laws.*

/** A backend that serialises every device element to one line of text. */
object CommandLogRenderer extends RendererHarness[Vector[String]]:
  def render(scene: Scene): Either[String, Vector[String]] =
    DeviceScene
      .fromScene(scene, RendererConformance.targetDevice)
      .left
      .map(_.message)
      .map(device => device.elements.flatMap(emit))

  def containsMarker(out: Vector[String], name: GraphicsName): Boolean =
    out.exists(_.endsWith(s" name=${name.value}"))

  private def emit(element: DeviceElement): Vector[String] =
    element match
      case DeviceElement.Mark(primitive) =>
        Vector(mark(primitive))
      case DeviceElement.Group(name, clip, rotation, children) =>
        s"group clipped=${clip.nonEmpty} rotated=${rotation.nonEmpty} name=${label(name)}" +:
          children.flatMap(emit)
      case DeviceElement.Annotated(meta, children) =>
        s"annotated class=${meta.cssClass.fold("")(_.value)}" +: children.flatMap(emit)

  private def mark(primitive: DevicePrimitive): String =
    primitive match
      case DevicePrimitive.Disc(cx, cy, radius, _, name) =>
        s"disc $cx $cy $radius name=${label(name)}"
      case DevicePrimitive.PointBatch(points, radii, shapes, _, name) =>
        val kinds = points.indices.map(index => shapes.valueAt(index)).distinct.mkString(",")
        s"point-batch ${points.length} [$kinds] uniform=${radii.isConstant} name=${label(name)}"
      case DevicePrimitive.Polyline(points, closed, _, name) =>
        s"polyline ${points.length} closed=$closed name=${label(name)}"
      case DevicePrimitive.CompoundPolygon(rings, _, name) =>
        s"compound-polygon ${rings.length} name=${label(name)}"
      case DevicePrimitive.RectShape(x, y, width, height, cornerRadius, _, name) =>
        s"rect $x $y $width $height radius=$cornerRadius name=${label(name)}"
      case DevicePrimitive.TextRun(text, x, y, h, v, angle, fontPx, family, _, name) =>
        s"text $x $y $h $v $angle ${fontPx}px ${family.getOrElse("-")} '$text' name=${label(name)}"
      case DevicePrimitive.Image(image, x, y, width, height, interpolation, alpha, name) =>
        s"image ${image.width}x${image.height} $x $y $width $height $interpolation $alpha name=${label(name)}"

  private def label(name: Option[GraphicsName]): String =
    name.fold("<unnamed>")(_.value)

val conformance: Either[GraphicsError, Vector[RendererConformance.Violation]] =
  RendererConformance.check(CommandLogRenderer)

val backendSuite: LawSuite =
  BackendLaws(CommandLogRenderer)

val geometrySuites: Vector[LawSuite] =
  Vector(
    PointShapeLaws(RendererConformance.targetDevice),
    RectCornerLaws(RendererConformance.targetDevice),
    LineInterpolationLaws(RendererConformance.targetDevice)
  )
```

That harness handles every element and primitive case, and it still fails conformance: it inherits
`satisfies = false`, so every case carrying a `RenderRequirement` reports a missing semantic
requirement. The complete worked example — including the `RenderRequirement` match over primitive
kinds, styles, pattern fills, text placement, text style, images, and group effects, plus a
`validate` that scans for non-finite coordinates — is
`modules/laws/shared/src/test/scala/external/laws/ExternalBackendLawsSuite.scala`. It is written in a
consumer package using public contracts only, and it is the file to copy.

## Laws to run

- **`RendererConformance.check(harness)`** (`RendererConformance.scala`) returns
  `Either[GraphicsError, Vector[Violation]]`; an empty vector means the backend passed. It renders
  every canonical case twice and reports four kinds of violation: non-deterministic rendering, a
  missing marker, an unsatisfied `RenderRequirement`, and whatever your own `validate` rejects. Cases
  are grouped by `ConformanceGroup` — `Primitive`, `PatternFill`, `Layout`, `Guide`, `CompiledPlot`.
  While bringing a backend up, `RendererConformance.group(g)` returns just that group's
  `ConformanceCase` values so you can render them one family at a time; `RendererConformance.cases`
  returns all of them.
- **`BackendLaws(harness)`** (`ExtensionLaws.scala`) wraps the same check as a framework-neutral
  `LawSuite`, turning each violation into a `LawFailure` labelled `group/caseName`. Use it when the
  rest of your suite is already law-shaped.
- **`PointShapeLaws(device, size, tolerance)`** (`PointShapeLaws.scala`) — every `PointShape` lowers
  successfully, every shape's marks are centred on the resolved point, and the diamond covers exactly
  the circle's area at the same size. These are laws about the *shared lowering*, so run them at your
  target's `DeviceContext` — including an anisotropic, high-density one — to confirm the geometry
  your backend receives is the geometry it should draw.
- **`RectCornerLaws(device, size, tolerance)`** (`GrobFormLaws.scala`) — a rounded rectangle occupies
  the same device rectangle whatever its radius, a zero radius lowers to exactly the primitive a
  sharp rectangle always produced, and an over-large request is clamped to half the shorter side.
  This is the executable form of the clamping rule above.
- **`LineInterpolationLaws(device, tolerance)`** (`GrobFormLaws.scala`) — a step lowers to exactly the
  polyline the explicit corner form produces, `Linear` is untouched, and transposing the axes
  exchanges the two step forms. Run it if you implement a coordinate as well as a backend; see
  [`coords.md`](coords.md).

`modules/laws/shared/src/test/scala/external/laws/PointShapeLawsSuite.scala` and
`GrobFormLawsSuite.scala` show the pattern: run each kit at `RendererConformance.targetDevice` and
again at something deliberately hostile such as `DeviceContext.unsafe(300.0, 90.0, 300.0)`.

## Scala.js and Canvas

`intaglio-canvas` is a Scala.js-only module and is **not** on the documentation classpath, so there is
no compiled example for it here. Its shape is the same as everything above: `CanvasRenderer` consumes
a `DeviceScene` and issues Canvas 2D commands, and its test suite records those commands into a
deterministic value that implements `RendererHarness` and runs `RendererConformance.check` unchanged.

Two Canvas-specific points are worth knowing before writing a browser backend. `CanvasOptions.hidpi`
builds actual backing-store dimensions from a logical size and a device-pixel ratio and preserves the
exact requested logical size when a fractional ratio forces integer rounding — use it rather than
multiplying dimensions by hand. And `CanvasRenderError` adds `InvalidCanvasSize`,
`InvalidRasterCacheCapacity`, and `PatternResourceFailure` to the `Graphics(error)` wrapper, because
pattern resources and raster caches are Canvas concerns that have no core equivalent.

Because MiMa reads JVM class files, Canvas is checked by the TASTy court rather than the binary one;
[ADR 0005](../adr/0005-three-compatibility-courts.md) explains why.
