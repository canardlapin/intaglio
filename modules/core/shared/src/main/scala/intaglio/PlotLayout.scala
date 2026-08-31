package intaglio

import scala.util.control.NonFatal

/** Text-extent capability used by the layout solver to size axis strips and legend columns. The
  * portable default is a conservative estimate; platform backends may provide real font metrics
  * without changing the solver contract.
  */
trait TextMetrics:
  def widthPt(text: String, fontSizePt: Double): Double
  def heightPt(fontSizePt: Double): Double

  /** Family-aware entry points used by layout. Existing deterministic or synthetic providers can
    * implement only the size-based contract; platform providers override these methods to honor the
    * requested family.
    */
  def widthPt(text: String, style: TextStyle): Double =
    widthPt(text, style.fontSizePt)

  def heightPt(style: TextStyle): Double =
    heightPt(style.fontSizePt)

final case class TextStyle(fontFamily: Option[String], fontSizePt: Double):
  require(fontSizePt > 0.0 && fontSizePt.isFinite, "`fontSizePt` must be finite and > 0")

object TextMetrics:
  /** Conservative portable estimator: wide-glyph average advance and a standard line height, so
    * allocated regions err toward whitespace rather than overlap.
    */
  val estimate: TextMetrics =
    new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        text.length.toDouble * fontSizePt * 0.62

      override def heightPt(fontSizePt: Double): Double =
        fontSizePt * 1.25

/** Placement and sizing defaults for solved plot layouts. All sizes are in points; the solver
  * converts them to npc fractions of the reference device.
  */
final case class LayoutPolicy(
    metrics: TextMetrics = TextMetrics.estimate,
    referenceDevice: DeviceContext = DeviceContext.unsafe(640.0, 480.0),
    outerMarginPt: Double = 10.0,
    tickLengthPt: Double = 4.0,
    tickLabelGapPt: Double = 4.0,
    axisFontPt: Double = 10.0,
    axisTitleFontPt: Double = 11.0,
    axisTitleGapPt: Double = 6.0,
    plotTitleFontPt: Double = 16.0,
    plotSubtitleFontPt: Double = 12.0,
    plotLabelGapPt: Double = 4.0,
    legendFontPt: Double = 10.0,
    legendKeyPt: Double = 10.0,
    legendGapPt: Double = 10.0,
    legendPaddingPt: Double = 6.0,
    legendRowGapPt: Double = 4.0,
    legendTitleGapPt: Double = 4.0,
    guideStackGapPt: Double = 12.0,
    colorbarWidthPt: Double = 12.0,
    colorbarHeightPt: Double = 120.0,
    colorbarTickLengthPt: Double = 4.0,
    colorbarLabelGapPt: Double = 4.0,
    panelGapPt: Double = 8.0,
    facetStripPt: Double = 18.0,
    axisFontFamily: Option[String] = None,
    axisTitleFontFamily: Option[String] = None,
    plotTitleFontFamily: Option[String] = None,
    plotSubtitleFontFamily: Option[String] = None,
    legendFontFamily: Option[String] = None,
    legendTitleFontPt: Double = 10.0,
    legendTitleFontFamily: Option[String] = None
):
  require(outerMarginPt >= 0.0 && outerMarginPt.isFinite, "`outerMarginPt` must be finite and >= 0")
  require(tickLengthPt >= 0.0 && tickLengthPt.isFinite, "`tickLengthPt` must be finite and >= 0")
  require(
    tickLabelGapPt >= 0.0 && tickLabelGapPt.isFinite,
    "`tickLabelGapPt` must be finite and >= 0"
  )
  require(axisFontPt > 0.0 && axisFontPt.isFinite, "`axisFontPt` must be finite and > 0")
  require(
    axisTitleFontPt > 0.0 && axisTitleFontPt.isFinite,
    "`axisTitleFontPt` must be finite and > 0"
  )
  require(
    axisTitleGapPt >= 0.0 && axisTitleGapPt.isFinite,
    "`axisTitleGapPt` must be finite and >= 0"
  )
  require(
    plotTitleFontPt > 0.0 && plotTitleFontPt.isFinite,
    "`plotTitleFontPt` must be finite and > 0"
  )
  require(
    plotSubtitleFontPt > 0.0 && plotSubtitleFontPt.isFinite,
    "`plotSubtitleFontPt` must be finite and > 0"
  )
  require(
    plotLabelGapPt >= 0.0 && plotLabelGapPt.isFinite,
    "`plotLabelGapPt` must be finite and >= 0"
  )
  require(legendFontPt > 0.0 && legendFontPt.isFinite, "`legendFontPt` must be finite and > 0")
  require(
    legendTitleFontPt > 0.0 && legendTitleFontPt.isFinite,
    "`legendTitleFontPt` must be finite and > 0"
  )
  require(legendKeyPt >= 0.0 && legendKeyPt.isFinite, "`legendKeyPt` must be finite and >= 0")
  require(legendGapPt >= 0.0 && legendGapPt.isFinite, "`legendGapPt` must be finite and >= 0")
  require(
    legendPaddingPt >= 0.0 && legendPaddingPt.isFinite,
    "`legendPaddingPt` must be finite and >= 0"
  )
  require(
    legendRowGapPt >= 0.0 && legendRowGapPt.isFinite,
    "`legendRowGapPt` must be finite and >= 0"
  )
  require(
    legendTitleGapPt >= 0.0 && legendTitleGapPt.isFinite,
    "`legendTitleGapPt` must be finite and >= 0"
  )
  require(
    guideStackGapPt >= 0.0 && guideStackGapPt.isFinite,
    "`guideStackGapPt` must be finite and >= 0"
  )
  require(
    colorbarWidthPt > 0.0 && colorbarWidthPt.isFinite,
    "`colorbarWidthPt` must be finite and > 0"
  )
  require(
    colorbarHeightPt > 0.0 && colorbarHeightPt.isFinite,
    "`colorbarHeightPt` must be finite and > 0"
  )
  require(
    colorbarTickLengthPt >= 0.0 && colorbarTickLengthPt.isFinite,
    "`colorbarTickLengthPt` must be finite and >= 0"
  )
  require(
    colorbarLabelGapPt >= 0.0 && colorbarLabelGapPt.isFinite,
    "`colorbarLabelGapPt` must be finite and >= 0"
  )
  require(panelGapPt >= 0.0 && panelGapPt.isFinite, "`panelGapPt` must be finite and >= 0")
  require(facetStripPt > 0.0 && facetStripPt.isFinite, "`facetStripPt` must be finite and > 0")

  def axisTextStyle: TextStyle = TextStyle(axisFontFamily, axisFontPt)
  def axisTitleTextStyle: TextStyle = TextStyle(axisTitleFontFamily, axisTitleFontPt)
  def plotTitleTextStyle: TextStyle = TextStyle(plotTitleFontFamily, plotTitleFontPt)
  def plotSubtitleTextStyle: TextStyle = TextStyle(plotSubtitleFontFamily, plotSubtitleFontPt)
  def legendTextStyle: TextStyle = TextStyle(legendFontFamily, legendFontPt)
  def legendTitleTextStyle: TextStyle = TextStyle(legendTitleFontFamily, legendTitleFontPt)

