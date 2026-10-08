package intaglio.javafx

import _root_.javafx.application.Platform
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.image.Image
import _root_.javafx.scene.paint.Color
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*
import intaglio.*

/** JavaFX-backed text metrics against the pixels the JavaFX renderer draws, under headless Monocle.
  *
  * What "within 1 px" means here. Every comparison is between a measured quantity and the edges of
  * the drawn ink, found by scanning a white-background canvas snapshot for pixels at least 50%
  * covered (any channel below 128). The TextMetrics contract is not the ink, so each quantity is
  * compared with the ink edges that show it:
  *
  *   - advance width: the same run drawn left- and right-justified at one anchor moves by exactly
  *     its advance, so the shift of each of the ink's vertical edges equals `widthPt`;
  *   - line box height: drawn top- and bottom-justified, each horizontal ink edge moves by
  *     `heightPt`;
  *   - ascent and ink: top-left-justified ink lies at the anchor plus `ascentPt` plus `inkBounds`;
  *   - rotation: a zero-padding text plate fills the layout box, so its drawn extent at any angle
  *     is [[intaglio.TextExtent]] of `widthPt` by `heightPt`.
  *
  * Every run is printed with its signed errors, so the margins are visible in the test log.
  *
  * Canvases are drawn at 72 pixels per inch so one point is one device pixel, except where a test
  * says otherwise.
  */
class JavaFxTextMetricsSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def fx[A](body: => A): A = FxToolkit.fx(body)

  private val metrics = JavaFxTextMetrics()
  private val canvasWidth = 1000
  private val canvasHeight = 480
  private val anchorX = 500.0
  private val anchorY = 240.0
  private val anchorPoint = Point.npcUnsafe(0.5, 0.5)
  private val transparent = Rgba.unsafe(0, 0, 0, 0)

  /** A pixel is ink when some channel is below this: black at 50% coverage or more on white. */
  private val InkThreshold = 128

  private val labels = Vector("Header", "Mean response (ms)", "Quantile 0.95", "WAVE")
  private val styles = Vector(
    TextStyle(None, 12.0),
    TextStyle(None, 24.0),
    TextStyle(None, 40.0),
    TextStyle(None, 24.0, Some(FontWeight.Bold)),
    TextStyle(Some("SansSerif"), 20.0, Some(FontWeight.Bold)),
    TextStyle(Some("Serif"), 24.0),
    TextStyle(Some("Monospaced"), 18.0)
  )

  private final case class Ink(left: Int, top: Int, right: Int, bottom: Int)

  /** Signed errors in device pixels: drawn shift minus `widthPt` (both edges), drawn shift minus
    * `heightPt` (both edges), and drawn ink edge minus predicted (left, top, right, bottom).
    */
  private final case class Row(
      label: String,
      style: TextStyle,
      advance: Vector[Double],
      line: Vector[Double],
      ink: Vector[Double],
      synthetic: Boolean
  ):
    /** How far drawn ink reaches outside the predicted ink box, in pixels. */
    def escape: Double = Vector(-ink(0), -ink(1), ink(2), ink(3), 0.0).max

    def describe: String =
      val face = style.fontFamily.getOrElse("default")
      val weight = style.fontWeight.fold(400)(value => value)
      def show(values: Vector[Double]) = values.map(value => f"$value%+.2f").mkString(" ")
      f"$label%-20s $face%-10s ${style.fontSizePt}%4.0f/$weight%d advance ${show(advance)}" +
        s" line ${show(line)} ink ${show(ink)}" + (if synthetic then " (synthesized bold)" else "")

  private def textGrob(
      label: String,
      style: TextStyle,
      anchor: Anchor,
      degrees: Double = 0.0,
      plateOnly: Boolean = false
  ): Grob =
    val gp = GraphicParams.unsafe(
      stroke = None,
      fill = Some(if plateOnly then transparent else Rgba.Black),
      fontFamily = style.fontFamily,
      fontSize = Length.pointsUnsafe(style.fontSizePt),
      fontWeight = style.fontWeight
    )
    val styled =
      if plateOnly then gp.withTextPlate(TextPlate(Rgba.Black, StrokeWidth.devicePixelsUnsafe(0.0)))
      else gp
    Grob.text(label, anchorPoint, anchor, degrees, styled).orThrow

  private def snapshot(grob: Grob, pixelsPerInch: Double = 72.0): Image =
    fx {
      val canvas = new Canvas(canvasWidth.toDouble, canvasHeight.toDouble)
      val context = new JavaFxCanvasContext(canvas.getGraphicsContext2D)
      JavaFxRenderer
        .render(
          Scene(Vector(grob)),
          context,
          JavaFxOptions.unsafe(canvasWidth, canvasHeight, pixelsPerInch = pixelsPerInch)
        )
        .fold(error => fail(error.message), identity)
      val parameters = new SnapshotParameters()
      parameters.setFill(Color.WHITE)
      canvas.snapshot(parameters, null)
    }

  private def ink(image: Image, threshold: Int = InkThreshold): Ink =
    val reader = image.getPixelReader
    var left = Int.MaxValue
    var top = Int.MaxValue
    var right = Int.MinValue
    var bottom = Int.MinValue
    for
      y <- 0 until image.getHeight.toInt
      x <- 0 until image.getWidth.toInt
    do
      val argb = reader.getArgb(x, y)
      val darkest = math.min((argb >> 16) & 0xff, math.min((argb >> 8) & 0xff, argb & 0xff))
      if darkest < threshold then
        left = math.min(left, x)
        top = math.min(top, y)
        right = math.max(right, x + 1)
        bottom = math.max(bottom, y + 1)
    assert(left != Int.MaxValue, "nothing was drawn")
    Ink(left, top, right, bottom)

  private def draw(
      label: String,
      style: TextStyle,
      anchor: Anchor,
      degrees: Double = 0.0,
      plateOnly: Boolean = false,
      threshold: Int = InkThreshold
  ): Ink =
    ink(snapshot(textGrob(label, style, anchor, degrees, plateOnly)), threshold)

  test("snapshots are one image pixel per canvas pixel") {
    val image = snapshot(textGrob("Header", styles.head, Anchor.Center))
    assertEquals((image.getWidth, image.getHeight), (canvasWidth.toDouble, canvasHeight.toDouble))
  }

  test("advance width and line box agree with the drawn JavaFX ink within 1 px") {
    val rows =
      for
        style <- styles
        label <- labels
      yield
        val width = metrics.widthPt(label, style)
        val height = metrics.heightPt(style)
        val topLeft = draw(label, style, Anchor(HJust.Left, VJust.Top))
        val topRight = draw(label, style, Anchor(HJust.Right, VJust.Top))
        val bottomLeft = draw(label, style, Anchor(HJust.Left, VJust.Bottom))
        // The ink's two horizontal edges each move by the advance, and its two vertical edges by
        // the line box.
        val advance = Vector(topLeft.left - topRight.left, topLeft.right - topRight.right)
        val line = Vector(topLeft.top - bottomLeft.top, topLeft.bottom - bottomLeft.bottom)
        // Top-left justified: the baseline is ascent below the anchor; the ink is inkBounds there.
        val glyphs = metrics.inkBounds(label, style)
        val baseline = anchorY + metrics.ascentPt(style)
        val predicted = Vector(
          anchorX + glyphs.left,
          baseline + glyphs.top,
          anchorX + glyphs.left + glyphs.width,
          baseline + glyphs.top + glyphs.height
        )
        val drawn =
          Vector(topLeft.left, topLeft.top, topLeft.right, topLeft.bottom).map(_.toDouble)
        // A bold that measures exactly as the regular face is synthesized by JavaFX when drawn.
        val synthetic = style.fontWeight.exists(_ != FontWeight.Regular) &&
          width == metrics.widthPt(label, style.copy(fontWeight = None))
        Row(
          label,
          style,
          advance.map(_ - width),
          line.map(_ - height),
          drawn.zip(predicted).map(_ - _),
          synthetic
        )
    rows.foreach(row => println(row.describe))
    val (emboldened, real) = rows.partition(_.synthetic)
    val advanceError = rows.flatMap(_.advance).map(math.abs).max
    val lineError = rows.flatMap(_.line).map(math.abs).max
    val realEscape = real.map(_.escape).max
    val realEdge = real.flatMap(_.ink).map(math.abs).max
    println(
      f"JavaFxTextMetrics vs drawn ink: max advance error $advanceError%.3f px, " +
        f"max line-box error $lineError%.3f px; real faces: ink outside inkBounds by at most " +
        f"$realEscape%.3f px, max |ink edge - inkBounds edge| $realEdge%.3f px; synthesized " +
        f"bold: ink outside inkBounds by at most ${emboldened.map(_.escape).maxOption.getOrElse(0.0)}%.3f px"
    )
    // The TextMetrics contract, for every face including a synthesized bold.
    assert(advanceError <= 1.0, advanceError)
    assert(lineError <= 1.0, lineError)
    // The ink: no drawn pixel lies more than 1 px outside inkBounds. Inside, a hairline serif can
    // fall below the 50% coverage threshold, so an edge can read up to 2 px inside the outline.
    assert(realEscape <= 1.0, realEscape)
    assert(realEdge <= 2.0, realEdge)
    // A synthesized bold is emboldened after layout, beyond the outline JavaFX reports.
    emboldened.foreach(row => assert(row.escape <= 0.1 * row.style.fontSizePt, row.describe))
  }

  test("ascent plus descent is the line box, and the ink lies within it for plain Latin text") {
    styles.foreach { style =>
      assertEqualsDouble(
        metrics.ascentPt(style) + metrics.descentPt(style),
        metrics.heightPt(style),
        1e-9
      )
      labels.foreach { label =>
        val glyphs = metrics.inkBounds(label, style)
        assert(glyphs.top >= -metrics.ascentPt(style) - 1e-9, clue((label, style, glyphs)))
        assert(glyphs.top + glyphs.height <= metrics.descentPt(style) + 1e-9, clue(glyphs))
      }
    }
    assertEquals(metrics.inkBounds("", styles.head), JavaFxTextBox(0.0, 0.0, 0.0, 0.0))
    assertEquals(metrics.inkBounds("   ", styles.head), JavaFxTextBox(0.0, 0.0, 0.0, 0.0))
    assertEqualsDouble(metrics.widthPt("", styles.head), 0.0, 1e-9)
  }

  test("family, weight and size are honoured; a missing family measures as the fallback") {
    val regular = TextStyle(None, 24.0)
    // JavaFX's own "System" family can resolve bold to a face with the regular advances (it does
    // on macOS), so weight is checked on the first family whose bold face is wider.
    val weighted = Vector("SansSerif", "Serif", "Arial", "DejaVu Sans", "Liberation Sans").find {
      family =>
        val plain = TextStyle(Some(family), 24.0)
        val bold = plain.copy(fontWeight = Some(FontWeight.Bold))
        metrics.widthPt("Quantile", bold) > metrics.widthPt("Quantile", plain)
    }
    assert(weighted.nonEmpty, "no family measured wider in bold")
    val mono = TextStyle(Some("Monospaced"), 24.0)
    assertEqualsDouble(metrics.widthPt("iiii", mono), metrics.widthPt("WWWW", mono), 1e-6)
    assert(metrics.widthPt("iiii", regular) < metrics.widthPt("WWWW", regular))
    val missing = TextStyle(Some("Intaglio No Such Face"), 24.0)
    assertEquals(metrics.resolvedFamily(missing.fontFamily), metrics.resolvedFamily(None))
    assertEqualsDouble(
      metrics.widthPt("fallback", missing),
      metrics.widthPt("fallback", regular),
      0.0
    )
    assertEqualsDouble(metrics.heightPt(missing), metrics.heightPt(regular), 0.0)
    // The size-only overloads are the default family.
    assertEqualsDouble(metrics.widthPt("size", 24.0), metrics.widthPt("size", regular), 0.0)
    assertEqualsDouble(metrics.heightPt(24.0), metrics.heightPt(regular), 0.0)
    // Size scales the measurement in proportion, to within a pixel at the larger size.
    val label = "Mean response (ms)"
    val small = metrics.widthPt(label, TextStyle(None, 12.0))
    val large = metrics.widthPt(label, TextStyle(None, 36.0))
    assert(math.abs(large - 3.0 * small) <= 1.0, clue((small, large)))
  }

  test("a layout measured in points matches a canvas drawn at another density within 1 px") {
    // Layout measures at 12 pt; at 144 pixels per inch the renderer draws the run at 24 px.
    val label = "Mean response (ms)"
    val style = TextStyle(None, 12.0)
    def drawnAt(anchor: Anchor): Ink =
      ink(snapshot(textGrob(label, style, anchor), pixelsPerInch = 144.0))
    val left = drawnAt(Anchor(HJust.Left, VJust.Top))
    val right = drawnAt(Anchor(HJust.Right, VJust.Top))
    val bottom = drawnAt(Anchor(HJust.Left, VJust.Bottom))
    assert(math.abs((left.left - right.left) - 2.0 * metrics.widthPt(label, style)) <= 1.0)
    assert(math.abs((left.right - right.right) - 2.0 * metrics.widthPt(label, style)) <= 1.0)
    assert(math.abs((left.top - bottom.top) - 2.0 * metrics.heightPt(style)) <= 1.0)
  }

  private val angles = Vector(0.0, 90.0, -90.0, 45.0, 180.0, 30.0)
  private val anchors = Vector(Anchor.BottomLeft, Anchor.Center, Anchor(HJust.Right, VJust.Top))

  test("the drawn layout box at any angle is TextExtent of widthPt by heightPt, within 1 px") {
    // Any coverage counts here (every channel below 255), so the sharp corner of a 45 degree box
    // registers: at 50% coverage a corner pixel reads up to a pixel inside the true extent.
    val anyCoverage = 255
    val style = TextStyle(None, 24.0)
    var worst = 0.0
    for
      label <- Vector("Header", "Mean response (ms)")
      degrees <- angles
      anchor <- anchors
    do
      val extent =
        TextExtent
          .measure(metrics, label, style, anchor, degrees)
          .fold(e => fail(e.message), identity)
      val plate = draw(label, style, anchor, degrees, plateOnly = true, threshold = anyCoverage)
      val predicted = Vector(
        anchorX + extent.left,
        anchorY + extent.top,
        anchorX + extent.right,
        anchorY + extent.bottom
      )
      val drawn = Vector(plate.left, plate.top, plate.right, plate.bottom).map(_.toDouble)
      val error = predicted.zip(drawn).map((p, d) => math.abs(p - d)).max
      assert(error <= 1.0, clue((label, degrees, anchor, predicted, drawn)))
      worst = math.max(worst, error)
      // The glyphs themselves stay inside the reserved extent.
      val glyphs = draw(label, style, anchor, degrees, threshold = anyCoverage)
      assert(glyphs.left >= predicted(0) - 1.0 && glyphs.right <= predicted(2) + 1.0, clue(glyphs))
      assert(glyphs.top >= predicted(1) - 1.0 && glyphs.bottom <= predicted(3) + 1.0, clue(glyphs))
    println(f"JavaFX rotated layout box vs TextExtent: max edge error $worst%.3f px")
  }

  test("measurement needs neither the FX thread nor a started toolkit (fresh JVM)") {
    val javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val process = new ProcessBuilder(
      javaBin,
      "-cp",
      System.getProperty("java.class.path"),
      "-Djava.awt.headless=true",
      "intaglio.javafx.JavaFxTextMetricsProbe"
    ).redirectErrorStream(true).start()
    val finished = process.waitFor(60, TimeUnit.SECONDS)
    if !finished then
      process.destroyForcibly()
      fail("measurement without a toolkit hung for 60 s")
    val output = new String(process.getInputStream.readAllBytes(), "UTF-8")
    assertEquals(process.exitValue(), 0, clue(output))
    val lines = output.linesIterator.toVector
    assert(lines.contains("toolkit=not-started"), clue(output))
    val expected = JavaFxTextMetricsProbe.measurements(metrics)
    assert(lines.contains(expected), clue((expected, output)))
  }

  test("measurement off the FX thread, concurrently, equals measurement on it") {
    val style = TextStyle(None, 18.0, Some(FontWeight.Bold))
    val expected = fx(labels.map(label => metrics.widthPt(label, style)))
    val pool = Executors.newFixedThreadPool(6)
    try
      val tasks = (0 until 24).map { _ =>
        new Callable[Vector[Double]]:
          def call(): Vector[Double] =
            (0 until 50).map(_ => labels.map(label => metrics.widthPt(label, style))).last
      }
      val results = pool.invokeAll(tasks.asJava, 60, TimeUnit.SECONDS).asScala.map(_.get)
      results.foreach(result => assertEquals(result, expected))
    finally pool.shutdownNow()
  }

  test("AWT and JavaFX advances for the same family and size are measured independently") {
    // Evidence for the documented hazard, not a fixed expectation: the two text stacks need not
    // agree, so a JavaFX host should not lay out with AWT metrics.
    // The render context Java2DTextMetrics measures with: an image graphics' default context.
    val image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    val frc =
      try graphics.getFontRenderContext
      finally graphics.dispose()
    val awtFamilies =
      java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment.getAvailableFontFamilyNames.toSet
    val fxFamilies = _root_.javafx.scene.text.Font.getFamilies.asScala.toSet
    // Each pair names one family in both stacks: the logical SansSerif family each maps to its own
    // physical face, and a physical family both see when it is installed.
    val pairs = Vector("SansSerif" -> "SansSerif") ++
      Vector("Arial", "DejaVu Sans", "Liberation Sans")
        .find(name => awtFamilies(name) && fxFamilies(name))
        .map(name => name -> name)
    pairs.foreach { (awtFamily, fxFamily) =>
      val differences = for
        label <- labels
        size <- Vector(12.0, 24.0)
      yield
        val awt = new java.awt.Font(awtFamily, java.awt.Font.PLAIN, 1)
          .deriveFont(size.toFloat)
          .getStringBounds(label, frc)
          .getWidth
        val javafx = metrics.widthPt(label, TextStyle(Some(fxFamily), size))
        assert(awt > 0.0 && javafx > 0.0)
        (label, size, awt, javafx)
      val (label, size, awt, javafx) = differences.maxBy(row => math.abs(row._4 - row._3))
      println(
        f"AWT vs JavaFX, $awtFamily: max |advance difference| ${math.abs(javafx - awt)}%.2f pt " +
          f"($label at $size%.0f pt: AWT $awt%.2f, JavaFX $javafx%.2f)"
      )
    }
  }

/** Run in a fresh JVM by the suite: measures without starting the JavaFX toolkit, off the FX
  * thread, then shows the toolkit was never started (`runLater` refuses).
  */
object JavaFxTextMetricsProbe:
  def measurements(metrics: JavaFxTextMetrics): String =
    val style = TextStyle(None, 24.0, Some(FontWeight.Bold))
    "widths=" + Vector("Header", "Mean response (ms)")
      .map(label => metrics.widthPt(label, style))
      .mkString(",") + s";height=${metrics.heightPt(style)}"

  def main(args: Array[String]): Unit =
    println(measurements(JavaFxTextMetrics()))
    val started =
      try
        Platform.runLater(() => ())
        true
      catch case _: IllegalStateException => false
    println(if started then "toolkit=started" else "toolkit=not-started")
