package intaglio.svg

import intaglio.*

class SvgTrackingSuite extends munit.FunSuite:
  test("Unicode text and signed physical spacing reach SVG without splitting text") {
    val label = "x\u0301 α سلام 👩‍🔬"
    val gp = GraphicParams.unsafe().withLetterSpacing(LetterSpacing.pointsUnsafe(-0.75))
    val scene = Scene(Vector(Grob.textUnsafe(label, Point.npcUnsafe(0.5, 0.5), gp = gp)))
    val document = SvgRenderer
      .render(RenderPlan(scene, RenderContext.unsafe(400, 100, pixelsPerInch = 144)))
      .orThrow
      .value
    assert(document.contains("letter-spacing=\"-1.5\""))
    assert(document.contains(label))
    assert(!document.contains("<tspan"))
  }

  test("zero spacing preserves SVG and plate metrics receive requested tracking") {
    val label = "x\u0301 α"
    val base = GraphicParams.unsafe()
    def render(gp: GraphicParams, metrics: TextMetrics = TextMetrics.estimate) =
      SvgRenderer
        .render(
          RenderPlan(
            Scene(Vector(Grob.textUnsafe(label, Point.npcUnsafe(0.5, 0.5), gp = gp))),
            RenderContext.unsafe(400, 100, pixelsPerInch = 72, textMetrics = metrics)
          )
        )
        .orThrow
        .value
    assertEquals(render(base), render(base.withLetterSpacing(LetterSpacing.pointsUnsafe(0))))
    var observed = Vector.empty[Double]
    val metrics = new TextMetrics:
      def widthPt(text: String, size: Double): Double = 40
      def heightPt(size: Double): Double = 15
      override def widthPt(text: String, style: TextStyle): Double =
        observed :+= style.letterSpacingPt
        40 + style.letterSpacingPt
    render(
      base.withLetterSpacing(LetterSpacing.pointsUnsafe(2)).withTextPlate(TextPlate(Rgba.White)),
      metrics
    )
    assert(observed.nonEmpty)
    assert(observed.forall(_ == 2))
  }
