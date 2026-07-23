package intaglio

final case class PanelMargins(
    top: ExtentExpr,
    right: ExtentExpr,
    bottom: ExtentExpr,
    left: ExtentExpr
)

object PanelMargins:
  val none: PanelMargins =
    PanelMargins(
      ExtentExpr.npcUnsafe(0.0),
      ExtentExpr.npcUnsafe(0.0),
      ExtentExpr.npcUnsafe(0.0),
      ExtentExpr.npcUnsafe(0.0)
    )

  def npc(top: Double, right: Double, bottom: Double, left: Double): Either[GraphicsError, PanelMargins] =
    for
      t <- ExtentExpr.npc(top)
      r <- ExtentExpr.npc(right)
      b <- ExtentExpr.npc(bottom)
      l <- ExtentExpr.npc(left)
    yield PanelMargins(t, r, b, l)

  def npcUnsafe(top: Double, right: Double, bottom: Double, left: Double): PanelMargins =
    npc(top, right, bottom, left).orThrow

final case class PanelFrame(origin: Point, size: Size)

object PanelFrame:
  def npc(x: Double, y: Double, width: Double, height: Double): Either[GraphicsError, PanelFrame] =
    for
      origin <- Point.npc(x, y)
      size <- Size.npc(width, height)
    yield PanelFrame(origin, size)

  def npcUnsafe(x: Double, y: Double, width: Double, height: Double): PanelFrame =
    npc(x, y, width, height).orThrow

final case class PanelLayout(
    frame: PanelFrame,
    xScale: Interval,
    yScale: Interval,
    margins: PanelMargins = PanelMargins.none,
    clip: Clip = Clip.On
):
  def viewport: Viewport =
    Viewport.unsafe(
      origin = frame.origin,
      size = frame.size,
      xScale = xScale,
      yScale = yScale,
      clip = clip
    )

  def guideViewport: Viewport =
    Viewport.unsafe(
      origin = frame.origin,
      size = frame.size,
      xScale = xScale,
      yScale = yScale,
      clip = Clip.Off
    )

  def withClip(value: Clip): PanelLayout =
    copy(clip = value)

  def contains(x: Double, y: Double): Boolean =
    xScale.contains(x) && yScale.contains(y)

  def dataToPanel(x: Double, y: Double): Either[GraphicsError, Point] =
    if !x.isFinite then Left(GraphicsError.InvalidLayoutCoordinate("x", x))
    else if !y.isFinite then Left(GraphicsError.InvalidLayoutCoordinate("y", y))
    else
      val tx = xScale.rescale(x)
      val ty = yScale.rescale(y)
      if !tx.isFinite then Left(GraphicsError.InvalidLayoutCoordinate("x", tx))
      else if !ty.isFinite then Left(GraphicsError.InvalidLayoutCoordinate("y", ty))
      else
        Right(
          Point(
            frame.origin.x + LengthExpr.Mul(tx, frame.size.width.expr),
            frame.origin.y + LengthExpr.Mul(ty, frame.size.height.expr)
          )
        )

  private[intaglio] def axisRange(side: AxisSide): Interval =
    if side.isHorizontal then xScale else yScale

  private[intaglio] def axisPosition(side: AxisSide): Double =
    side match
      case AxisSide.Bottom => yScale.lower
      case AxisSide.Top    => yScale.upper
      case AxisSide.Left   => xScale.lower
      case AxisSide.Right  => xScale.upper

object PanelLayout:
  def unit(xScale: Interval, yScale: Interval, clip: Clip = Clip.On): PanelLayout =
    PanelLayout(
      PanelFrame.npcUnsafe(0.0, 0.0, 1.0, 1.0),
      xScale,
      yScale,
      PanelMargins.none,
      clip
    )

final case class LegendEntry private (
    label: String,
    gp: GraphicParams,
    shape: PointShape
)

