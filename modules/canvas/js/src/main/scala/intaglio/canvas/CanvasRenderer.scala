package intaglio.canvas

import scala.scalajs.js
import scala.scalajs.js.typedarray.{Uint8Array, Uint8ClampedArray, Uint32Array}
import scala.collection.mutable
import scala.util.control.NonFatal
import intaglio.*

final case class CanvasOptions private (width: Int, height: Int)

object CanvasOptions:
  val default: CanvasOptions =
    unsafe()

  def apply(width: Int = 640, height: Int = 480): Either[CanvasRenderError, CanvasOptions] =
    if width <= 0 || height <= 0 then Left(CanvasRenderError.InvalidCanvasSize(width, height))
    else Right(new CanvasOptions(width, height))

  def unsafe(width: Int = 640, height: Int = 480): CanvasOptions =
    apply(width, height).orThrow

enum CanvasRenderError extends IntaglioError:
  case InvalidCanvasSize(width: Int, height: Int)
  case InvalidRasterCacheCapacity(value: Int)
  case PatternResourceFailure(detail: String)
  case Graphics(error: GraphicsError)

  def message: String =
    this match
      case InvalidCanvasSize(width, height) =>
        s"Canvas size must be positive: ${width}x$height"
      case InvalidRasterCacheCapacity(value) =>
        s"Canvas raster cache capacity must be non-negative; got $value"
      case PatternResourceFailure(detail) =>
        s"Canvas pattern resource failed: $detail"
      case Graphics(error) =>
        error.message

