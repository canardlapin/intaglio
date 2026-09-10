package external.stat

import intaglio.*

final case class Observation(x: Double, y: Double, panel: String = "all")

final case class CenteredRow[+Row](
    source: Row,
    members: Vector[Row],
    sourceIndex: Int,
    centeredX: Double,
    centeredY: Double,
    scope: StatScope
) extends StatRow[Row]:
  val category: Option[String] = None
  val kind: String = "external-center"

/** A real external statistic: neither its implementation nor its output row is under `intaglio`.
  */
final case class CenterStat[Row](x: Row => Double, y: Row => Double) extends Stat[Row]:
  val label: String = "external-center"

  val contract: StatContract =
    StatContract(
      inputPreservation = StatInputPreservation.OneToOne,
      grouping = StatGroupingPolicy.None,
      summarization = StatSummarizationPolicy.Custom("subtract each batch mean"),
      rejection = StatRejectionPolicy.FailBatch,
      mapping = StatMappingPolicy.Replace,
      geometry = StatGeometryPolicy.Require(Geom.Point),
      lowering = StatLowering.Geom
    )

  def compute[Input <: Row](
      batch: StatBatch[Input],
      context: StatContext
  ): Either[StatError, StatResult.Aux[Input, CenteredRow[Input]]] =
    if batch.isEmpty then Left(StatError.Rejected("centering requires a non-empty batch"))
    else if context.geom != Geom.Point then
      Left(StatError.Rejected(s"centering cannot lower ${context.geom.label}"))
    else
      for
        xs <- batch.evaluate(Aesthetic.X.label, x)
        ys <- batch.evaluate(Aesthetic.Y.label, y)
        _ <- xs.find(value => !value.isFinite) match
          case Some(value) => Left(StatError.NonFiniteInput(Aesthetic.X.label, value))
          case None        => Right(())
        _ <- ys.find(value => !value.isFinite) match
          case Some(value) => Left(StatError.NonFiniteInput(Aesthetic.Y.label, value))
          case None        => Right(())
      yield
        val meanX = xs.sum / xs.length.toDouble
        val meanY = ys.sum / ys.length.toDouble
        val rows = batch.inputs.zip(xs.zip(ys)).map { case (input, (xValue, yValue)) =>
          CenteredRow(
            source = input.value,
            members = Vector(input.value),
            sourceIndex = input.index,
            centeredX = xValue - meanX,
            centeredY = yValue - meanY,
            scope = context.scope
          )
        }
        val mapping = AesSpec[CenteredRow[Input]](
          x = Some(AesValue.total(_.centeredX)),
          y = Some(AesValue.total(_.centeredY))
        )
        StatResult[Input, CenteredRow[Input]](rows, mapping)

class OpenStatSuite extends munit.FunSuite:
  private val values =
    Vector(
      Observation(1.0, 2.0),
      Observation(3.0, 4.0),
      Observation(5.0, 9.0)
    )

  private val stat = CenterStat[Observation](_.x, _.y)

  private val layer =
    Layer
      .fromMapping(
        geom = Geom.Point,
        mapping = AesSpec.empty[Observation],
        inheritMapping = false,
        stat = stat
      )
      .orThrow

  test("an external stat compiles without compiler registration and retains its output type") {
    val trained = Plot(values)
      .addLayer(layer)
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
    val resolved = trained.layers.head
    val output = resolved.statFrame.rows.collect { case row: CenteredRow[?] => row }

    assertEquals(resolved.stat.contract, stat.contract)
    assertEquals(output.length, values.length)
    assertEquals(output.map(_.sourceIndex), Vector(0, 1, 2))
    assertEquals(output.map(_.centeredX), Vector(-2.0, 0.0, 2.0))
    assertEquals(output.map(_.scope), Vector.fill(3)(StatScope.Plot))
    assertEquals(resolved.rows.map(_.x), Vector(-2.0, 0.0, 2.0))
    assertEquals(resolved.rows.map(_.y), Vector(-3.0, -1.0, 4.0))
    assert(resolved.rows.forall(_.computed.aesthetics.isEmpty))
    assert(resolved.grobs.forall(_.isInstanceOf[Grob.Points]))
  }

  test("external stats receive the concrete facet context and current typed batch") {
    val faceted = Vector(
      Observation(0.0, 1.0, "a"),
      Observation(2.0, 3.0, "a"),
      Observation(10.0, 5.0, "b"),
      Observation(14.0, 9.0, "b")
    )
    val facet = FacetSpec.wrap[Observation](_.panel).orThrow
    val trained = Plot(faceted)
      .withFacet(facet)
      .addLayer(layer)
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
        )
      )
      .fold(error => fail(error.message), identity)

    assertEquals(
      trained.facetPanels.map(_.layers.head.rows.map(_.x)),
      Vector(Vector(-1.0, 1.0), Vector(-2.0, 2.0))
    )
    trained.facetPanels.foreach { panel =>
      val output = panel.layers.head.statFrame.rows.collect { case row: CenteredRow[?] => row }
      assertEquals(output.map(_.sourceIndex), Vector(0, 1))
      assert(output.forall(_.scope == StatScope.Facet(panel.cell)))
    }
  }

  test("an external StatError is translated at the compiler boundary") {
    val result = Plot(Vector.empty[Observation])
      .addLayer(layer)
      .flatMap(PlotCompiler.resolve(_))

    assertEquals(
      result.left.toOption,
      Some(GraphicsError.StatRejected("external-center", "centering requires a non-empty batch"))
    )
  }