/** Stable names for solver-allocated regions. */
object PlotRegion:
  val Panel: GraphicsName = GraphicsName.unsafe("plot-panel")
  val AxisBottom: GraphicsName = GraphicsName.unsafe("axis-bottom")
  val AxisLeft: GraphicsName = GraphicsName.unsafe("axis-left")
  val AxisTop: GraphicsName = GraphicsName.unsafe("axis-top")
  val AxisRight: GraphicsName = GraphicsName.unsafe("axis-right")
  val Legend: GraphicsName = GraphicsName.unsafe("legend-region")
  val Title: GraphicsName = GraphicsName.unsafe("plot-title")
  val Subtitle: GraphicsName = GraphicsName.unsafe("plot-subtitle")
  val PanelBackground: GraphicsName = GraphicsName.unsafe("plot-panel-background")
  val PanelGridX: GraphicsName = GraphicsName.unsafe("plot-panel-grid-x")
  val PanelGridY: GraphicsName = GraphicsName.unsafe("plot-panel-grid-y")

/** What the solver must make room for. */
final case class AxisRequest(labels: Vector[String], title: Option[String] = None)

final case class PlotLayoutRequest(
    axes: Map[AxisSide, AxisRequest] = Map.empty,
    legend: Option[LegendRequest] = None,
    labels: PlotLabels = PlotLabels(),
    panelAspect: Option[CoordinateRatio] = None,
    grid: Option[PanelGridRequest] = None
)

/** The guides to measure as one stacked column. `items` is the single representation; the
  * two-argument constructors below lift the common single-legend case into it.
  */
