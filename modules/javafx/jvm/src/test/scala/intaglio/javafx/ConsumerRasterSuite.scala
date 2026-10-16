package intaglio.javafx

import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.image.Image
import _root_.javafx.scene.paint.Color
import intaglio.*

/** JavaFX canvas evidence for the native consumer raster reports. The oracle maps each physical
  * destination-pixel centre directly to the source column and therefore does not reuse the
  * production raster sampling helper.
  */
class ConsumerRasterSuite extends munit.FunSuite:
  private val sourceWidth = 301
  private val logicalWidth = 700
  private val logicalHeight = 20
  private val indexed = RasterImage.tabulate(RasterDimensions.unsafe(sourceWidth, 1)) { (x, _) =>
    Rgba32.unsafe(x % 256, x / 256, 0)
  }

  override def beforeAll(): Unit = FxToolkit.start()

  private def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(error => fail(error.message), identity)
  private def fx[A](body: => A): A = FxToolkit.fx(body)

  private def snapshot(scene: Scene, scale: Int): Image = fx {
    val width = logicalWidth * scale
    val height = logicalHeight * scale
    val canvas = new Canvas(width.toDouble, height.toDouble)
    val context = new JavaFxCanvasContext(canvas.getGraphicsContext2D)
    JavaFxRenderer
      .render(
        scene,
        context,
        JavaFxOptions.unsafe(width, height, pixelsPerInch = 96.0 * scale, deviceScale = scale)
      )
      .fold(error => fail(error.message), identity)
    val parameters = new SnapshotParameters()
    parameters.setFill(Color.TRANSPARENT)
    canvas.snapshot(parameters, null)
  }

  private def nearestScene(x: Double, width: Double, scale: Int): Scene =
    val deviceWidth = logicalWidth * scale
    val grob = Grob.imageUnsafe(
      indexed,
      Point.npcUnsafe(x * scale / deviceWidth, 1.0),
      Size.npcUnsafe(width * scale / deviceWidth, 1.0),
      anchor = Anchor(HJust.Left, VJust.Top),
      interpolation = RasterInterpolation.Nearest
    )
    Scene(Vector(grob))

  private def expectedColumn(pixel: Int, x: Double, width: Double, scale: Int): Option[Int] =
    val left = x * scale
    val span = width * scale
    if pixel + 0.5 < left || pixel + 0.5 >= left + span then None
    else Some(math.floor((pixel + 0.5 - left) / span * sourceWidth).toInt)

  private def assertNearest(x: Double, width: Double, scale: Int): Unit =
    val rendered = snapshot(nearestScene(x, width, scale), scale)
    val reader = rendered.getPixelReader
    val row = rendered.getHeight.toInt / 2
    for pixel <- 0 until rendered.getWidth.toInt do
      val argb = reader.getArgb(pixel, row)
      expectedColumn(pixel, x, width, scale) match
        case Some(column) =>
          assertEquals(argb >>> 24, 255, clues(x, width, scale, pixel, column))
          assertEquals((argb >>> 16) & 255, column % 256, clues(x, width, scale, pixel, column))
          assertEquals((argb >>> 8) & 255, column / 256, clues(x, width, scale, pixel, column))
          assertEquals(argb & 255, 0, clues(x, width, scale, pixel, column))
        case None => assertEquals(argb >>> 24, 0, clues(x, width, scale, pixel))

  test("nearest images map every covered pixel centre to its encoded source column") {
    Vector((49.96, 286.25), (0.49, 301.0), (3.3, 150.2), (49.96, 79.01), (41.7, 287.3))
      .foreach { (x, width) => Vector(1, 2).foreach(scale => assertNearest(x, width, scale)) }
    Vector((0.0, 301.0), (50.0, 286.0)).foreach { (x, width) =>
      Vector(1, 2).foreach(scale => assertNearest(x, width, scale))
    }
  }

  private def densePlan(width: Int, scale: Int): RenderPlan =
    val actualWidth = width * scale
    val actualHeight = logicalHeight * scale
    val field = ScalarField2D.unsafe(
      RegularGridAxis.cellCenteredUnsafe(0, sourceWidth, sourceWidth),
      RegularGridAxis.cellCenteredUnsafe(0, 1, 1),
      Vector.fill(sourceWidth)(0.0)
    )
    val fill = ok(ContinuousScale.fixed("fixed", Vector(-13.0, 13.0), Palette.constant(Rgba.Black)))
    val theme = Theme.minimal.copy(
      layout = Theme.minimal.layout.copy(outerMarginPt = 0),
      panel = PanelTheme()
    )
    ok(
      plot(field)
        .geomRaster(scale = Some(fill))
        .compilerOptions(
          PlotCompilerOptions(
            guides = GuidePolicy.NoGuides,
            expansion = RangeExpansion.none,
            theme = theme
          )
        )
        .renderPlan(
          RenderContext
            .unsafe(actualWidth, actualHeight, pixelsPerInch = 96.0 * scale, deviceScale = scale)
        )
    )

  test("dense fixed-scale geomRaster has no internal seams at consumer widths and device scales") {
    for width <- Vector(291, 86); scale <- Vector(1, 2) do
      val rendered = fx {
        val actualWidth = width * scale
        val actualHeight = logicalHeight * scale
        val canvas = new Canvas(actualWidth.toDouble, actualHeight.toDouble)
        val context = new JavaFxCanvasContext(canvas.getGraphicsContext2D)
        JavaFxRenderer
          .render(densePlan(width, scale), context)
          .fold(error => fail(error.message), identity)
        val parameters = new SnapshotParameters()
        parameters.setFill(Color.TRANSPARENT)
        canvas.snapshot(parameters, null)
      }
      val reader = rendered.getPixelReader
      val row = rendered.getHeight.toInt / 2
      for x <- 0 until rendered.getWidth.toInt do
        assertEquals(reader.getArgb(x, row), 0xff000000, clues(width, scale, x))
  }

  test("derived axis thinning keeps a regular stride and two labels whenever a pair fits") {
    val metrics = JavaFxTextMetrics()
    val style = TextStyle(None, 10.0)
    val pxPerPt = 96.0 / 72.0
    val fontPx = style.fontSizePt * pxPerPt
    for
      (lo, hi) <- Vector((0.0, 150.0), (-0.4, 0.4))
      width <- ((100 to 400 by 20).toVector ++ Vector(250, 300)).distinct
      side <- Vector(AxisSide.Bottom, AxisSide.Left)
    do
      val range = Interval.unsafe(lo, hi)
      val ticks = Axis.ticks(range).orThrow
      val panel = PanelLayout.unit(range, range)
      val policy = LayoutPolicy(
        referenceDevice = DeviceContext.unsafe(width, width),
        metrics = metrics
      )
      val gp =
        GraphicParams.unsafe(fontFamily = style.fontFamily, fontSize = Length.pointsUnsafe(10))
      val spec = GuideSpec.Axis(side, ticks = Some(ticks), labelGp = Some(gp))
      val fitted = GuideSpec
        .lowerAxis(spec, panel, policy, Theme.minimal, avoidOverlap = true)
        .orThrow
        .spec
        .asInstanceOf[GuideSpec.Axis]
        .ticks
        .get
      if fitted.size >= 2 then
        val indices = fitted.map(ticks.indexOf)
        val stride = indices(1) - indices(0)
        assert(
          indices.head < stride && indices.last + stride >= ticks.size,
          clues(width, side, fitted)
        )
      def extent(tick: AxisTick): Double =
        (if side.isHorizontal then metrics.widthPt(tick.label, style)
         else metrics.heightPt(style)) * pxPerPt
      def clears(a: AxisTick, b: AxisTick): Boolean =
        (range.rescale(b.value) - range.rescale(a.value)) * width + 1e-7 >=
          (extent(a) + extent(b)) / 2.0 + fontPx * 0.5
      val steps = fitted.sliding(2).filter(_.size == 2).map(p => p(1).value - p(0).value).toVector
      steps.foreach(step =>
        assertEqualsDouble(step, steps.head, 1e-9, clues(lo, hi, width, side, fitted))
      )
      fitted
        .sliding(2)
        .filter(_.size == 2)
        .foreach(p => assert(clears(p(0), p(1)), clues(width, side, fitted)))
      val pairFits = ticks.indices.exists(i => ticks.drop(i + 1).exists(clears(ticks(i), _)))
      if pairFits then assert(fitted.size >= 2, clues(width, side, fitted))
      if width == 400 then assertEquals(fitted, ticks)
  }
