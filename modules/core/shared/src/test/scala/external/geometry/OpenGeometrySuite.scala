package external.geometry

import intaglio.*

final case class Observation(x: Double, y: Double)

/** A true consumer-defined geometry. It uses only the public resolved-row and grob contracts. */
case object CrossGeom extends Geom:
  val label: String = "external-cross"

  val contract: GeomAestheticContract =
    GeomAestheticContract
      .checked(
        required = Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
        optional = Vector(
          Aesthetic.Color,
          Aesthetic.Alpha,
          Aesthetic.Size,
          Aesthetic.Group
        )
      )
      .orThrow

  def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
    val arm = 0.1
    batch.rows.zipWithIndex.foldLeft[Either[GraphicsError, Vector[Grob]]](Right(Vector.empty)) {
      case (result, (row, index)) =>
        result.flatMap { grobs =>
          val segments = Vector(
            Point.nativeUnsafe(row.x - arm, row.y - arm) ->
              Point.nativeUnsafe(row.x + arm, row.y + arm),
            Point.nativeUnsafe(row.x - arm, row.y + arm) ->
              Point.nativeUnsafe(row.x + arm, row.y - arm)
          )
          Grob
            .segments(
              segments,
              gp = row.gp,
              name = Some(
                GraphicsName.unsafe(s"external-cross-${batch.context.layerIndex}-$index")
              )
            )
            .map(grobs :+ _)
        }
    }

/** A consumer-defined coordinate that translates logical output in native panel units. */
final case class ShiftCoord(x: Double, y: Double, clipping: Clip = Clip.Off) extends Coord:
  override def transform(input: CoordInput): Either[GraphicsError, CoordResult] =
    CoordinateTransform.translate(input, x, y)

  override def guideLayout(xRange: Interval, yRange: Interval): CoordGuideLayout =
    CoordGuideLayout(
      AxisSide.Bottom,
      Interval.unsafe(xRange.lower + x, xRange.upper + x),
      AxisSide.Left,
      Interval.unsafe(yRange.lower + y, yRange.upper + y)
    )

class OpenGeometrySuite extends munit.FunSuite:
  private val values = Vector(Observation(1.0, 10.0), Observation(2.0, 20.0))

  private val mapping =
    AesSpec.empty[Observation].withPosition(_.x, _.y)

  private val layer =
    Layer
      .fromMapping(
        geom = CrossGeom,
        mapping = mapping,
        inheritMapping = false
      )
      .orThrow

  private def native(value: LengthExpr): Double =
    value match
      case LengthExpr.Const(length) if length.unit == LengthUnit.Native => length.value
      case other => fail(s"expected a native coordinate, found $other")

  test("an external geom declares its contract and lowers without compiler registration") {
    val trained = Plot(values)
      .addLayer(layer)
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
    val resolved = trained.layers.head

    assertEquals(resolved.geom, CrossGeom)
    assertEquals(resolved.rows.map(row => row.x -> row.y), Vector(1.0 -> 10.0, 2.0 -> 20.0))
    assertEquals(
      resolved.grobs.map(_.name.map(_.value)),
      Vector(Some("external-cross-0-0"), Some("external-cross-0-1"))
    )
    assert(resolved.grobs.forall(_.isInstanceOf[Grob.Segments]))
  }

  test("an external coordinate transforms rows, grobs, ranges, and clipping") {
    val trained = Plot(values)
      .addLayer(layer)
      .map(_.withCoord(ShiftCoord(5.0, -3.0)))
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(
            policy = Some(LayoutPolicy()),
            expansion = RangeExpansion.none
          )
        )
      )
      .fold(error => fail(error.message), identity)
    val resolved = trained.layers.head

    assertEquals(resolved.rows.map(row => row.x -> row.y), Vector(6.0 -> 7.0, 7.0 -> 17.0))
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(6.0, 7.0)))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(7.0, 17.0)))
    assertEquals(trained.layout.map(_.clip), Some(Clip.Off))

    val first = resolved.grobs.head.asInstanceOf[Grob.Segments].segments.head._1
    assertEqualsDouble(native(first.x), 5.9, 1e-12)
    assertEqualsDouble(native(first.y), 6.9, 1e-12)
  }

  test("coordinate helpers reject non-finite translations as values") {
    val input = CoordInput(Vector.empty, None)
    assert(
      CoordinateTransform.translate(input, Double.NaN, 0.0).left.toOption.exists {
        case GraphicsError.InvalidCoordinateTranslation(x, y) => x.isNaN && y == 0.0
        case _                                                => false
      }
    )
  }
