package intaglio.javafx

import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.paint.Color
import intaglio.*

class CasedSegmentsSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  test("twenty cased segments match one continuous line, including translucent casing at joints") {
    FxToolkit.fx {
      val points = Vector.tabulate(21)(i => Point.npcUnsafe(0.1 + i * 0.04, 0.5))
      for opacity <- Vector(1.0, 0.5) do
        val gp = GraphicParams
          .unsafe(lineWidth = 2, lineCap = LineCap.Round)
          .withCasing(
            StrokeCasing.unsafe(Rgba.White, CasingWidth.relativeUnsafe(4), alpha = opacity)
          )
        def draw(grob: Grob) =
          val canvas = new Canvas(300, 40)
          val context = new JavaFxCanvasContext(canvas.getGraphicsContext2D)
          JavaFxRenderer.render(Scene(Vector(grob)), context, JavaFxOptions.unsafe(300, 40)).orThrow
          val parameters = new SnapshotParameters()
          parameters.setFill(Color.TRANSPARENT)
          canvas.snapshot(parameters, null).getPixelReader
        val actual = draw(Grob.segments(points.zip(points.tail), gp = gp).orThrow)
        val expected = draw(Grob.lines(points, gp = gp).orThrow)
        for y <- 0 until 40; x <- 0 until 300 do
          assertEquals(actual.getArgb(x, y), expected.getArgb(x, y), clues(opacity, x, y))
    }
  }
