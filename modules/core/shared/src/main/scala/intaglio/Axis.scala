package intaglio

enum AxisSide:
  case Bottom
  case Top
  case Left
  case Right

  def isHorizontal: Boolean =
    this match
      case Bottom | Top => true
      case Left | Right => false

  /** Sign of the outward normal in y-up scene coordinates: ticks and labels extend away from the
    * panel, so Bottom and Left are negative.
    */
  private[intaglio] def direction: Double =
    this match
      case Top | Right   => 1.0
      case Bottom | Left => -1.0

final case class AxisTick private (value: Double, label: String)

object AxisTick:
  def apply(value: Double, label: String): Either[GraphicsError, AxisTick] =
    if value.isFinite then Right(new AxisTick(value, label))
    else Left(GraphicsError.InvalidAxisCoordinate("tick", value))

  def unsafe(value: Double, label: String): AxisTick =
    apply(value, label).orThrow

final case class Axis private (
    side: AxisSide,
    range: Interval,
    ticks: Vector[AxisTick],
    position: Double,
    tickLength: ExtentExpr,
    labelOffset: ExtentExpr,
    axisGp: GraphicParams,
    tickGp: GraphicParams,
    labelGp: GraphicParams,
    name: Option[GraphicsName],
    title: Option[String],
    titleOffset: ExtentExpr,
    titleGp: GraphicParams
):
  def toGrob(viewport: Option[Viewport] = None): Either[GraphicsError, Grob] =
    val baseline =
      if side.isHorizontal then
        (Point.nativeUnsafe(range.lower, position), Point.nativeUnsafe(range.upper, position))
      else (Point.nativeUnsafe(position, range.lower), Point.nativeUnsafe(position, range.upper))
    val tickSegments =
      ticks.map { tick =>
        if side.isHorizontal then
          val y2 = outward(position, tickLength)
          (Point.nativeUnsafe(tick.value, position), Point(LengthExpr.nativeUnsafe(tick.value), y2))
        else
          val x2 = outward(position, tickLength)
          (Point.nativeUnsafe(position, tick.value), Point(x2, LengthExpr.nativeUnsafe(tick.value)))
      }
    for
      baselineGrob <- Grob.segments(Vector(baseline), axisGp, name = childName("baseline"))
      tickGrobs <- tickGroup(tickSegments)
    yield
      val labels = ticks.map(labelGrob)
      val titleGrob = title.map(axisTitleGrob).toVector
      Grob.group(
        Vector(baselineGrob) ++ tickGrobs ++ labels ++ titleGrob,
        viewport = viewport,
        name = name
      )

  private def tickGroup(tickSegments: Vector[(Point, Point)]): Either[GraphicsError, Vector[Grob]] =
    if tickSegments.isEmpty then Right(Vector.empty)
    else Grob.segments(tickSegments, tickGp, name = childName("ticks")).map(Vector(_))

  private def labelGrob(tick: AxisTick): Grob =
    val at =
      if side.isHorizontal then
        Point(LengthExpr.nativeUnsafe(tick.value), outward(position, labelOffset))
      else Point(outward(position, labelOffset), LengthExpr.nativeUnsafe(tick.value))
    Grob.textUnsafe(
      tick.label,
      at,
      anchor = labelAnchor,
      gp = labelGp,
      name = childName("label")
    )

  private def axisTitleGrob(label: String): Grob =
    val midpoint = range.lower + range.width / 2.0
    val at =
      if side.isHorizontal then
        Point(LengthExpr.nativeUnsafe(midpoint), outward(position, titleOffset))
      else Point(outward(position, titleOffset), LengthExpr.nativeUnsafe(midpoint))
    val rotation =
      side match
        case AxisSide.Left  => 90.0
        case AxisSide.Right => -90.0
        case _              => 0.0
    Grob.textUnsafe(
      label,
      at,
      anchor = Anchor(HJust.Center, VJust.Center),
      rotationDegrees = rotation,
      gp = titleGp,
      name = childName("title")
    )

  private def outward(position: Double, distance: ExtentExpr): LengthExpr =
    val base = LengthExpr.nativeUnsafe(position)
    if side.direction > 0.0 then base + distance
    else base - distance

  private def labelAnchor: Anchor =
    side match
      case AxisSide.Bottom => Anchor(HJust.Center, VJust.Top)
      case AxisSide.Top    => Anchor(HJust.Center, VJust.Bottom)
      case AxisSide.Left   => Anchor(HJust.Right, VJust.Center)
      case AxisSide.Right  => Anchor(HJust.Left, VJust.Center)

  private def childName(suffix: String): Option[GraphicsName] =
    name.map(n => GraphicsName.unsafe(s"${n.value}-$suffix"))

