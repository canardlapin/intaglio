package intaglio

class TextPlateSuite extends munit.FunSuite:
  private val plate = TextPlate(
    Rgba.unsafe(20, 20, 20, 0.6),
    padding = StrokeWidth.pointsUnsafe(3.0),
    cornerRadius = StrokeWidth.pointsUnsafe(2.0)
  )

  private def loweredText(gp: GraphicParams, pixelsPerInch: Double): GraphicParams =
    val text = Grob.text("label", Point.npcUnsafe(0.5, 0.5), gp = gp).orThrow
    DeviceScene
      .fromScene(Scene(Vector(text)), DeviceContext.unsafe(100, 100, pixelsPerInch))
      .orThrow
      .elements
      .collect { case DeviceElement.Mark(run: DevicePrimitive.TextRun) => run.gp }
      .head

  test("padding and corner radius resolve to device pixels once, at the target density") {
    val resolved = loweredText(GraphicParams.unsafe().withTextPlate(plate), 144).textPlate
    assertEquals(
      resolved,
      Some(
        plate.copy(
          padding = StrokeWidth.devicePixelsUnsafe(6.0),
          cornerRadius = StrokeWidth.devicePixelsUnsafe(4.0)
        )
      )
    )
    assertEquals(loweredText(GraphicParams.unsafe(), 144).textPlate, None)
    assertEquals(
      GraphicParams.unsafe().withTextPlate(plate).withoutTextPlate,
      GraphicParams.unsafe()
    )
  }

  test("a plate is the measured box plus padding on every side, its radius clamped") {
    val resolved = plate.copy(
      padding = StrokeWidth.devicePixelsUnsafe(4.0),
      cornerRadius = StrokeWidth.devicePixelsUnsafe(50.0)
    )
    assertEquals(
      resolved.around(10.0, 20.0, 30.0, 8.0),
      TextPlateBounds(6.0, 16.0, 38.0, 16.0, 8.0)
    )
    val sharp = TextPlate(Rgba.White, StrokeWidth.devicePixelsUnsafe(0.0))
    assertEquals(sharp.around(1.0, 2.0, 3.0, 4.0), TextPlateBounds(1.0, 2.0, 3.0, 4.0, 0.0))
  }

  test("an oversized plate padding is a typed lowering error") {
    val huge = TextPlate(Rgba.White, StrokeWidth.pointsUnsafe(1.0e13))
    val text = Grob
      .text("label", Point.npcUnsafe(0.5, 0.5), gp = GraphicParams.unsafe().withTextPlate(huge))
      .orThrow
    assert(DeviceScene.fromScene(Scene(Vector(text)), DeviceContext.unsafe(100, 100)).isLeft)
  }
