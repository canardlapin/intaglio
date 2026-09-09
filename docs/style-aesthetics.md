# Style aesthetics

Intaglio's built-in plotting grammar exposes style as typed aesthetic keys. The supported channels
are deliberately geom-specific:

| Geom | Style aesthetics |
| --- | --- |
| `Geom.Point` | `color` (stroke), `fill`, `alpha`, `size`, `shape` |
| `Geom.Line` | `color`, `alpha`, `linetype`, `linewidth` |
| `Geom.Text` | `color`, `fill`, `alpha`, `angle`, `hjust`, `vjust` |

Bind constants or row functions with `AesSpec`:

```scala
val points = AesSpec
  .empty[Observation]
  .withPosition(_.x, _.y)
  .withShape(_.shape)
  .withSize(_.pointSize)
  .withColor(_.outline)
  .withFill(_.interior)

val lines = AesSpec
  .empty[Observation]
  .withPosition(_.x, _.y)
  .withGroup(_.series)
  .withLineType(_.lineType)
  .withLineWidth(_.lineWidthPt)

val labels = AesSpec
  .empty[Observation]
  .withPosition(_.x, _.y)
  .withLabel(_.label)
  .withAngle(_.angleDegrees)
  .withHJust(_.horizontalJustification)
  .withVJust(_.verticalJustification)
```

## Dash rhythms

`LineType` has three named cases and one open one. `Solid`, `Dashed` and `Dotted` cover the common
figure; `Custom(DashPattern)` carries an explicit rhythm for an encoding that needs to tell apart
more states than two dash patterns can:

```scala mdoc:silent
import intaglio.*

val reference = LineType.Dashed
val embedded = LineType.Custom(DashPattern.unsafe(8.0, 2.0, 1.0, 2.0))
```

`LineType.dash` resolves any of the four to an `Option[DashPattern]`, and every backend goes through
it, so the two named rhythms have one definition rather than one per renderer. Segments are
alternating on and off lengths in device pixels; `DashPattern` refuses a rhythm no backend could
draw, and [limits](limits.md) records the bound and why an all-zero rhythm is rejected.

## Point shapes

`shape` values are `PointShape` cases. Every shape is centred on its point and sized by one resolved
device radius `r`, the point `size`:

| Shape | Geometry at radius `r` | Area | Device primitive |
| --- | --- | --- | --- |
| `Circle` | disc of radius `r` | `pi r^2` | disc |
| `Square` | axis-aligned square spanning `[-r, r]` | `4 r^2` | rectangle |
| `Triangle` | apex at `-r`, base at `+r`, spanning `[-r, r]` horizontally | `2 r^2` | closed polyline |
| `Cross` | two strokes of length `2r` through the centre | none (open) | two polylines |
| `Diamond` | square rotated 45 degrees, half-diagonal `r sqrt(pi / 2)` | `pi r^2` | closed polyline |

`Diamond` is the one shape defined by area parity with `Circle`: a size-by-value encoding reads the
same whether a mark is a circle or a diamond. `PointShape.diamondHalfDiagonal(r)` is the single
source of that half-diagonal for every backend, and `PointShapeLaws` in `intaglio-laws` pins the
rule. An open variant of any filled shape is a `GraphicParams` with `fill = None`.

`linewidth` values are typographic points. Compilation records `StrokeUnit.Point`, and device
lowering converts the width using the active render context's pixels-per-inch and device scale.
`angle` values are finite degrees. Horizontal justification is `HJust.Left`, `Center`, or `Right`;
vertical justification is `VJust.Bottom`, `Center`, or `Top`.

One line grob represents one structural group, so its color, alpha, line type, and line width must be
constant within that group. Map `group` explicitly when row functions select different line styles.
Discrete scaled style bindings participate in the normal inferred grouping interaction.

Geom contracts reject unsupported bindings before compilation. This prevents a style channel from
being accepted by the grammar and then silently discarded by rich or batch lowering.
