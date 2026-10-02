package intaglio

import intaglio.interaction.*

/** Every row of [[InteractionCoverage.entries]] is checked against a real interactive compilation:
  * the declared granularity must produce exactly the expected number of targets.
  */
class InteractionCoverageSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class R(id: String, x: Double, y: Double, g: String)

  // Two groups of three rows; x repeats so a summary has three positions.
  private val rows = Vector(
    R("a1", 1, 1.0, "a"),
    R("a2", 2, 2.0, "a"),
    R("a3", 3, 1.5, "a"),
    R("b1", 1, 3.0, "b"),
    R("b2", 2, 2.5, "b"),
    R("b3", 3, 3.5, "b")
  )

  private val space = ok(KeySpace("row", KeyCodec.text))
  private val revision = ok(DataRevision("d1"))

  private def targets[Row](builder: PlotBuilder[Row, ?])(key: Row => String): Int =
    val plot = ok(builder.build).plot
    val plan = ok(
      InteractionCompiler.compile(
        plot,
        space,
        revision,
        SemanticId.unsafe("p"),
        ok(PlanRevision("r"))
      )(key)
    )
    plan.groups.map(_.size).sum

  private def base = plot(rows).aes(_.x, _.y)

  private val checks: Map[String, () => (Int, Int)] = Map(
    "geomPoint" -> (() => (targets(base.geomPoint())(_.id), rows.length)),
    "geomText" -> (() => (targets(base.geomText(_.id))(_.id), rows.length)),
    "geomSegment" -> (() => (targets(base.geomSegment(_.x + 0.5, _.y))(_.id), rows.length)),
    "geomTile" -> (() => (targets(base.geomTile(_ => 0.4, _ => 0.4))(_.id), rows.length)),
    "geomErrorBar" -> (() => (targets(base.geomErrorBar(_.y - 0.5, _.y + 0.5))(_.id), rows.length)),
    "geomLine" -> (() => (targets(base.group(_.g).geomLine())(_.id), 2)),
    "geomArea" -> (() => (targets(base.group(_.g).geomArea())(_.id), 2)),
    "geomRibbon" -> (() => (targets(base.group(_.g).geomRibbon(_.y - 0.5, _.y + 0.5))(_.id), 2)),
    "geomPolygon" -> (() => (targets(base.group(_.g).geomPolygon())(_.id), 2)),
    "geomHistogram" -> (() =>
      (targets(plot(rows).aes(_.y).geomHistogram(bins = ok(HistogramBins.count(3))))(_.id), 3)
    ),
    "geomSummary" -> (() => (targets(base.geomSummary())(_.id), 3)),
    "geomDensity" -> (() => (targets(plot(rows).aes(_.y).geomDensity())(_.id), 1)),
    "geomEcdf" -> (() => (targets(plot(rows).aes(_.y).geomEcdf(group = Some(_.g)))(_.id), 2)),
    "geomRaster" -> (() =>
      val field = ok(
        for
          x <- RegularGridAxis.cellCentered(0, 4, 4)
          y <- RegularGridAxis.cellCentered(0, 3, 3)
          f <- ScalarField2D.tabulate(x, y)(_ + _)
        yield f
      )
      (targets(plot(field).geomRaster())(c => s"${c.xIndex}-${c.yIndex}"), 12)
    ),
    "hline / vline" -> (() =>
      (targets(base.geomPoint().hline(2.0).vline(2.0))(_.id), rows.length + 2)
    )
  )

  test("the coverage matrix and its checks name the same components") {
    assertEquals(InteractionCoverage.entries.map(_.component).toSet, checks.keySet)
    assertEquals(InteractionCoverage.entries.map(_.component).distinct.length, checks.size)
  }

  InteractionCoverage.entries.foreach { entry =>
    test(s"${entry.component} compiles to ${entry.granularity} targets") {
      val (obtained, expected) = checks(entry.component)()
      assertEquals(obtained, expected)
    }
  }
