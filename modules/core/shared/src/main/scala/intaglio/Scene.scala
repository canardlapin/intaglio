package intaglio

enum LengthUnit:
  case Npc
  case Native
  case Cm
  case Mm
  case Inch
  case Point
  case Line

final case class Length private (value: Double, unit: LengthUnit):
  require(value.isFinite, "`value` must be finite")

object Length:
  def apply(value: Double, unit: LengthUnit): Either[GraphicsError, Length] =
    if value.isFinite then Right(new Length(value, unit))
    else Left(GraphicsError.InvalidLength(value))

  def unsafe(value: Double, unit: LengthUnit): Length =
    apply(value, unit).orThrow

  def npc(value: Double): Either[GraphicsError, Length] =
    apply(value, LengthUnit.Npc)

  def npcUnsafe(value: Double): Length =
    npc(value).orThrow

  def native(value: Double): Either[GraphicsError, Length] =
    apply(value, LengthUnit.Native)

  def nativeUnsafe(value: Double): Length =
    native(value).orThrow

  def points(value: Double): Either[GraphicsError, Length] =
    apply(value, LengthUnit.Point)

  def pointsUnsafe(value: Double): Length =
    points(value).orThrow

  def lines(value: Double): Either[GraphicsError, Length] =
    apply(value, LengthUnit.Line)

  def linesUnsafe(value: Double): Length =
    lines(value).orThrow

sealed trait LengthExpr:
  def +(that: LengthExpr): LengthExpr =
    LengthExpr.Add(this, that)

  def -(that: LengthExpr): LengthExpr =
    LengthExpr.Sub(this, that)

  /** Translate a location by an extent. Unlike adding two raw length expressions, native units in
    * `that` resolve as a delta rather than as a second location in the frame's scale.
    */
  def +(that: ExtentExpr): LengthExpr =
    LengthExpr.Offset(this, that, 1.0)

  def -(that: ExtentExpr): LengthExpr =
    LengthExpr.Offset(this, that, -1.0)

  def times(factor: Double): Either[GraphicsError, LengthExpr] =
    if factor.isFinite then Right(LengthExpr.Mul(factor, this))
    else Left(GraphicsError.InvalidLength(factor))

object LengthExpr:
  final case class Const(length: Length) extends LengthExpr
  final case class Add private[intaglio] (left: LengthExpr, right: LengthExpr) extends LengthExpr
  final case class Sub private[intaglio] (left: LengthExpr, right: LengthExpr) extends LengthExpr
  final case class Offset private[intaglio] (
      location: LengthExpr,
      extent: ExtentExpr,
      direction: Double
  ) extends LengthExpr:
    require(direction.isFinite, "`direction` must be finite")
  final case class Mul private[intaglio] (factor: Double, value: LengthExpr) extends LengthExpr:
    require(factor.isFinite, "`factor` must be finite")

  def apply(length: Length): LengthExpr =
    Const(length)

  def npc(value: Double): Either[GraphicsError, LengthExpr] =
    Length.npc(value).map(Const(_))

  def npcUnsafe(value: Double): LengthExpr =
    npc(value).orThrow

  def native(value: Double): Either[GraphicsError, LengthExpr] =
    Length.native(value).map(Const(_))

  def nativeUnsafe(value: Double): LengthExpr =
    native(value).orThrow

  def lines(value: Double): Either[GraphicsError, LengthExpr] =
    Length.lines(value).map(Const(_))

  def linesUnsafe(value: Double): LengthExpr =
    lines(value).orThrow

final case class ExtentExpr private (expr: LengthExpr):
  def +(that: ExtentExpr): ExtentExpr =
    ExtentExpr.unsafe(expr + that.expr)

  def times(factor: Double): Either[GraphicsError, ExtentExpr] =
    if !factor.isFinite then Left(GraphicsError.InvalidLength(factor))
    else if factor < 0.0 then Left(GraphicsError.InvalidExtent(factor.toString))
    else Right(ExtentExpr.unsafe(LengthExpr.Mul(factor, expr)))

