package intaglio

class SceneSuite extends munit.FunSuite:

  private val p0 = Point.npcUnsafe(0.0, 0.0)
  private val p1 = Point.npcUnsafe(1.0, 1.0)

  test("scene append has empty identity and preserves grob order") {
    val line = Grob.lines(Vector(p0, p1)).toOption.get
    val point = Grob.points(Vector(p0)).toOption.get

    val scene = Scene.empty.append(line).append(point)

    assertEquals((Scene.empty ++ scene), scene)
    assertEquals((scene ++ Scene.empty), scene)
    assertEquals(scene.grobs, Vector(line, point))
  }

  test("scene concatenation is associative") {
    val a = Scene(Vector(Grob.points(Vector(p0)).toOption.get))
    val b = Scene(Vector(Grob.lines(Vector(p0, p1)).toOption.get))
    val c = Scene(Vector(Grob.circle(p1, ExtentExpr.npcUnsafe(0.2)).toOption.get))

    assertEquals((a ++ b) ++ c, a ++ (b ++ c))
  }

  test("grob constructors reject empty geometries before renderer boundaries") {
    assertEquals(
      Grob.points(Vector.empty).left.toOption,
      Some(GraphicsError.EmptyGeometry("points"))
    )
    assertEquals(Grob.lines(Vector.empty).left.toOption, Some(GraphicsError.EmptyGeometry("lines")))
    assertEquals(
      Grob.segments(Vector.empty).left.toOption,
      Some(GraphicsError.EmptyGeometry("segments"))
    )
    assertEquals(
      Grob.polygon(Vector(p0, p1)).left.toOption,
      Some(GraphicsError.InvalidGeometrySize("polygon", 3, 2))
    )
  }

  test("public polygons lower as closed renderer-neutral polylines") {
    val polygon = Grob
      .polygon(
        Vector(
          Point.nativeUnsafe(0.0, 0.0),
          Point.nativeUnsafe(1.0, 0.0),
          Point.nativeUnsafe(0.5, 1.0)
        )
      )
      .fold(error => fail(error.message), identity)
    val device = DeviceScene
      .fromScene(Scene(Vector(polygon)), DeviceContext.unsafe(100.0, 100.0))
      .fold(error => fail(error.message), identity)

    val closed = device.elements.collectFirst {
      case DeviceElement.Mark(DevicePrimitive.Polyline(_, isClosed, _, _)) => isClosed
    }
    assertEquals(closed, Some(true))
  }

  test("color and length constructors keep invalid scalar values out of the scene tree") {
    assertEquals(Rgba(300, 0, 0).left.toOption, Some(GraphicsError.InvalidColorChannel("red", 300)))
    assertEquals(Rgba(0, 0, 0, 1.5).left.toOption, Some(GraphicsError.InvalidAlpha(1.5)))
    assertEquals(
      GraphicParams.checked(lineWidth = -1.0).left.toOption,
      Some(GraphicsError.InvalidLineWidth(-1.0))
    )
    assertEquals(
      StrokeWidth.points(-1.0).left.toOption,
      Some(GraphicsError.InvalidLineWidth(-1.0))
    )
    assert(Length(Double.NaN, LengthUnit.Npc).left.toOption.exists {
      case GraphicsError.InvalidLength(value) => value.isNaN
      case _                                  => false
    })
    assertEquals(ExtentExpr.npc(-0.5).left.toOption, Some(GraphicsError.InvalidExtent("-0.5 Npc")))
    assert(Grob.text("bad", p0, rotationDegrees = Double.NaN).left.toOption.exists {
      case GraphicsError.InvalidRotation(value) => value.isNaN
      case _                                    => false
    })
    val image = RasterImage.solid(RasterDimensions.unsafe(1, 1), Rgba32.unsafe(0, 0, 0))
    assert(
      Grob.image(image, p0, Size.npcUnsafe(0.5, 0.5), alpha = Double.NaN).left.toOption.exists {
        case GraphicsError.InvalidAlpha(value) => value.isNaN
        case _                                 => false
      }
    )
  }

  test("graphic parameters define backend-neutral stroke geometry defaults") {
    val gp = GraphicParams.unsafe()
    val physical = gp.withStrokeWidth(StrokeWidth.pointsUnsafe(0.75))

    assertEquals(gp.lineCap, LineCap.Butt)
    assertEquals(gp.lineJoin, LineJoin.Miter)
    assertEquals(gp.strokeWidth, StrokeWidth.devicePixelsUnsafe(1.0))
    assertEquals(physical.strokeWidth, StrokeWidth.pointsUnsafe(0.75))
    assertEquals(physical.lineWidthUnit, StrokeUnit.Point)
  }

  test("line lengths have public checked constructors at every expression layer") {
    assertEquals(Length.lines(1.5).map(_.unit), Right(LengthUnit.Line))
    assertEquals(LengthExpr.lines(1.5), Right(LengthExpr(Length.linesUnsafe(1.5))))
    assertEquals(ExtentExpr.lines(1.5), ExtentExpr(Length.linesUnsafe(1.5)))
  }

  test("pattern recipes reject non-finite and non-positive geometry") {
    val invalid = Vector(
      PatternRecipe.angledHatch(Double.NaN, 8.0, 1.0),
      PatternRecipe.angledHatch(Double.PositiveInfinity, 8.0, 1.0),
      PatternRecipe.crossHatch(Double.NegativeInfinity, 8.0, 1.0),
      PatternRecipe.angledHatch(30.0, Double.NaN, 1.0),
      PatternRecipe.crossHatch(30.0, Double.PositiveInfinity, 1.0),
      PatternRecipe.parallelRules(RuleOrientation.Horizontal, Double.NegativeInfinity, 1.0),
      PatternRecipe.parallelRules(RuleOrientation.Vertical, 0.0, 1.0),
      PatternRecipe.parallelRules(RuleOrientation.Vertical, -1.0, 1.0),
      PatternRecipe.angledHatch(30.0, 8.0, Double.NaN),
      PatternRecipe.crossHatch(30.0, 8.0, Double.PositiveInfinity),
      PatternRecipe.parallelRules(RuleOrientation.Horizontal, 8.0, Double.NegativeInfinity),
      PatternRecipe.parallelRules(RuleOrientation.Horizontal, 8.0, 0.0),
      PatternRecipe.parallelRules(RuleOrientation.Horizontal, 8.0, -1.0),
      PatternRecipe.stipple(0.0, 1.0),
      PatternRecipe.stipple(8.0, Double.NaN),
      PatternRecipe.stipple(8.0, Double.PositiveInfinity),
      PatternRecipe.stipple(8.0, -1.0)
    )

    invalid.foreach { result =>
      assert(result.left.toOption.exists(_.isInstanceOf[GraphicsError.InvalidPatternParameter]))
    }
  }

  test("stipple radius must fit its tile") {
    assert(PatternRecipe.stipple(8.0, 4.0).isRight)
    assertEquals(
      PatternRecipe.stipple(8.0, 4.0001).left.toOption,
      Some(
        GraphicsError.InvalidPatternParameter(
          "stipple",
          "radius",
          4.0001,
          "no greater than half its spacing"
        )
      )
    )
  }

  test("pattern fill is explicit and mutually exclusive with solid fill") {
    val recipe =
      PatternRecipe.angledHatch(30.0, 8.0, 1.5).fold(error => fail(error.message), identity)
    val paint = PatternPaint(recipe, Rgba.Black, Some(Rgba.White))
    val solid = GraphicParams.unsafe(fill = Some(Rgba.unsafe(10, 20, 30)))
    val patterned = solid.withPatternFill(paint)

    assertEquals(patterned.fill, None)
    assertEquals(patterned.fillPattern, Some(paint))
    assertEquals(patterned.withSolidFill(Some(Rgba.White)).fill, Some(Rgba.White))
    assertEquals(patterned.withSolidFill(Some(Rgba.White)).fillPattern, None)
  }

  test("pattern recipe equality is structural across independent construction") {
    val first = PatternRecipe.crossHatch(45.0, 6.0, 0.75)
    val second = PatternRecipe.crossHatch(45.0, 6.0, 0.75)

    assertEquals(first, second)
  }

  test("length expressions preserve symbolic unit composition") {
    val expr =
      LengthExpr.npcUnsafe(0.5) + LengthExpr(Length.pointsUnsafe(2.0)) - LengthExpr.nativeUnsafe(
        1.0
      )
    val scaled =
      expr.times(2.0).toOption.get

    assertEquals(
      expr,
      LengthExpr.Sub(
        LengthExpr.Add(LengthExpr.npcUnsafe(0.5), LengthExpr(Length.pointsUnsafe(2.0))),
        LengthExpr.nativeUnsafe(1.0)
      )
    )
    assertEquals(scaled, LengthExpr.Mul(2.0, expr))
    assert(LengthExpr.npcUnsafe(0.5).times(Double.NaN).left.toOption.exists {
      case GraphicsError.InvalidLength(value) => value.isNaN
      case _                                  => false
    })
  }

  test("dash patterns refuse rhythms a backend could not draw") {
    assertEquals(
      DashPattern(Vector.empty).left.toOption,
      Some(GraphicsError.InvalidDashPattern("at least one segment", "empty"))
    )
    assertEquals(
      DashPattern(Vector(0.0, 0.0)).left.toOption,
      Some(GraphicsError.InvalidDashPattern("one segment above zero", "every segment is zero"))
    )
    assertEquals(
      DashPattern(Vector(4.0, -1.0)).left.toOption,
      Some(GraphicsError.InvalidDashPattern("non-negative segments", "segment 1 is negative"))
    )
    assertEquals(
      DashPattern(Vector(1.0, Double.NaN)).left.toOption,
      Some(GraphicsError.InvalidDashPattern("finite segments", "segment 1 is not finite"))
    )
    assert(DashPattern(Vector(1.0, Double.NaN)).isLeft)
    assert(DashPattern(Vector(1.0, Double.PositiveInfinity)).isLeft)
    assert(DashPattern(Vector.fill(DashPattern.MaximumSegments + 1)(1.0)).isLeft)

    // A zero segment is legal beside a positive one: "0 4" with a round cap is how a dotted
    // rhythm is drawn, and only an all-zero array is what `java.awt.BasicStroke` rejects.
    assert(DashPattern(Vector(0.0, 4.0)).isRight)
    assert(DashPattern(Vector.fill(DashPattern.MaximumSegments)(1.0)).isRight)
    assertEquals(DashPattern.unsafe(6.0, 4.0).segments, Vector(6.0, 4.0))
  }

  test("every line type resolves through one dash definition") {
    assertEquals(LineType.Solid.dash, None)
    assertEquals(LineType.Dashed.dash.map(_.segments), Some(Vector(6.0, 4.0)))
    assertEquals(LineType.Dotted.dash.map(_.segments), Some(Vector(1.0, 3.0)))

    val custom = DashPattern.unsafe(8.0, 2.0, 1.0, 2.0)
    assertEquals(LineType.Custom(custom).dash, Some(custom))

    // The named rhythms are expressible as custom ones, which is what makes this a widening of the
    // existing vocabulary rather than a parallel channel.
    assertEquals(LineType.Custom(DashPattern.unsafe(6.0, 4.0)).dash, LineType.Dashed.dash)
  }
