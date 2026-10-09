package intaglio.java2d

import intaglio.*
import java.awt.image.BufferedImage

/** [[intaglio.TextExtent]] against what the Java2D renderer draws for rotated text. A zero-padding
  * text plate fills the layout box the renderer anchors on when no glyph overhangs it, so its drawn
  * extent at any angle is the rotated extent of [[Java2DTextMetrics]]' width and height; the glyphs
  * stay inside it. Drawn at 72 pixels per inch, one point per device pixel, on a transparent canvas
  * whose alpha is the coverage.
  *
  * Both are read at sub-pixel precision. The plate is a filled rectangle, recovered from its
  * coverage moments: its covariance, less the pixel's own 1/12 variance, has eigenvalues `w^2/12`
  * and `h^2/12` along its sides, so no corner pixel is read. A glyph edge in a row is the first
  * covered pixel `x` at coverage `a`, placed at `x + 1 - a` (the last at `x + a`).
  */
class Java2DRotatedTextSuite extends munit.FunSuite:
  // The anchor sits in the middle with room for the widest label at any angle: Linux faces run wider
  // than macOS ones (AWT measured "Mean response (ms)" near 258 px at 24 pt on CI).
  private val size = 1000
  private val anchorX = 500.0
  private val anchorY = 500.0
  private val metrics = Java2DTextMetrics()
  private val style = TextStyle(None, 24.0)
  private val noise = 2.0 / 255.0

  private final class Coverage(image: BufferedImage):
    def at(x: Int, y: Int): Double = (image.getRGB(x, y) >>> 24) / 255.0
    private val cells =
      for y <- 0 until size; x <- 0 until size if at(x, y) > 0.0 yield (x, y, at(x, y))
    assert(cells.nonEmpty, "nothing was drawn")
    private val mass = cells.map(_._3).sum
    private val cx = cells.map((x, _, a) => a * (x + 0.5)).sum / mass
    private val cy = cells.map((_, y, a) => a * (y + 0.5)).sum / mass

    def rectangleExtent: Vector[Double] =
      val vxx = cells.map((x, _, a) => a * math.pow(x + 0.5 - cx, 2)).sum / mass - 1.0 / 12.0
      val vyy = cells.map((_, y, a) => a * math.pow(y + 0.5 - cy, 2)).sum / mass - 1.0 / 12.0
      val vxy = cells.map((x, y, a) => a * (x + 0.5 - cx) * (y + 0.5 - cy)).sum / mass
      val mean = (vxx + vyy) / 2.0
      val spread = math.sqrt(math.pow((vxx - vyy) / 2.0, 2) + vxy * vxy)
      val long = math.sqrt(12.0 * (mean + spread))
      val short = math.sqrt(12.0 * math.max(mean - spread, 0.0))
      val angle = 0.5 * math.atan2(2.0 * vxy, vxx - vyy)
      val c = math.abs(math.cos(angle))
      val s = math.abs(math.sin(angle))
      val w = long * c + short * s
      val h = long * s + short * c
      Vector(cx - w / 2.0, cy - h / 2.0, cx + w / 2.0, cy + h / 2.0)

    def edges: Vector[Double] =
      val covered = cells.filter(_._3 > noise)
      val rows = covered.groupBy(_._2).values
      val columns = covered.groupBy(_._1).values
      Vector(
        rows.map(row => row.minBy(_._1)).map((x, _, a) => x + 1.0 - a).min,
        columns.map(column => column.minBy(_._2)).map((_, y, a) => y + 1.0 - a).min,
        rows.map(row => row.maxBy(_._1)).map((x, _, a) => x + a).max,
        columns.map(column => column.maxBy(_._2)).map((_, y, a) => y + a).max
      )

  private def draw(label: String, anchor: Anchor, degrees: Double, plateOnly: Boolean): Coverage =
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
    new Coverage(image)

  test("the drawn layout box at 0, 90, -90, 45, 180 and 30 degrees is TextExtent, within 1 px") {
    var worstPlate = 0.0
    var worstGlyph = Double.NegativeInfinity
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
      // A box that leaves the image is clipped when drawn and would read as a measurement error.
      assert(
        predicted(0) >= 1 && predicted(1) >= 1 && predicted(2) <= size - 1 && predicted(
          3
        ) <= size - 1,
        clue(("predicted box leaves the image; enlarge it", label, degrees, anchor, predicted))
      )
      val plate = draw(label, anchor, degrees, plateOnly = true).rectangleExtent
      val error = predicted.zip(plate).map((p, d) => math.abs(p - d)).max
      assert(error <= 1.0, clue((label, degrees, anchor, predicted, plate)))
      worstPlate = math.max(worstPlate, error)
      val glyphs = draw(label, anchor, degrees, plateOnly = false).edges
      val outside = Vector(
        predicted(0) - glyphs(0),
        predicted(1) - glyphs(1),
        glyphs(2) - predicted(2),
        glyphs(3) - predicted(3)
      ).max
      assert(outside <= 1.0, clue((label, degrees, anchor, predicted, glyphs)))
      worstGlyph = math.max(worstGlyph, outside)
    println(
      f"Java2D rotated layout box vs TextExtent: max edge error $worstPlate%.3f px; glyphs reach " +
        f"at most $worstGlyph%+.3f px outside it"
    )
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
