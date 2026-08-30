package intaglio.javafx

import scala.collection.mutable
import intaglio.*

final case class JavaFxOptions private (
    width: Int,
    height: Int,
    pixelsPerInch: Double,
    deviceScale: Double
):
  def logicalWidth: Double = width.toDouble / deviceScale
  def logicalHeight: Double = height.toDouble / deviceScale

object JavaFxOptions:
  val default: JavaFxOptions =
    unsafe()

  def apply(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0
  ): Either[JavaFxRenderError, JavaFxOptions] =
    if width <= 0 || height <= 0 then Left(JavaFxRenderError.InvalidCanvasSize(width, height))
    else
      RenderContext(width, height, pixelsPerInch, deviceScale = deviceScale).left
        .map(JavaFxRenderError.Graphics(_))
        .map(_ => new JavaFxOptions(width, height, pixelsPerInch, deviceScale))

  def unsafe(
      width: Int = 640,
      height: Int = 480,
      pixelsPerInch: Double = 96.0,
      deviceScale: Double = 1.0
  ): JavaFxOptions =
    apply(width, height, pixelsPerInch, deviceScale).orThrow

enum JavaFxRenderError extends IntaglioError:
  case InvalidCanvasSize(width: Int, height: Int)
  case Graphics(error: GraphicsError)

  def message: String =
    this match
      case InvalidCanvasSize(width, height) =>
        s"JavaFX canvas size must be positive: ${width}x$height"
      case Graphics(error) =>
        error.message

object JavaFxRenderError:
  extension [A](either: Either[JavaFxRenderError, A])
    def orThrow: A =
      either match
        case Right(value) => value
        case Left(error)  => throw new IllegalArgumentException(error.message)

final case class JavaFxColor(red: Int, green: Int, blue: Int, alpha: Double):
  private[javafx] def combined(opacity: Double): JavaFxColor =
    copy(alpha = (alpha * opacity).max(0.0).min(1.0))

object JavaFxColor:
  def fromRgba(color: Rgba): JavaFxColor =
    JavaFxColor(color.red, color.green, color.blue, color.alpha)

enum JavaFxLineDash:
  case Solid
  case Pattern(values: Vector[Double])

object JavaFxLineDash:
  def fromLineType(lineType: LineType): JavaFxLineDash =
    lineType match
      case LineType.Solid  => JavaFxLineDash.Solid
      case LineType.Dashed => JavaFxLineDash.Pattern(Vector(6.0, 4.0))
      case LineType.Dotted => JavaFxLineDash.Pattern(Vector(1.0, 3.0))

final case class JavaFxPaint(
    stroke: Option[JavaFxColor],
    fill: Option[JavaFxColor],
    lineWidth: Double,
    dash: JavaFxLineDash,
    lineCap: LineCap,
    lineJoin: LineJoin,
    opacity: Double,
    fillPattern: Option[PatternPaint] = None
):
  /** Binary bridge for callers compiled before pattern fills were added. */
  def this(
      stroke: Option[JavaFxColor],
      fill: Option[JavaFxColor],
      lineWidth: Double,
      dash: JavaFxLineDash,
      lineCap: LineCap,
      lineJoin: LineJoin,
      opacity: Double
  ) = this(stroke, fill, lineWidth, dash, lineCap, lineJoin, opacity, None)

  /** Binary bridge for the former seven-field case-class copy descriptor. */
  def copy(
      stroke: Option[JavaFxColor],
      fill: Option[JavaFxColor],
      lineWidth: Double,
      dash: JavaFxLineDash,
      lineCap: LineCap,
      lineJoin: LineJoin,
      opacity: Double
  ): JavaFxPaint =
    new JavaFxPaint(stroke, fill, lineWidth, dash, lineCap, lineJoin, opacity, None)

