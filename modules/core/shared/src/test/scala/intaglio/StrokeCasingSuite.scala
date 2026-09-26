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
