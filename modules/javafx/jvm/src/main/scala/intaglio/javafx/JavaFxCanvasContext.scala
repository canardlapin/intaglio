package intaglio.javafx

import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.{Image, PixelFormat, WritableImage}
import javafx.scene.paint.{Color, ImagePattern}
import javafx.scene.shape.{StrokeLineCap, StrokeLineJoin}
import javafx.scene.text.{TextAlignment, TextBoundsType}
import java.lang.ref.WeakReference
import scala.collection.mutable
import intaglio.*

/** Adapter from the toolkit-free [[JavaFxGraphicsContext]] contract onto a live JavaFX
  * `GraphicsContext`. Construction has no toolkit side effects; drawing must happen on the JavaFX
  * application thread like any other `Canvas` access. Raster images are materialized per adapter as
  * cached ARGB `WritableImage` values, within a shared byte-accounted LRU budget.
  */
final class JavaFxCanvasContext(context: GraphicsContext, val cacheByteLimit: Long)
    extends JavaFxGraphicsContext:
  /** Retains the original constructor descriptor. */
  def this(context: GraphicsContext) = this(context, JavaFxCanvasContext.DefaultCacheByteLimit)

  require(cacheByteLimit >= 0, "cache byte limit must be non-negative")

  private type CacheKey = Either[RasterImage, PatternPaint]
  private val images = mutable.HashMap.empty[RasterImage, (Image, CacheKey)]
  private val patterns = mutable.HashMap.empty[PatternPaint, (ImagePattern, CacheKey)]
  private val order = mutable.LinkedHashMap.empty[Either[RasterImage, PatternPaint], Long]
  private var retainedBytes = 0L
  private var lastPattern = new WeakReference[ImagePattern](null)
  private var savedPatterns = List.empty[WeakReference[ImagePattern]]

  /** Accounted pixel storage plus an entry allowance; excludes toolkit/driver overhead. */
  def cachedResourceBytes: Long = retainedBytes
  def cachedResourceCount: Int = order.size

  private[javafx] def cachedNativeImages: Vector[Image] =
    images.valuesIterator.map(_._1).toVector ++
      patterns.valuesIterator.map(_._1.getImage).toVector

  /** Drop adapter-owned references after balanced drawing; FX application thread only. The adapter
    * remains reusable. JavaFX reclaims images after pending Canvas operations finish.
    */
  def release(): Unit =
    val paint = lastPattern.get()
    if paint != null && (context.getFill eq paint) then context.setFill(Color.BLACK)
    lastPattern.clear()
    savedPatterns = Nil
    images.clear()
    patterns.clear()
    order.clear()
    retainedBytes = 0L

  /** Compatibility alias used by existing hosts. */
  def clearCaches(): Unit =
    release()

  private def touch(key: Either[RasterImage, PatternPaint]): Unit =
    order.remove(key).foreach(bytes => order.put(key, bytes))

  private def retain(key: Either[RasterImage, PatternPaint], bytes: Long): Boolean =
    if bytes > cacheByteLimit then false
    else
      while retainedBytes > cacheByteLimit - bytes do
        val (oldest, size) = order.head
        order.remove(oldest)
        oldest match
          case Left(image)    => images.remove(image)
          case Right(pattern) => patterns.remove(pattern)
        retainedBytes -= size
      order.put(key, bytes)
      retainedBytes += bytes
      true

  override def save(): Unit =
    context.save()
    savedPatterns = lastPattern :: savedPatterns

  override def restore(): Unit =
    context.restore()
    savedPatterns match
      case previous :: rest =>
        lastPattern = previous
        savedPatterns = rest
      case Nil => ()

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
    val cached = patterns.get(pattern)
    val resource = cached match
      case Some((value, key)) =>
        touch(key)
        value
      case None =>
        val value = imagePattern(pattern)
        val bytes = 256L + value.getImage.getWidth.toLong * value.getImage.getHeight.toLong * 4L
        val key = Right(pattern)
        if retain(key, bytes) then patterns.put(pattern, (value, key))
        value
    context.setFill(resource)
    lastPattern = new WeakReference(resource)
    cached.nonEmpty

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
    context.setMiterLimit(4.0)
    context.setLineJoin(
      join match
        case LineJoin.Miter => StrokeLineJoin.MITER
        case LineJoin.Round => StrokeLineJoin.ROUND
        case LineJoin.Bevel => StrokeLineJoin.BEVEL
    )

  override def setLineDashes(pattern: Vector[Double]): Unit =
    context.setLineDashes(pattern.toArray*)

  override def setFont(family: Option[String], sizePx: Double, weight: Option[FontWeight]): Unit =
    context.setFont(JavaFxTextMetrics.font(family, sizePx, weight))

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
    val source = images.get(image) match
      case Some((value, key)) =>
        touch(key)
        value
      case None =>
        val value = writable(image)
        // The key retains the packed source pixels as well as the native ARGB image.
        val bytes = 256L + image.dimensions.pixelCount.toLong * 8L
        val key = Left(image)
        if retain(key, bytes) then images.put(image, (value, key))
        value
    context.drawImage(source, x, y, width, height)

  private def fx(color: JavaFxColor): Color =
    Color.rgb(color.red, color.green, color.blue, color.alpha.max(0.0).min(1.0))

  private def imagePattern(pattern: PatternPaint): ImagePattern =
    val tile = PatternTile
      .fromPaint(pattern)
      .fold(error => throw new IllegalStateException(error.message), identity)
    new ImagePattern(writable(tile.image), 0.0, 0.0, tile.width, tile.height, false)

  private def writable(image: RasterImage): WritableImage =
    JavaFxCanvasContext.validateRaster(image).orThrow
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

  /** Measures with JavaFX's own text layout in the face `Font.font` resolves (a fallback face when
    * the family is not installed), using the same vertical origin `fillText` uses.
    */
  override def measureText(
      label: String,
      family: Option[String],
      sizePx: Double,
      weight: Option[FontWeight],
      horizontal: HJust,
      vertical: VJust
  ): Option[JavaFxTextBox] =
    val node = new _root_.javafx.scene.text.Text(label)
    node.setFont(JavaFxTextMetrics.font(family, sizePx, weight))
    // Measure both boxes at the alphabetic baseline. Switching bounds type with a top/center/
    // bottom origin would also change that origin, moving the visual box relative to fillText.
    node.setTextOrigin(VPos.BASELINE)
    val logical = node.getLayoutBounds
    node.setBoundsType(TextBoundsType.VISUAL)
    val visual = node.getLayoutBounds
    val minX = math.min(logical.getMinX, visual.getMinX)
    val maxX = math.max(logical.getMaxX, visual.getMaxX)
    val minY = math.min(logical.getMinY, visual.getMinY)
    val maxY = math.max(logical.getMaxY, visual.getMaxY)
    // Canvas positions the text from the logical advance, even when a glyph overhangs it.
    val left = horizontal match
      case HJust.Left   => 0.0
      case HJust.Center => -logical.getWidth / 2.0
      case HJust.Right  => -logical.getWidth
    val baseline = vertical match
      case VJust.Top    => -logical.getMinY
      case VJust.Center => -(logical.getMinY + logical.getHeight / 2.0)
      case VJust.Bottom => -logical.getMaxY
    Some(JavaFxTextBox(left + minX, baseline + minY, maxX - minX, maxY - minY))

object JavaFxCanvasContext:
  val DefaultCacheByteLimit: Long = 64L * 1024L * 1024L

  /** Conservative backend policy, independent of the active Prism driver. */
  val MaxRasterDimension: Int = 4096

  private[javafx] def validateRaster(image: RasterImage): Either[JavaFxRenderError, Unit] =
    if image.width <= MaxRasterDimension && image.height <= MaxRasterDimension then Right(())
    else Left(JavaFxRenderError.RasterTooLarge(image.width, image.height, MaxRasterDimension))
