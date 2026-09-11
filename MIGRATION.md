# Migration guide

Each section names one breaking change, the compiler error it produces, and the
edit that fixes it. Changes are listed newest first, under the release that
introduces them. The compatibility policy behind these notes —  which courts a
given release preserves, and what moving the baseline requires — is
[docs/compatibility.md](docs/compatibility.md).

## Unreleased

### `DisplayThreshold` and `DisplayError` gained cases

`DisplayThreshold` gained `Below`, `Above` and `TwoSided`; `DisplayError` gained
`InvalidThresholdCutoff` and `InvalidThresholdNesting`. Both are sealed, so
construction and ordinary use are unaffected --- but an exhaustive `match` that
listed every case is no longer exhaustive. Scala 3 reports that as a warning,
not an error, so the build still passes and one of the new cases reaching the
match throws `MatchError` at run time:

```
match may not be exhaustive.
It would fail on pattern case: DisplayThreshold.Below(_), DisplayThreshold.Above(_), ...
```

Add the new cases, or a wildcard if the code genuinely does not care:

```scala
threshold match
  case DisplayThreshold.Disabled              => ...
  case DisplayThreshold.TransparentBand(band) => ...
  case DisplayThreshold.Below(cutoff)         => ...
  case DisplayThreshold.Above(cutoff)         => ...
  case DisplayThreshold.TwoSided(inner, outer) => ...
```

`hides` already accounts for every case, so code that asks the threshold rather
than matching on it needs no edit. `TwoSided(band, None)` hides exactly what
`TransparentBand(band)` hides, so the old case keeps its meaning.

### PDF font catalogs index a face, not a family

`PdfFont.fromBytes` gained a trailing `weight: FontWeight = FontWeight.Regular`, and
`PdfFontCatalog` now indexes `(family, weight)`. A catalog that never mentions weight behaves
exactly as before, so most callers need no edit.

Two changes are visible. Registering the same family twice is now legal when the weights differ —
that is how a document gets a bold face — and `PdfRenderError.DuplicateFontFamily` has been removed
because it can no longer be produced. Matching on it no longer compiles:

```
Not found: DuplicateFontFamily
```

Use `DuplicateFontFace(family, weight)`, which is raised when two registered faces share both. A
text run asking for a weight no registered face carries is `MissingFontWeight(family, weight)`
rather than a silent fallback to the regular face.

### `GraphicParams`, `TextStyle` and `LayoutPolicy` gained typographic weight

All three are `final case class`es, so their `apply`, `copy` and `unapply` descriptors changed.
Every field is trailing and defaulted, so named-argument calls are unaffected; a positional call
that filled every field no longer compiles.

`GraphicParams`'s constructor is private and its `copy` is not accessible, so callers enter through
`GraphicParams.checked`/`.unsafe` and are unaffected. Set the channel with `withFontWeight`:

```scala
GraphicParams.unsafe(fill = Some(Rgba.Black)).withFontWeight(FontWeight.Bold)
```

`RenderRequirement.TextStyle` also gained a trailing `fontWeight`. A backend harness that
destructures it positionally with five bindings no longer compiles:

```
Wrong number of argument patterns for intaglio.RenderRequirement.TextStyle; expected: (GraphicsName, Rgba, Double, Option[String], Double, Option[FontWeight])
```

### Backend paint records and the JavaFX font contract carry a weight

`Java2DPaint` and `JavaFxPaint` gained a trailing defaulted `fontWeight`, changing their `apply`,
`copy` and constructor descriptors. Both are public because a backend author reads them; a
positional construction that filled every field no longer compiles, and a named one is unaffected.

`JavaFxGraphicsContext.setFont` gained a third parameter:

```
def setFont(family: Option[String], sizePx: Double, weight: Option[FontWeight]): Unit
```

An implementation of that trait — the interception point for a test double or an alternative canvas
— must add the parameter. Ignoring it draws at the face's own weight, which is the previous
behaviour:

```
class MyContext extends JavaFxGraphicsContext:
  override def setFont(family: Option[String], sizePx: Double, weight: Option[FontWeight]): Unit =
    ...
```

### `Grob.rect` and `Grob.lines` gained a geometry parameter before `gp`

