package intaglio

/** Corner radii and step interpolation at the scene boundary: what construction refuses, what
  * lowering clamps, and how the coordinate transpose treats a step line.
  */
class GrobFormSuite extends munit.FunSuite:
  private val device = DeviceContext.unsafe(200.0, 100.0)

  private def rect(radius: ExtentExpr): DevicePrimitive.RectShape =
    val grob = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.4),
      cornerRadius = radius
    )
    DeviceScene.fromScene(Scene(Vector(grob)), device).orThrow.elements match
      case Vector(DeviceElement.Mark(shape: DevicePrimitive.RectShape)) => shape
      case other => fail(s"expected one rectangle primitive, found $other")

  test("a corner radius is an extent, so negative and non-finite values are unrepresentable") {
    // `InvalidExtent` carries a rendered description whose double formatting differs between the
    // JVM and Scala.js, so the case is matched rather than compared.
    assert(clue(ExtentExpr.points(-1.0)).left.exists {
      case GraphicsError.InvalidExtent(_) => true
      case _                              => false
    })
    // NaN is not equal to itself, so the error is matched rather than compared.
    assert(clue(ExtentExpr.points(Double.NaN)).left.exists {
      case GraphicsError.InvalidLength(value) => value.isNaN
      case _                                  => false
    })
    assertEquals(
      ExtentExpr.points(Double.PositiveInfinity),
      Left(GraphicsError.InvalidLength(Double.PositiveInfinity))
    )
  }

  test("the default rectangle carries the zero radius and lowers to a sharp rectangle") {
    val grob = Grob.rectUnsafe(Point.npcUnsafe(0.5, 0.5), Size.npcUnsafe(0.5, 0.4))
    assertEquals(grob.asInstanceOf[Grob.Rect].cornerRadius, ExtentExpr.zero)
    assertEquals(rect(ExtentExpr.zero).cornerRadius, 0.0)
  }

  test("a corner radius resolves like a circle radius: the smaller axis resolution wins") {
    // 6 points at 96 dpi is 8 device pixels on both axes.
    assertEquals(rect(ExtentExpr.pointsUnsafe(6.0)).cornerRadius, 8.0)
    // 0.1 npc of a 200x100 frame is 20 px across and 10 px down; the extent takes 10.
    assertEquals(rect(ExtentExpr.npcUnsafe(0.1)).cornerRadius, 10.0)
  }

  test("an oversized radius is clamped to half the shorter side, never refused") {
    val clamped = rect(ExtentExpr.npcUnsafe(4.0))
    assertEquals((clamped.width, clamped.height), (100.0, 40.0))
    assertEquals(clamped.cornerRadius, 20.0)
  }

  test("the corner radius never moves the rectangle") {
    val sharp = rect(ExtentExpr.zero)
    Vector(ExtentExpr.pointsUnsafe(2.0), ExtentExpr.npcUnsafe(0.05), ExtentExpr.npcUnsafe(9.0))
      .foreach { radius =>
        val rounded = rect(radius)
        assertEquals(
          (rounded.x, rounded.y, rounded.width, rounded.height),
          (sharp.x, sharp.y, sharp.width, sharp.height),
          clues(radius)
        )
      }
  }

  private val track =
    Vector(Point.npcUnsafe(0.2, 0.3), Point.npcUnsafe(0.6, 0.8), Point.npcUnsafe(0.9, 0.5))

  private def vertices(grob: Grob): Vector[DevicePoint] =
    DeviceScene.fromScene(Scene(Vector(grob)), device).orThrow.elements match
      case Vector(DeviceElement.Mark(DevicePrimitive.Polyline(points, false, _, _))) => points
      case other => fail(s"expected one open polyline, found $other")

  test("linear interpolation is the default and lowers the given points") {
    val grob = Grob.linesUnsafe(track)
    assertEquals(grob.asInstanceOf[Grob.Lines].interpolation, LineInterpolation.Linear)
    assertEquals(vertices(grob).length, 3)
  }

  test("step-after holds each y until the next x; step-before jumps at the current x") {
    assertEquals(
      vertices(Grob.linesUnsafe(track, interpolation = LineInterpolation.StepAfter)),
      Vector(
        DevicePoint(40.0, 70.0),
        DevicePoint(120.0, 70.0),
        DevicePoint(120.0, 20.0),
        DevicePoint(180.0, 20.0),
        DevicePoint(180.0, 50.0)
      )
    )
    assertEquals(
      vertices(Grob.linesUnsafe(track, interpolation = LineInterpolation.StepBefore)),
      Vector(
        DevicePoint(40.0, 70.0),
        DevicePoint(40.0, 20.0),
        DevicePoint(120.0, 20.0),
        DevicePoint(120.0, 50.0),
        DevicePoint(180.0, 50.0)
      )
    )
  }

  test("a one-point step line lowers to that single point") {
    Vector(LineInterpolation.StepAfter, LineInterpolation.StepBefore).foreach { interpolation =>
      assertEquals(
        vertices(Grob.linesUnsafe(track.take(1), interpolation = interpolation)).length,
        1,
        clues(interpolation)
      )
    }
  }

  test("transposing an interpolation is an involution and fixes only Linear") {
    LineInterpolation.values.foreach { interpolation =>
      assertEquals(interpolation.transposed.transposed, interpolation, clues(interpolation))
    }
    assertEquals(LineInterpolation.Linear.transposed, LineInterpolation.Linear)
    assertEquals(LineInterpolation.StepAfter.transposed, LineInterpolation.StepBefore)
    assertEquals(LineInterpolation.StepBefore.transposed, LineInterpolation.StepAfter)
  }

  /** The reason `transposed` exchanges the two forms: a step line drawn on exchanged axes must have
    * exactly the exchanged vertices of its own expansion, not a step in the wrong direction.
    */
  test("a transposed step line has the transposed vertices of its own expansion") {
    def swap(point: Point): Point = Point(point.y, point.x)
    // The corners a step-after track stands for, written out in scene space.
    val expansion =
      Vector(
        track(0),
        Point(track(1).x, track(0).y),
        track(1),
        Point(track(2).x, track(1).y),
        track(2)
      )
    val transposedTrack = track.map(swap)

    assertEquals(
      vertices(Grob.linesUnsafe(transposedTrack, LineInterpolation.StepAfter.transposed)),
      vertices(Grob.linesUnsafe(expansion.map(swap)))
    )
    assertNotEquals(
      vertices(Grob.linesUnsafe(transposedTrack, LineInterpolation.StepAfter)),
      vertices(Grob.linesUnsafe(expansion.map(swap)))
    )
  }