object ExtentExpr:
  def apply(length: Length): Either[GraphicsError, ExtentExpr] =
    fromExpr(LengthExpr(length))

  def fromExpr(expr: LengthExpr): Either[GraphicsError, ExtentExpr] =
    if isProvablyNonNegative(expr) then Right(new ExtentExpr(expr))
    else Left(GraphicsError.InvalidExtent(describe(expr)))

  def unsafe(expr: LengthExpr): ExtentExpr =
    fromExpr(expr).orThrow

  def unsafe(length: Length): ExtentExpr =
    apply(length).orThrow

  def npc(value: Double): Either[GraphicsError, ExtentExpr] =
    Length.npc(value).flatMap(apply)

  def npcUnsafe(value: Double): ExtentExpr =
    npc(value).orThrow

  def native(value: Double): Either[GraphicsError, ExtentExpr] =
    Length.native(value).flatMap(apply)

  def nativeUnsafe(value: Double): ExtentExpr =
    native(value).orThrow

  def points(value: Double): Either[GraphicsError, ExtentExpr] =
    Length.points(value).flatMap(apply)

  def pointsUnsafe(value: Double): ExtentExpr =
    points(value).orThrow

  def lines(value: Double): Either[GraphicsError, ExtentExpr] =
    Length.lines(value).flatMap(apply)

  def linesUnsafe(value: Double): ExtentExpr =
    lines(value).orThrow

  private def isProvablyNonNegative(expr: LengthExpr): Boolean =
    expr match
      case LengthExpr.Const(length) =>
        length.value >= 0.0
      case LengthExpr.Add(left, right) =>
        isProvablyNonNegative(left) && isProvablyNonNegative(right)
      case LengthExpr.Sub(_, _) =>
        false
      case LengthExpr.Offset(_, _, _) =>
        false
      case LengthExpr.Mul(factor, value) =>
        factor >= 0.0 && isProvablyNonNegative(value)

  private def describe(expr: LengthExpr): String =
    expr match
      case LengthExpr.Const(length)   => s"${length.value} ${length.unit}"
      case LengthExpr.Add(_, _)       => "sum expression"
      case LengthExpr.Sub(_, _)       => "difference expression"
      case LengthExpr.Offset(_, _, _) => "location-offset expression"
      case LengthExpr.Mul(_, _)       => "scaled expression"

final case class Point(x: LengthExpr, y: LengthExpr)

object Point:
  def npc(x: Double, y: Double): Either[GraphicsError, Point] =
    for
      px <- LengthExpr.npc(x)
      py <- LengthExpr.npc(y)
    yield Point(px, py)

  def npcUnsafe(x: Double, y: Double): Point =
    npc(x, y).orThrow

  def native(x: Double, y: Double): Either[GraphicsError, Point] =
    for
      px <- LengthExpr.native(x)
      py <- LengthExpr.native(y)
    yield Point(px, py)

  def nativeUnsafe(x: Double, y: Double): Point =
    native(x, y).orThrow

final case class Size private (width: ExtentExpr, height: ExtentExpr)

object Size:
  def fromExtents(width: ExtentExpr, height: ExtentExpr): Size =
    new Size(width, height)

  def npc(width: Double, height: Double): Either[GraphicsError, Size] =
    for
      w <- ExtentExpr.npc(width)
      h <- ExtentExpr.npc(height)
    yield new Size(w, h)

  def npcUnsafe(width: Double, height: Double): Size =
    npc(width, height).orThrow

enum HJust:
  case Left
  case Center
  case Right

enum VJust:
  case Bottom
  case Center
  case Top

final case class Anchor(horizontal: HJust, vertical: VJust)

object Anchor:
  val Center: Anchor =
    Anchor(HJust.Center, VJust.Center)

  val BottomLeft: Anchor =
    Anchor(HJust.Left, VJust.Bottom)

final case class Rgba private (red: Int, green: Int, blue: Int, alpha: Double):
  require(red >= 0 && red <= 255, "`red` must be in [0, 255]")
  require(green >= 0 && green <= 255, "`green` must be in [0, 255]")
  require(blue >= 0 && blue <= 255, "`blue` must be in [0, 255]")
  require(alpha.isFinite && alpha >= 0.0 && alpha <= 1.0, "`alpha` must be in [0, 1]")

  def withAlpha(value: Double): Either[GraphicsError, Rgba] =
    Rgba(red, green, blue, value)

