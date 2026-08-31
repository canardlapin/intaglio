package intaglio.java2d

import java.io.ByteArrayOutputStream
import java.awt.{
  AlphaComposite,
  BasicStroke,
  Color,
  Font,
  Graphics2D,
  RenderingHints,
  Shape,
  TexturePaint
}
import java.awt.geom.{AffineTransform, Ellipse2D, Path2D, Rectangle2D}
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import scala.collection.mutable
import scala.util.control.NonFatal
import intaglio.*

final case class Java2DOptions private (
    width: Int,
    height: Int,
    pixelsPerInch: Double,
    deviceScale: Double
):
  def logicalWidth: Double = width.toDouble / deviceScale
  def logicalHeight: Double = height.toDouble / deviceScale

object Java2DOptions:
  val default: Java2DOptions =
    unsafe()

  def apply(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0
  ): Either[Java2DRenderError, Java2DOptions] =
    if width <= 0 || height <= 0 then Left(Java2DRenderError.InvalidImageSize(width, height))
    else
      RenderContext(width, height, pixelsPerInch, deviceScale = deviceScale).left
        .map(Java2DRenderError.Graphics(_))
        .map(_ => new Java2DOptions(width, height, pixelsPerInch, deviceScale))

  def unsafe(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0
  ): Java2DOptions =
    apply(width, height, pixelsPerInch, deviceScale).orThrow

enum Java2DAntialiasing:
  case Enabled
  case Disabled

final case class Java2DRenderingHints(
    geometry: Java2DAntialiasing = Java2DAntialiasing.Enabled,
    text: Java2DAntialiasing = Java2DAntialiasing.Enabled
):
  private[java2d] def configure(graphics: Graphics2D): Unit =
    graphics.setRenderingHint(
      RenderingHints.KEY_ANTIALIASING,
      geometry match
        case Java2DAntialiasing.Enabled  => RenderingHints.VALUE_ANTIALIAS_ON
        case Java2DAntialiasing.Disabled => RenderingHints.VALUE_ANTIALIAS_OFF
    )
    graphics.setRenderingHint(
      RenderingHints.KEY_TEXT_ANTIALIASING,
      text match
        case Java2DAntialiasing.Enabled  => RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        case Java2DAntialiasing.Disabled => RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
    )

object Java2DRenderingHints:
  val default: Java2DRenderingHints = Java2DRenderingHints()

/** Resolves a device text run to one concrete immutable AWT font. The system resolver preserves
  * ordinary Java2D behavior; `fixed` lets reproducible exports and golden tests supply font bytes
  * without consulting host-installed families.
  */
final class Java2DFontResolver private (resolveFont: (Option[String], Double) => Font):
  private[java2d] def resolve(requestedFamily: Option[String], sizePx: Double): Font =
    resolveFont(requestedFamily, sizePx)

object Java2DFontResolver:
  val system: Java2DFontResolver =
    new Java2DFontResolver((requestedFamily, sizePx) =>
      val family = requestedFamily.getOrElse(Font.SANS_SERIF)
      new Font(family, Font.PLAIN, 1).deriveFont(sizePx.toFloat)
    )

  def fixed(font: Font): Java2DFontResolver =
    new Java2DFontResolver((_, sizePx) => font.deriveFont(sizePx.toFloat))

enum Java2DBackground:
  case Transparent
  case Solid(color: Rgba)

final case class Java2DExportOptions(
    background: Java2DBackground = Java2DBackground.Transparent,
    renderingHints: Java2DRenderingHints = Java2DRenderingHints.default
)

object Java2DExportOptions:
  val default: Java2DExportOptions = Java2DExportOptions()

enum Java2DRenderError extends IntaglioError:
  case InvalidImageSize(width: Int, height: Int)
  case Graphics(error: GraphicsError)
  case PngEncodingUnavailable
  case PngEncodingFailed(details: String)

  def message: String =
    this match
      case InvalidImageSize(width, height) =>
        s"Java2D image size must be positive: ${width}x$height"
      case Graphics(error) =>
        error.message
      case PngEncodingUnavailable =>
        "No PNG ImageIO writer is available in this JVM"
      case PngEncodingFailed(details) =>
        s"PNG encoding failed: $details"

