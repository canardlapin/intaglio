package intaglio.canvas

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import intaglio.*
import CanvasRasterFactory.given

/** Browser-only end-to-end consumer regressions; linked explicitly by the companion tool. */
@JSExportTopLevel("ConsumerRasterProbe")
object ConsumerRasterBrowserProbe:
  def main(args: Array[String]): Unit = ()

  private def imageProgram(
      image: RasterImage,
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      interpolation: RasterInterpolation = RasterInterpolation.Nearest
  ): CanvasProgram =
    CanvasProgram.fromDevice(
      DeviceScene(
        2108,
        2108,
        Vector(
          DeviceElement.Mark(
            DevicePrimitive.Image(image, x, y, width, height, interpolation, 1.0, None)
          )
        )
      ),
      RenderContext.unsafe(2108, 2108)
    )

  @JSExport
  def exact(
      canvas: js.Dynamic,
      sourceWidth: Int,
      sourceHeight: Int,
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      offscreen: Boolean = false
  ): js.Dynamic =
    canvas.width = math.max(8, math.ceil(x + width + 3).toInt)
    canvas.height = math.max(8, math.ceil(y + height + 3).toInt)
    val target = if offscreen then
      js.Dynamic.newInstance(js.Dynamic.global.OffscreenCanvas)(canvas.width, canvas.height)
    else canvas
    given CanvasRasterFactory = if offscreen then
      new CanvasRasterFactory:
        def create(image: RasterImage, context: CanvasRenderingContext2D): CanvasImageSource =
          val surface =
            js.Dynamic.newInstance(js.Dynamic.global.OffscreenCanvas)(image.width, image.height)
          val native = surface.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
          val data = native.createImageData(image.width, image.height)
          CanvasRasterFactory.writeRgbaBytes(
            image,
            data.data,
            CanvasRasterFactory.nativeLittleEndian
          )
          native.putImageData(data, 0, 0)
          surface.asInstanceOf[CanvasImageSource]
    else CanvasRasterFactory.browser
    val ctx = target.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    val source = RasterImage.tabulate(RasterDimensions.unsafe(sourceWidth, sourceHeight)) {
      (c, r) =>
        Rgba32.unsafe(
          (c * 67 + r * 13) % 256,
          (c * 11 + r * 97) % 256,
          (c * 31 + r * 41) % 256,
          Vector(0, 64, 128, 192, 255)((c + r) % 5)
        )
    }
    val cache = CanvasRasterCache.empty(1)
    val cold = CanvasRenderer.drawCached(imageProgram(source, x, y, width, height), ctx, cache)
    val expected = canvas.ownerDocument.createElement("canvas")
    expected.width = canvas.width; expected.height = canvas.height
    val reference = expected.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    RasterSampling
      .prepare(source, x, y, width, height, RasterInterpolation.Nearest)
      .orThrow
      .foreach { case (sampled, dx, dy, dw, dh) =>
        reference.imageSmoothingEnabled = false
        reference.drawImage(summon[CanvasRasterFactory].create(sampled, reference), dx, dy, dw, dh)
      }
    val actualBytes = target.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data
    val expectedBytes =
      expected.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data
    var mismatches = 0
    var i = 0
    while i < actualBytes.length.asInstanceOf[Int] do
      if actualBytes.selectDynamic(i.toString).asInstanceOf[Int] !=
          expectedBytes.selectDynamic(i.toString).asInstanceOf[Int]
      then mismatches += 1
      i += 1
    val resized = CanvasRenderer.drawCached(
      imageProgram(source, x + 0.2, y + 0.1, width + 1, height + 1),
      ctx,
      cache
    )
    js.Dynamic.literal(
      mismatches = mismatches,
      coldBytes = cold.uploadedBytes.toDouble,
      resizeBytes = resized.uploadedBytes.toDouble
    )

  @JSExport
  def resizeBenchmark(canvas: js.Dynamic, size: Int, legacy: Boolean, iterations: Int): js.Dynamic =
    canvas.width = size + 8; canvas.height = size + 8
    val context = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    val image = RasterImage.tabulate(RasterDimensions.unsafe(256, 256)) { (c, r) =>
      Rgba32.unsafe(c, r, (c + r) % 256)
    }
    val cache = CanvasRasterCache.empty(1)
    def frame(i: Int): Long =
      val width = size + 0.2 + (i % 2)
      val height = size + 0.1 + (i % 2)
      val program = if legacy then
        val (sampled, x, y, w, h) = RasterSampling
          .prepare(image, 0.3, 0.4, width, height, RasterInterpolation.Nearest)
          .orThrow
          .get
        imageProgram(sampled, x, y, w, h)
      else imageProgram(image, 0.3, 0.4, width, height)
      CanvasRenderer.drawCached(program, context, cache).uploadedBytes
    (0 until 4).foreach(frame)
    val start = js.Dynamic.global.performance.now().asInstanceOf[Double]
    var bytes = 0L
    (0 until iterations).foreach(i => bytes += frame(i))
    val elapsed = js.Dynamic.global.performance.now().asInstanceOf[Double] - start
    js.Dynamic.literal(
      millisecondsPerFrame = elapsed / iterations,
      uploadedBytesPerFrame = bytes.toDouble / iterations,
      iterations = iterations
    )

  @JSExport
  def nearest(canvas: js.Dynamic, x: Double, width: Double, scale: Double): Unit =
    val w = (700 * scale).toInt; val h = (20 * scale).toInt
    canvas.width = w; canvas.height = h
    val image = RasterImage.tabulate(RasterDimensions.unsafe(301, 1)) { (column, _) =>
      Rgba32.unsafe(column % 256, column / 256, 0)
    }
    val grob = Grob.imageUnsafe(
      image,
      Point.npcUnsafe(x * scale / w, 1.0),
      Size.npcUnsafe(width * scale / w, 1.0),
      anchor = Anchor(HJust.Left, VJust.Top)
    )
    CanvasRenderer
      .render(
        Scene(Vector(grob)),
        canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D],
        CanvasOptions.unsafe(w, h, pixelsPerInch = 96 * scale, deviceScale = scale)
      )
      .orThrow

  @JSExport
  def scaledHost(canvas: js.Dynamic, x: Double, width: Double, scale: Double): Unit =
    canvas.width = (700 * scale).toInt; canvas.height = (20 * scale).toInt
    val context = canvas.getContext("2d")
    context.setTransform(scale, 0, 0, scale, 0, 0)
    val image = RasterImage.tabulate(RasterDimensions.unsafe(301, 1)) { (column, _) =>
      Rgba32.unsafe(column % 256, column / 256, 0)
    }
    val grob = Grob.imageUnsafe(
      image,
      Point.npcUnsafe(x / 700, 1.0),
      Size.npcUnsafe(width / 700, 1.0),
      anchor = Anchor(HJust.Left, VJust.Top)
    )
    CanvasRenderer
      .render(
        Scene(Vector(grob)),
        context.asInstanceOf[CanvasRenderingContext2D],
        CanvasOptions.unsafe(700, 20)
      )
      .orThrow

  @JSExport
  def dense(canvas: js.Dynamic, width: Int, scale: Double): Unit =
    val w = (width * scale).toInt; val h = (20 * scale).toInt
    canvas.width = w; canvas.height = h
    val field = ScalarField2D.unsafe(
      RegularGridAxis.cellCenteredUnsafe(0, 301, 301),
      RegularGridAxis.cellCenteredUnsafe(0, 1, 1),
      Vector.fill(301)(0.0)
    )
    val fill =
      ContinuousScale.fixed("fixed", Vector(-13.0, 13.0), Palette.constant(Rgba.Black)).orThrow
    val theme = Theme.minimal.copy(
      layout = Theme.minimal.layout.copy(outerMarginPt = 0),
      panel = PanelTheme()
    )
    val plan = plot(field)
      .geomRaster(scale = Some(fill))
      .compilerOptions(
        PlotCompilerOptions(
          guides = GuidePolicy.NoGuides,
          expansion = RangeExpansion.none,
          theme = theme
        )
      )
      .renderPlan(RenderContext.unsafe(w, h, pixelsPerInch = 96 * scale, deviceScale = scale))
      .orThrow
    CanvasRenderer
      .render(plan, canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D])
      .orThrow

  @JSExport
  def font(canvas: js.Dynamic, family: String): Double =
    canvas.width = 400; canvas.height = 60
    val context = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    val text = Grob.textUnsafe(
      "Hamburgefonstiv",
      Point.npcUnsafe(0, 1),
      anchor = Anchor(HJust.Left, VJust.Top),
      gp = GraphicParams.unsafe(
        stroke = None,
        fill = Some(Rgba.Black),
        fontSize = Length.pointsUnsafe(24),
        fontFamily = Some(family)
      )
    )
    CanvasRenderer.render(Scene(Vector(text)), context, CanvasOptions.unsafe(400, 60)).orThrow
    new CanvasTextMetrics(context).widthPt("Hamburgefonstiv", TextStyle(Some(family), 24)) * 96 / 72

  @JSExport
  def tracking(
      canvas: js.Dynamic,
      label: String,
      spacing: Double,
      scale: Int,
      alignment: String
  ): Double =
    canvas.width = 600 * scale; canvas.height = 120 * scale
    val context = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    context.asInstanceOf[js.Dynamic].scale(scale, scale)
    val horizontal = alignment match
      case "center" => HJust.Center
      case "right"  => HJust.Right
      case _        => HJust.Left
    val params = GraphicParams
      .unsafe(
        stroke = None,
        fill = Some(Rgba.Black),
        fontFamily = Some("Arial"),
        fontSize = Length.pointsUnsafe(24)
      )
      .withLetterSpacing(LetterSpacing.devicePixelsUnsafe(spacing))
    val text = Grob.textUnsafe(
      label,
      Point.npcUnsafe(0.5, 0.5),
      anchor = Anchor(horizontal, VJust.Center),
      gp = params
    )
    CanvasRenderer.render(Scene(Vector(text)), context, CanvasOptions.unsafe(600, 120)).orThrow
    new CanvasTextMetrics(context)
      .widthPt(label, TextStyle(Some("Arial"), 24, letterSpacingPt = spacing * 72 / 96)) * 96 / 72

  @JSExport
  def guides(canvas: js.Dynamic, hidden: Boolean): Unit =
    canvas.width = 360; canvas.height = 260
    val context = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]
    val field = ScalarField2D.unsafe(
      RegularGridAxis.cellCenteredUnsafe(0, 150, 301),
      RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
      Vector.tabulate(903)(i => 4.6 * math.sin(i.toDouble / 50))
    )
    val fill = ContinuousScale.diverging("dB", 13, labeler = Labeler.typographic).orThrow
    val guides = if hidden then
      GuidePolicy.Derived().withoutAxes(Set(AxisSide.Bottom, AxisSide.Left))
    else GuidePolicy.Derived()
    val plan = plot(field)
      .geomRaster(scale = Some(fill))
      .compilerOptions(
        PlotCompilerOptions(
          guides = guides,
          theme = Theme.minimal,
          policy = Some(LayoutPolicy(colorbarHeightPt = 34))
        )
      )
      .renderPlan(RenderContext.unsafe(360, 260, textMetrics = new CanvasTextMetrics(context)))
      .orThrow
    CanvasRenderer.render(plan, context).orThrow