object JavaFxPaint:
  /** Binary bridge for the former seven-field case-class apply descriptor. */
  def apply(
      stroke: Option[JavaFxColor],
      fill: Option[JavaFxColor],
      lineWidth: Double,
      dash: JavaFxLineDash,
      lineCap: LineCap,
      lineJoin: LineJoin,
      opacity: Double
  ): JavaFxPaint =
    new JavaFxPaint(stroke, fill, lineWidth, dash, lineCap, lineJoin, opacity, None)

  def fromGraphicParams(gp: GraphicParams): JavaFxPaint =
    JavaFxPaint(
      gp.stroke.map(JavaFxColor.fromRgba),
      gp.fill.map(JavaFxColor.fromRgba),
      gp.lineWidth,
      JavaFxLineDash.fromLineType(gp.lineType),
      gp.lineCap,
      gp.lineJoin,
      gp.alpha,
      gp.fillPattern
    )

  def text(gp: GraphicParams): JavaFxPaint =
    val color = gp.fill.orElse(gp.stroke).getOrElse(Rgba.Black)
    JavaFxPaint(
      None,
      Some(JavaFxColor.fromRgba(color)),
      0.0,
      JavaFxLineDash.Solid,
      gp.lineCap,
      gp.lineJoin,
      gp.alpha,
      None
    )

final case class JavaFxDrawProfile(
    patternRequests: Int,
    patternCacheHits: Int,
    patternCacheMisses: Int
)

private final class JavaFxDrawAccumulator:
  private var patternRequests = 0
  private var patternCacheHits = 0
  private var patternCacheMisses = 0

  def recordPattern(hit: Boolean): Unit =
    patternRequests += 1
    if hit then patternCacheHits += 1
    else patternCacheMisses += 1

  def result: JavaFxDrawProfile =
    JavaFxDrawProfile(patternRequests, patternCacheHits, patternCacheMisses)

/** Deterministic JavaFX Canvas operations in device coordinates. Group effects deliberately record
  * rotation before clipping: the clip is installed in the rotated local coordinate system, matching
  * the SVG and Canvas backends.
  */