object Rgba:
  def apply(red: Int, green: Int, blue: Int, alpha: Double = 1.0): Either[GraphicsError, Rgba] =
    if red < 0 || red > 255 then Left(GraphicsError.InvalidColorChannel("red", red))
    else if green < 0 || green > 255 then Left(GraphicsError.InvalidColorChannel("green", green))
    else if blue < 0 || blue > 255 then Left(GraphicsError.InvalidColorChannel("blue", blue))
    else if !alpha.isFinite || alpha < 0.0 || alpha > 1.0 then
      Left(GraphicsError.InvalidAlpha(alpha))
    else Right(new Rgba(red, green, blue, alpha))

  def unsafe(red: Int, green: Int, blue: Int, alpha: Double = 1.0): Rgba =
    apply(red, green, blue, alpha).orThrow

  val Black: Rgba =
    unsafe(0, 0, 0)

  val White: Rgba =
    unsafe(255, 255, 255)

  val Transparent: Rgba =
    unsafe(0, 0, 0, 0.0)

enum RuleOrientation:
  case Horizontal
  case Vertical

/** A finite, backend-neutral recipe for a repeated fill pattern.
  *
  * Spacing, line width, and radius are measured in device pixels. Hatch angles are clockwise
  * degrees from a vertical rule in the device's y-down coordinate system. Tiles start at `(0, 0)`
  * in the current device coordinate system, repeat without shape-local re-anchoring, and follow
  * enclosing viewport transforms. These semantics intentionally do not admit backend objects,
  * callbacks, CSS, or raw SVG.
  */
sealed trait PatternRecipe:
  def spacing: Double

object PatternRecipe:
  final case class AngledHatch private[PatternRecipe] (
      angleDegrees: Double,
      spacing: Double,
      lineWidth: Double
  ) extends PatternRecipe

  final case class CrossHatch private[PatternRecipe] (
      angleDegrees: Double,
      spacing: Double,
      lineWidth: Double
  ) extends PatternRecipe

  final case class ParallelRules private[PatternRecipe] (
      orientation: RuleOrientation,
      spacing: Double,
      lineWidth: Double
  ) extends PatternRecipe

  final case class Stipple private[PatternRecipe] (
      spacing: Double,
      radius: Double
  ) extends PatternRecipe

  def angledHatch(
      angleDegrees: Double,
      spacing: Double,
      lineWidth: Double
  ): Either[GraphicsError, AngledHatch] =
    for
      _ <- finiteAngle("angled hatch", angleDegrees)
      _ <- positive("angled hatch", "spacing", spacing)
      _ <- positive("angled hatch", "line width", lineWidth)
    yield new AngledHatch(angleDegrees, spacing, lineWidth)

  def crossHatch(
      angleDegrees: Double,
      spacing: Double,
      lineWidth: Double
  ): Either[GraphicsError, CrossHatch] =
    for
      _ <- finiteAngle("cross-hatch", angleDegrees)
      _ <- positive("cross-hatch", "spacing", spacing)
      _ <- positive("cross-hatch", "line width", lineWidth)
    yield new CrossHatch(angleDegrees, spacing, lineWidth)

  def parallelRules(
      orientation: RuleOrientation,
      spacing: Double,
      lineWidth: Double
  ): Either[GraphicsError, ParallelRules] =
    for
      _ <- positive("parallel rules", "spacing", spacing)
      _ <- positive("parallel rules", "line width", lineWidth)
    yield new ParallelRules(orientation, spacing, lineWidth)

  def stipple(spacing: Double, radius: Double): Either[GraphicsError, Stipple] =
    for
      _ <- positive("stipple", "spacing", spacing)
      _ <- positive("stipple", "radius", radius)
      _ <-
        if radius <= spacing / 2.0 then Right(())
        else
          Left(
            GraphicsError.InvalidPatternParameter(
              "stipple",
              "radius",
              radius,
              "no greater than half its spacing"
            )
          )
    yield new Stipple(spacing, radius)

  private def finiteAngle(recipe: String, value: Double): Either[GraphicsError, Unit] =
    if value.isFinite then Right(())
    else Left(GraphicsError.InvalidPatternParameter(recipe, "angle", value, "finite"))

  private def positive(
      recipe: String,
      parameter: String,
      value: Double
  ): Either[GraphicsError, Unit] =
    if value.isFinite && value > 0.0 then Right(())
    else Left(GraphicsError.InvalidPatternParameter(recipe, parameter, value, "finite and > 0"))

