package intaglio.svg

import intaglio.*

class SvgRenderContextSuite extends munit.FunSuite:
  test("render consumes the dimensions and resolved family from one render plan") {
    val context = RenderContext.unsafe(
      width = 321,
      height = 123,
      pixelsPerInch = 144.0,
      fontRegistry = FontRegistry(_ => Some("Resolved Backend Sans")),
      lineHeightPt = 18.0,
      deviceScale = 1.5
    )
    val text = Grob.textUnsafe(
      "context",
      Point.npcUnsafe(0.5, 0.5),
      gp = GraphicParams.unsafe(fontFamily = Some("Requested Sans"))
    )
    val line = Grob
      .lines(
        Vector(Point.npcUnsafe(0.1, 0.1), Point.npcUnsafe(0.9, 0.1)),
        gp = GraphicParams.unsafe().withStrokeWidth(StrokeWidth.pointsUnsafe(2.0))
      )
      .fold(error => fail(error.message), identity)
    val document = SvgRenderer
      .render(RenderPlan(Scene(Vector(text, line)), context))
      .fold(error => fail(error.message), identity)

    assert(document.value.contains("width=\"321\" height=\"123\" viewBox=\"0 0 321 123\""))
    assert(document.value.contains("font-family=\"Resolved Backend Sans\""))
    assert(document.value.contains("font-size=\"24\""))
    assert(document.value.contains("stroke-width=\"4\""))
    assertEquals(document.width, 321)
    assertEquals(document.height, 123)
    assertEquals(document.pixelsPerInch, 144.0)
    assertEquals(document.deviceScale, 1.5)
    assertEquals(document.logicalWidth, 214.0)
    assertEquals(document.logicalHeight, 82.0)
  }
