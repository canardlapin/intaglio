package intaglio

/** An explicit continuous position scale reaches every position aesthetic of a layer that draws in
  * the plot's coordinates: segment ends, tile and rect bounds, and interval limits are mapped
  * through the same trained scale as `x`/`y`, and the axis takes its breaks and labels from it.
  */
class CompanionPositionScalingSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Event(x: Double, y: Double, xEnd: Double, yEnd: Double)

  private val events = Vector(
    Event(1.0, 0.0, 3.0, 2.0),
    Event(2.0, 1.0, 5.0, 1.0),
    Event(4.0, 2.0, 6.0, 0.0)
  )

  /** The segment ends as ordinary points, to compare against `geomPoint` at the same coordinates.
    */
  private val ends = events.map(e => e.copy(x = e.xEnd, y = e.yEnd))

  private val rowBreaks: Breaks = _ => Vector(0.0, 1.0, 2.0)
  private val rowLabeler: Labeler = values =>
    values.map(v => Vector("alpha", "beta", "gamma")(math.rint(v).toInt))

  private val rowTransform =
    ok(Transform("row", v => v, v => v, breaks = rowBreaks, labeler = rowLabeler))

  private val context = RenderContext.unsafe(width = 400, height = 300)

  private def layerRows(trained: TrainedPlot, geom: Geom): Vector[ResolvedRow[?]] =
    trained.layers.filter(_.geom == geom).flatMap(_.rows)

  private def leftAxisLabels(trained: TrainedPlot): Vector[String] =
    trained.guides
      .map(_.spec)
      .collect { case axis: GuideSpec.Axis if axis.side == AxisSide.Left => axis }
      .headOption
      .flatMap(_.ticks)
      .getOrElse(fail("missing left axis ticks"))
      .map(_.label)

  test("a segment under an explicit y scale resolves and maps its ends like points") {
    val scaledFirst = plot(events)
      .aes(_.x, _.y)
      .scaleYContinuous(name = "row", transform = rowTransform)
      .geomSegment(_.xEnd, _.yEnd)
      .geomPoint(data = Some(ends))
    val scaledLast = plot(events)
      .aes(_.x, _.y)
      .geomSegment(_.xEnd, _.yEnd)
      .geomPoint(data = Some(ends))
      .scaleYContinuous(name = "row", transform = rowTransform)

    Vector("scale before geoms" -> scaledFirst, "scale after geoms" -> scaledLast).foreach {
      (order, builder) =>
        val trained = ok(builder.resolve(context))
        val segments = layerRows(trained, Geom.Segment)
        val points = layerRows(trained, Geom.Point)
        assertEquals(segments.length, events.length, order)
        segments.zip(points).foreach { (segment, point) =>
          assertEqualsDouble(segment.yEnd.getOrElse(fail("missing yEnd")), point.y, 1e-12)
          assertEqualsDouble(segment.xEnd.getOrElse(fail("missing xEnd")), point.x, 1e-12)
        }
        assert(segments.forall(r => r.y >= 0.0 && r.y <= 1.0), s"$order: y is not in scale space")
        assertEquals(leftAxisLabels(trained), Vector("alpha", "beta", "gamma"), order)
    }
  }

  test("a segment alone under an explicit y scale renders") {
    val built = plot(events)
      .aes(_.x, _.y)
      .geomSegment(_.xEnd, _.yEnd)
      .scaleYContinuous(name = "row", transform = rowTransform)
    ok(built.renderPlan(context))
    assertEquals(leftAxisLabels(ok(built.resolve(context))), Vector("alpha", "beta", "gamma"))
  }

  test("an explicit x scale reaches segment ends on the x axis too") {
    val trained = ok(
      plot(events)
        .aes(_.x, _.y)
        .scaleXContinuous(transform = Transform.sqrt)
        .geomSegment(_.xEnd, _.yEnd)
        .geomPoint(data = Some(ends))
        .resolve(context)
    )
    layerRows(trained, Geom.Segment).zip(layerRows(trained, Geom.Point)).foreach { (s, p) =>
      assertEqualsDouble(s.xEnd.getOrElse(fail("missing xEnd")), p.x, 1e-12)
    }
  }

  final case class Cell(column: Double, row: Double)

  private val cells =
    for
      column <- Vector(0.0, 1.0, 2.0, 3.0)
      row <- Vector(0.0, 1.0, 2.0)
    yield Cell(column, row)

  test("tile bounds follow an explicit transformed y scale and the axis uses its labeler") {
    val trained = ok(
      plot(cells)
        .aes(_.column, _.row)
        .scaleYContinuous(name = "row", transform = rowTransform)
        .geomTile(_ => 1.0, _ => 1.0)
        .resolve(context)
    )
    val tiles = layerRows(trained, Geom.Tile)
    assertEquals(tiles.length, cells.length)
    // Mapped bounds bracket the mapped centre symmetrically under the identity transform.
    tiles.foreach { tile =>
      val lower = tile.yMin.getOrElse(fail("missing yMin"))
      val upper = tile.yMax.getOrElse(fail("missing yMax"))
      assertEqualsDouble(tile.y - lower, upper - tile.y, 1e-12)
      assert(lower >= 0.0 && upper <= 1.0, s"tile bounds $lower..$upper are not in scale space")
    }
    assertEquals(leftAxisLabels(trained), Vector("alpha", "beta", "gamma"))
  }

  test("a categorical-row raster labels its rows without guide overrides") {
    val built = plot(cells)
      .aes(_.column, _.row)
      .geomTile(_ => 0.9, _ => 0.9)
      .scaleYContinuous(name = "row", transform = rowTransform)
    val trained = ok(built.resolve(context))
    assertEquals(leftAxisLabels(trained), Vector("alpha", "beta", "gamma"))
    val scene = ok(ok(built.renderPlan(context)).deviceScene)
    def labels(element: DeviceElement): Vector[String] =
      element match
        case DeviceElement.Mark(text: DevicePrimitive.TextRun) => Vector(text.label)
        case DeviceElement.Mark(_)                             => Vector.empty
        case DeviceElement.Group(_, _, _, children)            => children.flatMap(labels)
        case DeviceElement.Annotated(_, children)              => children.flatMap(labels)
    val texts = scene.elements.flatMap(labels)
    assert(Vector("alpha", "beta", "gamma").forall(texts.contains), s"row labels missing: $texts")
  }

  final case class Visit(day: CalendarDate, end: Double, score: Double)

  test("a raw companion beside a temporal position scale is refused, naming geom and aesthetic") {
    val visits = Vector(Visit(ok(CalendarDate(2026, 1, 1)), 3.0, 1.0))
    val mapping = ok(
      AesSpec
        .empty[Visit]
        .withSegment(_ => 0.0, _.score, _.end, _.score)
        .bindScale(ScaleBinding(Aesthetic.X, (v: Visit) => v.day, ok(DateScaleSpec("day"))))
    )
    val layer = ok(Layer.fromMapping(Geom.Segment, mapping, inheritMapping = false))
    val result = Plot(visits).addLayer(layer).flatMap(PlotCompiler.resolve(_))
    assertEquals(
      result.left.toOption,
      Some(GraphicsError.UnsupportedGeomAesthetic(Geom.Segment.label, Aesthetic.XEnd.label))
    )
  }

  test("an explicitly scaled companion keeps its own scale") {
    val endScale = ok(ContinuousScaleSpec("end", Palette.numeric, Transform.identity))
    val mapping = ok(
      AesSpec
        .empty[Event]
        .withSegment(_.x, _.y, _.xEnd, _.yEnd)
        .bindScale(ScaleBinding(Aesthetic.YEnd, (e: Event) => e.yEnd, endScale))
    )
    val layer = ok(Layer.fromMapping(Geom.Segment, mapping, inheritMapping = false))
    val trained = ok(Plot(events).addLayer(layer).flatMap(PlotCompiler.resolve(_)))
    // Raw y beside a separately scaled yEnd: yEnd is mapped by its own scale, y is untouched.
    val segments = layerRows(trained, Geom.Segment)
    assertEquals(segments.map(_.y), events.map(_.y))
    assert(segments.flatMap(_.yEnd).forall(v => v >= 0.0 && v <= 1.0))
  }

  test("tile bounds are trained: a scale's domain covers the outer tile edges") {
    val trained = ok(
      plot(cells)
        .aes(_.column, _.row)
        .scaleYContinuous()
        .geomTile(_ => 1.0, _ => 1.0)
        .resolve(context)
    )
    val tiles = layerRows(trained, Geom.Tile)
    val lowest = tiles.flatMap(_.yMin).min
    val highest = tiles.flatMap(_.yMax).max
    // Edges at -0.5 and 2.5 are inside the trained domain, so neither is censored or clipped.
    assert(lowest >= 0.0 && highest <= 1.0, s"edges $lowest..$highest escape the scale")
    assertEquals(tiles.length, cells.length)
  }
