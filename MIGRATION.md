# Migration guide

Each section names one breaking change, the compiler error it produces, and the
edit that fixes it. Changes are listed newest first, under the release that
introduces them. The compatibility policy behind these notes —  which courts a
given release preserves, and what moving the baseline requires — is
[docs/compatibility.md](docs/compatibility.md).

## Unreleased

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
