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
