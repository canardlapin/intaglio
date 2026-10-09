package intaglio.javafx

import _root_.javafx.application.Platform
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.image.Image
import _root_.javafx.scene.paint.Color
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*
import intaglio.*

/** Coverage of black ink on a white snapshot, per pixel in [0, 1], with sub-pixel readouts.
  *
  *   - `centroid` is the coverage-weighted mean of pixel centres. Antialiased coverage is the shape
  *     filtered by the pixel, so the centroid moves exactly with the shape: the shift between two
  *     drawings of one run is read to a small fraction of a pixel, whatever the sub-pixel phase.
  *   - `edges` (left, top, right, bottom): in each row, the first covered pixel `x` with coverage
  *     `a` puts the edge at `x + 1 - a` (the last at `x + a`), taking the outermost over all rows;
  *     columns likewise for top and bottom.
  *   - `rectangleExtent` recovers a filled rectangle at any angle from its moments: its covariance
  *     (less the pixel's own 1/12 variance) has eigenvalues `w^2/12` and `h^2/12` along its sides,
  *     so its axis-aligned extent follows without reading any corner pixel.
  */
private[javafx] final class Coverage(image: Image):
  private val width = image.getWidth.toInt
  private val height = image.getHeight.toInt
  private val values: Array[Double] =
    val reader = image.getPixelReader
    Array.tabulate(width * height) { index =>
      val argb = reader.getArgb(index % width, index / width)
      val darkest = math.min((argb >> 16) & 0xff, math.min((argb >> 8) & 0xff, argb & 0xff))
      (255 - darkest) / 255.0
    }
  private val noise = 2.0 / 255.0

  def at(x: Int, y: Int): Double = values(y * width + x)

  private lazy val mass: Double =
    val total = values.sum
    assert(total > 0.0, "nothing was drawn")
    total

  lazy val centroid: (Double, Double) =
    var sx = 0.0
    var sy = 0.0
    for y <- 0 until height; x <- 0 until width do
      val a = at(x, y)
      if a > 0.0 then
        sx += a * (x + 0.5)
        sy += a * (y + 0.5)
    (sx / mass, sy / mass)

  lazy val edges: Vector[Double] =
    var left = Double.PositiveInfinity
    var right = Double.NegativeInfinity
    var top = Double.PositiveInfinity
    var bottom = Double.NegativeInfinity
    for y <- 0 until height do
      val covered = (0 until width).filter(x => at(x, y) > noise)
      if covered.nonEmpty then
        left = math.min(left, covered.head + 1.0 - at(covered.head, y))
        right = math.max(right, covered.last + at(covered.last, y))
    for x <- 0 until width do
      val covered = (0 until height).filter(y => at(x, y) > noise)
      if covered.nonEmpty then
        top = math.min(top, covered.head + 1.0 - at(x, covered.head))
        bottom = math.max(bottom, covered.last + at(x, covered.last))
    assert(left.isFinite, "nothing was drawn")
    Vector(left, top, right, bottom)

  lazy val rectangleExtent: Vector[Double] =
    val (cx, cy) = centroid
    var xx = 0.0
    var yy = 0.0
    var xy = 0.0
    for y <- 0 until height; x <- 0 until width do
      val a = at(x, y)
      if a > 0.0 then
        val dx = x + 0.5 - cx
        val dy = y + 0.5 - cy
        xx += a * dx * dx
        yy += a * dy * dy
        xy += a * dx * dy
    val vxx = xx / mass - 1.0 / 12.0
    val vyy = yy / mass - 1.0 / 12.0
    val vxy = xy / mass
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

