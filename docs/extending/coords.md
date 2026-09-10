# Extending coordinates

A coordinate is the logical-to-physical stage. It runs after geom lowering, so it receives trained
layers that already hold resolved rows and grobs, and it returns the same layers rearranged. It also
declares where guides go, whether the panel has a forced aspect, whether it clips, and whether it can
be faceted.

## What you implement

`Coord` has one abstract member and four with defaults:

- `def clipping: Clip` — abstract. Whether the panel viewport clips.
- `def transform(input: CoordInput): Either[GraphicsError, CoordResult]` — defaults to
  `CoordinateTransform.identity`.
- `def guideLayout(xRange: Interval, yRange: Interval): CoordGuideLayout` — defaults to
  `Bottom`/`Left` with the ranges unchanged. Return the *physical* placement: `Coord.Flipped` reports
  `CoordGuideLayout(AxisSide.Left, xRange, AxisSide.Bottom, yRange)`, so the logical x guide is drawn
  on the left.
- `def panelAspect(xRange, yRange): Either[GraphicsError, Option[CoordinateRatio]]` — defaults to
  `None`. `Coord.Fixed` returns `yRange.width / xRange.width * ratio` and rejects a degenerate range.
- `def validateFacet: Either[GraphicsError, Unit]` — defaults to `Right(())`. Return a `Left` if your
  coordinate cannot be repeated across panels, as `Coord.Fixed` does with
  `GraphicsError.FacetFixedCoordinates`.

`CoordInput` carries `layers: Vector[TrainedLayer]`, the optional physical `ranges`, and the
`PlotScaleRegistry` — the last of these is how `Coord.Zoom` maps a raw data-space window through the
trained position scale after statistics have run. `CoordResult` carries the transformed layers and
ranges.

`CoordinateTransform` supplies the reusable whole-scene operations: `identity`, `transpose`,
checked `translate(input, x, y)`, and `zoom(input, x, y)`. Each rewrites every resolved row, every
`ResolvedReferenceLine`, and every grob in every layer. Built-in Cartesian, flipped, fixed, and zoom
coordinates use the same public methods you do.

## Transposing a scene: `LineInterpolation#transposed`

`Grob.Lines` carries a `LineInterpolation` — `Linear`, `StepAfter`, or `StepBefore` — which device
lowering expands into corner points. A step is not symmetric under exchanging the axes: the corner a
`StepAfter` inserts at `(x(i+1), y(i))` becomes the corner a `StepBefore` inserts at `(y(i), x(i+1))`.

**A coordinate that transposes axes must apply `LineInterpolation#transposed` to every `Grob.Lines`
it flips.** Flipping only the points leaves a track that holds and then jumps drawn as one that jumps
and then holds — a silently wrong plot, not an error. `CoordinateTransform.transpose` already does
this; if you rewrite grobs yourself, do it too.

```scala mdoc:compile-only
import intaglio.*

/** Exchange a grob's axes. Note the interpolation, not just the points. */
def transposeGrob(grob: Grob): Either[GraphicsError, Grob] =
  def flip(point: Point): Point =
    Point(point.y, point.x)

  grob match
    case lines: Grob.Lines =>
      Grob.lines(
        lines.points.map(flip),
        interpolation = lines.interpolation.transposed,
        gp = lines.gp,
        viewport = lines.viewport,
        name = lines.name
      )
    case marks: Grob.Points =>
      Grob.points(
        marks.points.map(flip),
        size = marks.size,
        shape = marks.shape,
        gp = marks.gp,
        viewport = marks.viewport,
        name = marks.name
      )
    case other =>
      Right(other)
```

`Grob.Lines`, `Grob.Points`, and the other grob cases have `private[intaglio]` constructors, so a
rewrite goes back through the checked companion constructors (`Grob.lines`, `Grob.points`, …) rather
than through `copy`. That is why the function returns `Either`.

`LineInterpolationLaws` (in `docs/extending/backends.md`) contains the executable statement of the
transposition rule, so a coordinate that gets it wrong fails a published law rather than a golden
image.

## A worked coordinate

```scala mdoc:compile-only
import intaglio.*
import intaglio.laws.*

final case class Observation(x: Double, y: Double)

/** Shift the panel by a native offset and place the guides at the shifted ranges. */
final case class ShiftCoord(dx: Double, dy: Double, clipping: Clip = Clip.Off) extends Coord:
  override def transform(input: CoordInput): Either[GraphicsError, CoordResult] =
    CoordinateTransform.translate(input, dx, dy)

  override def guideLayout(xRange: Interval, yRange: Interval): CoordGuideLayout =
    CoordGuideLayout(
      AxisSide.Bottom,
      Interval.unsafe(xRange.lower + dx, xRange.upper + dx),
      AxisSide.Left,
      Interval.unsafe(yRange.lower + dy, yRange.upper + dy)
    )

val observations: Vector[Observation] =
  Vector(Observation(1.0, 2.0), Observation(3.0, 6.0), Observation(5.0, 4.0))

val trained: Either[GraphicsError, TrainedPlot] =
  Plot(observations)
    .addLayer(Layer.point[Observation](_.x, _.y))
    .flatMap(PlotCompiler.resolve(_))

val xRange: Interval = Interval.unsafe(1.0, 5.0)
val yRange: Interval = Interval.unsafe(2.0, 6.0)

val suites: Either[GraphicsError, Vector[LawSuite]] =
  trained.map { plot =>
    val input = CoordInput(plot.layers, Some(xRange -> yRange))
    Vector(
      CoordLaws(ShiftCoord(2.0, -1.0), input, xRange, yRange),
      CoordinateInvolutionLaws(input)
    )
  }

val failures: Either[GraphicsError, Vector[LawFailure]] =
  suites.map(_.flatMap(_.failures))
```

The fixture comes from `PlotCompiler.resolve`, whose default `ProvenancePolicy` is `Full` — the laws
compare resolved rows, so a lean plot would compare empty vectors and prove nothing.

## Laws to run

- **`CoordLaws(coord, successfulInput, xRange, yRange)`** (`ExtensionLaws.scala`) — three laws. The
  *successful layer-preserving transform* law requires the fixture to be accepted and requires the
  layer count to be unchanged: a coordinate rearranges layers, it does not add or remove them. The
  *deterministic transform* law requires two transforms of the same input to agree, comparing
  `ranges`, layer indices, geoms, stat labels and contracts, positions, data sizes, annotations,
  grouping decisions, scale declarations, resolved rows, dropped rows, and grobs. The *deterministic
  layout declarations* law reads `clipping`, `guideLayout`, `panelAspect`, and `validateFacet` twice
  and requires them to agree — which is what forbids a coordinate that caches into mutable state.
  Use `CoordLaws.withEquality` when your `CoordResult` needs a tolerance-aware comparison.
- **`CoordinateInvolutionLaws(input)`** (`SceneLayoutLaws.scala`) — the native transpose is an
  involution. It applies `Coord.Flipped().transform` twice to *your* input and requires the public
  result — rows, grobs, annotations, and panel ranges — to be the original. Run it on the input your
  coordinate consumes: it proves that the fixture round-trips through the built-in transposition, and
  it is the law that catches a grob type whose flip is not self-inverse. `withEquality` takes an
  explicit `CoordResult` comparison.

Neither kit checks that your placement is *right*. If your coordinate moves guides, assert the
`CoordGuideLayout` you expect directly, and run the plot through
[`RendererConformance`](backends.md) if the change should be visible in backend output.
