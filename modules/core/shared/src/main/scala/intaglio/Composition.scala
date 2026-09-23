package intaglio

/** How non-position guides participate in a multi-plot composition. */
enum CompositionGuidePolicy:
  /** Retain every plot's axes, legends, and colorbars in its own cell. */
  case KeepPerPlot

  /** Keep axes with their panels, move legends and colorbars to one composition-owned guide region,
    * and reuse compatible guides in stable first-use order.
    */
  case CollectCompatible

/** Target-aware but renderer-neutral composition policy. Gap sizes are physical points; `None`
  * selects the layout policy's ordinary panel gap.
  */
final class CompositionOptions private (
    val guides: CompositionGuidePolicy,
    val layoutPolicy: LayoutPolicy,
    val theme: Theme,
    val columnGapPt: Option[Double],
    val rowGapPt: Option[Double],
    val cellClip: Clip
)

object CompositionOptions:
  val default: CompositionOptions = unsafe()

  def apply(
      guides: CompositionGuidePolicy = CompositionGuidePolicy.KeepPerPlot,
      layoutPolicy: LayoutPolicy = LayoutPolicy(),
      theme: Theme = Theme.default,
      columnGapPt: Option[Double] = None,
      rowGapPt: Option[Double] = None,
      cellClip: Clip = Clip.On
  ): Either[GraphicsError, CompositionOptions] =
    for
      _ <- validateGap("column", columnGapPt)
      _ <- validateGap("row", rowGapPt)
    yield new CompositionOptions(guides, layoutPolicy, theme, columnGapPt, rowGapPt, cellClip)

  def unsafe(
      guides: CompositionGuidePolicy = CompositionGuidePolicy.KeepPerPlot,
      layoutPolicy: LayoutPolicy = LayoutPolicy(),
      theme: Theme = Theme.default,
      columnGapPt: Option[Double] = None,
      rowGapPt: Option[Double] = None,
      cellClip: Clip = Clip.On
  ): CompositionOptions =
    apply(guides, layoutPolicy, theme, columnGapPt, rowGapPt, cellClip).orThrow

  private def validateGap(
      axis: String,
      value: Option[Double]
  ): Either[GraphicsError, Unit] =
    value match
      case Some(gap) if !gap.isFinite || gap < 0.0 =>
        Left(GraphicsError.InvalidCompositionGap(axis, gap))
      case _ => Right(())

/** Explicit location and clipping contract for one composition inset. Bounds are fractions of the
  * whole composition in the ordinary y-up scene coordinate system.
  */
final class PlotInset private (val frame: PanelFrame, val clip: Clip):
  def viewport: Viewport =
    Viewport.unsafe(origin = frame.origin, size = frame.size, clip = clip)

object PlotInset:
  def npc(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      clip: Clip
  ): Either[GraphicsError, PlotInset] =
    if !validBounds(x, y, width, height) then
      Left(GraphicsError.InvalidInsetBounds(x, y, width, height))
    else PanelFrame.npc(x, y, width, height).map(new PlotInset(_, clip))

  def npcUnsafe(
      x: Double,
      y: Double,
      width: Double,
      height: Double,
      clip: Clip
  ): PlotInset =
    npc(x, y, width, height, clip).orThrow

  private def validBounds(x: Double, y: Double, width: Double, height: Double): Boolean =
    x.isFinite && y.isFinite && width.isFinite && height.isFinite &&
      x >= 0.0 && y >= 0.0 && width > 0.0 && height > 0.0 &&
      x + width <= 1.0 && y + height <= 1.0

/** Inspectable placement of one plot in a renderer-neutral composition. `panel` is the aligned
  * panel envelope in whole-composition coordinates; `viewport` is the affine transform applied to
  * the complete child plot, in the same coordinates. Clipping, when the composition asks for it,
  * applies to the whole cell rather than to that viewport, so a plot's axis text stays visible.
  */
final case class CompositionCell(
    index: Int,
    row: Int,
    column: Int,
    viewport: Viewport,
    panel: PanelFrame
)

