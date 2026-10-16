package intaglio.java2d

import java.awt.Color
import java.awt.image.BufferedImage
import intaglio.*

/** Native end-to-end regressions for the raster and measured-axis consumer reports. The nearest
  * oracle is deliberately independent of RasterSampling: it maps the centre of every destination
  * pixel directly back into the 301-column source image.
  */
class ConsumerRasterSuite extends munit.FunSuite:
  private val sourceWidth = 301
  private val logicalWidth = 700
  private val logicalHeight = 20

  private val indexed = RasterImage.tabulate(RasterDimensions.unsafe(sourceWidth, 1)) { (x, _) =>
    Rgba32.unsafe(x % 256, x / 256, 0)
  }

  private def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(error => fail(error.message), identity)

  private def raster(scene: Scene, scale: Int): BufferedImage =
    val width = logicalWidth * scale
    val height = logicalHeight * scale
    val target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = target.createGraphics()
    try
      Java2DRenderer
        .render(
          scene,
          graphics,
          Java2DOptions.unsafe(width, height, pixelsPerInch = 96.0 * scale, deviceScale = scale)
        )
        .fold(error => fail(error.message), identity)
    finally graphics.dispose()
    target

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
    val image = raster(nearestScene(x, width, scale), scale)
    val row = image.getHeight / 2
    for pixel <- 0 until image.getWidth do
      val actual = new Color(image.getRGB(pixel, row), true)
      expectedColumn(pixel, x, width, scale) match
        case Some(column) =>
          assertEquals(actual.getAlpha, 255, clues(x, width, scale, pixel, column))
          assertEquals(actual.getRed, column % 256, clues(x, width, scale, pixel, column))
          assertEquals(actual.getGreen, column / 256, clues(x, width, scale, pixel, column))
          assertEquals(actual.getBlue, 0, clues(x, width, scale, pixel, column))
        case None => assertEquals(actual.getAlpha, 0, clues(x, width, scale, pixel))

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
      val plan = densePlan(width, scale)
      val target =
        new BufferedImage(width * scale, logicalHeight * scale, BufferedImage.TYPE_INT_ARGB)
      val graphics = target.createGraphics()
      try Java2DRenderer.render(plan, graphics).fold(error => fail(error.message), identity)
      finally graphics.dispose()
      val row = target.getHeight / 2
      for x <- 0 until target.getWidth do
        assertEquals(
          new Color(target.getRGB(x, row), true),
          new Color(0, 0, 0, 255),
          clues(width, scale, x)
        )
  }

  test("measured Java2D axis labels retain non-overlapping spacing across consumer ranges") {
    val metrics = Java2DTextMetrics()
    for
      (lo, hi) <- Vector((0.0, 150.0), (-0.4, 0.4))
      width <- 100 to 400 by 20
    do
      val context = RenderContext.unsafe(width, width, textMetrics = metrics)
      val plan = ok(
        plot(Vector((lo, lo), (hi, hi)))
          .aes(_._1, _._2)
          .geomPoint()
          .compilerOptions(
            PlotCompilerOptions(
              guides = GuidePolicy.Derived(),
              theme = Theme.minimal,
              expansion = RangeExpansion.none,
              renderContext = Some(context)
            )
          )
          .renderPlan(context)
      )
      def labels(elements: Vector[DeviceElement]): Vector[DevicePrimitive.TextRun] =
        elements.flatMap {
          case DeviceElement.Mark(text: DevicePrimitive.TextRun) => Vector(text)
          case DeviceElement.Mark(_)                             => Vector.empty
          case DeviceElement.Group(_, _, _, children)            => labels(children)
          case DeviceElement.Annotated(_, children)              => labels(children)
        }
      val text = labels(plan.deviceScene.fold(error => fail(error.message), identity).elements)
      for (prefix, horizontal) <- Vector(("x-axis-label", true), ("y-axis-label", false)) do
        val axis = text
          .filter(_.name.exists(_.value.startsWith(prefix)))
          .sortBy(t => if horizontal then t.x else t.y)
        assert(axis.nonEmpty, clues(lo, hi, width, prefix))
        axis.sliding(2).filter(_.size == 2).foreach { pair =>
          val first = pair.head
          val second = pair.last
          def deviceExtent(text: DevicePrimitive.TextRun): Double =
            val style = TextStyle(text.fontFamily, text.fontSizePx * 72.0 / context.pixelsPerInch)
            val points =
              if horizontal then metrics.widthPt(text.label, style) else metrics.heightPt(style)
            points * context.pixelsPerInch / 72.0
          val extent = deviceExtent(first)
          val nextExtent = deviceExtent(second)
          val distance = if horizontal then second.x - first.x else second.y - first.y
          assert(
            distance + 1e-7 >= (extent + nextExtent) / 2 + first.fontSizePx * 0.25,
            clues(lo, hi, width, prefix, first.label, second.label, distance, extent, nextExtent)
          )
        }
  }
