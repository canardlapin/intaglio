package intaglio

class CoordSuite extends munit.FunSuite:
  private val tol = 1e-9

  private final case class Observation(x: Double, y: Double)

  private def native(expr: LengthExpr): Double =
    expr match
      case LengthExpr.Const(length) if length.unit == LengthUnit.Native => length.value
      case other => fail(s"expected native constant, found $other")

  private def npc(expr: ExtentExpr): Double =
    expr.expr match
      case LengthExpr.Const(length) if length.unit == LengthUnit.Npc => length.value
      case other => fail(s"expected npc constant, found $other")

  test("coordinate ratios are positive finite domain values") {
    assertEquals(Coord.fixed(0.0).left.toOption, Some(GraphicsError.InvalidCoordinateRatio(0.0)))
    assert(Coord.fixed(Double.NaN).isLeft)
    assertEquals(Coord.fixed(2.0).toOption, Some(Coord.Fixed(CoordinateRatio.unsafe(2.0), Clip.On)))
  }

  test("built-in coordinates obey their public transformation and layout contracts") {
    val ordinary = Plot(Vector(Observation(1.0, 10.0), Observation(2.0, 20.0)))
      .addLayer(Layer.point[Observation](_.x, _.y))
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
    val ranges = Some(Interval.unsafe(1.0, 2.0) -> Interval.unsafe(10.0, 20.0))
    val input = CoordInput(ordinary.layers, ranges)

    val cartesian = Coord.Cartesian().transform(input).orThrow
    val once = Coord.Flipped().transform(input).orThrow
    val twice = Coord.Flipped().transform(CoordInput(once.layers, once.ranges)).orThrow
    val fixed = Coord.fixedUnsafe(2.0)

    assertEquals(cartesian, CoordResult(input.layers, input.ranges))
    assertEquals(once.ranges, ranges.map(_.swap))
    assertEquals(twice.ranges, ranges)
    assertEquals(
      twice.layers.head.rows.map(row => row.x -> row.y),
      ordinary.layers.head.rows.map(row => row.x -> row.y)
    )
    assertEquals(twice.layers.head.grobs, ordinary.layers.head.grobs)
    assertEquals(
      Coord.Cartesian().guideLayout(ranges.get._1, ranges.get._2),
      CoordGuideLayout(AxisSide.Bottom, ranges.get._1, AxisSide.Left, ranges.get._2)
    )
    assertEquals(
      Coord.Flipped().guideLayout(ranges.get._1, ranges.get._2),
      CoordGuideLayout(AxisSide.Left, ranges.get._1, AxisSide.Bottom, ranges.get._2)
    )
    assertEquals(
      fixed.panelAspect(ranges.get._1, ranges.get._2).map(_.map(_.toDouble)),
      Right(Some(20.0))
    )
    assertEquals(fixed.validateFacet.left.toOption, Some(GraphicsError.FacetFixedCoordinates))
  }

  test("flipped coordinates swap resolved positions and point grobs once") {
    val trained =
      Plot(Vector(Observation(1.0, 10.0), Observation(2.0, 20.0)))
        .addLayer(Layer.point[Observation](_.x, _.y))
        .map(_.withCoord(Coord.Flipped()))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    val layer = trained.layers.head

    assertEquals(layer.rows.map(row => row.x -> row.y), Vector(10.0 -> 1.0, 20.0 -> 2.0))
    val points = layer.grobs.map(_.asInstanceOf[Grob.Points].points.head)
    assertEquals(
      points.map(point => native(point.x) -> native(point.y)),
      Vector(10.0 -> 1.0, 20.0 -> 2.0)
    )
  }

  test("flipped histograms become horizontal bars with swapped panel ranges") {
    val bins = HistogramBins.breaksUnsafe(Vector(0.0, 2.0, 4.0))
    val trained =
      Plot(Vector(0.0, 1.0, 2.0, 3.0, 4.0))
        .addLayer(Layer.histogram(identity, bins = bins))
        .map(_.withCoord(Coord.Flipped()))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)
    val layer = trained.layers.head
    val first = layer.grobs.head.asInstanceOf[Grob.Rect]

    assertEquals(layer.rows.map(row => row.x -> row.y), Vector(3.0 -> 1.0, 2.0 -> 3.0))
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(0.0, 3.0)))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(0.0, 4.0)))
    assertEqualsDouble(native(first.center.x), 1.5, tol)
    assertEqualsDouble(native(first.center.y), 1.0, tol)
    assertEqualsDouble(native(first.size.width.expr), 3.0, tol)
    assertEqualsDouble(native(first.size.height.expr), 2.0, tol)
  }

  test("flipped coordinates transpose every ring of compound polygons") {
    val axis = RegularGridAxis.vertexCenteredUnsafe(-2.0, 2.0, 41)
    val field = ScalarField2D.tabulate(axis, axis)((x, y) => x * x + y * y).toOption.get
    val bands =
      ContourBandSet.extract(field, ContourBreaks.atUnsafe(Vector(0.25, 1.0))).toOption.get
    val ordinary =
      plot(bands).geomFilledContour().resolve.fold(error => fail(error.message), identity)
    val flipped =
      plot(bands)
        .coord(Coord.Flipped())
        .geomFilledContour()
        .resolve
        .fold(error => fail(error.message), identity)
    val ordinaryRings = ordinary.layers.head.grobs.head.asInstanceOf[Grob.CompoundPolygon].rings
    val flippedRings = flipped.layers.head.grobs.head.asInstanceOf[Grob.CompoundPolygon].rings

    assertEquals(flippedRings.map(_.length), ordinaryRings.map(_.length))
    ordinaryRings.flatten.zip(flippedRings.flatten).foreach { case (point, transposed) =>
      assertEqualsDouble(native(transposed.x), native(point.y), tol)
      assertEqualsDouble(native(transposed.y), native(point.x), tol)
    }
  }

  test("derived x and y guides follow flipped physical axes") {
    val data = Vector(Observation(0.0, 10.0), Observation(1.0, 20.0), Observation(2.0, 30.0))
    val trained =
      Plot(data)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .map(_.withCoord(Coord.Flipped()))
        .map(_.withAxisTitles("time", "signal"))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
          )
        )
        .fold(error => fail(error.message), identity)

    val axes = trained.guides.collect { case ResolvedGuide(axis: GuideSpec.Axis, _) => axis }
    val left = axes.find(_.side == AxisSide.Left).getOrElse(fail("missing flipped x axis"))
    val bottom = axes.find(_.side == AxisSide.Bottom).getOrElse(fail("missing flipped y axis"))
    assertEquals(left.title, Some("time"))
    assertEquals(bottom.title, Some("signal"))
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(9.0, 31.0)))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(-0.1, 2.1)))
  }

  test("fixed coordinates constrain physical panel aspect through the solver") {
    val data = Vector(Observation(0.0, 0.0), Observation(4.0, 2.0))

    def physicalAspect(ratio: Double): Double =
      val trained =
        Plot(data)
          .addLayer(Layer.point[Observation](_.x, _.y))
          .map(_.withCoord(Coord.fixedUnsafe(ratio)))
          .flatMap(
            PlotCompiler.resolve(
              _,
              PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
            )
          )
          .fold(error => fail(error.message), identity)
      val frame = trained.layout.getOrElse(fail("missing fixed layout")).frame
      npc(frame.size.height) * 480.0 / (npc(frame.size.width) * 640.0)

    assertEqualsDouble(physicalAspect(1.0), 0.5, tol)
    assertEqualsDouble(physicalAspect(2.0), 1.0, tol)
  }

  test("fixed coordinates reject unexpanded degenerate ranges") {
    val result =
      Plot(Vector(Observation(1.0, 2.0)))
        .addLayer(Layer.point[Observation](_.x, _.y))
        .map(_.withCoord(Coord.fixedUnsafe()))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )

    assertEquals(result.left.toOption, Some(GraphicsError.DegenerateFixedAspect(0.0, 0.0)))
  }

  test("coordinate zoom changes only the post-stat viewport and preserves histogram observations") {
    val values = Vector(1.0, 2.0, 3.0, 90.0, 95.0)
    val bins = HistogramBins.breaksUnsafe(Vector(0.0, 10.0, 100.0))

    def compile(coord: Coord): TrainedPlot =
      Plot(values)
        .addLayer(Layer.histogram(identity, bins = bins))
        .map(_.withCoord(coord))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(
              policy = Some(LayoutPolicy()),
              guides = GuidePolicy.Derived()
            )
          )
        )
        .fold(error => fail(error.message), identity)

    val ordinary = compile(Coord.Cartesian())
    val zoomed = compile(
      Coord.zoomUnsafe(x = Some(Interval.unsafe(0.0, 10.0)))
    )
    val counts = zoomed.layers.head.statFrame.rows.collect { case output: StatRow.Binned[?] =>
      output.count
    }

    assertEquals(counts, Vector(3, 2))
    assertEquals(zoomed.layers.head.dataSize, values.length)
    assertEquals(zoomed.layers.head.statFrame, ordinary.layers.head.statFrame)
    assertEquals(zoomed.layers.head.rows, ordinary.layers.head.rows)
    assertEquals(zoomed.layers.head.grobs, ordinary.layers.head.grobs)
    assertEquals(zoomed.layers.head.droppedRows, Vector.empty)
    assertEquals(zoomed.layout.map(_.xScale), Some(Interval.unsafe(0.0, 10.0)))
    assertEquals(zoomed.layout.map(_.clip), Some(Clip.On))
  }

  test("raw numeric zoom bounds map through the trained scale without becoming scale limits") {
    val data = Vector(
      Observation(0.0, 1.0),
      Observation(20.0, 2.0),
      Observation(40.0, 3.0),
      Observation(100.0, 4.0)
    )
    val xScale = ContinuousScale
      .fixed("measurement", Vector(0.0, 100.0), Palette.numeric)
      .fold(error => fail(error.message), identity)
    val trained = plot(data)
      .aes(_.x, _.y)
      .encode(Aesthetic.X, _.x, xScale)
      .coordZoom(x = Some(Interval.unsafe(20.0, 40.0)))
      .geomPoint()
      .resolve
      .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.head.rows.length, data.length)
    assertEquals(trained.layers.head.droppedRows, Vector.empty)
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(0.2, 0.4)))
    assertEquals(xScale.domain, Interval.unsafe(0.0, 100.0))
    val xAxis = trained.guides
      .collectFirst {
        case ResolvedGuide(axis: GuideSpec.Axis, _) if axis.side == AxisSide.Bottom => axis
      }
      .getOrElse(fail("missing zoomed numeric axis"))
    assertEquals(xAxis.ticks.toVector.flatten.map(_.label), Vector("20", "40"))
  }

  test("typed date windows map after temporal scale training and retain every row") {
    final case class Dated(day: CalendarDate, value: Double)
    val start = CalendarDate.parseUnsafe("2024-01-01")
    val data =
      Vector.tabulate(10)(index => Dated(start.addDaysUnsafe(index.toLong), index.toDouble))
    val yScale = ContinuousScaleSpec.numeric("value").fold(error => fail(error.message), identity)
    val window = CoordinateWindow.dateUnsafe(start.addDaysUnsafe(2), start.addDaysUnsafe(5))
    val trained = plot(data)
      .scaleXDate(
        _.day,
        name = "day",
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Day)
      )
      .encode(Aesthetic.Y, _.value, yScale)
      .coordZoomWindows(x = Some(window))
      .geomLine()
      .resolve
      .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.head.rows.length, data.length)
    assertEquals(trained.layers.head.droppedRows, Vector.empty)
    val xRange = trained.layout.map(_.xScale).getOrElse(fail("missing date layout"))
    assertEqualsDouble(xRange.lower, 2.0 / 9.0, tol)
    assertEqualsDouble(xRange.upper, 5.0 / 9.0, tol)
    val xAxis = trained.guides
      .collectFirst {
        case ResolvedGuide(axis: GuideSpec.Axis, _) if axis.side == AxisSide.Bottom => axis
      }
      .getOrElse(fail("missing zoomed date axis"))
    assertEquals(
      xAxis.ticks.toVector.flatten.map(_.label),
      Vector("2024-01-03", "2024-01-04", "2024-01-05", "2024-01-06")
    )
  }

  test("empty and scale-incompatible coordinate windows fail through typed errors") {
    assertEquals(Coord.zoom().left.toOption, Some(GraphicsError.EmptyCoordinateZoom))

    val dateWindow = CoordinateWindow.dateUnsafe(
      CalendarDate.parseUnsafe("2024-01-01"),
      CalendarDate.parseUnsafe("2024-01-02")
    )
    val zoom = Coord.zoomWindowsUnsafe(x = Some(dateWindow))
    assertEquals(
      zoom
        .transform(
          CoordInput(
            Vector.empty,
            Some(Interval.unsafe(0.0, 1.0) -> Interval.unsafe(0.0, 1.0))
          )
        )
        .left
        .toOption,
      Some(GraphicsError.CoordinateZoomScaleMismatch("x", "date", "unscaled"))
    )
  }

  test("coordinate zoom remains exact and non-filtering across facet panels") {
    final case class Faceted(x: Double, y: Double, group: String)
    val data = Vector(
      Faceted(0.0, 1.0, "a"),
      Faceted(5.0, 2.0, "a"),
      Faceted(10.0, 3.0, "b"),
      Faceted(15.0, 4.0, "b")
    )
    val trained = plot(data)
      .aes(_.x, _.y)
      .facetWrap(_.group)
      .coordZoom(x = Some(Interval.unsafe(0.0, 1.0)))
      .geomPoint()
      .resolve
      .fold(error => fail(error.message), identity)

    assertEquals(trained.facetPanels.length, 2)
    assertEquals(
      trained.facetPanels.map(_.layout.xScale),
      Vector.fill(2)(Interval.unsafe(0.0, 1.0))
    )
    assertEquals(trained.facetPanels.flatMap(_.layers).map(_.rows.length).sum, data.length)
    assertEquals(trained.facetPanels.flatMap(_.layers).flatMap(_.droppedRows), Vector.empty)
  }
