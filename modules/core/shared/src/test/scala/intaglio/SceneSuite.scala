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
    assertEquals(Grob.points(Vector.empty).left.toOption, Some(GraphicsError.EmptyGeometry("points")))
    assertEquals(Grob.lines(Vector.empty).left.toOption, Some(GraphicsError.EmptyGeometry("lines")))
    assertEquals(Grob.segments(Vector.empty).left.toOption, Some(GraphicsError.EmptyGeometry("segments")))
    assertEquals(
      Grob.polygon(Vector(p0, p1)).left.toOption,
      Some(GraphicsError.InvalidGeometrySize("polygon", 3, 2))
    )
  }

  test("public polygons lower as closed renderer-neutral polylines") {
    val polygon = Grob
      .polygon(Vector(Point.nativeUnsafe(0.0, 0.0), Point.nativeUnsafe(1.0, 0.0), Point.nativeUnsafe(0.5, 1.0)))
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
    assertEquals(GraphicParams.checked(lineWidth = -1.0).left.toOption, Some(GraphicsError.InvalidLineWidth(-1.0)))
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
    assert(Grob.image(image, p0, Size.npcUnsafe(0.5, 0.5), alpha = Double.NaN).left.toOption.exists {
      case GraphicsError.InvalidAlpha(value) => value.isNaN
      case _                                 => false
    })
  }

  test("graphic parameters define backend-neutral stroke geometry defaults") {
    val gp = GraphicParams.unsafe()

    assertEquals(gp.lineCap, LineCap.Butt)
    assertEquals(gp.lineJoin, LineJoin.Miter)
  }

  test("length expressions preserve symbolic unit composition") {
    val expr =
      LengthExpr.npcUnsafe(0.5) + LengthExpr(Length.pointsUnsafe(2.0)) - LengthExpr.nativeUnsafe(1.0)
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
