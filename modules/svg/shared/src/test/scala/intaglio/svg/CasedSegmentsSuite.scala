package intaglio.svg

import intaglio.*

class CasedSegmentsSuite extends munit.FunSuite:
  test("connected cased segments emit one underlay before one named ink stroke") {
    val points = Vector.tabulate(21)(i => Point.npcUnsafe(0.1 + i * 0.04, 0.5))
    val gp = GraphicParams
      .unsafe(lineWidth = 1, lineCap = LineCap.Round)
      .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(2), alpha = 0.5))
    val grob = Grob
      .segments(points.zip(points.tail), gp = gp, name = Some(GraphicsName.unsafe("contour")))
      .orThrow
    val svg = SvgRenderer.render(Scene(Vector(grob)), SvgOptions.unsafe(300, 40)).orThrow.value
    val strokes = svg.linesIterator.filter(_.contains("<polyline")).toVector
    assertEquals(strokes.size, 2)
    assert(strokes.head.contains("stroke-width=\"2\""))
    assert(strokes.head.contains("opacity=\"0.5\""))
    assert(!strokes.head.contains("data-name="))
    assert(strokes.last.contains("stroke-width=\"1\""))
    assert(strokes.last.contains("data-name=\"contour\""))
  }
