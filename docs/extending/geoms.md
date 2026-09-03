# Extending geoms

A geom turns already-resolved rows into portable grobs. By the time it runs, statistics have
computed, scales have trained, rows have been evaluated and dropped, and positions have been
adjusted: a `ResolvedRow` holds numeric coordinates in panel-native units and a complete
`GraphicParams`. The geom's only job is geometry.

## What you implement

`Geom` has three members:

- `def label: String` — appears in diagnostics and in `LayerSemantics.geom`.
- `def contract: GeomAestheticContract` — the complete mapping contract, checked before any row is
  evaluated.
- `def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]]`

`GeomAestheticContract` declares three vectors. `required: Vector[RequiredAesthetic]` are the
positional channels the geom cannot work without; a layer missing one fails in `Plot.addLayer`, not
at render time. `optional: Vector[Aesthetic[?]]` is everything else the geom understands; a bound
aesthetic outside `required ++ optional` is `GraphicsError.UnsupportedGeomAesthetic` rather than
being silently ignored. `groupConstant: Vector[Aesthetic[?]]` are optional channels whose *resolved*
values must not vary within one structural `GroupKey` — a line whose colour changes mid-path is
`GraphicsError.VaryingGroupAesthetic` naming both row indices, never a first-row style reduction.

`GeomAestheticContract.checked` enforces its own well-formedness: required and optional must be
disjoint, each must be distinct, `groupConstant` must be a subset of `optional`, and only
`Color`, `Fill`, `Alpha`, `Size`, `LineType`, and `LineWidth` may be group-constant. Build the
contract once, in a `val`.

`GeomBatch[Row]` carries `rows: Vector[ResolvedRow[Row]]` and a `GeomContext(layerIndex, theme)`.
`batch.groups` partitions rows by structural group, preserving first-encounter order between groups
and row order within them; use it for any geom whose output spans several rows. A `ResolvedRow`
exposes `x`, `y`, the optional bounds `xEnd`/`yEnd`/`xMin`/`xMax`/`yMin`/`yMax`, the categorical
`xBand`/`yBand`, a pre-built native `point`, `label`, `shape`, `textAnchor`, `rotationDegrees`, the
resolved `gp` and `size`, the grouping decision and key, and its `statRow` with the original
`source`.

Lowering must be deterministic and must not consult ambient state. Name your grobs: a
`GraphicsName` is what a backend emits as `data-name` and what `RendererHarness.containsMarker`
finds.

## A worked geom

```scala mdoc:compile-only
import intaglio.*

/** A vertical interval per row, drawn from an already-resolved ymin/ymax pair. */
case object IntervalGeom extends Geom:
  val label: String = "interval"

  val contract: GeomAestheticContract =
    GeomAestheticContract
      .checked(
        required = Vector(RequiredAesthetic.X, RequiredAesthetic.YMin, RequiredAesthetic.YMax),
        optional = Vector(Aesthetic.Color, Aesthetic.Alpha, Aesthetic.LineWidth),
        groupConstant = Vector(Aesthetic.Color, Aesthetic.Alpha)
      )
      .orThrow

  def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
    batch.rows.zipWithIndex.foldLeft[Either[GraphicsError, Vector[Grob]]](Right(Vector.empty)) {
      case (accumulated, (row, index)) =>
        accumulated.flatMap { grobs =>
          (row.yMin, row.yMax) match
            case (Some(low), Some(high)) =>
              Grob
                .segments(
                  Vector(
                    Point.nativeUnsafe(row.x, low) -> Point.nativeUnsafe(row.x, high)
                  ),
                  gp = row.gp,
                  name = Some(GraphicsName.unsafe(s"interval-$index"))
                )
                .map(grobs :+ _)
            case _ =>
              Left(GraphicsError.MissingAesthetic(label, "ymin"))
        }
    }
```

The `MissingAesthetic` branch is unreachable for a layer the compiler validated — `RequiredAesthetic.YMin`
in the contract guarantees the binding exists — but it is the honest way to consume an `Option`
without an `unsafe` call, and it is what runs if a caller constructs a `GeomBatch` by hand.