object CanvasRenderError:
  extension [A](either: Either[CanvasRenderError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

final case class CanvasColor(css: String, alpha: Double)

object CanvasColor:
  def fromRgba(color: Rgba): CanvasColor =
    def channel(value: Int): String =
      val encoded = value.toHexString
      if encoded.length == 1 then "0" + encoded else encoded
    CanvasColor(
      "#" + channel(color.red) + channel(color.green) + channel(color.blue),
      color.alpha
    )

enum CanvasLineDash:
  case Solid
  case Pattern(values: Vector[Double])

object CanvasLineDash:
  def fromLineType(lineType: LineType): CanvasLineDash =
    lineType match
      case LineType.Solid  => CanvasLineDash.Solid
      case LineType.Dashed => CanvasLineDash.Pattern(Vector(6.0, 4.0))
      case LineType.Dotted => CanvasLineDash.Pattern(Vector(1.0, 3.0))

final case class CanvasPaint(
    stroke: Option[CanvasColor],
    fill: Option[CanvasColor],
    lineWidth: Double,
    dash: CanvasLineDash,
    lineCap: LineCap,
    lineJoin: LineJoin,
    opacity: Double,
    fillPattern: Option[PatternPaint] = None
)

object CanvasPaint:
  def fromGraphicParams(gp: GraphicParams): CanvasPaint =
    CanvasPaint(
      gp.stroke.map(CanvasColor.fromRgba),
      gp.fill.map(CanvasColor.fromRgba),
      gp.lineWidth,
      CanvasLineDash.fromLineType(gp.lineType),
      gp.lineCap,
      gp.lineJoin,
      gp.alpha,
      gp.fillPattern
    )

  def text(gp: GraphicParams): CanvasPaint =
    val color = gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black)
    CanvasPaint(
      None,
      Some(CanvasColor.fromRgba(color)),
      0.0,
      CanvasLineDash.Solid,
      gp.lineCap,
      gp.lineJoin,
      gp.alpha,
      None
    )

/** Deterministic Canvas 2D operations in device coordinates. Group effects
  * deliberately record rotation before clipping: the clip is installed in
  * the rotated local coordinate system, matching the corresponding SVG group.
  */
enum CanvasCommand:
  case Save(name: Option[GraphicsName])
  case Rotate(degrees: Double, pivotX: Double, pivotY: Double)
  case ClipRect(x: Double, y: Double, width: Double, height: Double)
  case Disc(centerX: Double, centerY: Double, radius: Double, paint: CanvasPaint, name: Option[GraphicsName])
  case Polyline(points: Vector[DevicePoint], closed: Boolean, paint: CanvasPaint, name: Option[GraphicsName])
  case CompoundPolygon(rings: Vector[Vector[DevicePoint]], paint: CanvasPaint, name: Option[GraphicsName])
  case Rectangle(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      paint: CanvasPaint,
      name: Option[GraphicsName]
  )
  case Text(
      label: String,
      x: Double,
      y: Double,
      horizontal: HJust,
      vertical: VJust,
      rotationDegrees: Double,
      fontSizePx: Double,
      fontFamily: Option[String],
      paint: CanvasPaint,
      name: Option[GraphicsName]
  )
  case Image(
      image: RasterImage,
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      interpolation: RasterInterpolation,
      alpha: Double,
      name: Option[GraphicsName]
  )
  case Restore(name: Option[GraphicsName])

final case class CanvasProgram private (
    width: Int,
    height: Int,
    commands: Vector[CanvasCommand]
)

object CanvasProgram:
  private[canvas] def fromDevice(scene: DeviceScene): CanvasProgram =
    val out = Vector.newBuilder[CanvasCommand]
    scene.elements.foreach(appendElement(_, out))
    new CanvasProgram(scene.width.toInt, scene.height.toInt, out.result())

  def validate(program: CanvasProgram): Option[String] =
    var stack = List.empty[Option[GraphicsName]]
    var idx = 0
    var problem: Option[String] = None
    while idx < program.commands.length && problem.isEmpty do
      val command = program.commands(idx)
      command match
        case CanvasCommand.Save(name) =>
          stack = name :: stack
        case CanvasCommand.Restore(name) =>
          stack match
            case expected :: rest if expected == name => stack = rest
            case expected :: _ => problem = Some(s"restore marker $name does not match save marker $expected")
            case Nil           => problem = Some("restore without a matching save")
        case other =>
          problem = firstInvalidNumber(other)
      idx += 1
    problem.orElse(if stack.nonEmpty then Some(s"${stack.length} canvas save operations were not restored") else None)

  private def appendElement(element: DeviceElement, out: scala.collection.mutable.Builder[CanvasCommand, Vector[CanvasCommand]]): Unit =
    element match
      case DeviceElement.Mark(primitive) =>
        out += fromPrimitive(primitive)
      case DeviceElement.Group(name, clip, rotation, children) =>
        out += CanvasCommand.Save(name)
        rotation.foreach(value => out += CanvasCommand.Rotate(value.degrees, value.pivotX, value.pivotY))
        clip.foreach(value => out += CanvasCommand.ClipRect(value.x, value.y, value.width, value.height))
        children.foreach(appendElement(_, out))
        out += CanvasCommand.Restore(name)

  private def fromPrimitive(primitive: DevicePrimitive): CanvasCommand =
    primitive match
      case DevicePrimitive.Disc(centerX, centerY, radius, gp, name) =>
        CanvasCommand.Disc(centerX, centerY, radius, CanvasPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.Polyline(points, closed, gp, name) =>
        CanvasCommand.Polyline(points, closed, CanvasPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.CompoundPolygon(rings, gp, name) =>
        CanvasCommand.CompoundPolygon(rings, CanvasPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.RectShape(x, y, width, height, gp, name) =>
        CanvasCommand.Rectangle(x, y, width, height, CanvasPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.TextRun(label, x, y, horizontal, vertical, rotation, fontSize, fontFamily, gp, name) =>
        CanvasCommand.Text(
          label,
          x,
          y,
          horizontal,
          vertical,
          rotation,
          fontSize,
          fontFamily,
          CanvasPaint.text(gp),
          name
        )
      case DevicePrimitive.Image(image, x, y, width, height, interpolation, alpha, name) =>
        CanvasCommand.Image(image, x, y, width, height, interpolation, alpha, name)

  private def firstInvalidNumber(command: CanvasCommand): Option[String] =
    val values = command match
      case CanvasCommand.Rotate(degrees, pivotX, pivotY) =>
        Vector(degrees, pivotX, pivotY)
      case CanvasCommand.ClipRect(x, y, width, height) =>
        Vector(x, y, width, height)
      case CanvasCommand.Disc(centerX, centerY, radius, paint, _) =>
        Vector(centerX, centerY, radius, paint.lineWidth, paint.opacity)
      case CanvasCommand.Polyline(points, _, paint, _) =>
        points.flatMap(point => Vector(point.x, point.y)) ++ Vector(paint.lineWidth, paint.opacity)
      case CanvasCommand.CompoundPolygon(rings, paint, _) =>
        rings.flatten.flatMap(point => Vector(point.x, point.y)) ++ Vector(paint.lineWidth, paint.opacity)
      case CanvasCommand.Rectangle(x, y, width, height, paint, _) =>
        Vector(x, y, width, height, paint.lineWidth, paint.opacity)
      case CanvasCommand.Text(_, x, y, _, _, rotation, fontSize, _, paint, _) =>
        Vector(x, y, rotation, fontSize, paint.opacity)
      case CanvasCommand.Image(_, x, y, width, height, _, alpha, _) =>
        Vector(x, y, width, height, alpha)
      case CanvasCommand.Save(_) | CanvasCommand.Restore(_) =>
        Vector.empty
    if values.forall(_.isFinite) then None else Some(s"non-finite numeric value in $command")

@js.native
trait CanvasImageData extends js.Object:
  val data: Uint8ClampedArray = js.native

@js.native
trait CanvasImageSource extends js.Object

@js.native
trait CanvasPattern extends js.Object:
  def setTransform(transform: js.Any): Unit = js.native

@js.native
trait CanvasElement extends CanvasImageSource:
  var width: Int = js.native
  var height: Int = js.native
  val ownerDocument: js.Dynamic = js.native
  def getContext(kind: String): CanvasRenderingContext2D = js.native

@js.native
trait CanvasRenderingContext2D extends js.Object:
  val canvas: CanvasElement = js.native
  var strokeStyle: js.Any = js.native
  var fillStyle: js.Any = js.native
  var globalAlpha: Double = js.native
  var lineWidth: Double = js.native
  var lineCap: String = js.native
  var lineJoin: String = js.native
  var font: String = js.native
  var textAlign: String = js.native
  var textBaseline: String = js.native
  var imageSmoothingEnabled: Boolean = js.native

  def save(): Unit = js.native
  def restore(): Unit = js.native
  def beginPath(): Unit = js.native
  def closePath(): Unit = js.native
  def moveTo(x: Double, y: Double): Unit = js.native
  def lineTo(x: Double, y: Double): Unit = js.native
  def rect(x: Double, y: Double, width: Double, height: Double): Unit = js.native
  def arc(x: Double, y: Double, radius: Double, startAngle: Double, endAngle: Double, counterclockwise: Boolean): Unit = js.native
  def fill(): Unit = js.native
  def stroke(): Unit = js.native
  def clip(): Unit = js.native
  def translate(x: Double, y: Double): Unit = js.native
  def rotate(angleRadians: Double): Unit = js.native
  def setLineDash(segments: js.Array[Double]): Unit = js.native
  def fillText(text: String, x: Double, y: Double): Unit = js.native
  def measureText(text: String): CanvasTextMeasurement = js.native
  def createImageData(width: Int, height: Int): CanvasImageData = js.native
  def putImageData(image: CanvasImageData, x: Double, y: Double): Unit = js.native
  def drawImage(image: CanvasImageSource, x: Double, y: Double, width: Double, height: Double): Unit = js.native
  def createPattern(image: CanvasImageSource, repetition: String): CanvasPattern = js.native

trait CanvasRasterFactory:
  def create(image: RasterImage, target: CanvasRenderingContext2D): CanvasImageSource

object CanvasRasterFactory:
  private[canvas] lazy val nativeLittleEndian: Boolean =
    val words = new Uint32Array(1)
    words(0) = 0x01020304
    val bytes = new Uint8Array(words.buffer)
    bytes(0) == 0x04

  private[canvas] def writeRgbaBytes(
      image: RasterImage,
      bytes: Uint8ClampedArray,
      usePackedLittleEndian: Boolean
  ): Unit =
    require(bytes.length >= image.dimensions.pixelCount * 4, "Canvas RGBA destination is too small")
    if usePackedLittleEndian then
      val words = new Uint32Array(bytes.buffer, bytes.byteOffset, image.dimensions.pixelCount)
      var idx = 0
      while idx < image.dimensions.pixelCount do
        words(idx) = rgbaToLittleEndianWord(image.packedAt(idx).toPackedInt)
        idx += 1
    else
      var idx = 0
      while idx < image.dimensions.pixelCount do
        val pixel = image.packedAt(idx)
        val offset = idx * 4
        bytes(offset) = pixel.red
        bytes(offset + 1) = pixel.green
        bytes(offset + 2) = pixel.blue
        bytes(offset + 3) = pixel.alpha
        idx += 1

  private[canvas] inline def rgbaToLittleEndianWord(packed: Int): Int =
    ((packed & 0x000000ff) << 24) |
      ((packed & 0x0000ff00) << 8) |
      ((packed >>> 8) & 0x0000ff00) |
      ((packed >>> 24) & 0x000000ff)

  given browser: CanvasRasterFactory with
    override def create(image: RasterImage, target: CanvasRenderingContext2D): CanvasImageSource =
      val canvas = target.canvas.ownerDocument.createElement("canvas").asInstanceOf[CanvasElement]
      canvas.width = image.width
      canvas.height = image.height
      val imageContext = canvas.getContext("2d")
      val imageData = imageContext.createImageData(image.width, image.height)
      writeRgbaBytes(image, imageData.data, nativeLittleEndian)
      imageContext.putImageData(imageData, 0.0, 0.0)
      canvas

/** Bounded LRU of browser-native image resources, keyed by raster identity.
  * Identity lookup avoids scanning immutable pixel buffers through
  * `RasterImage.equals` and `hashCode` during an interactive redraw.
  */
final class CanvasRasterCache private (val capacity: Int):
  private final case class Entry(image: RasterImage, source: CanvasImageSource)
  private val entries = mutable.ArrayBuffer.empty[Entry]

  def size: Int =
    entries.size

  private[canvas] def resolve(
    image: RasterImage,
    context: CanvasRenderingContext2D
  )(using factory: CanvasRasterFactory): (CanvasImageSource, Boolean) =
    var index = 0
    var found = -1
    while index < entries.length && found < 0 do
      if entries(index).image.eq(image) then found = index
      index += 1
    if found >= 0 then
      val entry = entries.remove(found)
      entries += entry
      entry.source -> true
    else
      val source = factory.create(image, context)
      if capacity > 0 then
        if entries.size >= capacity then entries.remove(0)
        entries += Entry(image, source)
      source -> false

object CanvasRasterCache:
  def make(capacity: Int): Either[CanvasRenderError, CanvasRasterCache] =
    if capacity < 0 then Left(CanvasRenderError.InvalidRasterCacheCapacity(capacity))
    else Right(new CanvasRasterCache(capacity))

  def empty(capacity: Int): CanvasRasterCache =
    make(capacity).orThrow

final case class CanvasDrawProfile(
  imageRequests: Int,
  cacheHits: Int,
  cacheMisses: Int,
  uploadedBytes: Long,
  patternRequests: Int = 0,
  patternCacheHits: Int = 0,
  patternCacheMisses: Int = 0
):
  def hitRate: Double =
    if imageRequests == 0 then 1.0 else cacheHits.toDouble / imageRequests

object CanvasDrawProfile:
  val Zero: CanvasDrawProfile =
    CanvasDrawProfile(0, 0, 0, 0L, 0, 0, 0)

private final class CanvasDrawAccumulator:
  private var imageRequests = 0
  private var cacheHits = 0
  private var cacheMisses = 0
  private var uploadedBytes = 0L
  private var patternRequests = 0
  private var patternCacheHits = 0
  private var patternCacheMisses = 0

  def recordImage(hit: Boolean, bytes: Long): Unit =
    imageRequests += 1
    if hit then cacheHits += 1
    else
      cacheMisses += 1
      uploadedBytes += bytes

  def recordPattern(hit: Boolean): Unit =
    patternRequests += 1
    if hit then patternCacheHits += 1
    else patternCacheMisses += 1

  def result: CanvasDrawProfile =
    CanvasDrawProfile(
      imageRequests,
      cacheHits,
      cacheMisses,
      uploadedBytes,
      patternRequests,
      patternCacheHits,
      patternCacheMisses
    )

private final class CanvasPatternCache:
  private val entries = mutable.ArrayBuffer.empty[(PatternPaint, CanvasPattern)]

  def resolve(
      paint: PatternPaint,
      context: CanvasRenderingContext2D
  )(using factory: CanvasRasterFactory): Either[CanvasRenderError, (CanvasPattern, Boolean)] =
    entries.indexWhere(_._1 == paint) match
      case index if index >= 0 =>
        Right(entries(index)._2 -> true)
      case _ =>
        PatternTile.fromPaint(paint).left.map(CanvasRenderError.Graphics(_)).flatMap { tile =>
          try
            val source = factory.create(tile.image, context)
            val pattern = context.createPattern(source, "repeat")
            if pattern == null then Left(CanvasRenderError.PatternResourceFailure("createPattern returned null"))
            else
              val scaleX = tile.width / tile.image.width.toDouble
              val scaleY = tile.height / tile.image.height.toDouble
              if scaleX != 1.0 || scaleY != 1.0 then
                pattern.setTransform(
                  js.Dynamic.literal(
                    a = scaleX,
                    b = 0.0,
                    c = 0.0,
                    d = scaleY,
                    e = 0.0,
                    f = 0.0
                  )
                )
              entries += paint -> pattern
              Right(pattern -> false)
          catch
            case NonFatal(_) =>
              Left(CanvasRenderError.PatternResourceFailure("native pattern creation threw an exception"))
        }

object CanvasRenderer:
  def compile(scene: Scene, options: CanvasOptions = CanvasOptions.default): Either[CanvasRenderError, CanvasProgram] =
    for
      device <- DeviceContext(options.width.toDouble, options.height.toDouble).left.map(CanvasRenderError.Graphics(_))
      resolved <- DeviceScene.fromScene(scene, device).left.map(CanvasRenderError.Graphics(_))
      _ <- PatternTile.validate(resolved).left.map(CanvasRenderError.Graphics(_))
    yield CanvasProgram.fromDevice(resolved)

  def render(
      scene: Scene,
      context: CanvasRenderingContext2D,
      options: CanvasOptions = CanvasOptions.default
  )(using factory: CanvasRasterFactory): Either[CanvasRenderError, CanvasProgram] =
    compile(scene, options).flatMap { program =>
      drawChecked(program, context).map(_ => program)
    }

  def draw(program: CanvasProgram, context: CanvasRenderingContext2D)(using factory: CanvasRasterFactory): Unit =
    drawChecked(program, context).orThrow

  def drawChecked(
      program: CanvasProgram,
      context: CanvasRenderingContext2D
  )(using factory: CanvasRasterFactory): Either[CanvasRenderError, CanvasDrawProfile] =
    val imageCount = program.commands.count(_.isInstanceOf[CanvasCommand.Image])
    drawCachedChecked(program, context, CanvasRasterCache.empty(imageCount))

  def drawCached(
      program: CanvasProgram,
      context: CanvasRenderingContext2D,
      cache: CanvasRasterCache
  )(using factory: CanvasRasterFactory): CanvasDrawProfile =
    drawCachedChecked(program, context, cache).orThrow

  def drawCachedChecked(
      program: CanvasProgram,
      context: CanvasRenderingContext2D,
      cache: CanvasRasterCache
  )(using factory: CanvasRasterFactory): Either[CanvasRenderError, CanvasDrawProfile] =
    var openGroups = 0
    val accumulator = new CanvasDrawAccumulator
    val patterns = new CanvasPatternCache
    var result: Either[CanvasRenderError, Unit] = Right(())
    try
      var commandIndex = 0
      while commandIndex < program.commands.length && result.isRight do
        val command = program.commands(commandIndex)
        result = execute(command, context, cache, patterns, accumulator)
        if result.isRight then
          command match
            case CanvasCommand.Save(_)    => openGroups += 1
            case CanvasCommand.Restore(_) => openGroups -= 1
            case _                        => ()
        commandIndex += 1
    finally
      while openGroups > 0 do
        context.restore()
        openGroups -= 1
    result.map(_ => accumulator.result)

  private def execute(
      command: CanvasCommand,
      context: CanvasRenderingContext2D,
      cache: CanvasRasterCache,
      patterns: CanvasPatternCache,
      accumulator: CanvasDrawAccumulator
  )(using factory: CanvasRasterFactory): Either[CanvasRenderError, Unit] =
    command match
      case CanvasCommand.Save(_) =>
        context.save()
        Right(())
      case CanvasCommand.Restore(_) =>
        context.restore()
        Right(())
      case CanvasCommand.Rotate(degrees, pivotX, pivotY) =>
        context.translate(pivotX, pivotY)
        context.rotate(degrees * math.Pi / 180.0)
        context.translate(-pivotX, -pivotY)
        Right(())
      case CanvasCommand.ClipRect(x, y, width, height) =>
        context.beginPath()
        context.rect(x, y, width, height)
        context.clip()
        Right(())
      case CanvasCommand.Disc(centerX, centerY, radius, paint, _) =>
        withSavedEither(context) {
          context.beginPath()
          context.arc(centerX, centerY, radius, 0.0, math.Pi * 2.0, false)
          paintPath(context, paint, true, patterns, accumulator)
        }
      case CanvasCommand.Polyline(points, closed, paint, _) =>
        withSavedEither(context) {
          context.beginPath()
          context.moveTo(points.head.x, points.head.y)
          points.tail.foreach(point => context.lineTo(point.x, point.y))
          if closed then context.closePath()
          paintPath(context, paint, closed, patterns, accumulator)
        }
      case CanvasCommand.CompoundPolygon(rings, paint, _) =>
        withSavedEither(context) {
          context.beginPath()
          rings.foreach { ring =>
            context.moveTo(ring.head.x, ring.head.y)
            ring.tail.foreach(point => context.lineTo(point.x, point.y))
            context.closePath()
          }
          paintPath(context, paint, true, patterns, accumulator)
        }
      case CanvasCommand.Rectangle(x, y, width, height, paint, _) =>
        withSavedEither(context) {
          context.beginPath()
          context.rect(x, y, width, height)
          paintPath(context, paint, true, patterns, accumulator)
        }
      case CanvasCommand.Text(label, x, y, horizontal, vertical, rotation, fontSize, fontFamily, paint, _) =>
        withSaved(context) {
          val color = paint.fill.getOrElse(CanvasColor.fromRgba(Rgba.Black))
          context.fillStyle = color.css
          context.globalAlpha = paint.opacity * color.alpha
          context.font = s"${fontSize}px ${canvasFontFamily(fontFamily)}"
          context.textAlign = textAlign(horizontal)
          context.textBaseline = textBaseline(vertical)
          if rotation == 0.0 then context.fillText(label, x, y)
          else
            context.translate(x, y)
            context.rotate(rotation * math.Pi / 180.0)
            context.fillText(label, 0.0, 0.0)
        }
        Right(())
      case CanvasCommand.Image(image, x, y, width, height, interpolation, alpha, _) =>
        val (source, hit) = cache.resolve(image, context)
        withSaved(context) {
          context.globalAlpha = alpha
          context.imageSmoothingEnabled = interpolation == RasterInterpolation.Smooth
          context.drawImage(source, x, y, width, height)
        }
        accumulator.recordImage(hit, image.dimensions.pixelCount.toLong * 4L)
        Right(())

  private def withSaved(context: CanvasRenderingContext2D)(body: => Unit): Unit =
    context.save()
    try body
    finally context.restore()

  private def withSavedEither(
      context: CanvasRenderingContext2D
  )(body: => Either[CanvasRenderError, Unit]): Either[CanvasRenderError, Unit] =
    context.save()
    try body
    finally context.restore()

  private def paintPath(
      context: CanvasRenderingContext2D,
      paint: CanvasPaint,
      allowFill: Boolean,
      patterns: CanvasPatternCache,
      accumulator: CanvasDrawAccumulator
  )(using factory: CanvasRasterFactory): Either[CanvasRenderError, Unit] =
    val filled =
      if !allowFill then Right(())
      else
        paint.fillPattern match
          case Some(pattern) =>
            patterns.resolve(pattern, context).map { case (resource, hit) =>
              accumulator.recordPattern(hit)
              context.fillStyle = resource
              context.globalAlpha = paint.opacity
              context.fill()
            }
          case None =>
            paint.fill.foreach { color =>
              context.fillStyle = color.css
              context.globalAlpha = paint.opacity * color.alpha
              context.fill()
            }
            Right(())
    filled.map { _ =>
      paint.stroke.foreach { color =>
        context.strokeStyle = color.css
        context.globalAlpha = paint.opacity * color.alpha
        context.lineWidth = paint.lineWidth
        context.lineCap = canvasLineCap(paint.lineCap)
        context.lineJoin = canvasLineJoin(paint.lineJoin)
        val dash = paint.dash match
          case CanvasLineDash.Solid           => js.Array[Double]()
          case CanvasLineDash.Pattern(values) => js.Array(values*)
        context.setLineDash(dash)
        context.stroke()
      }
    }

  private def canvasLineCap(value: LineCap): String =
    value match
      case LineCap.Butt   => "butt"
      case LineCap.Round  => "round"
      case LineCap.Square => "square"

  private def canvasLineJoin(value: LineJoin): String =
    value match
      case LineJoin.Miter => "miter"
      case LineJoin.Round => "round"
      case LineJoin.Bevel => "bevel"

  private def canvasFontFamily(value: Option[String]): String =
    value match
      case Some(family) => "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      case None         => "sans-serif"

  private def textAlign(value: HJust): String =
    value match
      case HJust.Left   => "left"
      case HJust.Center => "center"
      case HJust.Right  => "right"

  private def textBaseline(value: VJust): String =
    value match
      case VJust.Bottom => "bottom"
      case VJust.Center => "middle"
      case VJust.Top    => "top"
