package external.laws

import intaglio.*
import intaglio.laws.*

final case class Observation(x: Double, y: Double, confidence: Double)

final case class OffsetScale(name: GraphicsName, offset: Double) extends Scale[Double, Double]:
  override val descriptor: ScaleDescriptor =
    ScaleDescriptor(name, ScaleKind.Generic, ScaleDomain.Unspecified, ScaleTraining.Fixed)

  def mapValue(value: Double): Option[Double] =
    Option.when(value.isFinite)(value + offset)

final case class CenteredRow[+Row](
    source: Row,
    members: Vector[Row],
    centeredX: Double,
    centeredY: Double
) extends StatRow[Row]:
  val category: Option[String] = None
  val kind: String = "external-center-law"

case object CenterStat extends Stat[Observation]:
  val label: String = "external-center-law"
  val contract: StatContract =
    StatContract(
      StatInputPreservation.OneToOne,
      StatGroupingPolicy.None,
      StatSummarizationPolicy.Custom("subtract the batch mean"),
      StatRejectionPolicy.FailBatch,
      StatMappingPolicy.Replace,
      StatGeometryPolicy.Require(Geom.Point),
      StatLowering.Geom
    )

  def compute[Input <: Observation](
      batch: StatBatch[Input],
      context: StatContext
  ): Either[StatError, StatResult.Aux[Input, CenteredRow[Input]]] =
    if batch.isEmpty then Left(StatError.Rejected("centering requires observations"))
    else
      val meanX = batch.rows.map(_.x).sum / batch.size.toDouble
      val meanY = batch.rows.map(_.y).sum / batch.size.toDouble
      val rows = batch.rows.map(row => CenteredRow(row, Vector(row), row.x - meanX, row.y - meanY))
      Right(
        StatResult(
          rows,
          AesSpec[CenteredRow[Input]](
            x = Some(AesValue.total(_.centeredX)),
            y = Some(AesValue.total(_.centeredY))
          )
        )
      )

case object CrossGeom extends Geom:
  val label: String = "external-cross-law"
  val contract: GeomAestheticContract =
    GeomAestheticContract.unsafe(
      Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
      Vector(Aesthetic.Color, Aesthetic.Alpha, Aesthetic.Size)
    )

  def lower[Row](batch: GeomBatch[Row]): Either[GraphicsError, Vector[Grob]] =
    batch.rows.zipWithIndex.foldLeft[Either[GraphicsError, Vector[Grob]]](Right(Vector.empty)) {
      case (result, (row, index)) =>
        result.flatMap { grobs =>
          val arm = 0.1
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
              name = Some(GraphicsName.unsafe(s"external-law-cross-$index"))
            )
            .map(grobs :+ _)
        }
    }

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

final case class Series(values: Vector[Observation])

given seriesRecipe: PlotRecipe.Aux[Series, Observation] =
  PlotRecipe.checked { series =>
    plot(series.values)
      .aes(_.x, _.y)
      .geomPoint()
      .build
      .map(PlotSpec.fromProgram)
  }

class ExternalExtensionLawsSuite extends munit.FunSuite:
  private val values =
    Vector(
      Observation(1.0, 2.0, 0.8),
      Observation(3.0, 6.0, 0.9),
      Observation(5.0, 4.0, 0.7)
    )

  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  private def resolvedRow(index: Int, value: Observation): ResolvedRow[Observation] =
    ResolvedRow(
      rowIndex = index,
      source = value,
      statRow = StatRow.Identity(value),
      x = value.x,
      y = value.y,
      xBand = None,
      yBand = None,
      xEnd = None,
      yEnd = None,
      xMin = None,
      xMax = None,
      yMin = None,
      yMax = None,
      point = Point.nativeUnsafe(value.x, value.y),
      label = None,
      grouping = GroupingDecision.Ungrouped,
      groupKey = None,
      group = None,
      subpath = None,
      gp = GraphicParams.unsafe(),
      size = ExtentExpr.pointsUnsafe(4.0)
    )

  test("an external typed aesthetic passes the published storage laws") {
    val confidence = Aesthetic.unsafe[Double]("confidence-law")
    val sameLabel = Aesthetic.unsafe[Double]("confidence-law")
    assertValid(
      AestheticLaws(
        confidence,
        sameLabel,
        AesValue.constant[Observation, Double](0.5),
        AesValue.total[Observation, Double](_.confidence)
      )
    )
  }

  test("an external scale passes the published mapping laws") {
    val scale = OffsetScale(GraphicsName.unsafe("external-offset"), 273.15)
    assertValid(ScaleLaws(scale, Vector(-273.15, 0.0, 100.0, Double.NaN)))
  }

  test("an external stat passes public contract and preservation laws") {
    val batch = StatBatch(values, AesSpec.empty[Observation].withPosition(_.x, _.y))
    val context = StatContext(0, Geom.Point, StatScope.Plot)
    assertValid(
      StatLaws(CenterStat, batch, context)(result => result.rows.map(_.toString))
    )
  }

  test("an external geom passes checked-contract and lowering laws") {
    val batch = GeomBatch(
      values.zipWithIndex.map((value, index) => resolvedRow(index, value)),
      GeomContext(0, Theme.default)
    )
    assertValid(GeomLaws(CrossGeom, batch))
  }

  test("an external coordinate passes transform and layout laws") {
    val trained = Plot(values)
      .addLayer(Layer.point[Observation](_.x, _.y))
      .flatMap(PlotCompiler.resolve(_))
      .orThrow
    val xRange = Interval.unsafe(1.0, 5.0)
    val yRange = Interval.unsafe(2.0, 6.0)
    val input = CoordInput(trained.layers, Some(xRange -> yRange))

    assertValid(CoordLaws(ShiftCoord(2.0, -1.0), input, xRange, yRange))
  }

  test("an external model recipe passes conversion and scene laws") {
    val source = Series(values)
    assertValid(
      PlotRecipeLaws(source, summon[PlotRecipe.Aux[Series, Observation]])(spec => spec.plot.data)
    )
  }

  test("law failures are structured and include thrown extension errors") {
    val throwing = new Scale[Double, Double]:
      val name: GraphicsName = GraphicsName.unsafe("throwing-scale")
      def mapValue(value: Double): Option[Double] = throw new IllegalStateException("boom")
    val failures = ScaleLaws(throwing, Vector(1.0)).failures

    assert(failures.nonEmpty)
    assert(failures.exists(_.detail.contains("IllegalStateException: boom")))
  }
