package intaglio.interaction

import intaglio.*

/** Hollow point glyphs are hit on their whole disc by default; other hollow marks are not. */
class PointGlyphPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 200, pixelsPerInch = 288)
  private val keys = ok(KeySpace("rows", KeyCodec.integer))
  private val hollow = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = 2)
  private val filled =
    GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = Some(Rgba.Black), lineWidth = 2)
  private val outlinePoints = PickPolicy.default.withHollowPoints(HollowPicking.Outline)
  private val full = PlotCompilerOptions.default.copy(renderContext = Some(context))
  private val lean = PlotCompilerOptions.lean.copy(renderContext = Some(context))
  private val lowerings = Vector("per-row points" -> full, "point batch" -> lean)

  private def compile(
      rows: Vector[Int],
      layer: Layer[Int],
      options: PlotCompilerOptions,
      name: String = "glyphs"
  ): InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(
        ok(Plot(rows).addLayer(layer)),
        keys,
        ok(DataRevision("d")),
        SemanticId.unsafe(name),
        ok(PlanRevision("r")),
        options
      )(identity)
    )
  private def points(
      rows: Vector[Int],
      x: Int => Double,
      y: Int => Double,
      gp: GraphicParams,
      options: PlotCompilerOptions
  ): InteractionPlan[Int] =
    compile(rows, Layer.point[Int](x, y, params = Some(gp)), options)
  private def pick(plan: InteractionPlan[Int], policy: PickPolicy = PickPolicy.default) =
    ok(Picking.compile(plan, context, policy))
  // Rows on the data range's edge are partly clipped by the panel; tests probe the interior ones.
  private def diagonal(options: PlotCompilerOptions, gp: GraphicParams = hollow) =
    points(Vector(0, 1, 2, 3, 4), _.toDouble, _.toDouble, gp, options)
  private def comparable(hits: Either[PickingError, Vector[PickHit[Int]]]) =
    ok(hits).map(hit => (hit.target.entity.map(_.value), hit.distanceDevicePx, hit.drawOrder))
  private def entities(plan: PickingPlan[Int], point: DevicePoint): Vector[Int] =
    ok(plan.hits(point)).flatMap(_.target.entity).map(_.value)
  private def geometryOf(plan: PickingPlan[Int], entity: Int): TargetGeometry[Int] =
    plan.prepareNavigation().targets.find(_.target.entity.exists(_.value == entity)).get
  private def centre(geometry: TargetGeometry[Int]): DevicePoint =
    DevicePoint((geometry.left + geometry.right) / 2, (geometry.top + geometry.bottom) / 2)
  private def rectangle(l: Double, t: Double, r: Double, b: Double) =
    ok(PickArea.rectangle(l, t, r, b))

  test("a hollow point is hit at its centre by default and on its outline either way") {
    for (label, options) <- lowerings do
      val plan = diagonal(options)
      assert(plan.groups.forall(_.pointGlyphs), label)
      val byDefault = pick(plan)
      val outlineOnly = pick(plan, outlinePoints)
      (1 to 3).foreach { entity =>
        val geometry = geometryOf(outlineOnly, entity)
        val middle = centre(geometry)
        val onStroke = DevicePoint(geometry.right - 0.25, middle.y)
        assertEquals(entities(byDefault, middle), Vector(entity), s"$label $entity")
        assertEquals(entities(outlineOnly, middle), Vector.empty, s"$label $entity")
        assertEquals(entities(byDefault, onStroke), Vector(entity), s"$label $entity")
        assertEquals(entities(outlineOnly, onStroke), Vector(entity), s"$label $entity")
        assertEquals(
          ok(byDefault.nearest(middle, 0)).flatMap(_.target.entity).map(_.value),
          Some(entity),
          "nearest hover at the centre"
        )
        assertEquals(geometryOf(byDefault, entity).anchor, middle, "the anchor is the centre")
      }
  }

  test("filled points pick identically under either point policy") {
    for (label, options) <- lowerings do
      val plan = points(Vector(0, 1, 2, 3), _.toDouble, i => (i % 3).toDouble, filled, options)
      val byDefault = pick(plan)
      val outlineOnly = pick(plan, outlinePoints)
      for x <- 0 to 200 by 4; y <- 0 to 200 by 4 do
        val point = DevicePoint(x.toDouble, y.toDouble)
        assertEquals(byDefault.hits(point, 1), outlineOnly.hits(point, 1), s"$label $point")
  }

  test("hollow rectangles, tiles and polygons keep outline-only picking by default") {
    val rows = Vector(0, 1, 2)
    val layers = Vector(
      "rect" -> Layer.rect[Int](
        _ * 3.0,
        _ * 3.0 + 2,
        _ * 3.0,
        _ * 3.0 + 2,
        params = Some(hollow)
      ),
      "tile" -> Layer.tile[Int](_.toDouble, _.toDouble, _ => 0.8, _ => 0.8, params = Some(hollow)),
      "polygon" -> Layer.polygon[Int](
        i => Vector(0.0, 4.0, 2.0)(i),
        i => Vector(0.0, 0.0, 4.0)(i),
        params = Some(hollow)
      )
    )
    for (label, layer) <- layers do
      val plan = compile(rows, layer, full, label)
      assert(plan.groups.forall(!_.pointGlyphs), label)
      val byDefault = pick(plan)
      val inside = pick(plan, PickPolicy.default.withHollow(HollowPicking.Interior))
      val target = byDefault.prepareNavigation().targets.head
      val middle = centre(target)
      assertEquals(ok(byDefault.hits(middle)), Vector.empty, label)
      assertEquals(ok(inside.hits(middle)).map(_.target.id), Vector(target.target.id), label)
      assertEquals(
        ok(byDefault.hits(middle)),
        ok(pick(plan, outlinePoints).hits(middle)),
        s"$label: the point policy does not reach it"
      )
  }

  test("a summary's centre point is a point glyph route; a line route is not") {
    val summary = compile(
      Vector.tabulate(8)(identity),
      Layer.summary[Int](i => (i % 2).toDouble, _.toDouble, params = Some(hollow)),
      full,
      "summary"
    )
    assert(summary.groups.nonEmpty)
    assert(summary.groups.forall(_.pointGlyphs), "segments beside a point do not demote it")
    val line = compile(
      Vector(0, 1, 2),
      Layer.line[Int](_.toDouble, _.toDouble, params = Some(hollow)),
      full,
      "line"
    )
    assert(line.groups.forall(!_.pointGlyphs))
  }

  test("overlapping hollow points report the upper one first") {
    for (label, options) <- lowerings do
      // Rows 0 and 1 overlap in the middle; rows 2 and 3 set the scale's range around them.
      val plan = points(
        Vector(0, 1, 2, 3),
        i => Vector(0.5, 0.53, 0.0, 1.0)(i),
        i => Vector(0.5, 0.5, 0.0, 1.0)(i),
        hollow,
        options
      )
      val byDefault = pick(plan)
      val a = centre(geometryOf(byDefault, 0))
      val b = centre(geometryOf(byDefault, 1))
      val shared = DevicePoint((a.x + b.x) / 2, (a.y + b.y) / 2)
      assertEquals(entities(byDefault, shared), Vector(1, 0), label)
      val orders = ok(byDefault.hits(shared)).map(_.drawOrder)
      assert(orders.head > orders.last, s"$label $orders")
      assertEquals(
        ok(byDefault.nearest(shared, 0)).flatMap(_.target.entity).map(_.value),
        Some(1),
        label
      )
      assertEquals(entities(pick(plan, outlinePoints), shared), Vector.empty, label)
  }

  test("an area inside a hollow point's ring selects it by default") {
    for (label, options) <- lowerings do
      val plan = diagonal(options)
      val byDefault = pick(plan)
      val outlineOnly = pick(plan, outlinePoints)
      val middle = centre(geometryOf(byDefault, 2))
      val core = rectangle(middle.x - 0.5, middle.y - 0.5, middle.x + 0.5, middle.y + 0.5)
      def selected(p: PickingPlan[Int], rule: AreaRule) =
        p.select(core, rule).flatMap(_.entity).map(_.value)
      assertEquals(selected(byDefault, AreaRule.Intersecting), Vector(2), label)
      assertEquals(selected(outlineOnly, AreaRule.Intersecting), Vector.empty, label)
      assertEquals(selected(byDefault, AreaRule.CenterInside), Vector(2), label)
      assertEquals(selected(outlineOnly, AreaRule.CenterInside), Vector(2), label)
      assertEquals(selected(byDefault, AreaRule.FullyContained), Vector.empty, label)
  }

  test("the indexed path agrees with the exhaustive oracle and with the same points filled") {
    val rows = Vector.tabulate(40)(identity)
    def x(i: Int) = ((i * 37) % 23).toDouble
    def y(i: Int) = ((i * 53) % 19).toDouble
    val areas = Vector(
      rectangle(10, 10, 90, 90),
      rectangle(60, 40, 180, 120),
      rectangle(100, 100, 104, 104),
      ok(
        PickArea.lasso(
          Vector(
            DevicePoint(20, 20),
            DevicePoint(180, 30),
            DevicePoint(100, 100),
            DevicePoint(170, 180),
            DevicePoint(30, 160)
          )
        )
      )
    )
    for (label, options) <- lowerings do
      val plan = points(rows, x, y, hollow, options)
      val asFilled = pick(points(rows, x, y, filled, options))
      for policy <- Vector(PickPolicy.default, outlinePoints) do
        val picking = pick(plan, policy)
        for px <- 0 to 200 by 3; py <- 0 to 200 by 3 do
          val point = DevicePoint(px.toDouble, py.toDouble)
          assertEquals(picking.hits(point, 1), picking.hitsExhaustive(point, 1), s"$label $point")
          if policy eq PickPolicy.default then
            assertEquals(
              comparable(picking.hits(point, 1)),
              comparable(asFilled.hits(point, 1)),
              s"$label $point"
            )
        for
          area <- areas;
          rule <- Vector(
            AreaRule.CenterInside,
            AreaRule.FullyContained,
            AreaRule.Intersecting
          )
        do assertEquals(picking.select(area, rule), picking.selectExhaustive(area, rule), label)
  }

  test("withHollow and withHollowPoints compose without resetting each other") {
    val both = PickPolicy.default
      .withHollowPoints(HollowPicking.Outline)
      .withHollow(HollowPicking.Interior)
    assertEquals(both.hollow, HollowPicking.Interior)
    assertEquals(both.hollowPoints, HollowPicking.Outline)
    assertEquals(PickPolicy.default.hollow, HollowPicking.Outline)
    assertEquals(PickPolicy.default.hollowPoints, HollowPicking.Interior)
    assertEquals(ok(PickPolicy(includeTransparent = true)).hollowPoints, HollowPicking.Interior)
    // `hollow` covers every closed mark, so it still includes a point's inside on its own.
    val plan = diagonal(full)
    val middle = centre(geometryOf(pick(plan), 2))
    assertEquals(entities(pick(plan), middle), Vector(2))
    assertEquals(entities(pick(plan, both), middle), Vector(2))
    val chosen = PickPolicy.default.withHollowPoints(HollowPicking.InteriorOf(Set.empty))
    assertEquals(entities(pick(plan, chosen), middle), Vector.empty)
  }

  test("hollow square, triangle and diamond glyphs are hit inside; a cross has no inside") {
    for
      (label, options) <- lowerings
      shape <- Vector(PointShape.Square, PointShape.Triangle, PointShape.Diamond, PointShape.Cross)
    do
      val plan = compile(
        Vector(0, 1, 2, 3, 4),
        Layer.point[Int](
          _.toDouble,
          _.toDouble,
          mapping = AesSpec.empty[Int].withShape(shape),
          params = Some(hollow)
        ),
        options,
        s"shape-$shape"
      )
      assert(plan.groups.forall(_.pointGlyphs), s"$label $shape")
      val byDefault = pick(plan)
      val outlineOnly = pick(plan, outlinePoints)
      val geometry = geometryOf(outlineOnly, 2)
      val middle = centre(geometry)
      if shape == PointShape.Cross then
        // The bars cross at the centre, so it is ink; a point between the bars is not.
        val between = DevicePoint(
          middle.x + (geometry.right - middle.x) / 2,
          middle.y + (geometry.bottom - middle.y) / 2
        )
        assertEquals(entities(byDefault, middle), Vector(2), s"$label cross centre")
        assertEquals(entities(byDefault, between), Vector.empty, s"$label cross")
        assertEquals(entities(outlineOnly, between), Vector.empty, s"$label cross")
      else
        assertEquals(entities(byDefault, middle), Vector(2), s"$label $shape")
        assertEquals(entities(outlineOnly, middle), Vector.empty, s"$label $shape")
  }

  test("a summary's hollow centre point is hit inside, beside its interval") {
    val thin = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = 0.5)
    val plan = compile(
      Vector.tabulate(12)(identity),
      Layer.summary[Int](i => (i % 3).toDouble, i => (i % 4).toDouble, params = Some(thin)),
      full,
      "summary-hit"
    )
    val byDefault = pick(plan)
    val outlineOnly = pick(plan, outlinePoints)
    // The middle x position: its interval is symmetric, so the bounds centre is the mean point.
    val target = byDefault.prepareNavigation().targets.sortBy(_.left).apply(1)
    val middle = centre(target)
    val radius = (target.right - target.left) / 2
    val beside = DevicePoint(middle.x + radius / 2, middle.y)
    def ids(plan: PickingPlan[Int]) = ok(plan.hits(beside)).map(_.target.id)
    assertEquals(ids(byDefault), Vector(target.target.id))
    assertEquals(ids(outlineOnly), Vector.empty, "off the interval and inside the ring")
  }

  test("an annotation-style point layer with its own data is a point glyph route") {
    val plot = ok(
      Plot(Vector(0, 1, 2, 3, 4))
        .addLayer(Layer.point[Int](_.toDouble, _.toDouble, params = Some(filled)))
        .flatMap(
          _.addLayer(
            Layer.point[Int](
              _ => 1.0,
              _ => 3.0,
              data = Some(Vector(7)),
              inheritMapping = false,
              params = Some(hollow)
            )
          )
        )
    )
    val plan = ok(
      InteractionCompiler.compile(
        plot,
        keys,
        ok(DataRevision("d")),
        SemanticId.unsafe("annotated"),
        ok(PlanRevision("r")),
        full
      )(identity)
    )
    assert(plan.groups.forall(_.pointGlyphs))
    val byDefault = pick(plan)
    val middle = centre(geometryOf(byDefault, 7))
    assertEquals(entities(byDefault, middle), Vector(7))
    assertEquals(entities(pick(plan, outlinePoints), middle), Vector.empty)
  }