final case class LegendRequest(
    items: Vector[GuideLayoutRequest],
    extraKeyWidthPt: Double = 0.0
):
  require(
    extraKeyWidthPt >= 0.0 && extraKeyWidthPt.isFinite,
    "`extraKeyWidthPt` must be finite and >= 0"
  )

object LegendRequest:
  def apply(title: Option[String], labels: Vector[String]): LegendRequest =
    LegendRequest(Vector(GuideLayoutRequest.Legend(title, labels)))

  def apply(title: Option[String], labels: Vector[String], extraKeyWidthPt: Double): LegendRequest =
    LegendRequest(Vector(GuideLayoutRequest.Legend(title, labels)), extraKeyWidthPt)

enum GuideLayoutRequest:
  case Legend(title: Option[String], labels: Vector[String])
  case Colorbar(title: Option[String], labels: Vector[String])

sealed trait GuidePlacement

object GuidePlacement:
  final case class Legend(
      xPt: Double,
      topPt: Double,
      rowPitchPt: Double,
      firstRowOffsetPt: Double,
      labelOffsetPt: Double,
      markerSizePt: Double
  ) extends GuidePlacement:
    require(xPt.isFinite && topPt.isFinite, "legend placement origin must be finite")
    require(rowPitchPt.isFinite && rowPitchPt >= 0.0, "`rowPitchPt` must be finite and >= 0")
    require(
      firstRowOffsetPt.isFinite && firstRowOffsetPt >= 0.0,
      "`firstRowOffsetPt` must be finite and >= 0"
    )
    require(labelOffsetPt.isFinite, "`labelOffsetPt` must be finite")
    require(markerSizePt.isFinite && markerSizePt >= 0.0, "`markerSizePt` must be finite and >= 0")

  final case class Colorbar(
      xPt: Double,
      topPt: Double,
      barTopOffsetPt: Double,
      barWidthPt: Double,
      barHeightPt: Double,
      tickLengthPt: Double,
      labelOffsetPt: Double,
      titleOffsetPt: Double
  ) extends GuidePlacement:
    require(xPt.isFinite && topPt.isFinite, "colorbar placement origin must be finite")
    require(barTopOffsetPt.isFinite, "`barTopOffsetPt` must be finite")
    require(barWidthPt.isFinite && barWidthPt >= 0.0, "`barWidthPt` must be finite and >= 0")
    require(barHeightPt.isFinite && barHeightPt >= 0.0, "`barHeightPt` must be finite and >= 0")
    require(tickLengthPt.isFinite && tickLengthPt >= 0.0, "`tickLengthPt` must be finite and >= 0")
    require(
      labelOffsetPt.isFinite && labelOffsetPt >= 0.0,
      "`labelOffsetPt` must be finite and >= 0"
    )
    require(
      titleOffsetPt.isFinite && titleOffsetPt >= 0.0,
      "`titleOffsetPt` must be finite and >= 0"
    )

/** Measured guide column. `placements` holds exactly one entry per `LegendRequest.items` entry, in
  * the same order and of the matching variant, so callers can associate the two by position.
  */
final case class GuideStackPlan(
    widthPt: Double,
    heightPt: Double,
    placements: Vector[GuidePlacement]
)

/** Measures a complete legend/colorbar column in points. Placement is stored as offsets from the
  * top-left of the guide viewport so the same plan can be lowered deterministically by every
  * backend.
  */