object LegendEntry:
  def apply(label: String, gp: GraphicParams, shape: PointShape = PointShape.Circle): Either[GraphicsError, LegendEntry] =
    val trimmed = label.trim
    if trimmed.isEmpty then Left(GraphicsError.BlankName("legend entry"))
    else Right(new LegendEntry(trimmed, gp, shape))

  def color(label: String, color: Rgba, shape: PointShape = PointShape.Circle): Either[GraphicsError, LegendEntry] =
    apply(
      label,
      GraphicParams.unsafe(stroke = Some(color), fill = Some(color)),
      shape
    )

  def unsafe(label: String, gp: GraphicParams, shape: PointShape = PointShape.Circle): LegendEntry =
    apply(label, gp, shape).orThrow

  def colorUnsafe(label: String, value: Rgba, shape: PointShape = PointShape.Circle): LegendEntry =
    color(label, value, shape).orThrow

sealed trait GuideSpec:
  def name: Option[GraphicsName]

object GuideSpec:
  final case class Axis(
      side: AxisSide,
      breaks: Breaks = Breaks.default,
      labeler: Labeler = Labeler.default,
      ticks: Option[Vector[AxisTick]] = None,
      tickLength: Option[ExtentExpr] = None,
      labelOffset: Option[ExtentExpr] = None,
      axisGp: Option[GraphicParams] = None,
      tickGp: Option[GraphicParams] = None,
      labelGp: Option[GraphicParams] = None,
      title: Option[String] = None,
      titleGp: Option[GraphicParams] = None,
      name: Option[GraphicsName] = None
  ) extends GuideSpec

  final case class Legend(
      title: Option[String],
      entries: Vector[LegendEntry],
      origin: Point = Point.npcUnsafe(0.82, 0.88),
      // Row gap and label offset are absolute (points) so legend spacing is
      // invariant to the viewport it is lowered into: a derived legend dropped
      // into a narrow reserved strip must still clear its own point-sized key
      // markers, which npc offsets fail to do once the strip is small.
      rowGap: ExtentExpr = ExtentExpr.pointsUnsafe(20.0),
      firstRowOffset: Option[ExtentExpr] = None,
      labelOffset: LengthExpr = LengthExpr(Length.pointsUnsafe(10.0)),
      markerSize: ExtentExpr = ExtentExpr.pointsUnsafe(5.0),
      titleGp: Option[GraphicParams] = None,
      labelGp: Option[GraphicParams] = None,
      name: Option[GraphicsName] = None
  ) extends GuideSpec

  /** A continuous color guide. `colors` are equal-width swatches ordered from
    * the low end of the transformed scale to the high end; tick values are
    * normalized positions in that same [0, 1] guide space.
    */
  final case class Colorbar(
      title: Option[String],
      colors: Vector[Rgba],
      ticks: Vector[AxisTick],
      origin: Point = Point.npcUnsafe(0.82, 0.14),
      barWidth: ExtentExpr = ExtentExpr.pointsUnsafe(12.0),
      barHeight: ExtentExpr = ExtentExpr.npcUnsafe(0.62),
      tickLength: ExtentExpr = ExtentExpr.pointsUnsafe(4.0),
      labelOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      titleOffset: ExtentExpr = ExtentExpr.pointsUnsafe(8.0),
      tickGp: Option[GraphicParams] = None,
      titleGp: Option[GraphicParams] = None,
      labelGp: Option[GraphicParams] = None,
      name: Option[GraphicsName] = None
  ) extends GuideSpec

  def lower(
      spec: GuideSpec,
      layout: PanelLayout,
      legendViewport: Option[Viewport] = None,
      policy: LayoutPolicy = LayoutPolicy(),
      theme: Theme = Theme.default
  ): Either[GraphicsError, ResolvedGuide] =
    spec match
      case axis: Axis =>
        lowerAxis(axis, layout, policy, theme)
      case legend: Legend =>
        lowerLegend(legend, legendViewport, theme)
      case colorbar: Colorbar =>
        lowerColorbar(colorbar, legendViewport, theme)

  private def lowerAxis(
      spec: Axis,
      layout: PanelLayout,
      policy: LayoutPolicy,
      theme: Theme
  ): Either[GraphicsError, ResolvedGuide] =
    val range = layout.axisRange(spec.side)
    // The layout solver reserves point-sized strips. Lower defaults in the same
    // unit system so guide geometry stays invariant when the panel or device
    // size changes; explicit native extents remain available to callers.
    val tickLength = spec.tickLength.getOrElse(ExtentExpr.pointsUnsafe(policy.tickLengthPt))
    val labelOffset = spec.labelOffset.getOrElse(
      ExtentExpr.pointsUnsafe(policy.tickLengthPt + policy.tickLabelGapPt)
    )
    val titleOffset = ExtentExpr.pointsUnsafe(axisTitleOffsetPt(spec, policy))
    val titleGp = spec.titleGp.getOrElse(theme.axis.title)
    val name = spec.name.orElse(Some(defaultAxisName(spec.side)))
    for
      ticks <- spec.ticks match
        case Some(explicit) => Right(explicit.filter(tick => range.contains(tick.value)))
        case None           => intaglio.Axis.ticks(range, spec.breaks, spec.labeler)
      axis <- intaglio.Axis.make(
        side = spec.side,
        range = range,
        ticks = ticks,
        position = layout.axisPosition(spec.side),
        tickLength = tickLength,
        labelOffset = labelOffset,
        axisGp = spec.axisGp.getOrElse(theme.axis.line),
        tickGp = spec.tickGp.getOrElse(theme.axis.tick),
        labelGp = spec.labelGp.getOrElse(theme.axis.text),
        name = name,
        title = spec.title,
        titleOffset = titleOffset,
        titleGp = titleGp
      )
      grob <- axis.toGrob(Some(layout.guideViewport))
    yield ResolvedGuide(spec, grob)

  private def axisTitleOffsetPt(spec: Axis, policy: LayoutPolicy): Double =
    val tickLabelExtent =
      if spec.side.isHorizontal then policy.metrics.heightPt(policy.axisTextStyle)
      else
        spec.ticks.getOrElse(Vector.empty).foldLeft(0.0) { (width, tick) =>
          math.max(width, policy.metrics.widthPt(tick.label, policy.axisTextStyle))
        }
    policy.tickLengthPt + policy.tickLabelGapPt + tickLabelExtent +
      policy.axisTitleGapPt + policy.metrics.heightPt(policy.axisTitleTextStyle) / 2.0

  private def lowerLegend(
      spec: Legend,
      viewport: Option[Viewport],
      theme: Theme
  ): Either[GraphicsError, ResolvedGuide] =
    if spec.entries.isEmpty then Left(GraphicsError.EmptyGeometry("legend"))
    else
      val children = Vector.newBuilder[Grob]
      var result: Either[GraphicsError, Unit] = addLegendTitle(spec, theme, children)
      var idx = 0
      while idx < spec.entries.length && result.isRight do
        result = addLegendEntry(spec, theme, spec.entries(idx), idx, children)
        idx += 1
      result.map { _ =>
        val group =
          Grob.group(
            children.result(),
            viewport = viewport,
            name = spec.name.orElse(Some(GraphicsName.unsafe("legend")))
          )
        ResolvedGuide(spec, group)
      }

  private def addLegendTitle(
      spec: Legend,
      theme: Theme,
      children: scala.collection.mutable.Builder[Grob, Vector[Grob]]
  ): Either[GraphicsError, Unit] =
    spec.title match
      case None =>
        Right(())
      case Some(title) =>
        Grob
          .text(
            title,
            spec.origin,
            anchor = Anchor(HJust.Left, VJust.Top),
            gp = spec.titleGp.getOrElse(theme.legend.title),
            name = spec.name.map(name => GraphicsName.unsafe(s"${name.value}-title"))
          )
          .map { grob =>
            children += grob
            ()
          }

  private def addLegendEntry(
      spec: Legend,
      theme: Theme,
      entry: LegendEntry,
      index: Int,
      children: scala.collection.mutable.Builder[Grob, Vector[Grob]]
  ): Either[GraphicsError, Unit] =
    val defaultFirstOffset =
      if spec.title.nonEmpty then spec.rowGap.expr else LengthExpr(Length.pointsUnsafe(0.0))
    val firstOffset = spec.firstRowOffset.map(_.expr).getOrElse(defaultFirstOffset)
    val y = spec.origin.y - firstOffset - LengthExpr.Mul(index.toDouble, spec.rowGap.expr)
    val keyAt = Point(spec.origin.x, y)
    val labelAt = Point(spec.origin.x + spec.labelOffset, y)
    val baseName = spec.name.map(name => s"${name.value}-entry-$index")
    for
      key <- Grob.points(
        Vector(keyAt),
        size = spec.markerSize,
        shape = entry.shape,
        gp = entry.gp,
        name = baseName.map(value => GraphicsName.unsafe(s"$value-key"))
      )
      label <- Grob.text(
        entry.label,
        labelAt,
        anchor = Anchor(HJust.Left, VJust.Center),
        gp = spec.labelGp.getOrElse(theme.legend.text),
        name = baseName.map(value => GraphicsName.unsafe(s"$value-label"))
      )
    yield
      children += key
      children += label
      ()

  private def lowerColorbar(
      spec: Colorbar,
      viewport: Option[Viewport],
      theme: Theme
  ): Either[GraphicsError, ResolvedGuide] =
    if spec.colors.isEmpty then Left(GraphicsError.EmptyGeometry("colorbar"))
    else
      val children = Vector.newBuilder[Grob]
      val swatchHeight = ExtentExpr.unsafe(LengthExpr.Mul(1.0 / spec.colors.length.toDouble, spec.barHeight.expr))
      var index = 0
      while index < spec.colors.length do
        val y = spec.origin.y + ExtentExpr.unsafe(LengthExpr.Mul(index.toDouble / spec.colors.length.toDouble, spec.barHeight.expr))
        val name = spec.name.map(value => GraphicsName.unsafe(s"${value.value}-swatch-$index"))
        children += Grob.rectUnsafe(
          Point(spec.origin.x, y),
          Size.fromExtents(spec.barWidth, swatchHeight),
          anchor = Anchor.BottomLeft,
          gp = GraphicParams.unsafe(stroke = None, fill = Some(spec.colors(index)), lineWidth = 0.0),
          name = name
        )
        index += 1

      val visibleTicks = spec.ticks.filter(tick => tick.value >= 0.0 && tick.value <= 1.0)
      val barRight = spec.origin.x + spec.barWidth
      val tickSegments = visibleTicks.map { tick =>
        val y = spec.origin.y + ExtentExpr.unsafe(LengthExpr.Mul(tick.value, spec.barHeight.expr))
        (Point(barRight, y), Point(barRight + spec.tickLength, y))
      }
      val tickResult =
        if tickSegments.isEmpty then Right(())
        else
          Grob
            .segments(
              tickSegments,
              gp = spec.tickGp.getOrElse(theme.axis.tick),
              name = spec.name.map(value => GraphicsName.unsafe(s"${value.value}-ticks"))
            )
            .map { grob =>
              children += grob
              ()
            }

      tickResult.flatMap { _ =>
        var labelIndex = 0
        var labelResult: Either[GraphicsError, Unit] = Right(())
        while labelIndex < visibleTicks.length && labelResult.isRight do
          val tick = visibleTicks(labelIndex)
          val y = spec.origin.y + ExtentExpr.unsafe(LengthExpr.Mul(tick.value, spec.barHeight.expr))
          labelResult = Grob
            .text(
              tick.label,
              Point(barRight + spec.labelOffset, y),
              anchor = Anchor(HJust.Left, VJust.Center),
              gp = spec.labelGp.getOrElse(theme.legend.text),
              name = spec.name.map(value => GraphicsName.unsafe(s"${value.value}-label-$labelIndex"))
            )
            .map { grob =>
              children += grob
              ()
            }
          labelIndex += 1
        labelResult
      }.flatMap { _ =>
        spec.title match
          case None => Right(())
          case Some(title) =>
            Grob
              .text(
                title,
                Point(spec.origin.x, spec.origin.y + spec.barHeight + spec.titleOffset),
                anchor = Anchor(HJust.Left, VJust.Bottom),
                gp = spec.titleGp.getOrElse(theme.legend.title),
                name = spec.name.map(value => GraphicsName.unsafe(s"${value.value}-title"))
              )
              .map { grob =>
                children += grob
                ()
              }
      }.map { _ =>
        val group = Grob.group(
          children.result(),
          viewport = viewport,
          name = spec.name.orElse(Some(GraphicsName.unsafe("colorbar")))
        )
        ResolvedGuide(spec, group)
      }

  private def defaultAxisName(side: AxisSide): GraphicsName =
    val label =
      side match
        case AxisSide.Bottom => "bottom-axis"
        case AxisSide.Top    => "top-axis"
        case AxisSide.Left   => "left-axis"
        case AxisSide.Right  => "right-axis"
    GraphicsName.unsafe(label)

final case class ResolvedGuide(spec: GuideSpec, grob: Grob)
