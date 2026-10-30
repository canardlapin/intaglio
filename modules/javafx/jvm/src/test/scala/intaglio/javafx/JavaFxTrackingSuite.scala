package intaglio.javafx

import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.image.Image
import _root_.javafx.scene.paint.Color
import _root_.javafx.scene.text.{Font, Text}
import intaglio.{FontWeight, HJust, TextStyle, VJust}

/** Pixel and native-node witnesses for the JavaFX tracking path. */
class JavaFxTrackingSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  test("a trailing invisible format character adds no tracking or plate extent") {
    fx {
      val plain = JavaFxTrackedText.layout("AB", None, 24, None, 5)
      val formatted = JavaFxTrackedText.layout("AB\u200b", None, 24, None, 5)
      assertEqualsDouble(formatted.advance, plain.advance, 1e-9)
      assertEquals(formatted.bounds, plain.bounds)
      assertEquals(formatted.ink, plain.ink)
      for horizontal <- HJust.values; vertical <- VJust.values do
        assertEquals(
          formatted.anchoredBounds(horizontal, vertical),
          plain.anchoredBounds(horizontal, vertical)
        )
    }
  }

  test("tracked whitespace has logical advance but no ink") {
    fx {
      val metrics = JavaFxTextMetrics()
      val style = TextStyle(None, 12, letterSpacingPt = 5)
      assert(metrics.widthPt("   ", style) > 0)
      assertEquals(metrics.inkBounds("   ", style), JavaFxTextBox(0, 0, 0, 0))
    }
  }

  test("vertical metrics do not reject a valid single-unit label with strong negative tracking") {
    fx {
      val metrics = JavaFxTextMetrics()
      val style = TextStyle(None, 12, letterSpacingPt = -50)
      assert(metrics.widthPt("A", style) > 0)
      assert(metrics.heightPt(style) > 0)
      assertEqualsDouble(
        metrics.ascentPt(style) + metrics.descentPt(style),
        metrics.heightPt(style),
        1e-9
      )
    }
  }

  private def fx[A](body: => A): A = FxToolkit.fx(body)

  private def nativeWidth(text: String, font: Font): Double =
    val node = new Text(text)
    node.setFont(font)
    node.getLayoutBounds.getWidth

  private def inkCentroid(image: Image): Double =
    val reader = image.getPixelReader
    var mass = 0.0
    var weightedX = 0.0
    for y <- 0 until image.getHeight.toInt; x <- 0 until image.getWidth.toInt do
      val argb = reader.getArgb(x, y)
      val alpha = (argb >>> 24) & 0xff
      val darkness =
        255 - math.min((argb >>> 16) & 0xff, math.min((argb >>> 8) & 0xff, argb & 0xff))
      val coverage = alpha * darkness / (255.0 * 255.0)
      mass += coverage
      weightedX += coverage * (x + 0.5)
    assert(mass > 0.0, "tracked text snapshot contains visible ink")
    weightedX / mass

  /** Called by the FX-thread test body; do not nest FxToolkit.fx here. */
  private def draw(plan: JavaFxTrackedText, justification: HJust, scale: Int): Image =
    val canvas = new Canvas(420.0 * scale, 120.0 * scale)
    val context = canvas.getGraphicsContext2D
    context.setFill(Color.WHITE)
    context.fillRect(0, 0, canvas.getWidth, canvas.getHeight)
    context.scale(scale, scale)
    context.setFill(Color.BLACK)
    plan.draw(context, 210.0, 60.0, justification, VJust.Center)
    val parameters = new SnapshotParameters()
    parameters.setFill(Color.WHITE)
    canvas.snapshot(parameters, null)

  test("Arabic remains one native shaping node and matches whole-Text advance") {
    fx {
      val label = "السلام"
      val plan = JavaFxTrackedText.layout(label, None, 32, None, 8)
      val whole = JavaFxTextMetrics.font(None, 32, None)
      assertEquals(plan.flow.getChildren.size, 1)
      val node = plan.flow.getChildren.get(0).asInstanceOf[Text]
      assertEquals(node.getText, label)
      assertEqualsDouble(plan.advance, nativeWidth(label, whole), 1e-7)
      assert(
        node.getBoundsInParent.getWidth > 0.0,
        "Arabic shaping must produce visible native bounds"
      )
    }
  }

  test("combining marks stay in one TextFlow unit while tracking translates the next unit") {
    fx {
      val plan = JavaFxTrackedText.layout("x\u0301\u0323x", None, 30, None, 7)
      assertEquals(plan.flow.getChildren.size, 2)
      val marked = plan.flow.getChildren.get(0).asInstanceOf[Text]
      val following = plan.flow.getChildren.get(1).asInstanceOf[Text]
      assertEquals(marked.getText, "x\u0301\u0323")
      assertEquals(following.getText, "x")
      assertEqualsDouble(marked.getTranslateX, 0.0, 1e-9)
      assertEqualsDouble(following.getTranslateX, 7.0, 1e-9)
      assert(marked.getBoundsInParent.getWidth > 0.0)
    }
  }

  test("rendered ink moves by anchors at DPR 1 and 2, and snapshots preserve visible Unicode") {
    fx {
      val plan = JavaFxTrackedText.layout("A😀x\u0301", None, 30, None, 5, Color.RED)
      val source = (0 until plan.flow.getChildren.size)
        .map(index => plan.flow.getChildren.get(index).asInstanceOf[Text].getText)
        .mkString
      assertEquals(source, "A😀x\u0301")
      Vector(1, 2).foreach { scale =>
        val left = inkCentroid(draw(plan, HJust.Left, scale))
        val center = inkCentroid(draw(plan, HJust.Center, scale))
        val right = inkCentroid(draw(plan, HJust.Right, scale))
        assertEqualsDouble(left - center, plan.advance * scale / 2.0, 2.0)
        assertEqualsDouble(left - right, plan.advance * scale, 2.0)
      }
    }
  }

  test("a loaded Plex face is retained by tracked Text nodes") {
    fx {
      val stream = getClass.getResourceAsStream("/fonts/ibm-plex/IBMPlexSans-Regular.ttf")
      assert(stream != null)
      val loaded = try Font.loadFont(stream, 26)
      finally stream.close()
      assert(loaded != null)
      val expected = JavaFxTextMetrics.font(Some(loaded.getFamily), 26, Some(FontWeight.Regular))
      val plan =
        JavaFxTrackedText.layout("Plex", Some(loaded.getFamily), 26, Some(FontWeight.Regular), 3)
      val faces =
        plan.flow.getChildren.toArray.toVector.map(_.asInstanceOf[Text].getFont.getName).distinct
      assertEquals(faces, Vector(expected.getName))
    }
  }