/** Complete paint for one pattern fill.
  *
  * Ink and optional background retain their own RGBA values. A mark's [[GraphicParams.alpha]] is
  * applied once to the composited pattern, so it multiplies the final ink/background result rather
  * than replacing either channel alpha. Equality covers the full recipe and both colors, which lets
  * renderers reuse resources without relying on object identity.
  */
final case class PatternPaint(
    recipe: PatternRecipe,
    ink: Rgba,
    background: Option[Rgba] = None
)

/** Unit carried by a stroke width until device lowering. */
enum StrokeUnit:
  /** A literal device pixel, independent of DPI and device scale. */
  case DevicePixel

  /** A physical point, converted at the render context's actual pixels per inch. */
  case Point

/** Checked stroke-width value whose unit cannot be confused at a backend boundary. */
final case class StrokeWidth private (value: Double, unit: StrokeUnit):
  require(value.isFinite && value >= 0.0, "`value` must be finite and >= 0")

object StrokeWidth:
  def checked(value: Double, unit: StrokeUnit): Either[GraphicsError, StrokeWidth] =
    if !value.isFinite || value < 0.0 then Left(GraphicsError.InvalidLineWidth(value))
    else Right(new StrokeWidth(value, unit))

  def unsafe(value: Double, unit: StrokeUnit): StrokeWidth =
    checked(value, unit).orThrow

  def devicePixels(value: Double): Either[GraphicsError, StrokeWidth] =
    checked(value, StrokeUnit.DevicePixel)

  def devicePixelsUnsafe(value: Double): StrokeWidth =
    devicePixels(value).orThrow

  def points(value: Double): Either[GraphicsError, StrokeWidth] =
    checked(value, StrokeUnit.Point)

  def pointsUnsafe(value: Double): StrokeWidth =
    points(value).orThrow