object GuideStackSolver:
  def plan(policy: LayoutPolicy, request: LegendRequest): GuideStackPlan =
    val textHeight = policy.metrics.heightPt(policy.legendTextStyle)
    val titleHeight = policy.metrics.heightPt(policy.legendTitleTextStyle)
    val rowHeight = math.max(policy.legendKeyPt, textHeight)
    val rowPitch = rowHeight + policy.legendRowGapPt
    val placements = Vector.newBuilder[GuidePlacement]
    var widest = 0.0
    var nextTop = policy.legendPaddingPt
    val items = request.items
    var index = 0
    while index < items.length do
      val item = items(index)
      val (title, labels) = item match
        case GuideLayoutRequest.Legend(value, values)   => (value, values)
        case GuideLayoutRequest.Colorbar(value, values) => (value, values)
      val labelWidth = labels.foldLeft(0.0) { (acc, label) =>
        math.max(acc, policy.metrics.widthPt(label, policy.legendTextStyle))
      }
      val titleWidth =
        title.fold(0.0)(value => policy.metrics.widthPt(value, policy.legendTitleTextStyle))
      val titleBlock = title.fold(0.0)(_ => titleHeight + policy.legendTitleGapPt)
      val itemHeight = item match
        case GuideLayoutRequest.Legend(_, values) =>
          val rowsHeight =
            if values.isEmpty then 0.0
            else
              rowHeight * values.length.toDouble + policy.legendRowGapPt * (values.length - 1).toDouble
          val firstRowOffset =
            if title.nonEmpty then titleBlock + rowHeight / 2.0
            else rowHeight / 2.0
          placements += GuidePlacement.Legend(
            policy.legendPaddingPt,
            nextTop,
            rowPitch,
            firstRowOffset,
            policy.legendKeyPt + policy.legendGapPt / 2.0,
            policy.legendKeyPt
          )
          val entryWidth =
            policy.legendKeyPt + policy.legendGapPt / 2.0 + request.extraKeyWidthPt + labelWidth
          widest = math.max(widest, math.max(entryWidth, titleWidth))
          titleBlock + rowsHeight
        case GuideLayoutRequest.Colorbar(_, _) =>
          val labelInset = textHeight / 2.0
          val barTopOffset = titleBlock + labelInset
          placements += GuidePlacement.Colorbar(
            policy.legendPaddingPt,
            nextTop,
            barTopOffset,
            policy.colorbarWidthPt,
            policy.colorbarHeightPt,
            policy.colorbarTickLengthPt,
            policy.colorbarTickLengthPt + policy.colorbarLabelGapPt,
            policy.legendTitleGapPt + labelInset
          )
          val entryWidth =
            policy.colorbarWidthPt + policy.colorbarTickLengthPt + policy.colorbarLabelGapPt + labelWidth
          widest = math.max(widest, math.max(entryWidth, titleWidth))
          titleBlock + policy.colorbarHeightPt + textHeight
      nextTop += itemHeight
      if index + 1 < items.length then nextTop += policy.guideStackGapPt
      index += 1
    GuideStackPlan(
      widthPt = widest + policy.legendPaddingPt * 2.0,
      heightPt = nextTop + policy.legendPaddingPt,
      placements = placements.result()
    )

final case class PanelGridRequest(
    rows: Int,
    columns: Int,
    count: Int,
    columnGapPt: Option[Double] = None,
    rowGapPt: Option[Double] = None
):
  require(rows >= 1, "`rows` must be >= 1")
  require(columns >= 1, "`columns` must be >= 1")
  require(count >= 1 && count <= rows * columns, "`count` must fit in the panel grid")
  require(
    columnGapPt.forall(value => value.isFinite && value >= 0.0),
    "`columnGapPt` must be finite and >= 0"
  )
  require(
    rowGapPt.forall(value => value.isFinite && value >= 0.0),
    "`rowGapPt` must be finite and >= 0"
  )

final case class PanelGridFrame(
    row: Int,
    column: Int,
    panel: PanelFrame,
    strip: PanelFrame
)

/** Solved plot regions, all as npc frames of the whole plot area. */
final case class PlotFrames(
    panel: PanelFrame,
    axes: Map[AxisSide, PanelFrame],
    legend: Option[PanelFrame],
    title: Option[PanelFrame],
    subtitle: Option[PanelFrame],
    grid: Vector[PanelGridFrame] = Vector.empty
):
  def panelFrames: Vector[PanelFrame] =
    if grid.isEmpty then Vector(panel) else grid.map(_.panel)

  def legendViewport(clip: Clip = Clip.Off): Option[Viewport] =
    legend.map { frame =>
      Viewport.unsafe(
        origin = frame.origin,
        size = frame.size,
        clip = clip
      )
    }

  def titleViewport: Option[Viewport] =
    title.map(frameViewport)

  def subtitleViewport: Option[Viewport] =
    subtitle.map(frameViewport)

  private def frameViewport(frame: PanelFrame): Viewport =
    Viewport.unsafe(origin = frame.origin, size = frame.size, clip = Clip.Off)

/** Allocates panel, axis-strip, and legend regions from declared extents and estimated text sizes.
  * Deterministic and portable: no font access, no platform calls.
  */