/** Renderer-neutral result of arranging trained plots. */
final case class ComposedPlot(
    scene: Scene,
    cells: Vector[CompositionCell],
    collectedGuides: Vector[ResolvedGuide],
    context: RenderContext,
    insetCount: Int = 0
):
  def renderPlan: RenderPlan =
    RenderPlan(scene, context)

  /** Overlay a trained plot using the inset's explicit viewport and clipping policy. */
  def withInset(plot: TrainedPlot, inset: PlotInset): ComposedPlot =
    withInset(plot.scene, inset)

  /** Overlay an arbitrary renderer-neutral scene using the inset's explicit viewport and clipping
    * policy.
    */
  def withInset(insetScene: Scene, inset: PlotInset): ComposedPlot =
    val group = Grob.group(
      insetScene.grobs,
      viewport = Some(inset.viewport),
      name = Some(GraphicsName.unsafe(s"composition-inset-$insetCount"))
    )
    val combined =
      Scene(scene.grobs :+ group).withSemantics(scene.semantics ++ insetScene.semantics)
    copy(scene = combined, insetCount = insetCount + 1)

/** Aligned rows and grids built entirely from core scenes, grobs, and viewports. Input plots must
  * be trained against `context`; the same context then resolves the returned [[RenderPlan]].
  */