final case class GraphicParams private (
    stroke: Option[Rgba] = Some(Rgba.Black),
    fill: Option[Rgba] = None,
    lineWidth: Double = 1.0,
    lineType: LineType = LineType.Solid,
    lineCap: LineCap = LineCap.Butt,
    lineJoin: LineJoin = LineJoin.Miter,
    alpha: Double = 1.0,
    fontFamily: Option[String] = None,
    fontSize: Length = Length.pointsUnsafe(12.0),
    fillPattern: Option[PatternPaint] = None,
    lineWidthUnit: StrokeUnit = StrokeUnit.DevicePixel
):
  /** Retains the pre-pattern JVM constructor descriptor for compiled callers; Scala callers still
    * enter through the checked companion constructors.
    */
  private[intaglio] def this(
      stroke: Option[Rgba],
      fill: Option[Rgba],
      lineWidth: Double,
      lineType: LineType,
      lineCap: LineCap,
      lineJoin: LineJoin,
      alpha: Double,
      fontFamily: Option[String],
      fontSize: Length
  ) =
    this(
      stroke,
      fill,
      lineWidth,
      lineType,
      lineCap,
      lineJoin,
      alpha,
      fontFamily,
      fontSize,
      None,
      StrokeUnit.DevicePixel
    )

  /** Retains the pattern-era constructor descriptor while adding typed stroke units. */
  private[intaglio] def this(
      stroke: Option[Rgba],
      fill: Option[Rgba],
      lineWidth: Double,
      lineType: LineType,
      lineCap: LineCap,
      lineJoin: LineJoin,
      alpha: Double,
      fontFamily: Option[String],
      fontSize: Length,
      fillPattern: Option[PatternPaint]
  ) =
    this(
      stroke,
      fill,
      lineWidth,
      lineType,
      lineCap,
      lineJoin,
      alpha,
      fontFamily,
      fontSize,
      fillPattern,
      StrokeUnit.DevicePixel
    )

  require(lineWidth.isFinite && lineWidth >= 0.0, "`lineWidth` must be finite and >= 0")
  require(alpha.isFinite && alpha >= 0.0 && alpha <= 1.0, "`alpha` must be in [0, 1]")

  def strokeWidth: StrokeWidth =
    StrokeWidth.unsafe(lineWidth, lineWidthUnit)

  /** Replace the legacy device-pixel width with an explicitly typed stroke width. */
  def withStrokeWidth(value: StrokeWidth): GraphicParams =
    copy(lineWidth = value.value, lineWidthUnit = value.unit)

  /** Apply row-mapped style channels while preserving every other parameter. `None` means that the
    * corresponding aesthetic is not mapped for this row.
    */
  private[intaglio] def withAestheticOverrides(
      stroke: Option[Rgba] = None,
      fill: Option[Rgba] = None,
      alpha: Option[Double] = None,
      lineType: Option[LineType] = None,
      lineWidthPoints: Option[Double] = None
  ): Either[GraphicsError, GraphicParams] =
    for
      _ <- alpha match
        case Some(value) if !value.isFinite || value < 0.0 || value > 1.0 =>
          Left(GraphicsError.InvalidAlpha(value))
        case _ => Right(())
      mappedWidth <- lineWidthPoints match
        case Some(value) => StrokeWidth.points(value).map(Some(_))
        case None        => Right(None)
    yield
      val styled =
        copy(
          stroke = stroke.orElse(this.stroke),
          fill = fill.orElse(this.fill),
          alpha = alpha.getOrElse(this.alpha),
          lineType = lineType.getOrElse(this.lineType),
          fillPattern = if fill.isDefined then None else this.fillPattern
        )
      mappedWidth.fold(styled)(styled.withStrokeWidth)

  /** Replace the solid fill channel with a validated pattern paint. */
  def withPatternFill(pattern: PatternPaint): GraphicParams =
    copy(fill = None, fillPattern = Some(pattern))

  /** Replace the pattern fill channel with an ordinary optional solid fill. */
  def withSolidFill(color: Option[Rgba]): GraphicParams =
    copy(fill = color, fillPattern = None)

object GraphicParams:
  def checked(
      stroke: Option[Rgba] = Some(Rgba.Black),
      fill: Option[Rgba] = None,
      lineWidth: Double = 1.0,
      lineType: LineType = LineType.Solid,
      lineCap: LineCap = LineCap.Butt,
      lineJoin: LineJoin = LineJoin.Miter,
      alpha: Double = 1.0,
      fontFamily: Option[String] = None,
      fontSize: Length = Length.pointsUnsafe(12.0),
      lineWidthUnit: StrokeUnit = StrokeUnit.DevicePixel
  ): Either[GraphicsError, GraphicParams] =
    if !lineWidth.isFinite || lineWidth < 0.0 then Left(GraphicsError.InvalidLineWidth(lineWidth))
    else if !alpha.isFinite || alpha < 0.0 || alpha > 1.0 then
      Left(GraphicsError.InvalidAlpha(alpha))
    else
      Right(
        new GraphicParams(
          stroke,
          fill,
          lineWidth,
          lineType,
          lineCap,
          lineJoin,
          alpha,
          fontFamily,
          fontSize,
          None,
          lineWidthUnit
        )
      )

  def unsafe(
      stroke: Option[Rgba] = Some(Rgba.Black),
      fill: Option[Rgba] = None,
      lineWidth: Double = 1.0,
      lineType: LineType = LineType.Solid,
      lineCap: LineCap = LineCap.Butt,
      lineJoin: LineJoin = LineJoin.Miter,
      alpha: Double = 1.0,
      fontFamily: Option[String] = None,
      fontSize: Length = Length.pointsUnsafe(12.0),
      lineWidthUnit: StrokeUnit = StrokeUnit.DevicePixel
  ): GraphicParams =
    checked(
      stroke,
      fill,
      lineWidth,
      lineType,
      lineCap,
      lineJoin,
      alpha,
      fontFamily,
      fontSize,
      lineWidthUnit
    ).orThrow

