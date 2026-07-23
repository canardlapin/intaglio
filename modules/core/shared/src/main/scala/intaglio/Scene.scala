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

sealed trait LengthExpr:
  def +(that: LengthExpr): LengthExpr =
    LengthExpr.Add(this, that)

  def -(that: LengthExpr): LengthExpr =
    LengthExpr.Sub(this, that)

  /** Translate a location by an extent. Unlike adding two raw length
    * expressions, native units in `that` resolve as a delta rather than as a
    * second location in the frame's scale.
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
  final case class Offset private[intaglio] (location: LengthExpr, extent: ExtentExpr, direction: Double) extends LengthExpr:
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
      case LengthExpr.Const(length) => s"${length.value} ${length.unit}"
      case LengthExpr.Add(_, _)     => "sum expression"
      case LengthExpr.Sub(_, _)     => "difference expression"
      case LengthExpr.Offset(_, _, _) => "location-offset expression"
      case LengthExpr.Mul(_, _)     => "scaled expression"

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
    else if !alpha.isFinite || alpha < 0.0 || alpha > 1.0 then Left(GraphicsError.InvalidAlpha(alpha))
    else Right(new Rgba(red, green, blue, alpha))

  def unsafe(red: Int, green: Int, blue: Int, alpha: Double = 1.0): Rgba =
    apply(red, green, blue, alpha).orThrow

  val Black: Rgba =
    unsafe(0, 0, 0)

  val White: Rgba =
    unsafe(255, 255, 255)

  val Transparent: Rgba =
    unsafe(0, 0, 0, 0.0)

final case class GraphicParams private (
    stroke: Option[Rgba] = Some(Rgba.Black),
    fill: Option[Rgba] = None,
    lineWidth: Double = 1.0,
    lineType: LineType = LineType.Solid,
    lineCap: LineCap = LineCap.Butt,
    lineJoin: LineJoin = LineJoin.Miter,
    alpha: Double = 1.0,
    fontFamily: Option[String] = None,
    fontSize: Length = Length.pointsUnsafe(12.0)
):
  require(lineWidth.isFinite && lineWidth >= 0.0, "`lineWidth` must be finite and >= 0")
  require(alpha.isFinite && alpha >= 0.0 && alpha <= 1.0, "`alpha` must be in [0, 1]")

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
      fontSize: Length = Length.pointsUnsafe(12.0)
  ): Either[GraphicsError, GraphicParams] =
    if !lineWidth.isFinite || lineWidth < 0.0 then Left(GraphicsError.InvalidLineWidth(lineWidth))
    else if !alpha.isFinite || alpha < 0.0 || alpha > 1.0 then Left(GraphicsError.InvalidAlpha(alpha))
    else Right(new GraphicParams(stroke, fill, lineWidth, lineType, lineCap, lineJoin, alpha, fontFamily, fontSize))

  def unsafe(
      stroke: Option[Rgba] = Some(Rgba.Black),
      fill: Option[Rgba] = None,
      lineWidth: Double = 1.0,
      lineType: LineType = LineType.Solid,
      lineCap: LineCap = LineCap.Butt,
      lineJoin: LineJoin = LineJoin.Miter,
      alpha: Double = 1.0,
      fontFamily: Option[String] = None,
      fontSize: Length = Length.pointsUnsafe(12.0)
  ): GraphicParams =
    checked(stroke, fill, lineWidth, lineType, lineCap, lineJoin, alpha, fontFamily, fontSize).orThrow

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

  /** One filled polygon made from independently closed rings. Ring winding
    * carries outer/hole semantics through the renderer-neutral scene.
    */
  final case class CompoundPolygon private[intaglio] (
      rings: Vector[Vector[Point]],
      gp: GraphicParams,
      viewport: Option[Viewport],
      name: Option[GraphicsName]
  ) extends Grob:
    require(rings.nonEmpty && rings.forall(_.length >= 3), "`rings` must contain non-empty polygon paths")

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
        case Some(points) => Left(GraphicsError.InvalidGeometrySize("compound polygon ring", 3, points.length))
        case None         => Right(CompoundPolygon(rings, gp, viewport, name))

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

final case class Scene private (grobs: Vector[Grob]):
  def append(grob: Grob): Scene =
    copy(grobs = grobs :+ grob)

  def ++(that: Scene): Scene =
    Scene(grobs ++ that.grobs)

  def isEmpty: Boolean =
    grobs.isEmpty

  def size: Int =
    grobs.length

object Scene:
  val empty: Scene =
    Scene(Vector.empty)

  def apply(grobs: Vector[Grob]): Scene =
    new Scene(grobs)
