package intaglio.interaction

import intaglio.*

class CasingPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 100)
  private val ordinary = GraphicParams.unsafe(lineWidth = 2, lineType = LineType.Dashed)
  private val cased =
    ordinary.withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(6)))

  test("line and segment casing preserve picking hits, target count and legend entries") {
    for segment <- Vector(false, true) do
      def build(params: GraphicParams) =
        val builder = plot(Vector((0.0, 0.0), (1.0, 1.0)))
          .aes(_._1, _._2)
          .scaleColorDiscrete(_ => "route")
        ok((if segment then builder.geomSegment(_._1 + 0.25, _._2, params = Some(params))
            else builder.geomLine(params = Some(params))).build)
      val plain = build(ordinary)
      val casing = build(cased)
      assertEquals(ok(plain.resolve).guides, ok(casing.resolve).guides)
      def compile(program: PlotProgram[(Double, Double)]) =
        val plan = ok(
          InteractionCompiler.compile(
            program.plot,
            ok(KeySpace("points", KeyCodec.integer)),
            ok(DataRevision("one")),
            SemanticId.unsafe("lines"),
            ok(PlanRevision("one")),
            program.compilerOptions
          )(p => p._1.toInt)
        )
        ok(Picking.compile(plan, context))
      val p = compile(plain)
      val c = compile(casing)
      assertEquals(c.targetCount, p.targetCount)
      for x <- 0 to 200 by 4; y <- 0 to 100 by 4 do
        def signature(hits: Vector[PickHit[Int]]) = hits.map(h =>
          (
            h.target.id,
            h.target.entity,
            h.target.membership.total,
            h.target.membership.retainedKeys,
            h.distanceDevicePx,
            h.drawOrder
          )
        )
        assertEquals(
          signature(ok(c.hits(DevicePoint(x, y)))),
          signature(ok(p.hits(DevicePoint(x, y))))
        )
  }

  test("contour casing survives lowering with one target per original path") {
    val field = ok(
      ScalarField2D.tabulate(
        RegularGridAxis.vertexCenteredUnsafe(-1, 1, 5),
        RegularGridAxis.vertexCenteredUnsafe(-1, 1, 5)
      )((x, y) => x + y)
    )
    val contours = ok(ContourSet.extract(field, ok(ContourLevels.at(Vector(-0.5, 0.5)))))
    val plain = ok(plot(contours).geomContour(params = Some(ordinary)).resolve)
    val casing = ok(plot(contours).geomContour(params = Some(cased)).resolve)
    assertEquals(casing.layers.head.grobs.size, plain.layers.head.grobs.size)
    assert(casing.layers.head.grobs.forall(_.asInstanceOf[Grob.Lines].gp.casing.nonEmpty))
    assertEquals(casing.guides, plain.guides)
  }
