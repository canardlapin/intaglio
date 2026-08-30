package intaglio.canvas

import intaglio.*

class CanvasRenderContextSuite extends munit.FunSuite:
  test("compile consumes the dimensions and resolved family from one render plan") {
    val context = RenderContext.unsafe(
      width = 321,
      height = 123,
      pixelsPerInch = 144.0,
      fontRegistry = FontRegistry(_ => Some("Resolved Backend Sans")),
      deviceScale = 1.5
    )
    val text = Grob.textUnsafe(
      "context",
      Point.npcUnsafe(0.5, 0.5),
      gp = GraphicParams.unsafe(fontFamily = Some("Requested Sans"))
    )
    val program = CanvasRenderer
      .compile(RenderPlan(Scene(Vector(text)), context))
      .fold(error => fail(error.message), identity)

    assertEquals(program.width, 321)
    assertEquals(program.height, 123)
    assertEquals(program.pixelsPerInch, 144.0)
    assertEquals(program.deviceScale, 1.5)
    assertEquals(program.logicalWidth, 214.0)
    assertEquals(program.logicalHeight, 82.0)
    program.commands.headOption match
      case Some(CanvasCommand.Text(_, _, _, _, _, _, fontSize, family, _, _)) =>
        assertEqualsDouble(fontSize, 24.0, 1e-9)
        assertEquals(family, Some("Resolved Backend Sans"))
      case other => fail(s"expected one text command, got $other")
  }

  test("Canvas HiDPI options derive backing pixels and actual density from logical dimensions") {
    val options = CanvasOptions
      .hidpi(321, 199, devicePixelRatio = 1.25)
      .fold(error => fail(error.message), identity)
    val point = Grob
      .points(Vector(Point.npcUnsafe(0.5, 0.5)))
      .fold(error => fail(error.message), identity)
    val program = CanvasRenderer
      .compile(Scene(Vector(point)), options)
      .fold(error => fail(error.message), identity)

    assertEquals(options.width, 401)
    assertEquals(options.height, 249)
    assertEquals(options.pixelsPerInch, 120.0)
    assertEquals(options.deviceScale, 1.25)
    assertEquals(options.logicalWidth, 321.0)
    assertEquals(options.logicalHeight, 199.0)
    assertEquals(program.width, 401)
    assertEquals(program.height, 249)
    assertEquals(program.pixelsPerInch, 120.0)
    assertEquals(program.deviceScale, 1.25)
    assertEquals(program.logicalWidth, 321.0)
    assertEquals(program.logicalHeight, 199.0)
  }
