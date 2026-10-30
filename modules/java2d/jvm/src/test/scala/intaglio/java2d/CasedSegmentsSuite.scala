package intaglio.java2d

import java.awt.image.BufferedImage
import intaglio.*

class CasedSegmentsSuite extends munit.FunSuite:
  test("twenty cased segments match one continuous line, including translucent casing at joints") {
    val points = Vector.tabulate(21)(i => Point.npcUnsafe(0.1 + i * 0.04, 0.5))
    for opacity <- Vector(1.0, 0.5) do
      val gp = GraphicParams
        .unsafe(lineWidth = 2, lineCap = LineCap.Round)
        .withCasing(StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(4), alpha = opacity))
      def draw(grob: Grob): BufferedImage =
        val image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try
          Java2DRenderer
            .render(Scene(Vector(grob)), graphics, Java2DOptions.unsafe(300, 40))
            .orThrow
        finally graphics.dispose()
        image
      val actual = draw(Grob.segments(points.zip(points.tail), gp = gp).orThrow)
      val expected = draw(Grob.lines(points, gp = gp).orThrow)
      for y <- 0 until 40; x <- 0 until 300 do
        assertEquals(actual.getRGB(x, y), expected.getRGB(x, y), clues(opacity, x, y))
  }
