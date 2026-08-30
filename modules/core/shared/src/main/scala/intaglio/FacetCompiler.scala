package intaglio

/** Compiler branch for small multiples. Facet membership is resolved before statistics, while scale
  * training happens over the resulting statistical frames. This keeps panel-local summaries honest
  * and plot-global scales coherent.
  */
private[intaglio] object FacetCompiler:
  private final case class PanelStats(
      cell: FacetCell,
      plans: Vector[PackedStatPlan]
  )

  private final case class PanelResolution(
      cell: FacetCell,
      layers: Vector[TrainedLayer],
      registry: PlotScaleRegistry,
      physicalRanges: (Interval, Interval),
      specs: Vector[GuideSpec]
  )

  def resolve[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      options: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlot] =
    (options.layout, options.frame, options.policy) match
      case (None, None, Some(policy)) => resolveWithPolicy(plot, facet, options, policy)
      case _                          => Left(GraphicsError.FacetRequiresSolver)

  private def resolveWithPolicy[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      options: PlotCompilerOptions,
      policy: LayoutPolicy
  ): Either[GraphicsError, TrainedPlot] =
    plot.coord match
      case _: Coord.Fixed =>
        Left(GraphicsError.FacetFixedCoordinates)
      case _ =>
        val allData = plot.data ++ plot.layers.flatMap(_.facetSeedData(plot.data))
        for
          facetLayout <- facet.layout(allData)
          panelStats <- transformPanels(plot, facet, facetLayout)
          globalScales <- ScalePhase.trainFacets(panelStats.flatMap(_.plans))
          globalLayers <- PlotCompiler.resolveLayers(globalScales.plans, options.theme)
          globalLogical <- LayoutPhase.panelRanges(globalLayers)
          globalCoordinates <- CoordPhase.transform(plot.coord, globalLayers, Some(globalLogical))
          globalPhysical <- requireRanges(globalCoordinates.ranges)
          panels <- resolvePanels(
            panelStats,
            globalScales.plans,
            globalLogical,
            plot.coord,
            plot.labels,
            facet.scales,
            options
          )
          globalSpecs <- GuidePhase.specs(
            options.guides,
            plot.coord,
            globalScales.registry,
            Some(globalLogical),
            relativeLegend = true,
            labels = plot.labels
          )
          nonPositionGuides = globalSpecs.collect {
            case guide: GuideSpec.Legend   => guide
            case guide: GuideSpec.Colorbar => guide
          }
          sizingAxes = representativeAxes(panels.flatMap(_.specs))
          expandedGlobal <- LayoutPhase.expandedRanges(
            options.expansion,
            globalPhysical._1,
            globalPhysical._2
          )
          frames <- PlotLayoutSolver.solve(
            policy,
            LayoutPhase.layoutRequest(
              sizingAxes ++ nonPositionGuides,
              expandedGlobal._1,
              expandedGlobal._2,
              plot.labels,
              panelAspect = None,
              grid = Some(
                PanelGridRequest(facetLayout.rows, facetLayout.columns, facetLayout.cells.length)
              )
            )
          )
          resolvedPanels <- lowerPanels[Row](panels, frames, plot.coord, options)
          axes <- lowerAxes(resolvedPanels, panels, facetLayout, policy, options)
          globalGuides <- GuidePhase.lower(
            resolvedPanels.headOption.map(_.layout),
            Some(frames),
            nonPositionGuides,
            policy,
            options.theme
          )
          labels <- PlotLabelPhase.lower(plot.labels, Some(frames), options.theme.plotText)
        yield TrainedPlot(
          layers = resolvedPanels.flatMap(_.layers),
          layout = resolvedPanels.headOption.map(_.layout),
          guides = axes ++ globalGuides,
          scaleRegistry = globalScales.registry,
          panelGrobs = Vector.empty,
          labelGrobs = labels,
          facetPanels = resolvedPanels
        )

  private def transformPanels[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      layout: FacetLayout
  ): Either[GraphicsError, Vector[PanelStats]] =
    traverse(layout.cells) { cell =>
      for
        plans <- MappingPhase.planPanel(plot, facet, cell)
        stats <- StatPhase.transform(plans)
      yield PanelStats(cell, stats)
    }

  private def resolvePanels(
      panels: Vector[PanelStats],
      globallyTrained: Vector[PackedStatPlan],
      globalRanges: (Interval, Interval),
      coord: Coord,
      labels: PlotLabels,
      scales: FacetScales,
      options: PlotCompilerOptions
  ): Either[GraphicsError, Vector[PanelResolution]] =
    val plansPerPanel = panels.headOption.fold(0)(_.plans.length)
    traverse(panels.zipWithIndex) { case (panel, panelIndex) =>
      val global =
        globallyTrained.slice(panelIndex * plansPerPanel, (panelIndex + 1) * plansPerPanel)
      for
        localPlans <-
          if panel.plans.forall(_.data.isEmpty) then Right(global)
          else ScalePhase.trainFacetPositions(panel.plans, scales)
        merged = global.zip(localPlans).map { case (globalPlan, localPlan) =>
          PackedStatPlan.mergePositionScales(globalPlan, localPlan, scales)
        }
        layers <- PlotCompiler.resolveLayers(merged, options.theme)
        localRanges <- localRangesOrGlobal(layers, globalRanges)
        selected = (
          if scales.xIsFree then localRanges._1 else globalRanges._1,
          if scales.yIsFree then localRanges._2 else globalRanges._2
        )
        specs <- GuidePhase.specs(
          axisPolicy(options.guides),
          coord,
          registry(merged),
          Some(selected),
          relativeLegend = true,
          labels = labels
        )
        coordinates <- CoordPhase.transform(coord, layers, Some(selected))
        physical <- requireRanges(coordinates.ranges)
      yield PanelResolution(
        panel.cell,
        coordinates.layers,
        registry(merged),
        physical,
        specs.collect { case axis: GuideSpec.Axis => axis }
      )
    }

  private def registry(plans: Vector[PackedStatPlan]): PlotScaleRegistry =
    val scales = Aesthetic.values.toVector.flatMap { aesthetic =>
      plans.iterator.flatMap(_.mapping.scaledEntry(aesthetic)).take(1).map(_.trained)
    }
    PlotScaleRegistry.from(scales)

  private def localRangesOrGlobal(
      layers: Vector[TrainedLayer],
      global: (Interval, Interval)
  ): Either[GraphicsError, (Interval, Interval)] =
    LayoutPhase.panelRanges(layers) match
      case Left(GraphicsError.EmptyContinuousRange) => Right(global)
      case result                                   => result

  private def axisPolicy(policy: GuidePolicy): GuidePolicy =
    policy match
      case GuidePolicy.NoGuides        => GuidePolicy.NoGuides
      case GuidePolicy.Explicit(specs) =>
        GuidePolicy.Explicit(specs.collect { case axis: GuideSpec.Axis => axis })
      case GuidePolicy.Derived(overrides, _) =>
        GuidePolicy.Derived(
          overrides.collect { case axis: GuideSpec.Axis => axis },
          deriveLegends = false
        )

  private def representativeAxes(specs: Vector[GuideSpec]): Vector[GuideSpec] =
    AxisSide.values.toVector.flatMap { side =>
      specs
        .collect { case axis: GuideSpec.Axis if axis.side == side => axis }
        .maxByOption(axisWeight)
    }

  private def axisWeight(axis: GuideSpec.Axis): Int =
    axis.ticks.fold(0)(_.foldLeft(0)((total, tick) => total + tick.label.length)) +
      axis.title.fold(0)(_.length)

  private def lowerPanels[Row](
      panels: Vector[PanelResolution],
      frames: PlotFrames,
      coord: Coord,
      options: PlotCompilerOptions
  ): Either[GraphicsError, Vector[ResolvedFacetPanel]] =
    if frames.grid.length != panels.length then Left(GraphicsError.EmptyFacet)
    else
      traverse(panels.zip(frames.grid)) { case (panel, frame) =>
        for
          expanded <- LayoutPhase.expandedRanges(
            options.expansion,
            panel.physicalRanges._1,
            panel.physicalRanges._2
          )
          layout = PanelLayout(
            frame.panel,
            expanded._1,
            expanded._2,
            options.margins,
            LayoutPhase.coordClip(coord)
          )
          decoration <- PanelPhase.lower(Some(layout), panel.specs, options.theme.panel)
          strip <- stripGrob(panel.cell, frame.strip, options.theme.axis.text)
        yield ResolvedFacetPanel(
          panel.cell,
          layout,
          panel.layers,
          panel.registry,
          decoration,
          strip
        )
      }

  private def stripGrob(
      cell: FacetCell,
      frame: PanelFrame,
      gp: GraphicParams
  ): Either[GraphicsError, Grob] =
    val viewport = Viewport.unsafe(
      origin = frame.origin,
      size = frame.size,
      xScale = Interval.unsafe(0.0, 1.0),
      yScale = Interval.unsafe(0.0, 1.0),
      clip = Clip.Off
    )
    Grob.text(
      cell.label,
      Point.npcUnsafe(0.5, 0.5),
      gp = gp,
      viewport = Some(viewport),
      name = Some(cell.stripName)
    )

  private def lowerAxes[Row](
      resolved: Vector[ResolvedFacetPanel],
      panels: Vector[PanelResolution],
      facetLayout: FacetLayout,
      policy: LayoutPolicy,
      options: PlotCompilerOptions
  ): Either[GraphicsError, Vector[ResolvedGuide]] =
    val cells = facetLayout.cells
    val bottomByColumn = cells.groupBy(_.column).view.mapValues(_.map(_.row).max).toMap
    val rightByRow = cells.groupBy(_.row).view.mapValues(_.map(_.column).max).toMap
    val out = Vector.newBuilder[ResolvedGuide]
    var result: Either[GraphicsError, Unit] = Right(())
    var panelIndex = 0
    while panelIndex < resolved.length && result.isRight do
      val panel = resolved(panelIndex)
      val specs = panels(panelIndex).specs.collect {
        case axis: GuideSpec.Axis if isOuter(axis.side, panel.cell, bottomByColumn, rightByRow) =>
          axis.copy(name = Some(axisName(axis, panel.cell)))
      }
      var specIndex = 0
      while specIndex < specs.length && result.isRight do
        result = GuideSpec
          .lower(specs(specIndex), panel.layout, None, policy, options.theme)
          .map { guide =>
            out += guide
            ()
          }
        specIndex += 1
      panelIndex += 1
    result.map(_ => out.result())

  private def isOuter(
      side: AxisSide,
      cell: FacetCell,
      bottomByColumn: Map[Int, Int],
      rightByRow: Map[Int, Int]
  ): Boolean =
    side match
      case AxisSide.Bottom => bottomByColumn.get(cell.column).contains(cell.row)
      case AxisSide.Top    => cell.row == 0
      case AxisSide.Left   => cell.column == 0
      case AxisSide.Right  => rightByRow.get(cell.row).contains(cell.column)

  private def axisName(axis: GuideSpec.Axis, cell: FacetCell): GraphicsName =
    val base = axis.name.fold(axis.side.toString.toLowerCase)(_.value)
    GraphicsName.unsafe(s"$base-${cell.row}-${cell.column}")

  private def requireRanges(
      ranges: Option[(Interval, Interval)]
  ): Either[GraphicsError, (Interval, Interval)] =
    ranges.toRight(GraphicsError.MissingLayout("facet panel ranges"))

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
