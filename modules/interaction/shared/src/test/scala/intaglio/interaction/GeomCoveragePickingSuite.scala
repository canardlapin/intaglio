package intaglio.interaction

import intaglio.*

/** Queries come from the fixture's data coordinates, independently of picking outlines/anchors. */
class GeomCoveragePickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private val context = RenderContext.unsafe(500, 400)
  private val space = ok(KeySpace("coverage", KeyCodec.text))
  private def compile[R](builder: PlotBuilder[R, ?])(key: R => String): InteractionPlan[String] =
    ok(
      InteractionCompiler.compile(
        ok(builder.build).plot,
        space,
        ok(DataRevision("d1")),
        SemanticId.unsafe("coverage"),
        ok(PlanRevision("p1")),
        PlotCompilerOptions(renderContext = Some(context))
      )(key)
    )
  private def hits(
      plan: InteractionPlan[String],
      x: Double,
      y: Double
  ): Vector[TargetInfo[String]] =
    val frame = ok(DeviceScene.fromScene(plan.scene, context))
      .frame(PlotRegion.Panel)
      .fold(e => fail(e.message), identity)
    val point = frame.nativeToDevice(DevicePoint(x, y)).fold(e => fail(e.message), identity)
    ok(ok(Picking.compile(plan, context)).hits(point)).map(_.target)

  private val cells = RegularGridAxis.cellCenteredUnsafe(0, 2, 2)
  private val field = ok(ScalarField2D.tabulate(cells, cells)(_ + _))
  test("heatmap and classed raster pick each source cell at independently specified centres") {
    val classes = Vector(
      ColorClass.unsafe("left", Palette.gradient(Rgba.Black, Rgba.White)),
      ColorClass.unsafe("right", Palette.gradient(Rgba.White, Rgba.Black))
    )
    val plans = Vector(
      compile(plot(field).geomHeatmap())(c => s"${c.xIndex}-${c.yIndex}"),
      compile(plot(field).geomRasterByClass(_.xIndex, classes))(c => s"${c.xIndex}-${c.yIndex}")
    )
    plans.foreach { plan =>
      for x <- 0 until 2; y <- 0 until 2 do
        assertEquals(hits(plan, x + 0.5, y + 0.5).flatMap(_.entity.map(_.value)), Vector(s"$x-$y"))
    }
  }

  private val vertices = RegularGridAxis.vertexCenteredUnsafe(0, 2, 3)
  private val slope = ok(ScalarField2D.tabulate(vertices, vertices)(_ + _))
  test("contour paths pick known line positions and reject space between levels") {
    val contours = ok(ContourSet.extract(slope, ContourLevels.atUnsafe(Vector(1, 3))))
    val plan = compile(plot(contours).geomContour())(c => s"${c.pathId}-${c.pointIndex}")
    val low = hits(plan, 0.5, 0.5)
    val high = hits(plan, 1.5, 1.5)
    assertEquals(low.size, 1); assertEquals(high.size, 1)
    assertNotEquals(low.head.id, high.head.id)
    assertEquals(hits(plan, 1, 1), Vector.empty)
  }

  test("filled contour picks its band interior and excludes the outer field") {
    val bands = ok(ContourBandSet.extract(slope, ContourBreaks.atUnsafe(Vector(1, 3))))
    val plan = compile(plot(bands).geomFilledContour())(c => s"${c.ringId}-${c.pointIndex}")
    assertEquals(hits(plan, 1, 1).size, 1)
    assertEquals(hits(plan, 0.25, 0.25), Vector.empty)
  }

  test("quantile summaries expose distinct targets at the known medians") {
    final case class Row(id: String, x: Double, y: Double)
    val rows = Vector(
      Row("a", 1, 1),
      Row("b", 1, 2),
      Row("c", 1, 3),
      Row("d", 2, 2),
      Row("e", 2, 3),
      Row("f", 2, 4)
    )
    val plan = compile(plot(rows).aes(_.x, _.y).geomQuantileSummary())(_.id)
    val a = hits(plan, 1, 2); val b = hits(plan, 2, 3)
    assertEquals(a.size, 1); assertEquals(b.size, 1)
    assertNotEquals(a.head.id, b.head.id)
    assertEquals(a.head.membership.total, Some(3))
    assertEquals(b.head.membership.total, Some(3))
    assertEquals(hits(plan, 1.5, 2.5), Vector.empty)
  }