/** JavaFX-backed text metrics against the pixels the JavaFX renderer draws, under headless Monocle.
  *
  * The TextMetrics contract is not the ink, so each quantity is compared with what shows it, read
  * at sub-pixel precision from antialiased coverage (see [[Coverage]]):
  *
  *   - advance width: the same run drawn left- and right-justified at one anchor moves by exactly
  *     its advance, so the shift of its ink centroid equals `widthPt`;
  *   - line box height: drawn top- and bottom-justified, the centroid moves by `heightPt`;
  *   - ascent and ink: top-left-justified ink lies at the anchor plus `ascentPt` plus `inkBounds`;
  *   - rotation: a zero-padding text plate fills the layout box when no glyph overhangs it, so its
  *     drawn extent at any angle is [[intaglio.TextExtent]] of `widthPt` by `heightPt`.
  *
  * Every run is printed with its signed errors, so the margins are visible in the test log.
  * Canvases are drawn at 72 pixels per inch so one point is one device pixel, except where a test
  * says otherwise.
  */
class JavaFxTextMetricsSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def fx[A](body: => A): A = FxToolkit.fx(body)

  private val metrics = JavaFxTextMetrics()
  // Square, with the anchor in the middle: a label turned 90 degrees needs as much room vertically as
  // horizontally, and Linux faces run wider than macOS ones (CI clipped a 245 px label at 480 high).
  private val canvasWidth = 1000
  private val canvasHeight = 1000
  private val anchorX = 500.0
  private val anchorY = 500.0
  private val anchorPoint = Point.npcUnsafe(0.5, 0.5)
  private val transparent = Rgba.unsafe(0, 0, 0, 0)

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

  /** Signed errors in device pixels: centroid shift minus `widthPt`, centroid shift minus
    * `heightPt`, and drawn ink edge minus predicted (left, top, right, bottom).
    */
  private final case class Row(
      label: String,
      style: TextStyle,
      advance: Double,
      line: Double,
      ink: Vector[Double],
      synthetic: Boolean
  ):
    /** How far drawn ink reaches outside the predicted ink box, in pixels. */
    def escape: Double = Vector(-ink(0), -ink(1), ink(2), ink(3), 0.0).max

    def describe: String =
      val face = style.fontFamily.getOrElse("default")
      val weight = style.fontWeight.fold(400)(value => value)
      val edges = ink.map(value => f"$value%+.2f").mkString(" ")
      f"$label%-20s $face%-10s ${style.fontSizePt}%4.0f/$weight%d advance $advance%+.3f" +
        f" line $line%+.3f ink $edges" + (if synthetic then " (synthesized bold)" else "")

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

  private def draw(
      label: String,
      style: TextStyle,
      anchor: Anchor,
      degrees: Double = 0.0,
      plateOnly: Boolean = false,
      pixelsPerInch: Double = 72.0
  ): Coverage =
    new Coverage(snapshot(textGrob(label, style, anchor, degrees, plateOnly), pixelsPerInch))

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
        val advance = topLeft.centroid._1 - topRight.centroid._1
        val line = topLeft.centroid._2 - bottomLeft.centroid._2
        // Top-left justified: the baseline is ascent below the anchor; the ink is inkBounds there.
        val glyphs = metrics.inkBounds(label, style)
        val baseline = anchorY + metrics.ascentPt(style)
        val predicted = Vector(
          anchorX + glyphs.left,
          baseline + glyphs.top,
          anchorX + glyphs.left + glyphs.width,
          baseline + glyphs.top + glyphs.height
        )
        // A bold that measures exactly as the regular face is synthesized by JavaFX when drawn.
        val synthetic = style.fontWeight.exists(_ != FontWeight.Regular) &&
          width == metrics.widthPt(label, style.copy(fontWeight = None))
        Row(
          label,
          style,
          advance - width,
          line - height,
          topLeft.edges.zip(predicted).map(_ - _),
          synthetic
        )
    rows.foreach(row => println(row.describe))
    val (emboldened, real) = rows.partition(_.synthetic)
    val advanceError = rows.map(row => math.abs(row.advance)).max
    val lineError = rows.map(row => math.abs(row.line)).max
    val realEscape = real.map(_.escape).max
    val realEdge = real.flatMap(_.ink).map(math.abs).max
    val emboldenedEscape = emboldened.map(_.escape).maxOption.getOrElse(0.0)
    println(
      f"JavaFxTextMetrics vs drawn ink: max advance error $advanceError%.3f px, " +
        f"max line-box error $lineError%.3f px; real faces: ink outside inkBounds by at most " +
        f"$realEscape%.3f px, max |ink edge - inkBounds edge| $realEdge%.3f px; synthesized " +
        f"bold: ink outside inkBounds by at most $emboldenedEscape%.3f px"
    )
    // The TextMetrics contract, for every face including a synthesized bold.
    assert(advanceError <= 1.0, advanceError)
    assert(lineError <= 1.0, lineError)
    // The ink of a real face: every edge within 1 px of inkBounds.
    assert(realEscape <= 1.0, realEscape)
    assert(realEdge <= 1.0, realEdge)
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
    def drawnAt(anchor: Anchor): Coverage = draw(label, style, anchor, pixelsPerInch = 144.0)
    val left = drawnAt(Anchor(HJust.Left, VJust.Top)).centroid
    val right = drawnAt(Anchor(HJust.Right, VJust.Top)).centroid
    val bottom = drawnAt(Anchor(HJust.Left, VJust.Bottom)).centroid
    val advanceError = (left._1 - right._1) - 2.0 * metrics.widthPt(label, style)
    val lineError = (left._2 - bottom._2) - 2.0 * metrics.heightPt(style)
    println(
      f"12 pt layout drawn at 24 px: advance error $advanceError%+.3f px, line $lineError%+.3f px"
    )
    assert(math.abs(advanceError) <= 1.0, advanceError)
    assert(math.abs(lineError) <= 1.0, lineError)
  }

  private val angles = Vector(0.0, 90.0, -90.0, 45.0, 180.0, 30.0)
  private val anchors = Vector(Anchor.BottomLeft, Anchor.Center, Anchor(HJust.Right, VJust.Top))

  test("the drawn layout box at any angle is TextExtent of widthPt by heightPt, within 1 px") {
    // These labels have no glyph overhanging the line box, so the plate is exactly that box.
    val style = TextStyle(None, 24.0)
    var worstPlate = 0.0
    var worstGlyph = Double.NegativeInfinity
    for
      label <- Vector("Header", "Mean response (ms)")
      degrees <- angles
      anchor <- anchors
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
      // A box that leaves the canvas is clipped when drawn and would read as a measurement error.
      assert(
        predicted(0) >= 1 && predicted(1) >= 1 &&
          predicted(2) <= canvasWidth - 1 && predicted(3) <= canvasHeight - 1,
        clue(("predicted box leaves the canvas; enlarge it", label, degrees, anchor, predicted))
      )
      val plate = draw(label, style, anchor, degrees, plateOnly = true).rectangleExtent
      val error = predicted.zip(plate).map((p, d) => math.abs(p - d)).max
      assert(error <= 1.0, clue((label, degrees, anchor, predicted, plate)))
      worstPlate = math.max(worstPlate, error)
      // The glyphs themselves stay inside the reserved extent.
      val glyphs = draw(label, style, anchor, degrees).edges
      val outside = Vector(
        predicted(0) - glyphs(0),
        predicted(1) - glyphs(1),
        glyphs(2) - predicted(2),
        glyphs(3) - predicted(3)
      ).max
      assert(outside <= 1.0, clue((label, degrees, anchor, predicted, glyphs)))
      worstGlyph = math.max(worstGlyph, outside)
    println(
      f"JavaFX rotated layout box vs TextExtent: max edge error $worstPlate%.3f px; glyphs reach " +
        f"at most $worstGlyph%+.3f px outside it"
    )
  }

  /** The parent's Monocle and Prism configuration, so a child JVM runs as CI's test JVM does. */
  private def childProperties(overrides: Map[String, String]): Vector[String] =
    val inherited = Vector("glass.platform", "monocle.platform", "prism.order", "java.awt.headless")
      .flatMap(key => Option(System.getProperty(key)).map(key -> _))
      .toMap
    (inherited ++ overrides).toVector.sorted.map((key, value) => s"-D$key=$value")

  private def runProbe(mode: String, overrides: Map[String, String] = Map.empty): Vector[String] =
    val javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command =
      Vector(javaBin, "-cp", System.getProperty("java.class.path")) ++ childProperties(overrides) ++
        Vector("intaglio.javafx.JavaFxTextMetricsProbe", mode)
    val process = new ProcessBuilder(command.asJava).redirectErrorStream(true).start()
    val finished = process.waitFor(60, TimeUnit.SECONDS)
    if !finished then
      process.destroyForcibly()
      fail(s"the $mode probe hung for 60 s")
    val output = new String(process.getInputStream.readAllBytes(), "UTF-8")
    assertEquals(process.exitValue(), 0, clue(output))
    output.linesIterator.toVector

  test("measurement does not start the JavaFX platform or need the FX thread (fresh JVM)") {
    val lines = runProbe("measure")
    // Platform.startup never ran: runLater still refuses after measuring.
    assert(lines.contains("platform=not-started"), clue(lines))
    val expected = JavaFxTextMetricsProbe.measurements(metrics)
    assert(lines.contains(expected), clue((expected, lines)))
    lines.filter(_.startsWith("renderer-thread=")).foreach(println)
  }

  test("an unusable Prism pipeline is a typed measurement failure, every time (fresh JVM)") {
    val lines = runProbe("unavailable", Map("prism.order" -> "intaglio-no-such-pipeline"))
    val failures = lines.filter(_.contains("LayoutMeasurementFailed"))
    // The first call and a repeat (where JavaFX throws NoClassDefFoundError), through
    // TextExtent.measure and through the layout solver.
    assertEquals(failures.size, 3, clue(lines))
    failures.foreach { line =>
      assert(line.contains("java.lang.IllegalStateException"), clue(line))
      assert(line.contains("JavaFX text measurement is unavailable"), clue(line))
    }
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

/** Run in a fresh JVM by the suite.
  *
  *   - `measure`: measures off the FX thread without `Platform.startup`, then shows the platform
  *     was never started (`runLater` refuses) and whether JavaFX's renderer thread now exists.
  *   - `unavailable`: run with an unusable Prism pipeline; measures twice through `TextExtent` and
  *     once through the layout solver, printing each result.
  */
object JavaFxTextMetricsProbe:
  def measurements(metrics: JavaFxTextMetrics): String =
    val style = TextStyle(None, 24.0, Some(FontWeight.Bold))
    "widths=" + Vector("Header", "Mean response (ms)")
      .map(label => metrics.widthPt(label, style))
      .mkString(",") + s";height=${metrics.heightPt(style)}"

  def main(args: Array[String]): Unit =
    args.headOption.getOrElse("measure") match
      case "unavailable" =>
        val metrics = JavaFxTextMetrics()
        val style = TextStyle(None, 12.0)
        (1 to 2).foreach { attempt =>
          println(s"extent $attempt: ${TextExtent.measure(metrics, "Header", style)}")
        }
        val layout = PlotLayoutSolver.solve(
          LayoutPolicy(metrics = metrics),
          PlotLayoutRequest(axes = Map(AxisSide.Left -> AxisRequest(Vector("label"))))
        )
        println(s"layout: ${layout.left.toOption}")
      case _ =>
        println(measurements(JavaFxTextMetrics()))
        val started =
          try
            Platform.runLater(() => ())
            true
          catch case _: IllegalStateException => false
        println(if started then "platform=started" else "platform=not-started")
        val renderer =
          Thread.getAllStackTraces.keySet.asScala.exists(_.getName.startsWith("QuantumRenderer"))
        println(s"renderer-thread=${if renderer then "running" else "absent"}")