enum JavaFxCommand:
  case Save(name: Option[GraphicsName])
  case Rotate(degrees: Double, pivotX: Double, pivotY: Double)
  case ClipRect(x: Double, y: Double, width: Double, height: Double)
  case Disc(
      centerX: Double,
      centerY: Double,
      radius: Double,
      paint: JavaFxPaint,
      name: Option[GraphicsName]
  )
  case Polyline(
      points: Vector[DevicePoint],
      closed: Boolean,
      paint: JavaFxPaint,
      name: Option[GraphicsName]
  )
  case CompoundPolygon(
      rings: Vector[Vector[DevicePoint]],
      paint: JavaFxPaint,
      name: Option[GraphicsName]
  )
  case Rectangle(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      paint: JavaFxPaint,
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
      paint: JavaFxPaint,
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

final case class JavaFxProgram private (
    width: Int,
    height: Int,
    pixelsPerInch: Double,
    deviceScale: Double,
    logicalWidth: Double,
    logicalHeight: Double,
    commands: Vector[JavaFxCommand]
)

object JavaFxProgram:
  private[javafx] def fromDevice(scene: DeviceScene, context: RenderContext): JavaFxProgram =
    val out = Vector.newBuilder[JavaFxCommand]
    scene.elements.foreach(appendElement(_, out))
    new JavaFxProgram(
      scene.width.toInt,
      scene.height.toInt,
      context.pixelsPerInch,
      context.deviceScale,
      context.logicalWidth,
      context.logicalHeight,
      out.result()
    )

  def validate(program: JavaFxProgram): Option[String] =
    var stack = List.empty[Option[GraphicsName]]
    var idx = 0
    var problem: Option[String] = None
    while idx < program.commands.length && problem.isEmpty do
      val command = program.commands(idx)
      command match
        case JavaFxCommand.Save(name) =>
          stack = name :: stack
        case JavaFxCommand.Restore(name) =>
          stack match
            case expected :: rest if expected == name => stack = rest
            case expected :: _                        =>
              problem = Some(s"restore marker $name does not match save marker $expected")
            case Nil => problem = Some("restore without a matching save")
        case other =>
          problem = firstInvalidNumber(other)
      idx += 1
    problem.orElse(if stack.nonEmpty then
      Some(s"${stack.length} JavaFX save operations were not restored")
    else None)

  private def appendElement(
      element: DeviceElement,
      out: scala.collection.mutable.Builder[JavaFxCommand, Vector[JavaFxCommand]]
  ): Unit =
    element match
      case DeviceElement.Mark(primitive) =>
        out += fromPrimitive(primitive)
      case DeviceElement.Group(name, clip, rotation, children) =>
        out += JavaFxCommand.Save(name)
        rotation.foreach(value =>
          out += JavaFxCommand.Rotate(value.degrees, value.pivotX, value.pivotY)
        )
        clip.foreach(value =>
          out += JavaFxCommand.ClipRect(value.x, value.y, value.width, value.height)
        )
        children.foreach(appendElement(_, out))
        out += JavaFxCommand.Restore(name)

  private def fromPrimitive(primitive: DevicePrimitive): JavaFxCommand =
    primitive match
      case DevicePrimitive.Disc(centerX, centerY, radius, gp, name) =>
        JavaFxCommand.Disc(centerX, centerY, radius, JavaFxPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.Polyline(points, closed, gp, name) =>
        JavaFxCommand.Polyline(points, closed, JavaFxPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.CompoundPolygon(rings, gp, name) =>
        JavaFxCommand.CompoundPolygon(rings, JavaFxPaint.fromGraphicParams(gp), name)
      case DevicePrimitive.RectShape(x, y, width, height, gp, name) =>
        JavaFxCommand.Rectangle(x, y, width, height, JavaFxPaint.fromGraphicParams(gp), name)
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
        JavaFxCommand.Text(
          label,
          x,
          y,
          horizontal,
          vertical,
          rotation,
          fontSize,
          fontFamily,
          JavaFxPaint.text(gp),
          name
        )
      case DevicePrimitive.Image(image, x, y, width, height, interpolation, alpha, name) =>
        JavaFxCommand.Image(image, x, y, width, height, interpolation, alpha, name)

  private def firstInvalidNumber(command: JavaFxCommand): Option[String] =
    val values = command match
      case JavaFxCommand.Rotate(degrees, pivotX, pivotY) =>
        Vector(degrees, pivotX, pivotY)
      case JavaFxCommand.ClipRect(x, y, width, height) =>
        Vector(x, y, width, height)
      case JavaFxCommand.Disc(centerX, centerY, radius, paint, _) =>
        Vector(centerX, centerY, radius, paint.lineWidth, paint.opacity)
      case JavaFxCommand.Polyline(points, _, paint, _) =>
        points.flatMap(point => Vector(point.x, point.y)) ++ Vector(paint.lineWidth, paint.opacity)
      case JavaFxCommand.CompoundPolygon(rings, paint, _) =>
        rings.flatten.flatMap(point => Vector(point.x, point.y)) ++ Vector(
          paint.lineWidth,
          paint.opacity
        )
      case JavaFxCommand.Rectangle(x, y, width, height, paint, _) =>
        Vector(x, y, width, height, paint.lineWidth, paint.opacity)
      case JavaFxCommand.Text(_, x, y, _, _, rotation, fontSize, _, paint, _) =>
        Vector(x, y, rotation, fontSize, paint.opacity)
      case JavaFxCommand.Image(_, x, y, width, height, _, alpha, _) =>
        Vector(x, y, width, height, alpha)
      case JavaFxCommand.Save(_) | JavaFxCommand.Restore(_) =>
        Vector.empty
    if values.forall(_.isFinite) then None else Some(s"non-finite numeric value in $command")

/** Toolkit-free drawing contract the interpreter targets. The one production implementation is
  * [[JavaFxCanvasContext]], a thin adapter over a live `javafx.scene.canvas.GraphicsContext`; tests
  * substitute recording implementations so the interpreter is exercised without starting the JavaFX
  * toolkit. Angles are degrees, clockwise-positive in device (y-down) space, matching
  * `GraphicsContext.rotate`.
  */
trait JavaFxGraphicsContext:
  def save(): Unit
  def restore(): Unit
  def translate(x: Double, y: Double): Unit
  def rotateDegrees(degrees: Double): Unit
  def beginPath(): Unit
  def moveTo(x: Double, y: Double): Unit
  def lineTo(x: Double, y: Double): Unit
  def closePath(): Unit
  def rect(x: Double, y: Double, width: Double, height: Double): Unit
  def clip(): Unit
  def fillPath(): Unit
  def strokePath(): Unit
  def fillOval(x: Double, y: Double, width: Double, height: Double): Unit
  def strokeOval(x: Double, y: Double, width: Double, height: Double): Unit
  def setFill(color: JavaFxColor): Unit

  /** Install a native pattern fill and report whether its cached resource was reused. */
  def setPatternFill(pattern: PatternPaint): Boolean
  def setStroke(color: JavaFxColor): Unit
  def setLineWidth(width: Double): Unit
  def setLineCap(cap: LineCap): Unit
  def setLineJoin(join: LineJoin): Unit

  /** An empty pattern means solid strokes. */
  def setLineDashes(pattern: Vector[Double]): Unit
  def setFont(family: Option[String], sizePx: Double): Unit
  def setTextAlign(horizontal: HJust): Unit
  def setTextBaseline(vertical: VJust): Unit
  def fillText(label: String, x: Double, y: Double): Unit
  def setGlobalAlpha(alpha: Double): Unit
  def setImageSmoothing(enabled: Boolean): Unit
  def drawImage(image: RasterImage, x: Double, y: Double, width: Double, height: Double): Unit

private[javafx] object JavaFxRaster:
  /** Row-major top-left ARGB pixels matching `PixelFormat.getIntArgbInstance`. */
  def argb(image: RasterImage): Array[Int] =
    val pixels = new Array[Int](image.dimensions.pixelCount)
    var idx = 0
    while idx < pixels.length do
      val pixel = image.packedAt(idx)
      pixels(idx) = (pixel.alpha << 24) | (pixel.red << 16) | (pixel.green << 8) | pixel.blue
      idx += 1
    pixels

object JavaFxRenderer:
  def compile(plan: RenderPlan): Either[JavaFxRenderError, JavaFxProgram] =
    for
      resolved <- plan.deviceScene.left.map(JavaFxRenderError.Graphics(_))
      _ <- PatternTile.validate(resolved).left.map(JavaFxRenderError.Graphics(_))
    yield JavaFxProgram.fromDevice(resolved, plan.context)

  def compile(
      scene: Scene,
      options: JavaFxOptions = JavaFxOptions.default
  ): Either[JavaFxRenderError, JavaFxProgram] =
    for
      context <- RenderContext(
        options.width,
        options.height,
        options.pixelsPerInch,
        deviceScale = options.deviceScale
      ).left
        .map(JavaFxRenderError.Graphics(_))
      program <- compile(RenderPlan(scene, context))
    yield program

  def render(
      plan: RenderPlan,
      context: JavaFxGraphicsContext
  ): Either[JavaFxRenderError, JavaFxProgram] =
    compile(plan).map { program =>
      draw(program, context)
      program
    }

  def render(
      scene: Scene,
      context: JavaFxGraphicsContext,
      options: JavaFxOptions = JavaFxOptions.default
  ): Either[JavaFxRenderError, JavaFxProgram] =
    compile(scene, options).map { program =>
      draw(program, context)
      program
    }

  def draw(program: JavaFxProgram, context: JavaFxGraphicsContext): Unit =
    drawProfile(program, context)

  def drawProfile(program: JavaFxProgram, context: JavaFxGraphicsContext): JavaFxDrawProfile =
    var openGroups = 0
    val accumulator = new JavaFxDrawAccumulator
    try
      program.commands.foreach {
        case JavaFxCommand.Save(_) =>
          context.save()
          openGroups += 1
        case JavaFxCommand.Restore(_) =>
          context.restore()
          openGroups -= 1
        case command =>
          execute(command, context, accumulator)
      }
    finally
      while openGroups > 0 do
        context.restore()
        openGroups -= 1
    accumulator.result

  private def execute(
      command: JavaFxCommand,
      context: JavaFxGraphicsContext,
      accumulator: JavaFxDrawAccumulator
  ): Unit =
    command match
      case JavaFxCommand.Save(_) | JavaFxCommand.Restore(_) =>
        ()
      case JavaFxCommand.Rotate(degrees, pivotX, pivotY) =>
        context.translate(pivotX, pivotY)
        context.rotateDegrees(degrees)
        context.translate(-pivotX, -pivotY)
      case JavaFxCommand.ClipRect(x, y, width, height) =>
        context.beginPath()
        context.rect(x, y, width, height)
        context.clip()
      case JavaFxCommand.Disc(centerX, centerY, radius, paint, _) =>
        withSaved(context) {
          val x = centerX - radius
          val y = centerY - radius
          val size = radius * 2.0
          fill(context, paint, accumulator) {
            context.fillOval(x, y, size, size)
          }
          paint.stroke.foreach { color =>
            strokeState(context, paint, color)
            context.strokeOval(x, y, size, size)
          }
        }
      case JavaFxCommand.Polyline(points, closed, paint, _) =>
        withSaved(context) {
          context.beginPath()
          context.moveTo(points.head.x, points.head.y)
          points.tail.foreach(point => context.lineTo(point.x, point.y))
          if closed then context.closePath()
          paintPath(context, paint, closed, accumulator)
        }
      case JavaFxCommand.CompoundPolygon(rings, paint, _) =>
        withSaved(context) {
          context.beginPath()
          rings.foreach { ring =>
            context.moveTo(ring.head.x, ring.head.y)
            ring.tail.foreach(point => context.lineTo(point.x, point.y))
            context.closePath()
          }
          paintPath(context, paint, true, accumulator)
        }
      case JavaFxCommand.Rectangle(x, y, width, height, paint, _) =>
        withSaved(context) {
          context.beginPath()
          context.rect(x, y, width, height)
          paintPath(context, paint, true, accumulator)
        }
      case JavaFxCommand.Text(
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
        withSaved(context) {
          val color = paint.fill.getOrElse(JavaFxColor.fromRgba(Rgba.Black))
          context.setFill(color.combined(paint.opacity))
          context.setFont(fontFamily, fontSize)
          context.setTextAlign(horizontal)
          context.setTextBaseline(vertical)
          if rotation == 0.0 then context.fillText(label, x, y)
          else
            context.translate(x, y)
            context.rotateDegrees(rotation)
            context.fillText(label, 0.0, 0.0)
        }
      case JavaFxCommand.Image(image, x, y, width, height, interpolation, alpha, _) =>
        withSaved(context) {
          context.setGlobalAlpha(alpha)
          context.setImageSmoothing(interpolation == RasterInterpolation.Smooth)
          context.drawImage(image, x, y, width, height)
        }

  private def withSaved(context: JavaFxGraphicsContext)(body: => Unit): Unit =
    context.save()
    try body
    finally context.restore()

  private def paintPath(
      context: JavaFxGraphicsContext,
      paint: JavaFxPaint,
      allowFill: Boolean,
      accumulator: JavaFxDrawAccumulator
  ): Unit =
    if allowFill then fill(context, paint, accumulator)(context.fillPath())
    paint.stroke.foreach { color =>
      strokeState(context, paint, color)
      context.strokePath()
    }

  private def fill(
      context: JavaFxGraphicsContext,
      paint: JavaFxPaint,
      accumulator: JavaFxDrawAccumulator
  )(draw: => Unit): Unit =
    paint.fillPattern match
      case Some(pattern) =>
        accumulator.recordPattern(context.setPatternFill(pattern))
        context.setGlobalAlpha(paint.opacity)
        try draw
        finally context.setGlobalAlpha(1.0)
      case None =>
        paint.fill.foreach { color =>
          context.setFill(color.combined(paint.opacity))
          draw
        }

  /** JavaFX save/restore does not cover all stroke geometry, so every stroke installs the complete
    * backend-neutral state explicitly.
    */
  private def strokeState(
      context: JavaFxGraphicsContext,
      paint: JavaFxPaint,
      color: JavaFxColor
  ): Unit =
    context.setStroke(color.combined(paint.opacity))
    context.setLineWidth(paint.lineWidth)
    context.setLineCap(paint.lineCap)
    context.setLineJoin(paint.lineJoin)
    paint.dash match
      case JavaFxLineDash.Solid =>
        context.setLineDashes(Vector.empty)
      case JavaFxLineDash.Pattern(values) =>
        context.setLineDashes(values)