final case class Viewport private (
    origin: Point = Point.npcUnsafe(0.0, 0.0),
    size: Size = Size.npcUnsafe(1.0, 1.0),
    xScale: Interval = Interval.unsafe(0.0, 1.0),
    yScale: Interval = Interval.unsafe(0.0, 1.0),
    clip: Clip = Clip.On,
    angleDegrees: Double = 0.0,
    yDirection: YDirection = YDirection.Up
):
  require(angleDegrees.isFinite, "`angleDegrees` must be finite")

object Viewport:
  def checked(
      origin: Point = Point.npcUnsafe(0.0, 0.0),
      size: Size = Size.npcUnsafe(1.0, 1.0),
      xScale: Interval = Interval.unsafe(0.0, 1.0),
      yScale: Interval = Interval.unsafe(0.0, 1.0),
      clip: Clip = Clip.On,
      angleDegrees: Double = 0.0,
      yDirection: YDirection = YDirection.Up
  ): Either[GraphicsError, Viewport] =
    if !angleDegrees.isFinite then Left(GraphicsError.InvalidRotation(angleDegrees))
    else Right(new Viewport(origin, size, xScale, yScale, clip, angleDegrees, yDirection))

  def unsafe(
      origin: Point = Point.npcUnsafe(0.0, 0.0),
      size: Size = Size.npcUnsafe(1.0, 1.0),
      xScale: Interval = Interval.unsafe(0.0, 1.0),
      yScale: Interval = Interval.unsafe(0.0, 1.0),
      clip: Clip = Clip.On,
      angleDegrees: Double = 0.0,
      yDirection: YDirection = YDirection.Up
  ): Viewport =
    checked(origin, size, xScale, yScale, clip, angleDegrees, yDirection).orThrow

/** One constant or one value per mark. Batch columns make style cardinality explicit without
  * widening every mark into an object. A value column is checked against its owning batch.
  */
enum BatchColumn[+A]:
  case Constant(value: A)
  case Values(values: Vector[A])

  def valueAt(index: Int): A =
    this match
      case Constant(value) => value
      case Values(values)  => values(index)

  def valueCount: Option[Int] =
    this match
      case Constant(_)    => None
      case Values(values) => Some(values.length)

  def isConstant: Boolean =
    this match
      case Constant(_) => true
      case Values(_)   => false

  def map[B](f: A => B): BatchColumn[B] =
    this match
      case Constant(value) => Constant(f(value))
      case Values(values)  => Values(values.map(f))

  private[intaglio] def compatible(markCount: Int): Boolean =
    valueCount.forall(_ == markCount)

  private[intaglio] def traverse[E, B](f: A => Either[E, B]): Either[E, BatchColumn[B]] =
    this match
      case Constant(value) => f(value).map(Constant(_))
      case Values(values)  =>
        val out = Vector.newBuilder[B]
        var index = 0
        var result: Either[E, Unit] = Right(())
        while index < values.length && result.isRight do
          result = f(values(index)).map { value =>
            out += value
            ()
          }
          index += 1
        result.map(_ => Values(out.result()))

object BatchColumn:
  def compact[A](values: Vector[A]): BatchColumn[A] =
    values.headOption match
      case Some(first) if values.tail.forall(_ == first) => BatchColumn.Constant(first)
      case _                                             => BatchColumn.Values(values)

sealed trait Grob:
  def name: Option[GraphicsName]
  def viewport: Option[Viewport]
  def children: Vector[Grob] =
    Vector.empty

