package intaglio

import scala.util.control.NonFatal

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

  def npc(
      top: Double,
      right: Double,
      bottom: Double,
      left: Double
  ): Either[GraphicsError, PanelMargins] =
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
    viewport(ViewportCoordinateMapping.native)

  def viewport(mapping: ViewportCoordinateMapping): Viewport =
    Viewport
      .unsafe(
        origin = frame.origin,
        size = frame.size,
        xScale = xScale,
        yScale = yScale,
        clip = clip
      )
      .withCoordinateMapping(mapping)

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
  def apply(
      label: String,
      gp: GraphicParams,
      shape: PointShape = PointShape.Circle
  ): Either[GraphicsError, LegendEntry] =
    val trimmed = label.trim
    if trimmed.isEmpty then Left(GraphicsError.BlankName("legend entry"))
    else Right(new LegendEntry(trimmed, gp, shape))

  def color(
      label: String,
      color: Rgba,
      shape: PointShape = PointShape.Circle
  ): Either[GraphicsError, LegendEntry] =
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
  private def letterSpacingPt(gp: GraphicParams, device: DeviceContext): Double =
    gp.letterSpacing.fold(0.0) {
      case LetterSpacing(value, StrokeUnit.Point)       => value
      case LetterSpacing(value, StrokeUnit.DevicePixel) => value * 72.0 / device.pixelsPerInch
    }

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

  /** A continuous color guide. `colors` are equal-width swatches ordered from the low end of the
    * transformed scale to the high end; tick values are normalized positions in that same [0, 1]
    * guide space.
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
        lowerAxis(axis, layout, policy, theme, avoidOverlap = false)
      case legend: Legend =>
        lowerLegend(legend, legendViewport, theme)
      case colorbar: Colorbar =>
        lowerColorbar(colorbar, legendViewport, policy, theme)

  /** Apply one measured guide-stack placement while retaining the semantic guide content. The
    * compiler and renderer-neutral plot composition share this operation so collected guides use
    * exactly the same point-based spacing as guides attached to an individual plot.
    */
  private[intaglio] def place(spec: GuideSpec, placement: GuidePlacement): GuideSpec =
    def x(value: Double): LengthExpr = LengthExpr(Length.pointsUnsafe(value))
    def y(value: Double): LengthExpr = LengthExpr.npcUnsafe(1.0) - ExtentExpr.pointsUnsafe(value)
    (spec, placement) match
      case (legend: Legend, solved: GuidePlacement.Legend) =>
        legend.copy(
          origin = Point(x(solved.xPt), y(solved.topPt)),
          rowGap = ExtentExpr.pointsUnsafe(solved.rowPitchPt),
          firstRowOffset = Some(ExtentExpr.pointsUnsafe(solved.firstRowOffsetPt)),
          labelOffset = x(solved.labelOffsetPt),
          markerSize =
            ExtentExpr.pointsUnsafe(fittedKeyRadiusPt(legend.entries, solved.markerSizePt))
        )
      case (colorbar: Colorbar, solved: GuidePlacement.Colorbar) =>
        colorbar.copy(
          origin = Point(
            x(solved.xPt),
            y(solved.topPt + solved.barTopOffsetPt + solved.barHeightPt)
          ),
          barWidth = ExtentExpr.pointsUnsafe(solved.barWidthPt),
          barHeight = ExtentExpr.pointsUnsafe(solved.barHeightPt),
          tickLength = ExtentExpr.pointsUnsafe(solved.tickLengthPt),
          labelOffset = ExtentExpr.pointsUnsafe(solved.labelOffsetPt),
          titleOffset = ExtentExpr.pointsUnsafe(solved.titleOffsetPt)
        )
      case _ => spec

  /** The largest key-glyph radius at which every entry's ink --- shape and stroke --- stays within
    * `halfBoxPt` of the key centre. One radius serves the whole legend, so shapes whose areas agree
    * at a given radius (a circle and a diamond) still agree between their keys. A device-pixel
    * stroke is budgeted at 96 ppi, its widest extent in points on any denser target.
    */
  private def fittedKeyRadiusPt(entries: Vector[LegendEntry], halfBoxPt: Double): Double =
    val extentPerRadius = entries.foldLeft(1.0) { (widest, entry) =>
      entry.shape match
        case PointShape.Diamond => math.max(widest, PointShape.DiamondHalfDiagonalRatio)
        case _                  => widest
    }
    val strokeHalfPt = entries.foldLeft(0.0) { (widest, entry) =>
      val gp = entry.gp
      if gp.stroke.isEmpty || gp.lineWidth <= 0.0 then widest
      else
        val widthPt = gp.lineWidthUnit match
          case StrokeUnit.Point       => gp.lineWidth
          case StrokeUnit.DevicePixel => gp.lineWidth * 72.0 / 96.0
        // A miter at a diamond's or triangle's corner reaches past the half width.
        val cornerFactor = entry.shape match
          case PointShape.Circle | PointShape.Square | PointShape.Cross => 1.0
          case PointShape.Diamond  => if gp.lineJoin == LineJoin.Miter then math.sqrt(2.0) else 1.0
          case PointShape.Triangle =>
            if gp.lineJoin == LineJoin.Miter then math.sqrt(5.0) else 1.0
        math.max(widest, widthPt / 2.0 * cornerFactor)
    }
    math.max(0.0, halfBoxPt - strokeHalfPt) / extentPerRadius

  private[intaglio] def lowerAxis(
      spec: Axis,
      layout: PanelLayout,
      policy: LayoutPolicy,
      theme: Theme,
      avoidOverlap: Boolean
  ): Either[GraphicsError, ResolvedGuide] =
    measuring(lowerAxisUnchecked(spec, layout, policy, theme, avoidOverlap))

  private def lowerAxisUnchecked(
      spec: Axis,
      layout: PanelLayout,
      policy: LayoutPolicy,
      theme: Theme,
      avoidOverlap: Boolean
  ): Either[GraphicsError, ResolvedGuide] =
    val range = layout.axisRange(spec.side)
    // The layout solver reserves point-sized strips. Lower defaults in the same
    // unit system so guide geometry stays invariant when the panel or device
    // size changes; explicit native extents remain available to callers.
    val tickLength = spec.tickLength.getOrElse(ExtentExpr.pointsUnsafe(policy.tickLengthPt))
    val labelOffset = spec.labelOffset.getOrElse(
      ExtentExpr.pointsUnsafe(policy.tickLengthPt + policy.tickLabelGapPt)
    )
    val titleGp = spec.titleGp.getOrElse(theme.axis.title)
    val name = spec.name.orElse(Some(defaultAxisName(spec.side)))
    for
      ticks <- spec.ticks match
        case Some(explicit) => Right(explicit.filter(tick => range.contains(tick.value)))
        case None           => intaglio.Axis.ticks(range, spec.breaks, spec.labeler)
      titleDistance <- axisTitleOffsetPt(spec.copy(ticks = Some(ticks)), policy, theme)
      titleOffset = ExtentExpr.pointsUnsafe(titleDistance)
      fitted <-
        if !avoidOverlap then Right(ticks)
        else
          val device = policy.referenceDevice
          val resolver = new LengthResolver(device, DeviceFrame.root(device))
          val span = if spec.side.isHorizontal then resolver.width(layout.frame.size.width)
          else resolver.height(layout.frame.size.height)
          val gp = spec.labelGp.getOrElse(theme.axis.text)
          for
            length <- span
            fontPx <- resolver.fontSize(gp.fontSize)
          yield
            val pxPerPt = device.pixelsPerInch / 72.0
            val style =
              TextStyle(gp.fontFamily, fontPx / pxPerPt, gp.fontWeight, letterSpacingPt(gp, device))
            GuideTickSpacing.fitAxis(
              ticks,
              tick => range.rescale(tick.value) * length,
              tick =>
                if spec.side.isHorizontal then policy.metrics.widthPt(tick.label, style) * pxPerPt
                else policy.metrics.heightPt(style) * pxPerPt,
              fontPx * 0.5
            )
      axis <- intaglio.Axis.make(
        side = spec.side,
        range = range,
        ticks = fitted,
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
    yield ResolvedGuide(if avoidOverlap then spec.copy(ticks = Some(fitted)) else spec, grob)

  private def axisTitleOffsetPt(
      spec: Axis,
      policy: LayoutPolicy,
      theme: Theme
  ): Either[GraphicsError, Double] =
    val device = policy.referenceDevice
    val resolver = new LengthResolver(device, DeviceFrame.root(device))
    val labelGp = spec.labelGp.getOrElse(theme.axis.text)
    val titleGp = spec.titleGp.getOrElse(theme.axis.title)
    for
      labelPx <- resolver.fontSize(labelGp.fontSize)
      titlePx <- resolver.fontSize(titleGp.fontSize)
      offset <- measuring {
        val style =
          TextStyle(
            labelGp.fontFamily,
            labelPx * 72.0 / device.pixelsPerInch,
            labelGp.fontWeight,
            letterSpacingPt(labelGp, device)
          )
        val titleStyle =
          TextStyle(
            titleGp.fontFamily,
            titlePx * 72.0 / device.pixelsPerInch,
            titleGp.fontWeight,
            letterSpacingPt(titleGp, device)
          )
        val extent = if spec.side.isHorizontal then policy.metrics.heightPt(style)
        else
          spec.ticks
            .getOrElse(Vector.empty)
            .foldLeft(0.0)((width, tick) =>
              math.max(width, policy.metrics.widthPt(tick.label, style))
            )
        Right(
          policy.tickLengthPt + policy.tickLabelGapPt + extent + policy.axisTitleGapPt + policy.metrics
            .heightPt(titleStyle) / 2.0
        )
      }
    yield offset

  private def measuring[A](body: => Either[GraphicsError, A]): Either[GraphicsError, A] =
    try body
    catch
      case NonFatal(error) =>
        val (kind, detail) = GraphicsError.throwableDetails(error)
        Left(GraphicsError.LayoutMeasurementFailed(kind, detail))

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
      policy: LayoutPolicy,
      theme: Theme
  ): Either[GraphicsError, ResolvedGuide] =
    if spec.colors.isEmpty then Left(GraphicsError.EmptyGeometry("colorbar"))
    else
      val device = policy.referenceDevice
      val root = new LengthResolver(device, DeviceFrame.root(device))
      for
        frame <- viewport.fold[Either[GraphicsError, DeviceFrame]](Right(DeviceFrame.root(device)))(
          root.childFrame
        )
        resolver = new LengthResolver(device, frame)
        gp = spec.labelGp.getOrElse(theme.legend.text)
        fontPx <- resolver.fontSize(gp.fontSize)
        heightPx <- resolver.height(spec.barHeight)
        style = TextStyle(
          gp.fontFamily,
          fontPx * 72.0 / device.pixelsPerInch,
          gp.fontWeight,
          letterSpacingPt(gp, device)
        )
        visible <- measuring {
          Right(
            GuideTickSpacing.fit(
              spec.ticks.filter(tick => tick.value >= 0.0 && tick.value <= 1.0),
              tick => tick.value * heightPx,
              _ => policy.metrics.heightPt(style) * device.pixelsPerInch / 72.0,
              fontPx * 0.5
            )
          )
        }
        result <- lowerColorbarMarks(spec, viewport, theme, visible)
      yield result

  private def lowerColorbarMarks(
      spec: Colorbar,
      viewport: Option[Viewport],
      theme: Theme,
      visibleTicks: Vector[AxisTick]
  ): Either[GraphicsError, ResolvedGuide] =
    val children = Vector.newBuilder[Grob]
    // A single image has no independently composited swatch edges. Smooth interpolation
    // keeps the sampled continuous palette continuous even on a short or fractional bar.
    val strip = RasterImage.tabulate(RasterDimensions.unsafe(1, spec.colors.length)) { (_, row) =>
      Rgba32.fromRgba(spec.colors(spec.colors.length - 1 - row))
    }
    children += Grob.imageUnsafe(
      strip,
      spec.origin,
      Size.fromExtents(spec.barWidth, spec.barHeight),
      anchor = Anchor.BottomLeft,
      interpolation = RasterInterpolation.Smooth,
      name = spec.name.map(value => GraphicsName.unsafe(s"${value.value}-ramp"))
    )
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

    tickResult
      .flatMap { _ =>
        var labelIndex = 0
        var labelResult: Either[GraphicsError, Unit] = Right(())
        while labelIndex < visibleTicks.length && labelResult.isRight do
          val tick = visibleTicks(labelIndex)
          val y =
            spec.origin.y + ExtentExpr.unsafe(LengthExpr.Mul(tick.value, spec.barHeight.expr))
          labelResult = Grob
            .text(
              tick.label,
              Point(barRight + spec.labelOffset, y),
              anchor = Anchor(HJust.Left, VJust.Center),
              gp = spec.labelGp.getOrElse(theme.legend.text),
              name =
                spec.name.map(value => GraphicsName.unsafe(s"${value.value}-label-$labelIndex"))
            )
            .map { grob =>
              children += grob
              ()
            }
          labelIndex += 1
        labelResult
      }
      .flatMap { _ =>
        spec.title match
          case None        => Right(())
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
      }
      .map { _ =>
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

/** Measured tick spacing. Axes retain a regular stride; colorbars prioritize endpoints. A wide
  * guide retains its original tick vector exactly. Explicit axis overrides opt out.
  */
private[intaglio] object GuideTickSpacing:
  def fitAxis(
      ticks: Vector[AxisTick],
      position: AxisTick => Double,
      extent: AxisTick => Double,
      gap: Double
  ): Vector[AxisTick] =
    if ticks.size < 2 then ticks
    else
      val measured = ticks.map(t => (t, position(t), extent(t))).sortBy(_._2)
      def clears(a: Int, b: Int): Boolean =
        measured(b)._2 - measured(a)._2 + 1e-9 >=
          (measured(a)._3 + measured(b)._3) / 2.0 + gap
      if (1 until measured.size).forall(i => clears(i - 1, i)) then ticks
      else
        // Compiler-derived tick values are normalized positions, not the displayed data values.
        val zero = measured.indexWhere(_._1.label.replace('−', '-').toDoubleOption.contains(0.0))
        var best = Vector.empty[Int]
        def consider(run: Vector[Int]): Unit =
          if run.size > best.size ||
            (run.size == best.size && run.contains(zero) && !best.contains(zero))
          then best = run
        // Compare complete stride/offset classes first, so a dense prefix cannot beat an even
        // stride spanning the available breaks merely because its earlier labels are shorter.
        for stride <- 1 until measured.size; offset <- 0 until stride do
          val candidate = (offset until measured.size by stride).toVector
          if candidate.sliding(2).filter(_.size == 2).forall(p => clears(p(0), p(1))) then
            consider(candidate)
        // Very wide exterior labels can block every complete class. Only then allow shorter
        // clear runs, retaining two or more labels whenever any regularly strided run fits.
        if best.size < 2 then
          for stride <- 1 until measured.size; offset <- 0 until stride do
            var run = Vector.empty[Int]
            var index = offset
            while index < measured.size do
              if run.lastOption.exists(previous => !clears(previous, index)) then
                consider(run)
                run = Vector.empty
              run = run :+ index
              index += stride
            consider(run)
        val keep = best.map(i => measured(i)._1).toSet
        ticks.filter(keep.contains)

  def fit(
      ticks: Vector[AxisTick],
      position: AxisTick => Double,
      extent: AxisTick => Double,
      gap: Double
  ): Vector[AxisTick] =
    val sorted = ticks.sortBy(position)
    val priorities = (sorted.headOption.toVector ++ sorted.lastOption.toVector ++
      sorted.filter(t => t.label.replace('−', '-').toDoubleOption.contains(0.0)) ++ sorted).distinct
    val chosen = scala.collection.mutable.ArrayBuffer.empty[AxisTick]
    priorities.foreach { tick =>
      if chosen.forall(other =>
          math.abs(position(tick) - position(other)) + 1e-9 >=
            (extent(tick) + extent(other)) / 2.0 + gap
        )
      then chosen += tick
    }
    val keep = chosen.toSet
    ticks.filter(keep.contains)
