package intaglio

import scala.util.control.NonFatal

/** The axis-aligned box a text run occupies, relative to its anchor point.
  *
  * Offsets are in device orientation (y grows downward, as on screen and in every backend):
  * `left`/`top` locate the box's top-left corner relative to the anchor, so a box that extends
  * above the anchor has a negative `top`. `width` and `height` are the extent. Units are those of
  * the measurement the box came from: points when it is computed from a [[TextMetrics]], device
  * pixels when it is computed from a renderer's own measurement.
  *
  * In a y-up scene frame (the default), the box spans scene y from `anchorY - bottom` to
  * `anchorY - top` in the same units.
  */
final case class TextExtent(left: Double, top: Double, width: Double, height: Double):
  def right: Double = left + width
  def bottom: Double = top + height

/** The geometry every backend uses to place a text run, as one pure function.
  *
  * A backend anchors a text run on its ''layout box'': the advance width by the line box (ascent
  * plus descent, plus any leading the provider includes) that `TextMetrics.widthPt` and
  * `TextMetrics.heightPt` report. [[HJust]] positions that box horizontally on its advance width
  * (`Left` puts its left edge at the anchor) and [[VJust]] vertically on its line box (`Top` puts
  * its top edge at the anchor). The box is then rotated about the anchor. Java2D, JavaFX and the
  * browser Canvas all draw rotated text this way, so the axis-aligned extent of a rotated label is
  * a function of the provider's two measurements, the anchor and the angle alone. Java2D and JavaFX
  * position on the same line box their providers report; the browser Canvas positions its top,
  * middle and bottom baselines on the font's em box, which `CanvasTextMetrics.heightPt` (the ink
  * height of "Mg") approximates rather than equals.
  *
  * The layout box is not the ink: glyphs usually sit inside it with side bearings, and italic or
  * accented glyphs can overhang it. The extent is the space layout should reserve, the same box a
  * text plate with no padding fills in Java2D and JavaFX.
  */
object TextExtent:
  override def toString: String = "TextExtent"

  /** The device rotation, in degrees clockwise on screen, of a text run drawn with
    * `rotationDegrees` in a frame whose y axis points `yDirection`. A scene frame is y-up by
    * default, where a positive angle turns counterclockwise, as `Grob.text` documents; device
    * coordinates are y-down, where a positive angle turns clockwise.
    */
  def deviceDegrees(rotationDegrees: Double, yDirection: YDirection): Double =
    yDirection match
      case YDirection.Up   => -rotationDegrees
      case YDirection.Down => rotationDegrees

  /** The unrotated layout box of a `width` by `height` run placed at `anchor`. */
  def anchored(width: Double, height: Double, anchor: Anchor): Either[GraphicsError, TextExtent] =
    rotated(width, height, anchor, 0.0, YDirection.Down)

  /** The axis-aligned extent of a `width` by `height` layout box placed at `anchor` and rotated by
    * `rotationDegrees` about the anchor, in a frame whose y axis points `yDirection` (scene frames
    * are y-up by default). Pass the same angle and anchor given to `Grob.text`.
    *
    * Multiples of 90 degrees are exact. Other angles use the platform's `sin` and `cos`, whose last
    * bit may differ between JVM architectures and JavaScript engines.
    */
  def rotated(
      width: Double,
      height: Double,
      anchor: Anchor,
      rotationDegrees: Double,
      yDirection: YDirection = YDirection.Up
  ): Either[GraphicsError, TextExtent] =
    if !rotationDegrees.isFinite then Left(GraphicsError.InvalidRotation(rotationDegrees))
    else if !width.isFinite || width < 0.0 then
      Left(GraphicsError.InvalidExtent(s"text width must be finite and >= 0, got $width"))
    else if !height.isFinite || height < 0.0 then
      Left(GraphicsError.InvalidExtent(s"text height must be finite and >= 0, got $height"))
    else Right(compute(width, height, anchor, deviceDegrees(rotationDegrees, yDirection)))

  /** Measure `text` with `metrics` and return its rotated extent, in points. A provider that throws
    * is reported as [[GraphicsError.LayoutMeasurementFailed]], as the layout solver reports it.
    */
  def measure(
      metrics: TextMetrics,
      text: String,
      style: TextStyle,
      anchor: Anchor = Anchor.Center,
      rotationDegrees: Double = 0.0,
      yDirection: YDirection = YDirection.Up
  ): Either[GraphicsError, TextExtent] =
    val measured =
      try Right((metrics.widthPt(text, style), metrics.heightPt(style)))
      catch
        case NonFatal(error) =>
          val (exceptionType, detail) = GraphicsError.throwableDetails(error)
          Left(GraphicsError.LayoutMeasurementFailed(exceptionType, detail))
    measured.flatMap((width, height) => rotated(width, height, anchor, rotationDegrees, yDirection))

  private def compute(
      width: Double,
      height: Double,
      anchor: Anchor,
      degrees: Double
  ): TextExtent =
    val x0 = anchor.horizontal match
      case HJust.Left   => 0.0
      case HJust.Center => -width / 2.0
      case HJust.Right  => -width
    val y0 = anchor.vertical match
      case VJust.Top    => 0.0
      case VJust.Center => -height / 2.0
      case VJust.Bottom => -height
    val (cos, sin) = unitVector(degrees)
    // Device rotation by a clockwise-positive angle in y-down coordinates, the transform
    // Graphics2D.rotate, GraphicsContext.rotate and CanvasRenderingContext2D.rotate apply.
    def x(u: Double, v: Double): Double = u * cos - v * sin
    def y(u: Double, v: Double): Double = u * sin + v * cos
    val xs = Array(x(x0, y0), x(x0 + width, y0), x(x0, y0 + height), x(x0 + width, y0 + height))
    val ys = Array(y(x0, y0), y(x0 + width, y0), y(x0, y0 + height), y(x0 + width, y0 + height))
    val left = xs.min
    val top = ys.min
    TextExtent(left, top, xs.max - left, ys.max - top)

  private def unitVector(degrees: Double): (Double, Double) =
    val normalized = ((degrees % 360.0) + 360.0) % 360.0
    if normalized == 0.0 then (1.0, 0.0)
    else if normalized == 90.0 then (0.0, 1.0)
    else if normalized == 180.0 then (-1.0, 0.0)
    else if normalized == 270.0 then (0.0, -1.0)
    else
      val radians = normalized * math.Pi / 180.0
      (math.cos(radians), math.sin(radians))