object PlotLayoutSolver:
  private final class MeasurementException(val error: GraphicsError) extends RuntimeException

  def solve(
      policy: LayoutPolicy,
      request: PlotLayoutRequest
  ): Either[GraphicsError, PlotFrames] =
    val guarded = policy.copy(metrics = guardedMetrics(policy.metrics))
    try solveChecked(guarded, request)
    catch case error: MeasurementException => Left(error.error)

  private def guardedMetrics(metrics: TextMetrics): TextMetrics =
    new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        measure(metrics.widthPt(text, fontSizePt))

      override def heightPt(fontSizePt: Double): Double =
        measure(metrics.heightPt(fontSizePt))

      override def widthPt(text: String, style: TextStyle): Double =
        measure(metrics.widthPt(text, style))

      override def heightPt(style: TextStyle): Double =
        measure(metrics.heightPt(style))

  private def measure(value: => Double): Double =
    try value
    catch
      case NonFatal(error) =>
        val (exceptionType, detail) = GraphicsError.throwableDetails(error)
        throw new MeasurementException(
          GraphicsError.LayoutMeasurementFailed(exceptionType, detail)
        )

  private def solveChecked(
      policy: LayoutPolicy,
      request: PlotLayoutRequest
  ): Either[GraphicsError, PlotFrames] =
    val device = policy.referenceDevice
    val pxPerPt = device.pxPerUnit(LengthUnit.Point).getOrElse(96.0 / 72.0)
    def npcX(pt: Double): Double = pt * pxPerPt / device.width
    def npcY(pt: Double): Double = pt * pxPerPt / device.height

    val marginX = npcX(policy.outerMarginPt)
    val marginY = npcY(policy.outerMarginPt)

    def titleExtent(request: AxisRequest): Double =
      request.title.fold(0.0)(_ =>
        policy.axisTitleGapPt + policy.metrics.heightPt(policy.axisTitleTextStyle)
      )
    def axisStripPtY(request: AxisRequest): Double =
      policy.tickLengthPt + policy.tickLabelGapPt + policy.metrics.heightPt(
        policy.axisTextStyle
      ) + titleExtent(request)
    def axisStripPtX(request: AxisRequest): Double =
      val labelWidth = request.labels.foldLeft(0.0)((acc, label) =>
        math.max(acc, policy.metrics.widthPt(label, policy.axisTextStyle))
      )
      policy.tickLengthPt + policy.tickLabelGapPt + labelWidth + titleExtent(request)

    val bottom =
      request.axes.get(AxisSide.Bottom).map(axis => npcY(axisStripPtY(axis))).getOrElse(0.0)
    val top = request.axes.get(AxisSide.Top).map(axis => npcY(axisStripPtY(axis))).getOrElse(0.0)
    val left = request.axes.get(AxisSide.Left).map(axis => npcX(axisStripPtX(axis))).getOrElse(0.0)
    val right =
      request.axes.get(AxisSide.Right).map(axis => npcX(axisStripPtX(axis))).getOrElse(0.0)

    val titleHeight =
      request.labels.title.map(_ => npcY(policy.metrics.heightPt(policy.plotTitleTextStyle)))
    val subtitleHeight =
      request.labels.subtitle.map(_ => npcY(policy.metrics.heightPt(policy.plotSubtitleTextStyle)))
    val betweenLabels =
      if titleHeight.nonEmpty && subtitleHeight.nonEmpty then npcY(policy.plotLabelGapPt) else 0.0
    val belowLabels =
      if titleHeight.nonEmpty || subtitleHeight.nonEmpty then npcY(policy.plotLabelGapPt) else 0.0
    val headerHeight =
      titleHeight.getOrElse(0.0) + subtitleHeight.getOrElse(0.0) + betweenLabels + belowLabels

    val guideStack = request.legend.map(legend => GuideStackSolver.plan(policy, legend))
    val legendWidth = guideStack.map(plan => npcX(plan.widthPt))
    val legendGap = legendWidth.fold(0.0)(_ => npcX(policy.legendGapPt))

    val availableX0 = marginX + left
    val availableX1 = 1.0 - marginX - right - legendGap - legendWidth.getOrElse(0.0)
    val availableY0 = marginY + bottom
    val availableY1 = 1.0 - marginY - top - headerHeight

    if availableX1 <= availableX0 then Left(GraphicsError.LayoutOverflow("panel width"))
    else if availableY1 <= availableY0 then Left(GraphicsError.LayoutOverflow("panel height"))
    else
      val availableW = availableX1 - availableX0
      val availableH = availableY1 - availableY0
      val (panelX0, panelY0, panelW, panelH) =
        request.panelAspect match
          case None =>
            (availableX0, availableY0, availableW, availableH)
          case Some(aspect) =>
            val targetNpcAspect = aspect.toDouble * device.width / device.height
            if availableH / availableW > targetNpcAspect then
              val height = availableW * targetNpcAspect
              (availableX0, availableY0 + (availableH - height) / 2.0, availableW, height)
            else
              val width = availableH / targetNpcAspect
              (availableX0 + (availableW - width) / 2.0, availableY0, width, availableH)
      val panelHeightPt = panelH * device.height / pxPerPt
      if guideStack.exists(_.heightPt > panelHeightPt) then
        Left(GraphicsError.LayoutOverflow("guide stack height"))
      else
        for
          panel <- PanelFrame.npc(panelX0, panelY0, panelW, panelH)
          grid <- request.grid match
            case Some(spec) =>
              panelGridFrames(policy, spec, panelX0, panelY0, panelW, panelH, npcX, npcY)
            case None => Right(Vector.empty)
          axes <- axisFrames(request, panelX0, panelY0, panelW, panelH, bottom, top, left, right)
          legend <- legendWidth match
            case Some(width) =>
              PanelFrame.npc(availableX1 + right + legendGap, panelY0, width, panelH).map(Some(_))
            case None =>
              Right(None)
          subtitle <- subtitleHeight match
            case Some(height) =>
              PanelFrame.npc(panelX0, availableY1 + top + belowLabels, panelW, height).map(Some(_))
            case None => Right(None)
          title <- titleHeight match
            case Some(height) =>
              val y =
                availableY1 + top + belowLabels + subtitleHeight.getOrElse(0.0) + betweenLabels
              PanelFrame.npc(panelX0, y, panelW, height).map(Some(_))
            case None => Right(None)
        yield PlotFrames(panel, axes, legend, title, subtitle, grid)

  private def panelGridFrames(
      policy: LayoutPolicy,
      request: PanelGridRequest,
      panelX: Double,
      panelY: Double,
      panelW: Double,
      panelH: Double,
      npcX: Double => Double,
      npcY: Double => Double
  ): Either[GraphicsError, Vector[PanelGridFrame]] =
    val gapX = npcX(request.columnGapPt.getOrElse(policy.panelGapPt))
    val gapY = npcY(request.rowGapPt.getOrElse(policy.panelGapPt))
    val stripH = npcY(policy.facetStripPt)
    val cellW = (panelW - gapX * (request.columns - 1).toDouble) / request.columns.toDouble
    val blockH = (panelH - gapY * (request.rows - 1).toDouble) / request.rows.toDouble
    val dataH = blockH - stripH
    if cellW <= 0.0 then Left(GraphicsError.LayoutOverflow("facet panel width"))
    else if dataH <= 0.0 then Left(GraphicsError.LayoutOverflow("facet panel height"))
    else
      val out = Vector.newBuilder[PanelGridFrame]
      var index = 0
      var result: Either[GraphicsError, Unit] = Right(())
      while index < request.count && result.isRight do
        val row = index / request.columns
        val column = index % request.columns
        val x = panelX + column.toDouble * (cellW + gapX)
        val y = panelY + (request.rows - row - 1).toDouble * (blockH + gapY)
        result =
          for
            panel <- PanelFrame.npc(x, y, cellW, dataH)
            strip <- PanelFrame.npc(x, y + dataH, cellW, stripH)
          yield
            out += PanelGridFrame(row, column, panel, strip)
            ()
        index += 1
      result.map(_ => out.result())

  private def axisFrames(
      request: PlotLayoutRequest,
      panelX: Double,
      panelY: Double,
      panelW: Double,
      panelH: Double,
      bottom: Double,
      top: Double,
      left: Double,
      right: Double
  ): Either[GraphicsError, Map[AxisSide, PanelFrame]] =
    val out = Map.newBuilder[AxisSide, PanelFrame]
    var result: Either[GraphicsError, Unit] = Right(())
    def add(side: AxisSide, x: Double, y: Double, w: Double, h: Double): Unit =
      if result.isRight then
        result = PanelFrame.npc(x, y, w, h).map { frame =>
          out += side -> frame
          ()
        }
    if request.axes.contains(AxisSide.Bottom) then
      add(AxisSide.Bottom, panelX, panelY - bottom, panelW, bottom)
    if request.axes.contains(AxisSide.Top) then
      add(AxisSide.Top, panelX, panelY + panelH, panelW, top)
    if request.axes.contains(AxisSide.Left) then
      add(AxisSide.Left, panelX - left, panelY, left, panelH)
    if request.axes.contains(AxisSide.Right) then
      add(AxisSide.Right, panelX + panelW, panelY, right, panelH)
    result.map(_ => out.result())
