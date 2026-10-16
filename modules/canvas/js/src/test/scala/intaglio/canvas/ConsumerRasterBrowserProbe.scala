package intaglio.canvas

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}
import intaglio.*
import CanvasRasterFactory.given

/** Browser-only end-to-end consumer regressions; linked explicitly by the companion tool. */
@JSExportTopLevel("ConsumerRasterProbe")
object ConsumerRasterBrowserProbe:
  def main(args: Array[String]): Unit = ()

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
