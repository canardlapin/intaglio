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