object Axis:
  def make(
      side: AxisSide,
      range: Interval,
      ticks: Vector[AxisTick],
      position: Double = 0.0,
      tickLength: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      labelOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      axisGp: GraphicParams = GraphicParams.unsafe(),
      tickGp: GraphicParams = GraphicParams.unsafe(),
      labelGp: GraphicParams = GraphicParams.unsafe(),
      name: Option[GraphicsName] = None,
      title: Option[String] = None,
      titleOffset: ExtentExpr = ExtentExpr.pointsUnsafe(24.0),
      titleGp: GraphicParams = GraphicParams.unsafe()
  ): Either[GraphicsError, Axis] =
    validate(range, ticks, position).map { _ =>
      new Axis(
        side,
        range,
        ticks,
        position,
        tickLength,
        labelOffset,
        axisGp,
        tickGp,
        labelGp,
        name,
        title,
        titleOffset,
        titleGp
      )
    }

  def bottom(
      range: Interval,
      ticks: Vector[AxisTick],
      y: Double = 0.0,
      tickLength: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      labelOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      axisGp: GraphicParams = GraphicParams.unsafe(),
      tickGp: GraphicParams = GraphicParams.unsafe(),
      labelGp: GraphicParams = GraphicParams.unsafe(),
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Axis] =
    make(AxisSide.Bottom, range, ticks, y, tickLength, labelOffset, axisGp, tickGp, labelGp, name)

  def top(
      range: Interval,
      ticks: Vector[AxisTick],
      y: Double = 0.0,
      tickLength: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      labelOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      axisGp: GraphicParams = GraphicParams.unsafe(),
      tickGp: GraphicParams = GraphicParams.unsafe(),
      labelGp: GraphicParams = GraphicParams.unsafe(),
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Axis] =
    make(AxisSide.Top, range, ticks, y, tickLength, labelOffset, axisGp, tickGp, labelGp, name)

  def left(
      range: Interval,
      ticks: Vector[AxisTick],
      x: Double = 0.0,
      tickLength: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      labelOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      axisGp: GraphicParams = GraphicParams.unsafe(),
      tickGp: GraphicParams = GraphicParams.unsafe(),
      labelGp: GraphicParams = GraphicParams.unsafe(),
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Axis] =
    make(AxisSide.Left, range, ticks, x, tickLength, labelOffset, axisGp, tickGp, labelGp, name)

  def right(
      range: Interval,
      ticks: Vector[AxisTick],
      x: Double = 0.0,
      tickLength: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      labelOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      axisGp: GraphicParams = GraphicParams.unsafe(),
      tickGp: GraphicParams = GraphicParams.unsafe(),
      labelGp: GraphicParams = GraphicParams.unsafe(),
      name: Option[GraphicsName] = None
  ): Either[GraphicsError, Axis] =
    make(AxisSide.Right, range, ticks, x, tickLength, labelOffset, axisGp, tickGp, labelGp, name)

  def ticks(
      range: Interval,
      breaks: Breaks = Breaks.default,
      labeler: Labeler = Labeler.default
  ): Either[GraphicsError, Vector[AxisTick]] =
    breaks.generate(range).flatMap { generated =>
      val values = generated.filter(range.contains)
      val labels = labeler(values)
      if labels.length != values.length then
        Left(GraphicsError.AxisLabelCountMismatch(values.length, labels.length))
      else
        val out = Vector.newBuilder[AxisTick]
        var idx = 0
        var result: Either[GraphicsError, Unit] = Right(())
        while idx < values.length && result.isRight do
          AxisTick(values(idx), labels(idx)) match
            case Right(tick) => out += tick
            case Left(error) => result = Left(error)
          idx += 1
        result.map(_ => out.result())
    }

  private def validate(
      range: Interval,
      ticks: Vector[AxisTick],
      position: Double
  ): Either[GraphicsError, Unit] =
    if !position.isFinite then Left(GraphicsError.InvalidAxisCoordinate("position", position))
    else
      ticks.find(tick => !range.contains(tick.value)) match
        case Some(tick) =>
          Left(GraphicsError.AxisTickOutsideRange(tick.value, range.lower, range.upper))
        case None => Right(())
