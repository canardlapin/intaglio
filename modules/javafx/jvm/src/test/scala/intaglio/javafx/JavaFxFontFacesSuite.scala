package intaglio.javafx

import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.text.{Font, Text}
import intaglio.*

class JavaFxFontFacesSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def load(weight: String): Font =
    val stream = getClass.getResourceAsStream(s"/fonts/ibm-plex/IBMPlexSans-$weight.ttf")
    assert(stream != null)
    try
      val font = Font.loadFont(stream, 24)
      assert(font != null)
      font
    finally stream.close()

  test("registered Regular, Medium and SemiBold faces drive both Canvas drawing and measurement") {
    FxToolkit.fx {
      val regular = load("Regular")
      val metrics = JavaFxTextMetrics()
      // Warm the lookup before registering the intermediate weights.
      metrics.resolvedFontName(Some(regular.getFamily), Some(FontWeight.unsafe(500)))
      val faces = Vector(400 -> regular, 500 -> load("Medium"), 600 -> load("SemiBold"))
      val native = new Canvas(400, 80).getGraphicsContext2D
      val context = new JavaFxCanvasContext(native)
      val label = "Hamburgefonstiv Header"
      val advances = faces.map { case (weight, expected) =>
        val style = TextStyle(Some(regular.getFamily), 24, Some(FontWeight.unsafe(weight)))
        assertEquals(metrics.resolvedFontName(style.fontFamily, style.fontWeight), expected.getName)
        context.setFont(style.fontFamily, 24, style.fontWeight)
        assertEquals(native.getFont.getName, expected.getName)
        val text = new Text(label)
        text.setFont(expected)
        val actual = metrics.widthPt(label, style)
        assertEqualsDouble(actual, text.getLayoutBounds.getWidth, 1e-9)
        actual
      }
      assertEquals(advances.distinct.size, 3, clues(advances))
    }
  }

  test("unavailable families keep the JavaFX fallback and size") {
    val actual = JavaFxTextMetrics.font(Some("Intaglio Missing Family"), 19, Some(FontWeight.Bold))
    val expected =
      Font.font("Intaglio Missing Family", _root_.javafx.scene.text.FontWeight.BOLD, 19)
    assertEquals(actual.getName, expected.getName)
    assertEquals(actual.getSize, 19.0)
  }
