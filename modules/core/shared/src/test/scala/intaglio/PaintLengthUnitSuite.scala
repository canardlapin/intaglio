package intaglio

class PaintLengthUnitSuite extends munit.FunSuite:
  private def lowered(gp: GraphicParams, pixelsPerInch: Double): GraphicParams =
    val rect = Grob.rect(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(0.5, 0.5), gp = gp).orThrow
    DeviceScene
      .fromScene(Scene(Vector(rect)), DeviceContext.unsafe(100, 100, pixelsPerInch))
      .orThrow
      .elements
      .collect { case DeviceElement.Mark(DevicePrimitive.RectShape(_, _, _, _, _, style, _)) =>
        style
      }
      .head

  private def dashed(lineType: LineType) = GraphicParams.unsafe(lineType = lineType)

  private def segments(gp: GraphicParams): Vector[Double] =
    gp.lineType.dash.map(_.segments).getOrElse(Vector.empty)

  test("named rhythms lower unchanged at 96 ppi and scale with density elsewhere") {
    assertEquals(lowered(dashed(LineType.Dashed), 96).lineType, LineType.Dashed)
    assertEquals(lowered(dashed(LineType.Dotted), 96).lineType, LineType.Dotted)
    val doubled = lowered(dashed(LineType.Dashed), 192).lineType
    assertEquals(
      doubled,
      LineType.Custom(DashPattern(Vector(12.0, 8.0), PaintLengthUnit.DevicePixel).orThrow)
    )
    assertEquals(segments(lowered(dashed(LineType.Dotted), 48)), Vector(0.5, 1.5))
  }

  test("a layout-unit dash keeps one physical length at every density") {
    val rhythm = DashPattern.unsafe(5.0, 2.0, 1.0, 2.0)
    for ppi <- Vector(72.0, 96.0, 144.0, 192.0, 300.0) do
      val inches = segments(lowered(dashed(LineType.Custom(rhythm)), ppi)).map(_ / ppi)
      for (actual, expected) <- inches.zip(rhythm.segments.map(_ / 96.0)) do
        assertEqualsDouble(actual, expected, 1.0e-12, clue(ppi))
  }

  test("points and millimetres resolve through the same density as stroke widths") {
    val points = DashPattern(Vector(6.0, 3.0), PaintLengthUnit.Point).orThrow
    val millimetres = DashPattern(Vector(2.54), PaintLengthUnit.Millimetre).orThrow
    assertEquals(lowered(dashed(LineType.Custom(points)), 72).lineType, LineType.Custom(points))
    assertEquals(segments(lowered(dashed(LineType.Custom(points)), 144)), Vector(12.0, 6.0))
    assertEqualsDouble(
      segments(lowered(dashed(LineType.Custom(millimetres)), 100)).head,
      10.0,
      1.0e-12
    )
    val strokeInPoints = GraphicParams
      .unsafe(lineType = LineType.Custom(points))
      .withStrokeWidth(StrokeWidth.pointsUnsafe(6.0))
    val resolved = lowered(strokeInPoints, 144)
    assertEquals(segments(resolved).head, resolved.lineWidth, "a 6 pt dash is a 6 pt stroke")
  }

  test("device pixels are an explicit opt-in that never scales") {
    val literal = DashPattern.Dashed.withUnit(PaintLengthUnit.DevicePixel)
    for ppi <- Vector(72.0, 96.0, 192.0) do
      assertEquals(segments(lowered(dashed(LineType.Custom(literal)), ppi)), Vector(6.0, 4.0))
    val paint = PatternPaint(
      PatternRecipe.parallelRules(RuleOrientation.Horizontal, 10.0, 2.0).orThrow,
      Rgba.Black
    ).withUnit(PaintLengthUnit.DevicePixel)
    assertEquals(
      lowered(GraphicParams.unsafe().withPatternFill(paint), 192).fillPattern,
      Some(paint)
    )
  }

  test("pattern spacing, line width and stipple radius scale together, angles do not") {
    val hatch = PatternPaint(PatternRecipe.angledHatch(30.0, 12.0, 1.5).orThrow, Rgba.Black)
    assertEquals(
      lowered(GraphicParams.unsafe().withPatternFill(hatch), 96).fillPattern,
      Some(hatch)
    )
    lowered(GraphicParams.unsafe().withPatternFill(hatch), 192).fillPattern match
      case Some(PatternPaint(recipe: PatternRecipe.AngledHatch, Rgba.Black, None, unit)) =>
        assertEquals((recipe.angleDegrees, recipe.spacing, recipe.lineWidth), (30.0, 24.0, 3.0))
        assertEquals(unit, PaintLengthUnit.DevicePixel)
      case other => fail(s"expected a resolved angled hatch, found $other")
    val stipple =
      PatternPaint(PatternRecipe.stipple(8.0, 4.0).orThrow, Rgba.Black, Some(Rgba.White))
        .withUnit(PaintLengthUnit.Point)
    lowered(GraphicParams.unsafe().withPatternFill(stipple), 144).fillPattern match
      case Some(PatternPaint(recipe: PatternRecipe.Stipple, _, Some(Rgba.White), _)) =>
        assertEquals((recipe.spacing, recipe.radius), (16.0, 8.0))
      case other => fail(s"expected a resolved stipple, found $other")
  }

  test("a casing's own rhythm resolves with the stroke's") {
    val gp = GraphicParams
      .unsafe(lineType = LineType.Dashed)
      .withCasing(
        StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(3), lineType = LineType.Dotted)
      )
    val resolved = lowered(gp, 192)
    assertEquals(segments(resolved), Vector(12.0, 8.0))
    assertEquals(resolved.casing.flatMap(_.lineType.dash).map(_.segments), Some(Vector(2.0, 6.0)))
  }

  test("a unit-bearing pattern keeps the constructor's validation") {
    assert(DashPattern(Vector.empty, PaintLengthUnit.Point).isLeft)
    assert(DashPattern(Vector(0.0, 0.0), PaintLengthUnit.Millimetre).isLeft)
    assert(DashPattern(Vector(1.0, -1.0), PaintLengthUnit.DevicePixel).isLeft)
    assertEquals(DashPattern.unsafe(6.0, 4.0), DashPattern.Dashed)
    assertEquals(DashPattern.Dashed.unit, PaintLengthUnit.LayoutPixel)
    assertNotEquals(DashPattern.Dashed.withUnit(PaintLengthUnit.Point), DashPattern.Dashed)
  }

  test("a rhythm that would overflow device range is a typed error, not a non-finite dash") {
    // 1e13 mm is about 3.8e13 device pixels at 96 ppi, past the 1e13 device-value bound.
    val huge = DashPattern(Vector(1.0e13), PaintLengthUnit.Millimetre).orThrow
    val rect = Grob
      .rect(
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(0.5, 0.5),
        gp = dashed(LineType.Custom(huge))
      )
      .orThrow
    assert(
      DeviceScene.fromScene(Scene(Vector(rect)), DeviceContext.unsafe(100, 100, 96)).isLeft
    )
  }
