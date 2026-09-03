package intaglio.javafx

import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.{Image, PixelFormat, WritableImage}
import javafx.scene.paint.{Color, ImagePattern}
import javafx.scene.shape.{StrokeLineCap, StrokeLineJoin}
import javafx.scene.text.{Font, TextAlignment}
import scala.collection.mutable
import intaglio.*

/** Adapter from the toolkit-free [[JavaFxGraphicsContext]] contract onto a live JavaFX
  * `GraphicsContext`. Construction has no toolkit side effects; drawing must happen on the JavaFX
  * application thread like any other `Canvas` access. Raster images are materialized once per
  * adapter as cached ARGB `WritableImage` values.
  */
final class JavaFxCanvasContext(context: GraphicsContext) extends JavaFxGraphicsContext:
  private val images = mutable.HashMap.empty[RasterImage, Image]
  private val patterns = mutable.HashMap.empty[PatternPaint, ImagePattern]

  override def save(): Unit =
    context.save()

  override def restore(): Unit =
    context.restore()

  override def translate(x: Double, y: Double): Unit =
    context.translate(x, y)

  override def rotateDegrees(degrees: Double): Unit =
    context.rotate(degrees)

  override def beginPath(): Unit =
    context.beginPath()

  override def moveTo(x: Double, y: Double): Unit =
    context.moveTo(x, y)

  override def lineTo(x: Double, y: Double): Unit =
    context.lineTo(x, y)

  override def closePath(): Unit =
    context.closePath()

  override def rect(x: Double, y: Double, width: Double, height: Double): Unit =
    context.rect(x, y, width, height)

  override def arcTo(x1: Double, y1: Double, x2: Double, y2: Double, radius: Double): Unit =
    context.arcTo(x1, y1, x2, y2, radius)

  override def clip(): Unit =
    context.clip()

  override def fillPath(): Unit =
    context.fill()

  override def strokePath(): Unit =
    context.stroke()

  override def fillOval(x: Double, y: Double, width: Double, height: Double): Unit =
    context.fillOval(x, y, width, height)

  override def strokeOval(x: Double, y: Double, width: Double, height: Double): Unit =
    context.strokeOval(x, y, width, height)

  override def setFill(color: JavaFxColor): Unit =
    context.setFill(fx(color))

  override def setPatternFill(pattern: PatternPaint): Boolean =
    val hit = patterns.contains(pattern)
    val resource = patterns.getOrElseUpdate(pattern, imagePattern(pattern))
    context.setFill(resource)
    hit

  override def setStroke(color: JavaFxColor): Unit =
    context.setStroke(fx(color))

  override def setLineWidth(width: Double): Unit =
    context.setLineWidth(width)

  override def setLineCap(cap: LineCap): Unit =
    context.setLineCap(
      cap match
        case LineCap.Butt   => StrokeLineCap.BUTT
        case LineCap.Round  => StrokeLineCap.ROUND
        case LineCap.Square => StrokeLineCap.SQUARE
    )

  override def setLineJoin(join: LineJoin): Unit =
    context.setLineJoin(
      join match
        case LineJoin.Miter => StrokeLineJoin.MITER
        case LineJoin.Round => StrokeLineJoin.ROUND
        case LineJoin.Bevel => StrokeLineJoin.BEVEL
    )

  override def setLineDashes(pattern: Vector[Double]): Unit =
    context.setLineDashes(pattern.toArray*)

  override def setFont(family: Option[String], sizePx: Double): Unit =
    context.setFont(family.fold(Font.font(sizePx))(name => Font.font(name, sizePx)))

  override def setTextAlign(horizontal: HJust): Unit =
    context.setTextAlign(
      horizontal match
        case HJust.Left   => TextAlignment.LEFT
        case HJust.Center => TextAlignment.CENTER
        case HJust.Right  => TextAlignment.RIGHT
    )

  override def setTextBaseline(vertical: VJust): Unit =
    context.setTextBaseline(
      vertical match
        case VJust.Top    => VPos.TOP
        case VJust.Center => VPos.CENTER
        case VJust.Bottom => VPos.BOTTOM
    )

  override def fillText(label: String, x: Double, y: Double): Unit =
    context.fillText(label, x, y)

  override def setGlobalAlpha(alpha: Double): Unit =
    context.setGlobalAlpha(alpha)

  override def setImageSmoothing(enabled: Boolean): Unit =
    context.setImageSmoothing(enabled)

  override def drawImage(
      image: RasterImage,
      x: Double,
      y: Double,
      width: Double,
      height: Double
  ): Unit =
    val source = images.getOrElseUpdate(image, writable(image))
    context.drawImage(source, x, y, width, height)

  private def fx(color: JavaFxColor): Color =
    Color.rgb(color.red, color.green, color.blue, color.alpha.max(0.0).min(1.0))

  private def imagePattern(pattern: PatternPaint): ImagePattern =
    val tile = PatternTile
      .fromPaint(pattern)
      .fold(error => throw new IllegalStateException(error.message), identity)
    new ImagePattern(writable(tile.image), 0.0, 0.0, tile.width, tile.height, false)

  private def writable(image: RasterImage): WritableImage =
    val output = new WritableImage(image.width, image.height)
    output.getPixelWriter.setPixels(
      0,
      0,
      image.width,
      image.height,
      PixelFormat.getIntArgbInstance,
      JavaFxRaster.argb(image),
      0,
      image.width
    )
    output
