package intaglio

class StrokeCasingSuite extends munit.FunSuite:
  test("invalid directly constructed widths and opacity fail through checked casing") {
    for width <- Vector(-1.0, Double.NaN, Double.PositiveInfinity) do
      assert(StrokeCasing.checked(Rgba.White, CasingWidth.Relative(width)).isLeft)
    for alpha <- Vector(-0.1, 1.1, Double.NaN) do
      assert(StrokeCasing.checked(Rgba.White, CasingWidth.Relative(2), alpha).isLeft)
  }

  test("physical and relative casing widths resolve once at target DPI") {
    val context = RenderContext.unsafe(width = 100, height = 100, pixelsPerInch = 144)
    def resolved(width: CasingWidth) =
      val gp = GraphicParams
        .unsafe(lineWidth = 2, lineWidthUnit = StrokeUnit.Point)
        .withCasing(StrokeCasing.unsafe(Rgba.White, width, alpha = 0.6, lineType = LineType.Dotted))
      val line = Grob.lines(Vector(Point.npcUnsafe(0, 0), Point.npcUnsafe(1, 1)), gp = gp).orThrow
      DeviceScene.fromScene(Scene(Vector(line)), context).orThrow.elements.head match
        case DeviceElement.Mark(DevicePrimitive.Polyline(_, _, style, _)) => style
        case other => fail(s"expected line, got $other")
    val relative = resolved(CasingWidth.relativeUnsafe(3))
    val physical = resolved(CasingWidth.Absolute(StrokeWidth.pointsUnsafe(6)))
    assertEquals(relative, physical)
    assertEquals(relative.lineWidth, 4.0)
    assertEquals(
      relative.casing.get.width,
      CasingWidth.Absolute(StrokeWidth.devicePixelsUnsafe(12))
    )
    assertEquals(relative.casing.get.alpha, 0.6)
    assertEquals(relative.casing.get.lineType, LineType.Dotted)
    assertEquals(relative.withoutCasing.casing, None)
  }

  private def lowered(grob: Grob): Vector[DevicePrimitive] =
    DeviceScene
      .fromScene(Scene(Vector(grob)), RenderContext.unsafe(width = 100, height = 100))
      .orThrow
      .elements
      .collect { case DeviceElement.Mark(primitive) => primitive }

  private val casedPoint = GraphicParams
    .unsafe(stroke = Some(Rgba.Black), lineWidth = 2)
    .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(3)))

  test("a cased single cross lowers to one batch so both bars share one underlay") {
    val at = Vector(Point.npcUnsafe(0.3, 0.3), Point.npcUnsafe(0.7, 0.7))
    val name = Some(GraphicsName.unsafe("crosses"))
    val cased =
      lowered(Grob.points(at, shape = PointShape.Cross, gp = casedPoint, name = name).orThrow)
    cased match
      case Vector(DevicePrimitive.PointBatch(points, radii, shapes, params, batchName)) =>
        assertEquals(points.length, 2)
        assertEquals(shapes, BatchColumn.Constant(PointShape.Cross))
        assertEquals(batchName, name)
        assertEquals(
          params.valueAt(0).casing.map(_.width),
          Some(CasingWidth.Absolute(StrokeWidth.devicePixelsUnsafe(6)))
        )
        assert(radii.valueAt(0) > 0)
      case other => fail(s"expected one cased cross batch, found $other")
    val plain = lowered(
      Grob.points(at, shape = PointShape.Cross, gp = casedPoint.withoutCasing, name = name).orThrow
    )
    assertEquals(plain.length, 4)
    assert(plain.forall(_.isInstanceOf[DevicePrimitive.Polyline]))
    val unstroked = lowered(
      Grob
        .points(
          at,
          shape = PointShape.Cross,
          gp = GraphicParams
            .unsafe(stroke = None, lineWidth = 2)
            .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(3))),
          name = name
        )
        .orThrow
    )
    assertEquals(unstroked.length, 4, "a casing without a stroke to case changes nothing")
  }

  test("a cased circle point and circle grob lower to discs carrying the resolved casing") {
    val point = lowered(Grob.points(Vector(Point.npcUnsafe(0.5, 0.5)), gp = casedPoint).orThrow)
    val circle = lowered(
      Grob.circle(Point.npcUnsafe(0.5, 0.5), ExtentExpr.pointsUnsafe(4), gp = casedPoint).orThrow
    )
    for marks <- Vector(point, circle) do
      marks match
        case Vector(DevicePrimitive.Disc(_, _, _, gp, _)) =>
          assertEquals(
            gp.casing.map(_.width),
            Some(CasingWidth.Absolute(StrokeWidth.devicePixelsUnsafe(6)))
          )
        case other => fail(s"expected one disc, found $other")
  }
