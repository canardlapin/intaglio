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

## Typographic weight

`GraphicParams.fontWeight` carries a `FontWeight` on the conventional 100-to-900 scale, where
`FontWeight.Regular` is 400 and `FontWeight.Bold` is 700. It is checked, because the scale is
bounded on every backend that can honour it:

```scala mdoc:silent
import intaglio.*

val emphasis = GraphicParams.unsafe(fill = Some(Rgba.Black)).withFontWeight(FontWeight.Bold)
```

Weight changes glyph advance, so it is measured as well as drawn. `TextStyle` carries it, the
`TextMetrics` overloads that take a `TextStyle` receive it, and the Java2D and Canvas providers
build the same font for measuring that their renderers build for drawing — one rule each, in one
place, because two copies would let the solver reserve a regular advance for text drawn bold.

A theme sets it the same way, and `LayoutPolicy` carries one weight per text role beside the family
it already carried, so a bold axis title is sized as a bold axis title.

What each backend does with it: SVG emits `font-weight`; Canvas puts it in the CSS shorthand;
Java2D applies `TextAttribute.WEIGHT`, which resolves to the nearest face the family actually has,
since AWT does not synthesize intermediate weights; JavaFX resolves through
`FontWeight.findByWeight`, which clamps to the nearest of its nine named weights. PDF is different
in kind: it embeds font programs and cannot synthesize a face, so a weight names a face that must
have been registered. See [backends](backends.md) and [limits](limits.md).

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