`GeomAestheticContract.checked(...).orThrow` is the throwing convenience for a statically declared
contract; `GeomAestheticContract.unsafe(...)` is the same thing spelled once. Both throw
`IllegalArgumentException` if the contract is malformed, which for a literal contract is a
construction-time failure of your own code rather than a runtime failure of a caller's plot. See
[`unsafe.md`](unsafe.md).

## Laws to run

- **`GeomLaws(geom, successfulBatch)`** (`ExtensionLaws.scala`) — three laws. The *checked public
  contract* law re-runs `GeomAestheticContract.checked` on your contract's own three vectors and
  requires it to return exactly that contract, and requires `label` to be non-blank and `contract`
  to be stable across reads. The *successful fixture* law requires your batch to lower without
  error. The *deterministic lowering* law lowers the same batch twice and requires equal results.

Building the fixture means constructing `ResolvedRow` values directly. That is deliberate: it is the
compiler's output type, it has no hidden state, and constructing it by hand is how you test a geom
without compiling a whole plot.

```scala mdoc:compile-only
import intaglio.*
import intaglio.laws.*

final case class Estimate(index: Double, lower: Double, upper: Double)

case object BracketGeom extends Geom:
  val label: String = "bracket"

  val contract: GeomAestheticContract =
    GeomAestheticContract.unsafe(
      Vector(RequiredAesthetic.X, RequiredAesthetic.YMin, RequiredAesthetic.YMax),
      Vector(Aesthetic.Color, Aesthetic.Alpha)
    )

  def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
    batch.rows.zipWithIndex.foldLeft[Either[GraphicsError, Vector[Grob]]](Right(Vector.empty)) {
      case (accumulated, (row, index)) =>
        accumulated.flatMap { grobs =>
          (row.yMin, row.yMax) match
            case (Some(low), Some(high)) =>
              Grob
                .segments(
                  Vector(Point.nativeUnsafe(row.x, low) -> Point.nativeUnsafe(row.x, high)),
                  gp = row.gp,
                  name = Some(GraphicsName.unsafe(s"bracket-$index"))
                )
                .map(grobs :+ _)
            case _ =>
              Left(GraphicsError.MissingAesthetic(label, "ymin"))
        }
    }

def resolvedEstimate(index: Int, value: Estimate): ResolvedRow[Estimate] =
  val centre = (value.lower + value.upper) / 2.0
  ResolvedRow(
    rowIndex = index,
    source = value,
    statRow = StatRow.Identity(value),
    x = value.index,
    y = centre,
    xBand = None,
    yBand = None,
    xEnd = None,
    yEnd = None,
    xMin = None,
    xMax = None,
    yMin = Some(value.lower),
    yMax = Some(value.upper),
    point = Point.nativeUnsafe(value.index, centre),
    label = None,
    grouping = GroupingDecision.Ungrouped,
    groupKey = None,
    group = None,
    subpath = None,
    gp = GraphicParams.unsafe(),
    size = ExtentExpr.pointsUnsafe(4.0)
  )

val estimates: Vector[Estimate] =
  Vector(Estimate(0.0, 1.2, 2.4), Estimate(1.0, 0.8, 1.9), Estimate(2.0, -0.3, 0.6))

val fixture: GeomBatch[Estimate] =
  GeomBatch(
    estimates.zipWithIndex.map((value, index) => resolvedEstimate(index, value)),
    GeomContext(0, Theme.default)
  )

val geomSuite: LawSuite =
  GeomLaws(BracketGeom, fixture)

val failures: Vector[LawFailure] =
  geomSuite.failures
```

`GeomLaws` does not check that your grobs are *correct*, only that the contract is well formed and
lowering is total on the fixture and deterministic. Geometry correctness belongs to
`PointShapeLaws`, `RectCornerLaws`, and `LineInterpolationLaws` for the shapes the shared lowering
owns, and to your own golden or property tests for the shapes you invent. If your geom emits
`Grob.Lines`, read [`coords.md`](coords.md) before you assume a flipped coordinate will transpose it
correctly.