`Grob.rect` and `Grob.rectUnsafe` now take `cornerRadius: ExtentExpr` between
`anchor` and `gp`, and `Grob.lines` takes `interpolation: LineInterpolation`
between `points` and `gp`. Both default to the previous behaviour, so a call
that already named its arguments is unaffected. A call that passed `gp`
positionally no longer compiles. For `Grob.rect`:

```
Found:    intaglio.GraphicParams
Required: intaglio.ExtentExpr
```

and for `Grob.lines`:

```
Found:    intaglio.GraphicParams
Required: intaglio.LineInterpolation
```

The parameter is placed with the other geometry rather than after `name` so the
signature reads in the order the shape is described. Name the argument:

```scala
// before
Grob.rect(centre, size, Anchor.Center, params)
Grob.lines(points, params)

// after
Grob.rect(centre, size, Anchor.Center, gp = params)
Grob.lines(points, gp = params)
```

The types of the two new parameters do not coincide with `GraphicParams`, so
this is always a compile error and never a silent rebinding.

The full signatures are now:

```scala
Grob.rect(
  center: Point,
  size: Size,
  anchor: Anchor = Anchor.Center,
  cornerRadius: ExtentExpr = ExtentExpr.zero,
  gp: GraphicParams = GraphicParams.unsafe(),
  viewport: Option[Viewport] = None,
  name: Option[GraphicsName] = None
): Either[GraphicsError, Grob]

Grob.lines(
  points: Vector[Point],
  interpolation: LineInterpolation = LineInterpolation.Linear,
  gp: GraphicParams = GraphicParams.unsafe(),
  viewport: Option[Viewport] = None,
  name: Option[GraphicsName] = None
): Either[GraphicsError, Grob]
```

### `DeviceElement` gained an `Annotated` case

A backend, or anything that walks a `DeviceScene`, matches exhaustively on
`DeviceElement`. The new case carries metadata around already-lowered children
and nothing else — no name, no clip, no rotation — so a backend that cannot
express the metadata draws the children as if the wrapper were absent:

```scala
case DeviceElement.Annotated(_, children) =>
  children.foreach(draw)
```

Only the SVG backend reads the metadata. `Grob` gained the matching
`Grob.Annotated` case; anything that walks a `Grob` tree through
`Grob#children` already traverses it, because the wrapper reports its child
there.

### `DevicePrimitive.RectShape` gained `cornerRadius`

The field sits between `height` and `gp`, so a full destructuring gains one
binding:

```scala
// before
case DevicePrimitive.RectShape(x, y, width, height, gp, name) => …

// after
case DevicePrimitive.RectShape(x, y, width, height, cornerRadius, gp, name) => …
```

The value is already resolved to device pixels and already clamped to half the
shorter side, so a backend rounds the corner it is given and never re-derives a
limit. Zero means the sharp rectangle a backend drew before this field existed;
emit exactly the same output for it, so an unrounded scene stays byte-identical.

### `PointShape` gained `Diamond`

Every exhaustive match on `PointShape` — each backend has one, and so does each
conformance harness's `pointShapeKind` table — must handle it. A diamond lowers
to a closed four-vertex polyline on the axes, so a backend that already draws
`Triangle` as a polygon needs the same treatment:

```scala
case PointShape.Diamond => // closed polygon, four vertices
```

Its area equals the `Circle` of the same size (half-diagonal
`PointShape.diamondHalfDiagonal(r)` = `r * sqrt(pi / 2)`), which means it is the
one shape that extends past the `[-r, r]` box. A backend that assumes a point
mark fits that box — for hit-testing, for culling, or for a dirty-rectangle
optimisation — must widen the assumption for `Diamond`.

### The default Scala version moved to the LTS line

The build now defaults to Scala 3.3.8 and cross-builds 3.9.0 in CI. Source
compiled against Intaglio needs no change; a consumer pinning the compiler
should know that the published artifact is built with the LTS, so any
Scala 3.3.8-or-later compiler can read it.

One source-level consequence inside Intaglio: Scala 3.3 does not accept an
irrefutable tuple pattern on the left of a `<-` in a `for` comprehension over
`Either`, which 3.4 relaxed. `ContinuousScale.train` was rewritten to bind the
pair and project it. An extension author whose own code uses that form will hit
`value withFilter is not a member of Either[…]` when compiling on the LTS.
