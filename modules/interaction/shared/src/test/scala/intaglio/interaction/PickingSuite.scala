package intaglio.interaction

import intaglio.*

class PickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val keys = ok(KeySpace("rows", KeyCodec.integer))
  private val revision = ok(PlanRevision("one"))
  private val context = RenderContext.unsafe(width = 200, height = 200)
  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
  private def group(size: Int = 1, name: String = "plot"): TargetGroup[Int] =
    val plot = ok(
      Plot(Vector.tabulate(size)(identity)).addLayer(Layer.point[Int](_.toDouble, _.toDouble))
    )
    ok(
      InteractionCompiler.compile(
        plot,
        keys,
        ok(DataRevision("one")),
        SemanticId.unsafe(name),
        revision
      )(identity)
    ).groups.head
  private val single = group()
  private def route(group: TargetGroup[Int], marks: DevicePrimitive*): DeviceElement =
    DeviceElement.Annotated(
      GrobMeta(data = Vector(InteractionCompiler.targetAttribute -> group.name.value)),
      marks.toVector.map(DeviceElement.Mark(_))
    )
  private def compile(
      elements: Vector[DeviceElement],
      groups: Vector[TargetGroup[Int]] = Vector(single),
      policy: PickPolicy = PickPolicy.default,
      renderContext: RenderContext = context
  ): PickingPlan[Int] =
    ok(Picking.fromDeviceScene(DeviceScene(200, 200, elements), groups, renderContext, policy))
  private def hit(plan: PickingPlan[Int], x: Double, y: Double): Boolean = ok(
    plan.hits(DevicePoint(x, y))
  ).nonEmpty
  private def disc(
      x: Double,
      y: Double,
      radius: Double = 5,
      gp: GraphicParams = fill
  ): DevicePrimitive =
    DevicePrimitive.Disc(x, y, radius, gp, None)
  private def line(
      points: Vector[(Double, Double)],
      gp: GraphicParams,
      closed: Boolean = false
  ): DevicePrimitive =
    DevicePrimitive.Polyline(points.map((x, y) => DevicePoint(x, y)), closed, gp, None)

  test("hit order prefers the topmost target while nearest order prefers actual distance") {
    val other = group(name = "other")
    val plan = compile(
      Vector(route(single, disc(50, 50)), route(other, disc(50, 50, 2))),
      Vector(single, other)
    )
    assertEquals(
      ok(plan.hits(DevicePoint(50, 50))).map(_.target.id),
      Vector(ok(other.at(0)).id, ok(single.at(0)).id)
    )
    assertEquals(
      ok(plan.nearest(DevicePoint(55, 50), 10)).map(_.target.id),
      Some(ok(single.at(0)).id)
    )
    assertEquals(ok(plan.nearest(DevicePoint(70, 50), 14)), None)
    assertEquals(ok(plan.nearest(DevicePoint(70, 50), 15)).map(_.distanceDevicePx), Some(15.0))
    assert(plan.hits(DevicePoint(Double.NaN, 0)).isLeft)
    assert(plan.hits(DevicePoint(0, 0), -1).isLeft)
  }

  test(
    "multiple primitives share one logical hit and area selection considers every visible part"
  ) {
    val plan = compile(Vector(route(single, disc(40, 50), disc(60, 50))))
    assertEquals(plan.targetCount, 1)
    assertEquals(ok(plan.hits(DevicePoint(50, 50), 20)).size, 1)
    val left = ok(PickArea.rectangle(34, 44, 46, 56))
    assertEquals(plan.select(left, AreaRule.Intersecting).size, 1)
    assertEquals(plan.select(left, AreaRule.FullyContained).size, 0)
    assertEquals(plan.select(left, AreaRule.CenterInside).size, 0)
    assertEquals(
      plan.select(ok(PickArea.rectangle(34, 44, 66, 56)), AreaRule.FullyContained).size,
      1
    )
  }

  test("point-batch indices retain their typed identities across all five point shapes") {
    val batchGroup = group(5)
    val batch = DevicePrimitive.PointBatch(
      Vector.tabulate(5)(i => DevicePoint(20 + 30 * i, 50)),
      BatchColumn.Constant(5.0),
      BatchColumn.Values(
        Vector(
          PointShape.Circle,
          PointShape.Square,
          PointShape.Triangle,
          PointShape.Cross,
          PointShape.Diamond
        )
      ),
      BatchColumn.Constant(GraphicParams.unsafe(fill = Some(Rgba.Black), lineWidth = 2)),
      None
    )
    val plan = compile(Vector(route(batchGroup, batch)), Vector(batchGroup))
    for i <- 0 until 5 do
      assertEquals(
        ok(plan.hits(DevicePoint(20 + 30 * i, 50))).flatMap(_.target.entity).map(_.value),
        Vector(i)
      )
    assert(!hit(plan, 113, 53), "cross bounding-box corner is not a painted stroke")
    assert(hit(plan, 140, 44), "diamond uses its area-preserving half-diagonal")
    assertEquals(plan.targetCount, 5)
  }

  test("rotated group clips are applied after the same group rotation as drawing") {
    val wrapped = DeviceElement.Group(
      None,
      Some(DeviceClip(50, 45, 5, 10)),
      Some(DeviceRotation(90, 50, 50)),
      Vector(route(single, disc(50, 50, 10)))
    )
    val plan = compile(Vector(wrapped))
    assert(hit(plan, 50, 53))
    assert(!hit(plan, 53, 48))
    assertEqualsDouble(ok(plan.nearest(DevicePoint(50, 40), 20)).get.distanceDevicePx, 10, 1e-7)
    val visible = ok(PickArea.rectangle(44, 49, 56, 56))
    assertEquals(plan.select(visible, AreaRule.FullyContained).size, 1)
  }

  test("transparent paint is opt-in and an absent paint is never manufactured") {
    val transparent = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Transparent))
    val hidden = compile(Vector(route(single, disc(50, 50, gp = transparent))))
    assert(!hit(hidden, 50, 50))
    val include = ok(PickPolicy(includeTransparent = true))
    assert(
      hit(compile(Vector(route(single, disc(50, 50, gp = transparent))), policy = include), 50, 50)
    )
    assert(
      !hit(
        compile(
          Vector(route(single, disc(50, 50, gp = GraphicParams.unsafe(stroke = None)))),
          policy = include
        ),
        50,
        50
      )
    )
    val outline =
      compile(Vector(route(single, disc(50, 50, gp = GraphicParams.unsafe(lineWidth = 2)))))
    assert(!hit(outline, 50, 50))
    assert(hit(outline, 55, 50))
    assertEquals(
      outline.select(ok(PickArea.rectangle(49, 49, 51, 51)), AreaRule.CenterInside).size,
      1
    )
  }

  test("butt, round and square caps have distinct endpoint geometry") {
    def withCap(cap: LineCap) = compile(
      Vector(
        route(
          single,
          line(
            Vector(50.0 -> 50.0, 60.0 -> 50.0),
            GraphicParams.unsafe(lineWidth = 4, lineCap = cap)
          )
        )
      )
    )
    assert(!hit(withCap(LineCap.Butt), 49, 50))
    assert(hit(withCap(LineCap.Round), 49, 50))
    assert(!hit(withCap(LineCap.Round), 48.5, 51.5))
    assert(hit(withCap(LineCap.Square), 48.5, 51.5))
  }

  test("miter, round and bevel joins have distinct exterior corner geometry") {
    def withJoin(join: LineJoin) = compile(
      Vector(
        route(
          single,
          line(
            Vector(50.0 -> 50.0, 60.0 -> 50.0, 60.0 -> 60.0),
            GraphicParams.unsafe(lineWidth = 4, lineJoin = join)
          )
        )
      )
    )
    assert(hit(withJoin(LineJoin.Miter), 61.5, 48.5))
    assert(!hit(withJoin(LineJoin.Round), 61.5, 48.5))
    assert(hit(withJoin(LineJoin.Round), 61.3, 48.7))
    assert(!hit(withJoin(LineJoin.Bevel), 61.3, 48.7))
  }

  test("painted linear dash intervals carry their phase across path vertices") {
    val mark = line(
      Vector(40.0 -> 50.0, 44.0 -> 50.0, 60.0 -> 50.0),
      GraphicParams.unsafe(lineWidth = 2, lineType = LineType.Dashed)
    )
    val painted =
      compile(Vector(route(single, mark)), policy = ok(PickPolicy(dashes = DashPicking.Painted)))
    assert(hit(painted, 45, 50))
    assert(!hit(painted, 47, 50))
    assert(hit(painted, 51, 50))
    assert(hit(compile(Vector(route(single, mark))), 47, 50))
    val curved = disc(50, 50, gp = GraphicParams.unsafe(lineType = LineType.Dashed))
    assert(
      Picking
        .fromDeviceScene(
          DeviceScene(200, 200, Vector(route(single, curved))),
          Vector(single),
          context,
          ok(PickPolicy(dashes = DashPicking.Painted))
        )
        .left
        .exists(_.isInstanceOf[PickingError.Unsupported])
    )
  }

  test("a single dash covering a closed path keeps endpoint caps at its coincident seam") {
    val points = Vector(50.0 -> 50.0, 50.5 -> 50.0, 50.5 -> 50.7)
    def pick(cap: LineCap, dash: LineType) = compile(
      Vector(
        route(
          single,
          line(
            points,
            GraphicParams
              .unsafe(lineWidth = 4, lineCap = cap, lineJoin = LineJoin.Miter, lineType = dash),
            closed = true
          )
        )
      ),
      policy = ok(PickPolicy(dashes = DashPicking.Painted))
    )
    // Independently reproduced with Java2D BasicStroke and Chromium 141 SVG/Canvas.
    assert(hit(pick(LineCap.Butt, LineType.Solid), 48.137, 48.271))
    assert(!hit(pick(LineCap.Butt, LineType.Dashed), 48.137, 48.271))
    assert(!hit(pick(LineCap.Round, LineType.Dashed), 48.137, 48.271))
    assert(hit(pick(LineCap.Square, LineType.Dashed), 48.137, 48.271))
  }

  test("compound polygons use the renderer's nonzero winding rule") {
    val outer =
      Vector(DevicePoint(30, 30), DevicePoint(70, 30), DevicePoint(70, 70), DevicePoint(30, 70))
    val inner =
      Vector(DevicePoint(40, 40), DevicePoint(60, 40), DevicePoint(60, 60), DevicePoint(40, 60))
    def polygon(ring: Vector[DevicePoint]) = compile(
      Vector(route(single, DevicePrimitive.CompoundPolygon(Vector(outer, ring), fill, None)))
    )
    assert(hit(polygon(inner), 50, 50))
    assert(!hit(polygon(inner.reverse), 50, 50))
    assert(hit(polygon(inner.reverse), 40, 50))
    val query = ok(
      PickArea.lasso(
        Vector(DevicePoint(25, 25), DevicePoint(75, 25), DevicePoint(75, 75), DevicePoint(25, 75))
      )
    )
    assertEquals(polygon(inner).select(query, AreaRule.FullyContained).size, 1)
  }

  test("rounded rectangle strokes preserve empty interiors and curved corners") {
    val mark =
      DevicePrimitive.RectShape(40, 40, 20, 10, 3, GraphicParams.unsafe(lineWidth = 2), None)
    val plan = compile(Vector(route(single, mark)))
    assert(!hit(plan, 50, 45))
    assert(!hit(plan, 39, 39))
    assert(hit(plan, 43, 39))
    assert(hit(plan, 50, 40))
  }

  test("text and image bounds are explicit and text measurement failures stay typed") {
    val metrics = new TextMetrics:
      def widthPt(text: String, size: Double): Double = size * text.length
      def heightPt(size: Double): Double = size
    val measured = RenderContext.unsafe(width = 200, height = 200, textMetrics = metrics)
    val text =
      DevicePrimitive.TextRun("abcd", 50, 50, HJust.Center, VJust.Center, 90, 10, None, fill, None)
    val plan = compile(Vector(route(single, text)), renderContext = measured)
    assert(hit(plan, 50, 68))
    assert(!hit(plan, 68, 50))
    val image = RasterImage.solid(RasterDimensions.unsafe(1, 1), Rgba32.unsafe(0, 0, 0, 0))
    val raster = compile(
      Vector(
        route(
          single,
          DevicePrimitive.Image(image, 40, 40, 20, 10, RasterInterpolation.Nearest, 1, None)
        )
      )
    )
    assert(hit(raster, 50, 45), "image picking uses bounds, not pixel alpha")
    val throwing = new TextMetrics:
      def widthPt(text: String, size: Double): Double = throw new IllegalStateException("metrics")
      def heightPt(size: Double): Double = size
    assert(
      Picking
        .fromDeviceScene(
          DeviceScene(200, 200, Vector(route(single, text))),
          Vector(single),
          RenderContext.unsafe(textMetrics = throwing),
          PickPolicy.default
        )
        .left
        .exists(_ == PickingError.TextMeasurementFailed)
    )
  }

  test(
    "unknown, missing, duplicate and incoherent routes fail instead of yielding partial targets"
  ) {
    val extra = group(name = "extra")
    def result(elements: Vector[DeviceElement], groups: Vector[TargetGroup[Int]]) =
      Picking.fromDeviceScene(DeviceScene(200, 200, elements), groups, context, PickPolicy.default)
    assert(result(Vector(route(extra, disc(50, 50))), Vector(single)).isLeft)
    assert(result(Vector.empty, Vector(single)).isLeft)
    assert(result(Vector(route(single, disc(50, 50))), Vector(single, single)).isLeft)
    val multiple = group(3)
    assert(result(Vector(route(multiple, disc(50, 50))), Vector(multiple)).isLeft)
  }

  test("ordinary interaction compilation feeds the picker without a second geom family") {
    val plot = ok(Plot(Vector(1, 2, 3)).addLayer(Layer.point[Int](_.toDouble, _.toDouble)))
    val options = PlotCompilerOptions.lean.copy(renderContext = Some(context))
    val compiled = ok(
      InteractionCompiler.compile(
        plot,
        keys,
        ok(DataRevision("real")),
        SemanticId.unsafe("real"),
        revision,
        options
      )(identity)
    )
    val picker = ok(Picking.compile(compiled, context))
    assertEquals(picker.targetCount, 3)
    val all = picker.select(ok(PickArea.rectangle(-1, -1, 201, 201)), AreaRule.Intersecting)
    assertEquals(all.flatMap(_.entity).map(_.value).toSet, Set(1, 2, 3))
  }

  /** A plan large enough to engage the spatial grid (small plans fall back to the full scan): a 24
    * × 24 disc lattice, two scene-spanning polylines, and one clipped, rotated disc.
    */
  private def densePlan: PickingPlan[Int] =
    val lattice = group(576, "lattice")
    val batch = DevicePrimitive.PointBatch(
      Vector.tabulate(576)(i => DevicePoint(10 + (i % 24) * 8, 10 + (i / 24) * 8)),
      BatchColumn.Constant(3.0),
      BatchColumn.Constant(PointShape.Circle),
      BatchColumn.Constant(fill),
      None
    )
    val lines = group(name = "lines")
    val stroked = GraphicParams.unsafe(lineWidth = 2)
    val clippedGroup = group(name = "clipped")
    val clipped = DeviceElement.Group(
      None,
      Some(DeviceClip(60, 55, 30, 20)),
      Some(DeviceRotation(30, 75, 65)),
      Vector(route(clippedGroup, disc(75, 65, 18)))
    )
    ok(
      Picking.fromDeviceScene(
        DeviceScene(
          200,
          200,
          Vector(
            route(lattice, batch),
            route(
              lines,
              line(Vector(0.0 -> 3.0, 200.0 -> 197.0), stroked),
              line(Vector(0.0 -> 150.0, 200.0 -> 20.0), stroked)
            ),
            clipped
          )
        ),
        Vector(lattice, lines, clippedGroup),
        context,
        PickPolicy.default
      )
    )

  test("dense plans answer point queries identically to the exhaustive oracle") {
    val plan = densePlan
    assertEquals(plan.targetCount, 578)
    val tolerances = Vector(0.0, 2.0, 6.5, 25.0, 300.0)
    for
      x <- 0 to 200 by 7
      y <- 0 to 200 by 9
      tolerance <- tolerances
    do
      val point = DevicePoint(x.toDouble + 0.37, y.toDouble + 0.61)
      assertEquals(
        ok(plan.hits(point, tolerance)),
        ok(plan.hitsExhaustive(point, tolerance)),
        clues(x, y, tolerance)
      )
    assertEquals(
      ok(plan.nearest(DevicePoint(101.2, 88.8), 40)),
      ok(plan.hitsExhaustive(DevicePoint(101.2, 88.8), 40)).headOption
    )
  }

  test("dense plans answer area queries identically to the exhaustive oracle") {
    val plan = densePlan
    val areas = Vector(
      ok(PickArea.rectangle(12, 12, 45, 45)),
      ok(PickArea.rectangle(0.5, 0.5, 199.5, 199.5)),
      ok(PickArea.rectangle(58, 53, 95, 80)),
      ok(PickArea.rectangle(190, 190, 210, 210)),
      ok(PickArea.lasso(Vector(DevicePoint(5, 5), DevicePoint(120, 15), DevicePoint(40, 140))))
    )
    for
      area <- areas
      rule <- Vector(AreaRule.CenterInside, AreaRule.FullyContained, AreaRule.Intersecting)
    do
      assertEquals(
        plan.select(area, rule).map(_.id),
        plan.selectExhaustive(area, rule).map(_.id),
        clues(rule.toString)
      )
  }

  test("a bold text target is measured at the weight it is drawn at") {
    // A weight-blind measurement returns the regular advance, so a bold run's hit box would be
    // narrower than the glyphs a reader is clicking on. This provider's width depends on weight,
    // as any real one does.
    val metrics = new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        text.length.toDouble * fontSizePt

      override def heightPt(fontSizePt: Double): Double = fontSizePt

      override def widthPt(text: String, style: TextStyle): Double =
        val factor = style.fontWeight.fold(1.0)(weight => weight.value / 400.0)
        text.length.toDouble * style.fontSizePt * factor

    val weighted = RenderContext.unsafe(width = 200, height = 200, textMetrics = metrics)

    def rightEdgeHit(params: GraphicParams, x: Double): Boolean =
      val text = DevicePrimitive.TextRun(
        "label",
        10.0,
        10.0,
        HJust.Left,
        VJust.Top,
        0.0,
        10.0,
        None,
        params,
        Some(GraphicsName.unsafe("picked"))
      )
      hit(compile(Vector(route(single, text)), renderContext = weighted), x, 12.0)

    // Five glyphs at ten pixels is fifty wide regular, and seven-quarters of that bold. A point
    // inside the bold box but outside the regular one separates the two.
    // Five glyphs at ten pixels spans x 10 to 60 regular; bold is seven-quarters of that, to 95.
    // A point between the two right edges is inside the bold box and outside the regular one.
    assert(rightEdgeHit(fill, 40.0))
    assert(!rightEdgeHit(fill, 75.0))
    assert(rightEdgeHit(fill.withFontWeight(FontWeight.Bold), 75.0))
  }