object Java2DRenderError:
  extension [A](either: Either[Java2DRenderError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

final case class Java2DColor(red: Int, green: Int, blue: Int, alpha: Double):
  private[java2d] def awt(opacity: Double): Color =
    val combined = math.round(alpha * opacity * 255.0).toInt.max(0).min(255)
    new Color(red, green, blue, combined)

object Java2DColor:
  def fromRgba(color: Rgba): Java2DColor =
    Java2DColor(color.red, color.green, color.blue, color.alpha)

enum Java2DLineDash:
  case Solid
  case Pattern(values: Vector[Float])

object Java2DLineDash:
  def fromLineType(lineType: LineType): Java2DLineDash =
    lineType match
      case LineType.Solid  => Java2DLineDash.Solid
      case LineType.Dashed => Java2DLineDash.Pattern(Vector(6.0f, 4.0f))
      case LineType.Dotted => Java2DLineDash.Pattern(Vector(1.0f, 3.0f))

final case class Java2DPaint(
    stroke: Option[Java2DColor],
    fill: Option[Java2DColor],
    lineWidth: Double,
    dash: Java2DLineDash,
    lineCap: LineCap,
    lineJoin: LineJoin,
    opacity: Double,
    fillPattern: Option[PatternPaint] = None
):
  /** Binary bridge for callers compiled before pattern fills were added. */
  def this(
      stroke: Option[Java2DColor],
      fill: Option[Java2DColor],
      lineWidth: Double,
      dash: Java2DLineDash,
      lineCap: LineCap,
      lineJoin: LineJoin,
      opacity: Double
  ) = this(stroke, fill, lineWidth, dash, lineCap, lineJoin, opacity, None)

  /** Binary bridge for the former seven-field case-class copy descriptor. */
  def copy(
      stroke: Option[Java2DColor],
      fill: Option[Java2DColor],
      lineWidth: Double,
      dash: Java2DLineDash,
      lineCap: LineCap,
      lineJoin: LineJoin,
      opacity: Double
  ): Java2DPaint =
    new Java2DPaint(stroke, fill, lineWidth, dash, lineCap, lineJoin, opacity, None)

object Java2DPaint:
  /** Binary bridge for the former seven-field case-class apply descriptor. */
  def apply(
      stroke: Option[Java2DColor],
      fill: Option[Java2DColor],
      lineWidth: Double,
      dash: Java2DLineDash,
      lineCap: LineCap,
      lineJoin: LineJoin,
      opacity: Double
  ): Java2DPaint =
    new Java2DPaint(stroke, fill, lineWidth, dash, lineCap, lineJoin, opacity, None)

  def fromGraphicParams(gp: GraphicParams): Java2DPaint =
    Java2DPaint(
      gp.stroke.map(Java2DColor.fromRgba),
      gp.fill.map(Java2DColor.fromRgba),
      gp.lineWidth,
      Java2DLineDash.fromLineType(gp.lineType),
      gp.lineCap,
      gp.lineJoin,
      gp.alpha,
      gp.fillPattern
    )

  def text(gp: GraphicParams): Java2DPaint =
    val color = gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black)
    Java2DPaint(
      None,
      Some(Java2DColor.fromRgba(color)),
      0.0,
      Java2DLineDash.Solid,
      gp.lineCap,
      gp.lineJoin,
      gp.alpha,
      None
    )

final case class Java2DDrawProfile(
    patternRequests: Int,
    patternCacheHits: Int,
    patternCacheMisses: Int
)

private final class Java2DDrawAccumulator:
  private var patternRequests = 0
  private var patternCacheHits = 0
  private var patternCacheMisses = 0

  def recordPattern(hit: Boolean): Unit =
    patternRequests += 1
    if hit then patternCacheHits += 1
    else patternCacheMisses += 1

  def result: Java2DDrawProfile =
    Java2DDrawProfile(patternRequests, patternCacheHits, patternCacheMisses)

enum Java2DCommand:
  case Save(name: Option[GraphicsName])
  case Rotate(degrees: Double, pivotX: Double, pivotY: Double)
  case ClipRect(x: Double, y: Double, width: Double, height: Double)
  case Disc(
      centerX: Double,
      centerY: Double,
      radius: Double,
      paint: Java2DPaint,
      name: Option[GraphicsName]
  )
  case PointBatch(
      points: Vector[DevicePoint],
      radii: BatchColumn[Double],
      shapes: BatchColumn[PointShape],
      paints: BatchColumn[Java2DPaint],
      name: Option[GraphicsName]
  )
  case Polyline(
      points: Vector[DevicePoint],
      closed: Boolean,
      paint: Java2DPaint,
      name: Option[GraphicsName]
  )
  case CompoundPolygon(
      rings: Vector[Vector[DevicePoint]],
      paint: Java2DPaint,
      name: Option[GraphicsName]
  )
  case Rectangle(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      paint: Java2DPaint,
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
      paint: Java2DPaint,
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

final case class Java2DProgram private (
    width: Int,
    height: Int,
    pixelsPerInch: Double,
    deviceScale: Double,
    logicalWidth: Double,
    logicalHeight: Double,
    commands: Vector[Java2DCommand]
)

object Java2DProgram:
  private[java2d] def fromDevice(scene: DeviceScene, context: RenderContext): Java2DProgram =
    val out = Vector.newBuilder[Java2DCommand]
    scene.elements.foreach(appendElement(_, out))
    new Java2DProgram(
      scene.width.toInt,
      scene.height.toInt,
      context.pixelsPerInch,
      context.deviceScale,
      context.logicalWidth,
      context.logicalHeight,
      out.result()
    )

  def validate(program: Java2DProgram): Option[String] =
    var stack = List.empty[Option[GraphicsName]]
    var idx = 0
    var problem: Option[String] = None
    while idx < program.commands.length && problem.isEmpty do
      val command = program.commands(idx)
      command match
        case Java2DCommand.Save(name) =>
          stack = name :: stack
        case Java2DCommand.Restore(name) =>
          stack match
            case expected :: rest if expected == name => stack = rest
            case expected :: _                        =>
              problem = Some(s"restore marker $name does not match save marker $expected")
            case Nil => problem = Some("restore without a matching save")
        case other =>
          problem = firstInvalidNumber(other)
      idx += 1
    problem.orElse(if stack.nonEmpty then
      Some(s"${stack.length} Java2D save operations were not restored")
    else None)

  private def appendElement(
      element: DeviceElement,
      out: scala.collection.mutable.Builder[Java2DCommand, Vector[Java2DCommand]]
  ): Unit =
    element match
      case DeviceElement.Mark(primitive) =>
        out += fromPrimitive(primitive)
      case DeviceElement.Group(name, clip, rotation, children) =>
        out += Java2DCommand.Save(name)
        rotation.foreach(value =>
          out += Java2DCommand.Rotate(value.degrees, value.pivotX, value.pivotY)
        )
        clip.foreach(value =>
          out += Java2DCommand.ClipRect(value.x, value.y, value.width, value.height)
        )
        children.foreach(appendElement(_, out))
        out += Java2DCommand.Restore(name)

  private def fromPrimitive(primitive: DevicePrimitive): Java2DCommand =
    primitive match
      case DevicePrimitive.Disc(centerX, centerY, radius, gp, name) =>
        Java2DCommand.Disc(centerX, centerY, radius, Java2DPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.PointBatch(points, radii, shapes, params, name) =>
        Java2DCommand.PointBatch(
          points,
          radii,
          shapes,
          params.map(Java2DPaint.fromGraphicParams),
          name
        )
      case DevicePrimitive.Polyline(points, closed, gp, name) =>
        Java2DCommand.Polyline(points, closed, Java2DPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.CompoundPolygon(rings, gp, name) =>
        Java2DCommand.CompoundPolygon(rings, Java2DPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.RectShape(x, y, width, height, gp, name) =>
        Java2DCommand.Rectangle(x, y, width, height, Java2DPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.TextRun(
            label,
            x,
            y,
            horizontal,
            vertical,
            rotation,
            fontSize,
            fontFamily,
            gp,
            name
          ) =>
        Java2DCommand.Text(
          label,
          x,
          y,
          horizontal,
          vertical,
          rotation,
          fontSize,
          fontFamily,
          Java2DPaint.text(gp),
          name
        )
      case DevicePrimitive.Image(image, x, y, width, height, interpolation, alpha, name) =>
        Java2DCommand.Image(image, x, y, width, height, interpolation, alpha, name)

  private def firstInvalidNumber(command: Java2DCommand): Option[String] =
    command match
      case Java2DCommand.PointBatch(points, radii, _, paints, _) =>
        var index = 0
        var invalid = false
        while index < points.length && !invalid do
          val point = points(index)
          val paint = paints.valueAt(index)
          invalid = !point.x.isFinite || !point.y.isFinite || !radii.valueAt(index).isFinite ||
            !paint.lineWidth.isFinite || !paint.opacity.isFinite
          index += 1
        Option.when(invalid)(s"non-finite numeric value in $command")
      case other =>
        val values = other match
          case Java2DCommand.Rotate(degrees, pivotX, pivotY) =>
            Vector(degrees, pivotX, pivotY)
          case Java2DCommand.ClipRect(x, y, width, height) =>
            Vector(x, y, width, height)
          case Java2DCommand.Disc(centerX, centerY, radius, paint, _) =>
            Vector(centerX, centerY, radius, paint.lineWidth, paint.opacity)
          case Java2DCommand.Polyline(points, _, paint, _) =>
            points.flatMap(point => Vector(point.x, point.y)) ++ Vector(
              paint.lineWidth,
              paint.opacity
            )
          case Java2DCommand.CompoundPolygon(rings, paint, _) =>
            rings.flatten.flatMap(point => Vector(point.x, point.y)) ++ Vector(
              paint.lineWidth,
              paint.opacity
            )
          case Java2DCommand.Rectangle(x, y, width, height, paint, _) =>
            Vector(x, y, width, height, paint.lineWidth, paint.opacity)
          case Java2DCommand.Text(_, x, y, _, _, rotation, fontSize, _, paint, _) =>
            Vector(x, y, rotation, fontSize, paint.opacity)
          case Java2DCommand.Image(_, x, y, width, height, _, alpha, _) =>
            Vector(x, y, width, height, alpha)
          case Java2DCommand.Save(_) | Java2DCommand.Restore(_) =>
            Vector.empty
          case _: Java2DCommand.PointBatch =>
            Vector.empty
        if values.forall(_.isFinite) then None else Some(s"non-finite numeric value in $command")

object Java2DRenderer:
  def compile(plan: RenderPlan): Either[Java2DRenderError, Java2DProgram] =
    for
      resolved <- plan.deviceScene.left.map(Java2DRenderError.Graphics(_))
      _ <- PatternTile.validate(resolved).left.map(Java2DRenderError.Graphics(_))
    yield Java2DProgram.fromDevice(resolved, plan.context)

  def compile(
      scene: Scene,
      options: Java2DOptions = Java2DOptions.default
  ): Either[Java2DRenderError, Java2DProgram] =
    for
      context <- RenderContext(
        options.width,
        options.height,
        options.pixelsPerInch,
        deviceScale = options.deviceScale
      ).left
        .map(Java2DRenderError.Graphics(_))
      program <- compile(RenderPlan(scene, context))
    yield program

  def render(
      plan: RenderPlan,
      graphics: Graphics2D
  ): Either[Java2DRenderError, Java2DProgram] =
    compile(plan).map { program =>
      draw(program, graphics)
      program
    }

  def render(
      scene: Scene,
      graphics: Graphics2D,
      options: Java2DOptions = Java2DOptions.default
  ): Either[Java2DRenderError, Java2DProgram] =
    compile(scene, options).map { program =>
      draw(program, graphics)
      program
    }

  /** Render a target-bound scene to an ARGB image.
    *
    * The [[RenderPlan]] is the single source of truth for actual pixel dimensions, density, text
    * metrics, and font-family resolution. Export options add only background and Java2D hint
    * policy.
    */
  def renderImage(
      plan: RenderPlan,
      options: Java2DExportOptions = Java2DExportOptions.default
  ): Either[Java2DRenderError, BufferedImage] =
    renderImage(plan, options, Java2DFontResolver.system)

  def renderImage(
      plan: RenderPlan,
      options: Java2DExportOptions,
      fontResolver: Java2DFontResolver
  ): Either[Java2DRenderError, BufferedImage] =
    compile(plan).map(program => renderImage(program, options, fontResolver))

  /** Convenience image export using the portable default metrics and requested font families. */
  def renderImage(scene: Scene): Either[Java2DRenderError, BufferedImage] =
    renderImage(scene, Java2DOptions.default, Java2DExportOptions.default)

  def renderImage(
      scene: Scene,
      renderOptions: Java2DOptions
  ): Either[Java2DRenderError, BufferedImage] =
    renderImage(scene, renderOptions, Java2DExportOptions.default)

  def renderImage(
      scene: Scene,
      renderOptions: Java2DOptions,
      exportOptions: Java2DExportOptions
  ): Either[Java2DRenderError, BufferedImage] =
    compile(scene, renderOptions).map(program =>
      renderImage(program, exportOptions, Java2DFontResolver.system)
    )

  /** Encode a target-bound scene as PNG bytes. */
  def renderPng(
      plan: RenderPlan,
      options: Java2DExportOptions = Java2DExportOptions.default
  ): Either[Java2DRenderError, Array[Byte]] =
    renderPng(plan, options, Java2DFontResolver.system)

  def renderPng(
      plan: RenderPlan,
      options: Java2DExportOptions,
      fontResolver: Java2DFontResolver
  ): Either[Java2DRenderError, Array[Byte]] =
    renderImage(plan, options, fontResolver).flatMap(encodePng)

  /** Convenience PNG export using the portable default metrics and requested font families. */
  def renderPng(scene: Scene): Either[Java2DRenderError, Array[Byte]] =
    renderPng(scene, Java2DOptions.default, Java2DExportOptions.default)

  def renderPng(
      scene: Scene,
      renderOptions: Java2DOptions
  ): Either[Java2DRenderError, Array[Byte]] =
    renderPng(scene, renderOptions, Java2DExportOptions.default)

  def renderPng(
      scene: Scene,
      renderOptions: Java2DOptions,
      exportOptions: Java2DExportOptions
  ): Either[Java2DRenderError, Array[Byte]] =
    renderImage(scene, renderOptions, exportOptions).flatMap(encodePng)

  def draw(program: Java2DProgram, graphics: Graphics2D): Unit =
    draw(program, graphics, Java2DRenderingHints.default, Java2DFontResolver.system)

  def draw(
      program: Java2DProgram,
      graphics: Graphics2D,
      renderingHints: Java2DRenderingHints
  ): Unit =
    draw(program, graphics, renderingHints, Java2DFontResolver.system)

  def draw(
      program: Java2DProgram,
      graphics: Graphics2D,
      renderingHints: Java2DRenderingHints,
      fontResolver: Java2DFontResolver
  ): Unit =
    drawProfile(program, graphics, renderingHints, fontResolver)

  def drawProfile(program: Java2DProgram, graphics: Graphics2D): Java2DDrawProfile =
    drawProfile(
      program,
      graphics,
      Java2DRenderingHints.default,
      Java2DFontResolver.system
    )

  def drawProfile(
      program: Java2DProgram,
      graphics: Graphics2D,
      renderingHints: Java2DRenderingHints
  ): Java2DDrawProfile =
    drawProfile(program, graphics, renderingHints, Java2DFontResolver.system)

  def drawProfile(
      program: Java2DProgram,
      graphics: Graphics2D,
      renderingHints: Java2DRenderingHints,
      fontResolver: Java2DFontResolver
  ): Java2DDrawProfile =
    var stack = List(graphics)
    val images = mutable.HashMap.empty[RasterImage, BufferedImage]
    val patterns = mutable.HashMap.empty[PatternPaint, TexturePaint]
    val accumulator = new Java2DDrawAccumulator
    try
      program.commands.foreach {
        case Java2DCommand.Save(_) =>
          stack = stack.head.create().asInstanceOf[Graphics2D] :: stack
        case Java2DCommand.Restore(_) =>
          stack.head.dispose()
          stack = stack.tail
        case command =>
          execute(
            command,
            stack.head,
            images,
            patterns,
            accumulator,
            renderingHints,
            fontResolver
          )
      }
    finally
      stack.takeWhile(_ ne graphics).foreach(_.dispose())
    accumulator.result

  private def execute(
      command: Java2DCommand,
      graphics: Graphics2D,
      images: mutable.Map[RasterImage, BufferedImage],
      patterns: mutable.Map[PatternPaint, TexturePaint],
      accumulator: Java2DDrawAccumulator,
      renderingHints: Java2DRenderingHints,
      fontResolver: Java2DFontResolver
  ): Unit =
    command match
      case Java2DCommand.Rotate(degrees, pivotX, pivotY) =>
        graphics.rotate(degrees * math.Pi / 180.0, pivotX, pivotY)
      case Java2DCommand.ClipRect(x, y, width, height) =>
        graphics.clip(new Rectangle2D.Double(x, y, width, height))
      case Java2DCommand.Disc(centerX, centerY, radius, paint, _) =>
        paintShape(
          graphics,
          new Ellipse2D.Double(centerX - radius, centerY - radius, radius * 2.0, radius * 2.0),
          paint,
          true,
          patterns,
          accumulator,
          renderingHints
        )
      case Java2DCommand.PointBatch(points, radii, shapes, paints, _) =>
        var index = 0
        while index < points.length do
          drawPointMark(
            graphics,
            points(index),
            radii.valueAt(index),
            shapes.valueAt(index),
            paints.valueAt(index),
            patterns,
            accumulator,
            renderingHints
          )
          index += 1
      case Java2DCommand.Polyline(points, closed, paint, _) =>
        val path = new Path2D.Double()
        path.moveTo(points.head.x, points.head.y)
        points.tail.foreach(point => path.lineTo(point.x, point.y))
        if closed then path.closePath()
        paintShape(graphics, path, paint, closed, patterns, accumulator, renderingHints)
      case Java2DCommand.CompoundPolygon(rings, paint, _) =>
        val path = new Path2D.Double(Path2D.WIND_NON_ZERO)
        rings.foreach { ring =>
          path.moveTo(ring.head.x, ring.head.y)
          ring.tail.foreach(point => path.lineTo(point.x, point.y))
          path.closePath()
        }
        paintShape(graphics, path, paint, true, patterns, accumulator, renderingHints)
      case Java2DCommand.Rectangle(x, y, width, height, paint, _) =>
        paintShape(
          graphics,
          new Rectangle2D.Double(x, y, width, height),
          paint,
          true,
          patterns,
          accumulator,
          renderingHints
        )
      case Java2DCommand.Text(
            label,
            x,
            y,
            horizontal,
            vertical,
            rotation,
            fontSize,
            fontFamily,
            paint,
            _
          ) =>
        withCopy(graphics) { copy =>
          renderingHints.configure(copy)
          val font = fontResolver.resolve(fontFamily, fontSize)
          copy.setFont(font)
          val bounds = font.getStringBounds(label, copy.getFontRenderContext)
          val drawX = horizontal match
            case HJust.Left   => x
            case HJust.Center => x - bounds.getWidth / 2.0
            case HJust.Right  => x - bounds.getWidth
          val baseline = vertical match
            case VJust.Top    => y - bounds.getY
            case VJust.Center => y - (bounds.getY + bounds.getHeight / 2.0)
            case VJust.Bottom => y - (bounds.getY + bounds.getHeight)
          if rotation != 0.0 then copy.rotate(rotation * math.Pi / 180.0, x, y)
          val color = paint.fill.getOrElse(Java2DColor.fromRgba(Rgba.Black))
          copy.setColor(color.awt(paint.opacity))
          copy.drawString(label, drawX.toFloat, baseline.toFloat)
        }
      case Java2DCommand.Image(image, x, y, width, height, interpolation, alpha, _) =>
        withCopy(graphics) { copy =>
          val hint = interpolation match
            case RasterInterpolation.Nearest => RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            case RasterInterpolation.Smooth  => RenderingHints.VALUE_INTERPOLATION_BILINEAR
          copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint)
          copy.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.toFloat))
          val source = images.getOrElseUpdate(image, buffered(image))
          val transform = new AffineTransform()
          transform.translate(x, y)
          transform.scale(width / image.width.toDouble, height / image.height.toDouble)
          copy.drawImage(source, transform, null)
        }
      case Java2DCommand.Save(_) | Java2DCommand.Restore(_) =>
        ()

  private def drawPointMark(
      graphics: Graphics2D,
      point: DevicePoint,
      radius: Double,
      shape: PointShape,
      paint: Java2DPaint,
      patterns: mutable.Map[PatternPaint, TexturePaint],
      accumulator: Java2DDrawAccumulator,
      renderingHints: Java2DRenderingHints
  ): Unit =
    shape match
      case PointShape.Circle =>
        paintShape(
          graphics,
          new Ellipse2D.Double(point.x - radius, point.y - radius, radius * 2.0, radius * 2.0),
          paint,
          true,
          patterns,
          accumulator,
          renderingHints
        )
      case PointShape.Square =>
        paintShape(
          graphics,
          new Rectangle2D.Double(point.x - radius, point.y - radius, radius * 2.0, radius * 2.0),
          paint,
          true,
          patterns,
          accumulator,
          renderingHints
        )
      case PointShape.Triangle =>
        val path = new Path2D.Double()
        path.moveTo(point.x, point.y - radius)
        path.lineTo(point.x + radius, point.y + radius)
        path.lineTo(point.x - radius, point.y + radius)
        path.closePath()
        paintShape(graphics, path, paint, true, patterns, accumulator, renderingHints)
      case PointShape.Cross =>
        paintPointLine(
          graphics,
          point.x - radius,
          point.y,
          point.x + radius,
          point.y,
          paint,
          patterns,
          accumulator,
          renderingHints
        )
        paintPointLine(
          graphics,
          point.x,
          point.y - radius,
          point.x,
          point.y + radius,
          paint,
          patterns,
          accumulator,
          renderingHints
        )

  private def paintPointLine(
      graphics: Graphics2D,
      x0: Double,
      y0: Double,
      x1: Double,
      y1: Double,
      paint: Java2DPaint,
      patterns: mutable.Map[PatternPaint, TexturePaint],
      accumulator: Java2DDrawAccumulator,
      renderingHints: Java2DRenderingHints
  ): Unit =
    val path = new Path2D.Double()
    path.moveTo(x0, y0)
    path.lineTo(x1, y1)
    paintShape(graphics, path, paint, false, patterns, accumulator, renderingHints)

  private def paintShape(
      graphics: Graphics2D,
      shape: Shape,
      paint: Java2DPaint,
      allowFill: Boolean,
      patterns: mutable.Map[PatternPaint, TexturePaint],
      accumulator: Java2DDrawAccumulator,
      renderingHints: Java2DRenderingHints
  ): Unit =
    withCopy(graphics) { copy =>
      renderingHints.configure(copy)
      if allowFill then
        paint.fillPattern match
          case Some(pattern) =>
            copy.setComposite(
              AlphaComposite.getInstance(AlphaComposite.SRC_OVER, paint.opacity.toFloat)
            )
            copy.setPaint(resolvePattern(pattern, patterns, accumulator))
            copy.fill(shape)
          case None =>
            paint.fill.foreach { color =>
              copy.setColor(color.awt(paint.opacity))
              copy.fill(shape)
            }
      paint.stroke.foreach { color =>
        copy.setComposite(AlphaComposite.SrcOver)
        copy.setColor(color.awt(paint.opacity))
        copy.setStroke(stroke(paint))
        copy.draw(shape)
      }
    }

  private def resolvePattern(
      paint: PatternPaint,
      patterns: mutable.Map[PatternPaint, TexturePaint],
      accumulator: Java2DDrawAccumulator
  ): TexturePaint =
    patterns.get(paint) match
      case Some(pattern) =>
        accumulator.recordPattern(hit = true)
        pattern
      case None =>
        val tile = PatternTile
          .fromPaint(paint)
          .fold(error => throw new IllegalStateException(error.message), identity)
        val pattern = new TexturePaint(
          buffered(tile.image),
          new Rectangle2D.Double(0.0, 0.0, tile.width, tile.height)
        )
        patterns.update(paint, pattern)
        accumulator.recordPattern(hit = false)
        pattern

  private def stroke(paint: Java2DPaint): BasicStroke =
    val cap = paint.lineCap match
      case LineCap.Butt   => BasicStroke.CAP_BUTT
      case LineCap.Round  => BasicStroke.CAP_ROUND
      case LineCap.Square => BasicStroke.CAP_SQUARE
    val join = paint.lineJoin match
      case LineJoin.Miter => BasicStroke.JOIN_MITER
      case LineJoin.Round => BasicStroke.JOIN_ROUND
      case LineJoin.Bevel => BasicStroke.JOIN_BEVEL
    paint.dash match
      case Java2DLineDash.Solid =>
        new BasicStroke(paint.lineWidth.toFloat, cap, join)
      case Java2DLineDash.Pattern(values) =>
        new BasicStroke(
          paint.lineWidth.toFloat,
          cap,
          join,
          10.0f,
          values.toArray,
          0.0f
        )

  private def renderImage(
      program: Java2DProgram,
      options: Java2DExportOptions,
      fontResolver: Java2DFontResolver
  ): BufferedImage =
    val image = new BufferedImage(program.width, program.height, BufferedImage.TYPE_INT_ARGB)
    options.background match
      case Java2DBackground.Transparent  => ()
      case Java2DBackground.Solid(color) =>
        val graphics = image.createGraphics()
        try
          graphics.setComposite(AlphaComposite.Src)
          graphics.setColor(Java2DColor.fromRgba(color).awt(1.0))
          graphics.fillRect(0, 0, program.width, program.height)
        finally graphics.dispose()
    val graphics = image.createGraphics()
    try draw(program, graphics, options.renderingHints, fontResolver)
    finally graphics.dispose()
    image

  private def encodePng(image: BufferedImage): Either[Java2DRenderError, Array[Byte]] =
    val output = new ByteArrayOutputStream()
    try
      if ImageIO.write(image, "png", output) then Right(output.toByteArray)
      else Left(Java2DRenderError.PngEncodingUnavailable)
    catch
      case NonFatal(error) =>
        Left(
          Java2DRenderError.PngEncodingFailed(
            Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
          )
        )
    finally output.close()

  private def buffered(image: RasterImage): BufferedImage =
    val output = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val pixels = new Array[Int](image.dimensions.pixelCount)
    var idx = 0
    while idx < pixels.length do
      val pixel = image.packedAt(idx)
      pixels(idx) = (pixel.alpha << 24) | (pixel.red << 16) | (pixel.green << 8) | pixel.blue
      idx += 1
    output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    output

  private def withCopy(graphics: Graphics2D)(body: Graphics2D => Unit): Unit =
    val copy = graphics.create().asInstanceOf[Graphics2D]
    try body(copy)
    finally copy.dispose()