object PlotComposition:
  def row(
      plots: Vector[TrainedPlot],
      context: RenderContext,
      options: CompositionOptions = CompositionOptions.default
  ): Either[GraphicsError, ComposedPlot] =
    grid(plots, plots.length, context, options)

  def column(
      plots: Vector[TrainedPlot],
      context: RenderContext,
      options: CompositionOptions = CompositionOptions.default
  ): Either[GraphicsError, ComposedPlot] =
    grid(plots, 1, context, options)

  def grid(
      plots: Vector[TrainedPlot],
      columns: Int,
      context: RenderContext,
      options: CompositionOptions = CompositionOptions.default
  ): Either[GraphicsError, ComposedPlot] =
    for
      _ <- validateGrid(plots.length, columns)
      sourcePanels <- traverse(plots.zipWithIndex) { case (plot, index) =>
        panelEnvelope(plot, index, context)
      }
      rightEdges <- traverse(plots)(retainedRightEdge(_, options.guides, context))
      strips = stripsFor(sourcePanels, rightEdges, context)
      policy = context.layoutPolicy(options.layoutPolicy)
      uniqueGuides = collectedGuideSpecs(plots, options.guides)
      guideLayout <- layoutGuides(uniqueGuides, context, policy, options)
      gridFrames <- cellFrames(
        plots.length,
        columns,
        guideLayout.content,
        context,
        policy,
        options
      )
      built <- buildCells(plots, sourcePanels, gridFrames, columns, strips, context, options)
    yield
      val semantics = built.scenes.foldLeft(SceneSemantics.empty)(_ ++ _.semantics)
      val grobs = built.groups ++ guideLayout.guides.map(_.grob)
      ComposedPlot(
        Scene(grobs).withSemantics(semantics),
        built.cells,
        guideLayout.guides,
        context
      )

  private final case class NormalizedFrame(x: Double, y: Double, width: Double, height: Double):
    def right: Double = x + width
    def top: Double = y + height

  /** The aligned panel within one cell, as fractions of that cell. */
  private final case class Alignment(
      left: Double,
      bottom: Double,
      panelWidth: Double,
      panelHeight: Double
  )

  /** The widest strip any plot reserves on each side of its panel --- left, bottom, right, top ---
    * in device pixels. Strips hold point-sized ticks, labels and titles, so they keep their
    * physical size in every cell; only the panel between them takes up a cell's remaining room.
    */
  private type StripsPx = (Double, Double, Double, Double)

  private final case class GuideLayout(
      content: NormalizedFrame,
      guides: Vector[ResolvedGuide]
  )

  private final case class BuiltCells(
      groups: Vector[Grob],
      scenes: Vector[Scene],
      cells: Vector[CompositionCell]
  )

  private sealed trait GuideKey

  private object GuideKey:
    final case class Legend(
        title: Option[String],
        entries: Vector[LegendEntry],
        titleGp: Option[GraphicParams],
        labelGp: Option[GraphicParams],
        name: Option[GraphicsName]
    ) extends GuideKey

    final case class Colorbar(
        title: Option[String],
        colors: Vector[Rgba],
        ticks: Vector[AxisTick],
        tickGp: Option[GraphicParams],
        titleGp: Option[GraphicParams],
        labelGp: Option[GraphicParams],
        name: Option[GraphicsName]
    ) extends GuideKey

  private def validateGrid(plotCount: Int, columns: Int): Either[GraphicsError, Unit] =
    if plotCount < 1 || columns < 1 || columns > plotCount then
      Left(GraphicsError.InvalidCompositionGrid(plotCount, columns))
    else Right(())

  private def panelEnvelope(
      plot: TrainedPlot,
      index: Int,
      context: RenderContext
  ): Either[GraphicsError, NormalizedFrame] =
    val frames =
      if plot.facetPanels.nonEmpty then plot.facetPanels.map(_.layout.frame)
      else plot.layout.map(_.frame).toVector
    if frames.isEmpty then
      Left(GraphicsError.InvalidCompositionPanel(index, "plot was compiled without a layout"))
    else
      traverse(frames)(resolveFrame(_, context)).flatMap { resolved =>
        val x0 = resolved.map(_.x).min
        val y0 = resolved.map(_.y).min
        val x1 = resolved.map(_.right).max
        val y1 = resolved.map(_.top).max
        validatePanel(index, NormalizedFrame(x0, y0, x1 - x0, y1 - y0))
      }

  private def resolveFrame(
      frame: PanelFrame,
      context: RenderContext
  ): Either[GraphicsError, NormalizedFrame] =
    val device = context.deviceContext
    val resolver = new LengthResolver(
      device,
      DeviceFrame.root(device),
      context.fontRegistry,
      context.lineHeightPt
    )
    for
      x <- resolver.x(frame.origin.x)
      lowerY <- resolver.y(frame.origin.y)
      width <- resolver.width(frame.size.width)
      height <- resolver.height(frame.size.height)
    yield NormalizedFrame(
      x / context.width.toDouble,
      (context.height.toDouble - lowerY) / context.height.toDouble,
      width / context.width.toDouble,
      height / context.height.toDouble
    )

  private def validatePanel(
      index: Int,
      frame: NormalizedFrame
  ): Either[GraphicsError, NormalizedFrame] =
    val contained =
      frame.x.isFinite && frame.y.isFinite && frame.width.isFinite && frame.height.isFinite &&
        frame.x >= 0.0 && frame.y >= 0.0 && frame.width > 0.0 && frame.height > 0.0 &&
        frame.right <= 1.0 && frame.top <= 1.0
    if contained then Right(frame)
    else
      Left(
        GraphicsError.InvalidCompositionPanel(
          index,
          s"resolved npc bounds (${frame.x}, ${frame.y}, ${frame.width}, ${frame.height}) must be positive and contained in [0, 1]"
        )
      )

  private def stripsFor(
      panels: Vector[NormalizedFrame],
      rightEdges: Vector[Double],
      context: RenderContext
  ): StripsPx =
    val width = context.width.toDouble
    val height = context.height.toDouble
    (
      panels.map(_.x * width).max,
      panels.map(_.y * height).max,
      panels.zip(rightEdges).map((panel, edge) => (edge - panel.right) * width).max,
      panels.map(panel => (1.0 - panel.top) * height).max
    )

  /** Where a plot's retained content ends on the right, as a fraction of its canvas. A plot's own
    * legend column is the rightmost thing it draws; when the composition collects its legends and
    * colorbars into one column of its own, the plot no longer draws them, so its right strip ends
    * where that column began rather than at the canvas edge.
    */
  private def retainedRightEdge(
      plot: TrainedPlot,
      policy: CompositionGuidePolicy,
      context: RenderContext
  ): Either[GraphicsError, Double] =
    policy match
      case CompositionGuidePolicy.KeepPerPlot       => Right(1.0)
      case CompositionGuidePolicy.CollectCompatible =>
        val collected = plot.guides.flatMap {
          case ResolvedGuide(_: GuideSpec.Axis, _) => None
          case ResolvedGuide(_, grob)              => grob.viewport
        }
        traverse(collected)(viewport =>
          resolveFrame(PanelFrame(viewport.origin, viewport.size), context)
        ).map(frames => frames.map(_.x).minOption.fold(1.0)(math.min(1.0, _)))

  private def alignmentFor(
      strips: StripsPx,
      cell: NormalizedFrame,
      context: RenderContext
  ): Either[GraphicsError, Alignment] =
    val (leftPx, bottomPx, rightPx, topPx) = strips
    val cellWidthPx = cell.width * context.width.toDouble
    val cellHeightPx = cell.height * context.height.toDouble
    val width = 1.0 - (leftPx + rightPx) / cellWidthPx
    val height = 1.0 - (bottomPx + topPx) / cellHeightPx
    if width <= 0.0 then Left(GraphicsError.LayoutOverflow("aligned composition panel width"))
    else if height <= 0.0 then
      Left(GraphicsError.LayoutOverflow("aligned composition panel height"))
    else Right(Alignment(leftPx / cellWidthPx, bottomPx / cellHeightPx, width, height))

  private def collectedGuideSpecs(
      plots: Vector[TrainedPlot],
      policy: CompositionGuidePolicy
  ): Vector[GuideSpec] =
    policy match
      case CompositionGuidePolicy.KeepPerPlot       => Vector.empty
      case CompositionGuidePolicy.CollectCompatible =>
        val out = Vector.newBuilder[GuideSpec]
        var seen = Set.empty[GuideKey]
        plots.iterator.flatMap(_.guides.iterator.map(_.spec)).foreach { spec =>
          guideKey(spec).foreach { key =>
            if !seen.contains(key) then
              seen += key
              out += spec
          }
        }
        out.result()

  private def guideKey(spec: GuideSpec): Option[GuideKey] =
    spec match
      case legend: GuideSpec.Legend =>
        Some(
          GuideKey.Legend(
            legend.title,
            legend.entries,
            legend.titleGp,
            legend.labelGp,
            legend.name
          )
        )
      case colorbar: GuideSpec.Colorbar =>
        Some(
          GuideKey.Colorbar(
            colorbar.title,
            colorbar.colors,
            colorbar.ticks,
            colorbar.tickGp,
            colorbar.titleGp,
            colorbar.labelGp,
            colorbar.name
          )
        )
      case _: GuideSpec.Axis => None

  private def layoutGuides(
      specs: Vector[GuideSpec],
      context: RenderContext,
      policy: LayoutPolicy,
      options: CompositionOptions
  ): Either[GraphicsError, GuideLayout] =
    if specs.isEmpty then Right(GuideLayout(NormalizedFrame(0.0, 0.0, 1.0, 1.0), Vector.empty))
    else
      val requests = specs.map(guideRequest)
      val request = LegendRequest(requests)
      for
        frames <- PlotLayoutSolver.solve(policy, PlotLayoutRequest(legend = Some(request)))
        content <- resolveFrame(frames.panel, context)
        viewport <- frames
          .legendViewport(Clip.Off)
          .toRight(GraphicsError.LayoutOverflow("collected guide region"))
        guides <- lowerCollectedGuides(specs, request, viewport, policy, options.theme)
      yield GuideLayout(content, guides)

  private def guideRequest(spec: GuideSpec): GuideLayoutRequest =
    spec match
      case legend: GuideSpec.Legend =>
        GuideLayoutRequest.Legend(legend.title, legend.entries.map(_.label))
      case colorbar: GuideSpec.Colorbar =>
        GuideLayoutRequest.Colorbar(colorbar.title, colorbar.ticks.map(_.label))
      case _: GuideSpec.Axis =>
        throw new IllegalArgumentException("position guides cannot enter a collected guide region")

  private def lowerCollectedGuides(
      specs: Vector[GuideSpec],
      request: LegendRequest,
      viewport: Viewport,
      policy: LayoutPolicy,
      theme: Theme
  ): Either[GraphicsError, Vector[ResolvedGuide]] =
    val placements = GuideStackSolver.plan(policy, request).placements
    val unit = PanelLayout.unit(Interval.unsafe(0.0, 1.0), Interval.unsafe(0.0, 1.0))
    traverse(specs.zip(placements)) { case (spec, placement) =>
      GuideSpec.lower(GuideSpec.place(spec, placement), unit, Some(viewport), policy, theme)
    }

  private def cellFrames(
      count: Int,
      columns: Int,
      content: NormalizedFrame,
      context: RenderContext,
      policy: LayoutPolicy,
      options: CompositionOptions
  ): Either[GraphicsError, Vector[NormalizedFrame]] =
    val rows = (count + columns - 1) / columns
    val pxPerPt = context.pixelsPerInch / 72.0
    val gapX = options.columnGapPt.getOrElse(policy.panelGapPt) * pxPerPt / context.width.toDouble
    val gapY = options.rowGapPt.getOrElse(policy.panelGapPt) * pxPerPt / context.height.toDouble
    val cellWidth = (content.width - gapX * (columns - 1).toDouble) / columns.toDouble
    val cellHeight = (content.height - gapY * (rows - 1).toDouble) / rows.toDouble
    if cellWidth <= 0.0 then Left(GraphicsError.LayoutOverflow("composition cell width"))
    else if cellHeight <= 0.0 then Left(GraphicsError.LayoutOverflow("composition cell height"))
    else
      Right(
        Vector.tabulate(count) { index =>
          val row = index / columns
          val column = index % columns
          NormalizedFrame(
            content.x + column.toDouble * (cellWidth + gapX),
            content.y + (rows - row - 1).toDouble * (cellHeight + gapY),
            cellWidth,
            cellHeight
          )
        }
      )

  private def buildCells(
      plots: Vector[TrainedPlot],
      sourcePanels: Vector[NormalizedFrame],
      frames: Vector[NormalizedFrame],
      columns: Int,
      strips: StripsPx,
      context: RenderContext,
      options: CompositionOptions
  ): Either[GraphicsError, BuiltCells] =
    val groups = Vector.newBuilder[Grob]
    val scenes = Vector.newBuilder[Scene]
    val cells = Vector.newBuilder[CompositionCell]
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < plots.length && result.isRight do
      val source = sourcePanels(index)
      val cell = frames(index)
      val child = childScene(plots(index), options.guides)
      result =
        for
          // The aligned panel, as fractions of this cell: the cell less every plot's widest strips.
          alignment <- alignmentFor(strips, cell, context)
          scaleX = alignment.panelWidth / source.width
          scaleY = alignment.panelHeight / source.height
          offsetX = alignment.left - scaleX * source.x
          offsetY = alignment.bottom - scaleY * source.y
          origin <- Point.npc(
            cell.x + cell.width * offsetX,
            cell.y + cell.height * offsetY
          )
          size <- Size.npc(cell.width * scaleX, cell.height * scaleY)
          viewport <- Viewport.checked(origin = origin, size = size, clip = Clip.Off)
          // The clip is the cell, not the child's scaled canvas: the canvas shrinks with its panel
          // while its point-sized strips do not, so its edge falls inside the strips' text.
          cellOrigin <- Point.npc(cell.x, cell.y)
          cellSize <- Size.npc(cell.width, cell.height)
          cellViewport <- Viewport.checked(
            origin = cellOrigin,
            size = cellSize,
            clip = options.cellClip
          )
          childOrigin <- Point.npc(offsetX, offsetY)
          childSize <- Size.npc(scaleX, scaleY)
          childViewport <- Viewport.checked(origin = childOrigin, size = childSize, clip = Clip.Off)
          panel <- PanelFrame.npc(
            cell.x + cell.width * alignment.left,
            cell.y + cell.height * alignment.bottom,
            cell.width * alignment.panelWidth,
            cell.height * alignment.panelHeight
          )
        yield
          groups += Grob.group(
            Vector(Grob.group(child.grobs, viewport = Some(childViewport), name = None)),
            viewport = Some(cellViewport),
            name = Some(GraphicsName.unsafe(s"composition-cell-$index"))
          )
          scenes += child
          cells += CompositionCell(
            index,
            index / columns,
            index % columns,
            viewport,
            panel
          )
          ()
      index += 1
    result.map(_ => BuiltCells(groups.result(), scenes.result(), cells.result()))

  private def childScene(plot: TrainedPlot, policy: CompositionGuidePolicy): Scene =
    policy match
      case CompositionGuidePolicy.KeepPerPlot       => plot.scene
      case CompositionGuidePolicy.CollectCompatible =>
        val axes = plot.guides.collect { case guide @ ResolvedGuide(_: GuideSpec.Axis, _) => guide }
        plot.copy(guides = axes).scene

  private def traverse[A, B](values: Vector[A])(
      f: A => Either[GraphicsError, B]
  ): Either[GraphicsError, Vector[B]] =
    val out = Vector.newBuilder[B]
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < values.length && result.isRight do
      result = f(values(index)).map { value =>
        out += value
        ()
      }
      index += 1
    result.map(_ => out.result())
