package intaglio.javafx

import intaglio.*

class JavaFxRenderContextSuite extends munit.FunSuite:
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
    val program = JavaFxRenderer
      .compile(RenderPlan(Scene(Vector(text)), context))
      .fold(error => fail(error.message), identity)

    assertEquals(program.width, 321)
    assertEquals(program.height, 123)
    assertEquals(program.pixelsPerInch, 144.0)
    assertEquals(program.deviceScale, 1.5)
    assertEquals(program.logicalWidth, 214.0)
    assertEquals(program.logicalHeight, 82.0)
    program.commands.headOption match
      case Some(JavaFxCommand.Text(_, _, _, _, _, _, fontSize, family, _, _)) =>
        assertEqualsDouble(fontSize, 24.0, 1e-9)
        assertEquals(family, Some("Resolved Backend Sans"))
      case other => fail(s"expected one text command, got $other")
  }
