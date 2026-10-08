package intaglio.java2d

import intaglio.*
import java.awt.image.BufferedImage

/** [[intaglio.TextExtent]] against what the Java2D renderer draws for rotated text. A zero-padding
  * text plate fills the layout box the renderer anchors on, so its drawn extent at any angle is the
  * rotated extent of [[Java2DTextMetrics]]' width and height; the glyphs stay inside it. A pixel
  * counts when it is covered at all (alpha above zero on a transparent canvas), so the sharp corner
  * of a 45 degree box still registers. Drawn at 72 pixels per inch, one point per device pixel.
  */
class Java2DRotatedTextSuite extends munit.FunSuite:
  private val size = 480
  private val anchorX = 240.0
  private val anchorY = 240.0
  private val metrics = Java2DTextMetrics()
  private val style = TextStyle(None, 24.0)

  private final case class Ink(left: Int, top: Int, right: Int, bottom: Int)

  private def draw(label: String, anchor: Anchor, degrees: Double, plateOnly: Boolean): Ink =
    val gp = GraphicParams.unsafe(
      stroke = None,
      fill = Some(if plateOnly then Rgba.unsafe(0, 0, 0, 0) else Rgba.Black),
      fontSize = Length.pointsUnsafe(style.fontSizePt)
    )
    val styled =
      if plateOnly then gp.withTextPlate(TextPlate(Rgba.Black, StrokeWidth.devicePixelsUnsafe(0.0)))
      else gp
    val text = Grob.text(label, Point.npcUnsafe(0.5, 0.5), anchor, degrees, styled).orThrow
    val program = Java2DRenderer
      .compile(Scene(Vector(text)), Java2DOptions.unsafe(size, size, pixelsPerInch = 72.0))
      .fold(error => fail(error.message), identity)
    val image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try Java2DRenderer.draw(program, graphics)
    finally graphics.dispose()
    val inked = for
      y <- 0 until size
      x <- 0 until size
      if (image.getRGB(x, y) >>> 24) > 0
    yield (x, y)
    assert(inked.nonEmpty, "nothing was drawn")
    Ink(inked.map(_._1).min, inked.map(_._2).min, inked.map(_._1).max + 1, inked.map(_._2).max + 1)

  test("the drawn layout box at 0, 90, -90, 45, 180 and 30 degrees is TextExtent, within 1 px") {
    var worst = 0.0
    for
      label <- Vector("Header", "Mean response (ms)")
      degrees <- Vector(0.0, 90.0, -90.0, 45.0, 180.0, 30.0)
      anchor <- Vector(Anchor.BottomLeft, Anchor.Center, Anchor(HJust.Right, VJust.Top))
    do
      val extent =
        TextExtent
          .measure(metrics, label, style, anchor, degrees)
          .fold(e => fail(e.message), identity)
      val predicted = Vector(
        anchorX + extent.left,
        anchorY + extent.top,
        anchorX + extent.right,
        anchorY + extent.bottom
      )
      val plate = draw(label, anchor, degrees, plateOnly = true)
      val drawn = Vector(plate.left, plate.top, plate.right, plate.bottom).map(_.toDouble)
      val error = predicted.zip(drawn).map((p, d) => math.abs(p - d)).max
      assert(error <= 1.0, clue((label, degrees, anchor, predicted, drawn)))
      worst = math.max(worst, error)
      val glyphs = draw(label, anchor, degrees, plateOnly = false)
      assert(glyphs.left >= predicted(0) - 1.0 && glyphs.right <= predicted(2) + 1.0, clue(glyphs))
      assert(glyphs.top >= predicted(1) - 1.0 && glyphs.bottom <= predicted(3) + 1.0, clue(glyphs))
    println(f"Java2D rotated layout box vs TextExtent: max edge error $worst%.3f px")
  }

  test("goldens: a 90 degree header grows upward from a bottom-left anchor by its advance") {
    // The shape a rotated column header takes: anchored bottom-left in a y-up scene, the run
    // reads upward and its height on the page is its advance width.
    val label = "Mean response (ms)"
    val width = metrics.widthPt(label, style)
    val height = metrics.heightPt(style)
    val extent = TextExtent.measure(metrics, label, style, Anchor.BottomLeft, 90.0).orThrow
    assertEqualsDouble(extent.height, width, 1e-9)
    assertEqualsDouble(extent.width, height, 1e-9)
    assertEqualsDouble(extent.bottom, 0.0, 1e-9)
    assertEqualsDouble(extent.right, 0.0, 1e-9)
    // 45 degrees, centred: (w + h) / sqrt 2 square. StrictMath keeps the golden
    // architecture-independent; the extent itself may differ in the last bit.
    val diagonal = TextExtent.measure(metrics, label, style, Anchor.Center, 45.0).orThrow
    val expected = (width + height) * StrictMath.sin(StrictMath.PI / 4.0)
    assertEqualsDouble(diagonal.width, expected, 1e-9)
    assertEqualsDouble(diagonal.height, expected, 1e-9)
  }