object Grob:
  final case class Points private[intaglio] (
      points: Vector[Point],
      size: ExtentExpr,
      shape: PointShape,
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(points.nonEmpty, "`points` must be non-empty")

  /** Columnar point marks retained as one grob through device lowering. */
  final case class PointBatch private[intaglio] (
      points: Vector[Point],
      sizes: BatchColumn[ExtentExpr],
      shapes: BatchColumn[PointShape],
      graphicParams: BatchColumn[GraphicParams],
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(points.nonEmpty, "`points` must be non-empty")
    require(sizes.compatible(points.length), "`sizes` must match the point count")
    require(shapes.compatible(points.length), "`shapes` must match the point count")
    require(graphicParams.compatible(points.length), "`graphicParams` must match the point count")

  final case class Lines private[intaglio] (
      points: Vector[Point],
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(points.nonEmpty, "`points` must be non-empty")

  final case class Polygon private[intaglio] (
      points: Vector[Point],
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(points.length >= 3, "`points` must contain at least three vertices")

  /** One filled polygon made from independently closed rings. Ring winding carries outer/hole
    * semantics through the renderer-neutral scene.
    */
  final case class CompoundPolygon private[intaglio] (
      rings: Vector[Vector[Point]],
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(
      rings.nonEmpty && rings.forall(_.length >= 3),
      "`rings` must contain non-empty polygon paths"
    )

  final case class Segments private[intaglio] (
      segments: Vector[(Point, Point)],
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(segments.nonEmpty, "`segments` must be non-empty")

  final case class Rect private[intaglio] (
      center: Point,
      size: Size,
      anchor: Anchor,
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob

  final case class Circle private[intaglio] (
      center: Point,
      radius: ExtentExpr,
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob

  final case class Text private[intaglio] (
      label: String,
      at: Point,
      anchor: Anchor,
      rotationDegrees: Double,
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(rotationDegrees.isFinite, "`rotationDegrees` must be finite")

  final case class Image private[intaglio] (
      image: RasterImage,
      at: Point,
      size: Size,
      anchor: Anchor,
      interpolation: RasterInterpolation,
      alpha: Double,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(alpha.isFinite && alpha >= 0.0 && alpha <= 1.0, "`alpha` must be in [0, 1]")

  final case class Group private[intaglio] (
      override val children: Vector[Grob],
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob

  def points(
      points: Vector[Point],
      size: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      shape: PointShape = PointShape.Circle,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if points.isEmpty then Left(GraphicsError.EmptyGeometry("points"))
    else Right(Points(points, size, shape, gp, viewport, name))

  def pointBatch(
      points: Vector[Point],
      sizes: BatchColumn[ExtentExpr] = BatchColumn.Constant(ExtentExpr.pointsUnsafe(4.0)),
      shapes: BatchColumn[PointShape] = BatchColumn.Constant(PointShape.Circle),
      graphicParams: BatchColumn[GraphicParams] = BatchColumn.Constant(GraphicParams.unsafe()),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if points.isEmpty then Left(GraphicsError.EmptyGeometry("point batch"))
    else
      validateColumn("point size", sizes, points.length)
        .flatMap(_ => validateColumn("point shape", shapes, points.length))
        .flatMap(_ => validateColumn("point graphic parameters", graphicParams, points.length))
        .map(_ => PointBatch(points, sizes, shapes, graphicParams, viewport, name))

  def pointBatchUnsafe(
      points: Vector[Point],
      sizes: BatchColumn[ExtentExpr] = BatchColumn.Constant(ExtentExpr.pointsUnsafe(4.0)),
      shapes: BatchColumn[PointShape] = BatchColumn.Constant(PointShape.Circle),
      graphicParams: BatchColumn[GraphicParams] = BatchColumn.Constant(GraphicParams.unsafe()),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    pointBatch(points, sizes, shapes, graphicParams, viewport, name).orThrow

  private def validateColumn[A](
      name: String,
      column: BatchColumn[A],
      markCount: Int
  ): Either[GraphicsError, Unit] =
    column.valueCount match
      case Some(values) if values != markCount =>
        Left(GraphicsError.BatchColumnLengthMismatch(name, markCount, values))
      case _ => Right(())

  def lines(
      points: Vector[Point],
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if points.isEmpty then Left(GraphicsError.EmptyGeometry("lines"))
    else Right(Lines(points, gp, viewport, name))

  def polygon(
      points: Vector[Point],
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if points.length < 3 then Left(GraphicsError.InvalidGeometrySize("polygon", 3, points.length))
    else Right(Polygon(points, gp, viewport, name))

  def polygonUnsafe(
      points: Vector[Point],
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    polygon(points, gp, viewport, name).orThrow

  def compoundPolygon(
      rings: Vector[Vector[Point]],
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if rings.isEmpty then Left(GraphicsError.EmptyGeometry("compound polygon"))
    else
      rings.find(_.length < 3) match
        case Some(points) =>
          Left(GraphicsError.InvalidGeometrySize("compound polygon ring", 3, points.length))
        case None => Right(CompoundPolygon(rings, gp, viewport, name))

  def compoundPolygonUnsafe(
      rings: Vector[Vector[Point]],
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    compoundPolygon(rings, gp, viewport, name).orThrow

  def segments(
      segments: Vector[(Point, Point)],
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if segments.isEmpty then Left(GraphicsError.EmptyGeometry("segments"))
    else Right(Segments(segments, gp, viewport, name))

  def rect(
      center: Point,
      size: Size,
      anchor: Anchor = Anchor.Center,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    Right(Rect(center, size, anchor, gp, viewport, name))

  def rectUnsafe(
      center: Point,
      size: Size,
      anchor: Anchor = Anchor.Center,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    rect(center, size, anchor, gp, viewport, name).orThrow

  def circle(
      center: Point,
      radius: ExtentExpr,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    Right(Circle(center, radius, gp, viewport, name))

  def circleUnsafe(
      center: Point,
      radius: ExtentExpr,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    circle(center, radius, gp, viewport, name).orThrow

  def text(
      label: String,
      at: Point,
      anchor: Anchor = Anchor.Center,
      rotationDegrees: Double = 0.0,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if !rotationDegrees.isFinite then Left(GraphicsError.InvalidRotation(rotationDegrees))
    else Right(Text(label, at, anchor, rotationDegrees, gp, viewport, name))

  def textUnsafe(
      label: String,
      at: Point,
      anchor: Anchor = Anchor.Center,
      rotationDegrees: Double = 0.0,
      gp: GraphicParams = GraphicParams.unsafe(),
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    text(label, at, anchor, rotationDegrees, gp, viewport, name).orThrow

  def image(
      image: RasterImage,
      at: Point,
      size: Size,
      anchor: Anchor = Anchor.Center,
      interpolation: RasterInterpolation = RasterInterpolation.Nearest,
      alpha: Double = 1.0,
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Grob] =
    if !alpha.isFinite || alpha < 0.0 || alpha > 1.0 then Left(GraphicsError.InvalidAlpha(alpha))
    else Right(Image(image, at, size, anchor, interpolation, alpha, viewport, name))

  def imageUnsafe(
      image: RasterImage,
      at: Point,
      size: Size,
      anchor: Anchor = Anchor.Center,
      interpolation: RasterInterpolation = RasterInterpolation.Nearest,
      alpha: Double = 1.0,
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    Grob.image(image, at, size, anchor, interpolation, alpha, viewport, name).orThrow

  def group(
      children: Vector[Grob],
      viewport: Option[Viewport] = None,
      name: Option[GraphicsName] = None
  ): Grob =
    Group(children, viewport, name)

final case class Scene private (grobs: Vector[Grob], semantics: SceneSemantics):
  def append(grob: Grob): Scene =
    copy(grobs = grobs :+ grob)

  def ++(that: Scene): Scene =
    new Scene(grobs ++ that.grobs, semantics ++ that.semantics)

  def withSemantics(value: SceneSemantics): Scene =
    copy(semantics = value)

  def isEmpty: Boolean =
    grobs.isEmpty

  def size: Int =
    grobs.length

object Scene:
  val empty: Scene =
    new Scene(Vector.empty, SceneSemantics.empty)

  def apply(grobs: Vector[Grob]): Scene =
    new Scene(grobs, SceneSemantics.empty)
