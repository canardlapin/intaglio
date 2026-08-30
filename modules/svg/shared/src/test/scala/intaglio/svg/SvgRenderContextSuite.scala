package intaglio.svg

import intaglio.*

class SvgRenderContextSuite extends munit.FunSuite:
  test("render consumes the dimensions and resolved family from one render plan") {
    val context = RenderContext.unsafe(
      width = 321,
      height = 123,
      pixelsPerInch = 144.0,
      fontRegistry = FontRegistry(_ => Some("Resolved Backend Sans"))
    )
    val text = Grob.textUnsafe(
      "context",
      Point.npcUnsafe(0.5, 0.5),
      gp = GraphicParams.unsafe(fontFamily = Some("Requested Sans"))
    )
    val document = SvgRenderer
      .render(RenderPlan(Scene(Vector(text)), context))
      .fold(error => fail(error.message), identity)
      .value

    assert(document.contains("width=\"321\" height=\"123\" viewBox=\"0 0 321 123\""))
    assert(document.contains("font-family=\"Resolved Backend Sans\""))
    assert(document.contains("font-size=\"24\""))
  }
