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

  private[intaglio] final case class PanelResolution(
      cell: FacetCell,
      layers: Vector[TrainedLayer],
      registry: PlotScaleRegistry,
      physicalRanges: (Interval, Interval),
      specs: Vector[GuideSpec]
  )

  /** The three facet-global values that the panel body must establish: the resolved panels, the
    * shared logical ranges, and the shared physical ranges.
    */
  private final case class FacetResolution(
      panels: Vector[PanelResolution],
      globalLogical: (Interval, Interval),
      globalPhysical: (Interval, Interval)
  )

  /** Data-side facet phases: panel membership, per-panel statistics, global and per-panel scale
    * training, row resolution, coordinate transforms, and guide specification. Device-independent.
    */
  private[intaglio] def train[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      options: PlotCompilerOptions,
      baseOptions: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlotData] =
    (options.layout, options.frame, options.policy) match
      case (None, None, Some(_)) =>
        trainWithSolver(plot, facet, options, baseOptions, exhaustive = false)
      case _ => Left(GraphicsError.FacetRequiresSolver)

  /** Test oracle: forces the general per-panel path even when both position dimensions share
    * scales. The shared-scale fast path must match this panel by panel.
    */
  private[intaglio] def trainExhaustive[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      options: PlotCompilerOptions,
      baseOptions: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlotData] =
    (options.layout, options.frame, options.policy) match
      case (None, None, Some(_)) =>
        trainWithSolver(plot, facet, options, baseOptions, exhaustive = true)
      case _ => Left(GraphicsError.FacetRequiresSolver)

  private def trainWithSolver[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      options: PlotCompilerOptions,
      baseOptions: PlotCompilerOptions,
      exhaustive: Boolean
  ): Either[GraphicsError, TrainedPlotData] =
    plot.coord.validateFacet.flatMap { _ =>
      val allData = plot.data ++ plot.layers.flatMap(_.facetSeedData(plot.data))
      for
        facetLayout <- PhaseClock.timed(PhaseClock.Phase.Mapping)(facet.layout(allData))
        panelStats <- transformPanels(plot, facet, facetLayout)
        globalScales <- PhaseClock.timed(PhaseClock.Phase.ScaleTraining)(
          ScalePhase.trainFacets(panelStats.flatMap(_.plans), options.theme)
        )
        resolution <-
          if !exhaustive && !facet.scales.xIsFree && !facet.scales.yIsFree then
            resolveSharedPanels(panelStats, globalScales, plot.coord, plot.labels, options)
          else
            resolveFreePanels(
              panelStats,
              globalScales,
              plot.coord,
              plot.labels,
              facet.scales,
              options
            )
        globalSpecs <- PhaseClock.timed(PhaseClock.Phase.Layout)(
          GuidePhase.specs(
            options.guides,
            plot.coord,
            globalScales.registry,
            Some(resolution.globalLogical),
            relativeLegend = true,
            labels = plot.labels
          )
        )
        nonPositionGuides = globalSpecs.collect {
          case guide: GuideSpec.Legend   => guide
          case guide: GuideSpec.Colorbar => guide
        }
        semantics <- PlotSemantics.build(
          plot.accessibility,
          plot.labels,
          resolution.panels.flatMap(_.layers),
          globalScales.registry
        )
      yield TrainedPlotData(
        plot.coord,
        plot.labels,
        baseOptions,
        TrainedStage.Faceted(
          facetLayout,
          facet.scales,
          resolution.panels,
          nonPositionGuides,
          resolution.globalPhysical,
          globalScales.registry,
          semantics
        )
      )
    }

  /** General path: one global resolution pass establishes the shared ranges, then every panel
    * trains, merges, and resolves its own scales. Required when either dimension is free; also the
    * semantic oracle for [[resolveSharedPanels]].
    */
  private def resolveFreePanels(
      panelStats: Vector[PanelStats],
      globalScales: ScaleResolution,
      coord: Coord,
      labels: PlotLabels,
      scales: FacetScales,
      options: PlotCompilerOptions
  ): Either[GraphicsError, FacetResolution] =
    for
      globalLayers <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
        PlotCompiler.resolveLayers(
          globalScales.plans,
          options.theme,
          options.provenance
        )
      )
      globalLogical <- LayoutPhase.panelRanges(globalLayers)
      globalCoordinates <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
        CoordPhase.transform(
          coord,
          globalLayers,
          Some(globalLogical),
          globalScales.registry
        )
      )
      globalPhysical <- requireRanges(globalCoordinates.ranges)
      panels <- resolvePanels(
        panelStats,
        globalScales.plans,
        globalLogical,
        coord,
        labels,
        scales,
        options
      )
    yield FacetResolution(panels, globalLogical, globalPhysical)

  /** Shared-scale fast path. With both position dimensions shared, `mergePositionScales` returns
    * the globally trained plan unchanged, so each panel's rows are resolved exactly once against
    * its global slice and the global view is the concatenation of the panels. Per-panel position
    * training, per-panel range scans, and per-panel guide specification are all redundant here: the
    * registry and axis specs are computed once and shared by every panel.
    */
  private def resolveSharedPanels(
      panelStats: Vector[PanelStats],
      globalScales: ScaleResolution,
      coord: Coord,
      labels: PlotLabels,
      options: PlotCompilerOptions
  ): Either[GraphicsError, FacetResolution] =
    val plansPerPanel = panelStats.headOption.fold(0)(_.plans.length)
    def slice(panelIndex: Int): Vector[PackedStatPlan] =
      globalScales.plans.slice(panelIndex * plansPerPanel, (panelIndex + 1) * plansPerPanel)
    for
      panelLayers <- traverse(panelStats.indices.toVector) { panelIndex =>
        PhaseClock.timed(PhaseClock.Phase.Resolve)(
          PlotCompiler.resolveLayers(slice(panelIndex), options.theme, options.provenance)
        )
      }
      allLayers = panelLayers.flatten
      globalLogical <- LayoutPhase.panelRanges(allLayers)
      globalCoordinates <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
        CoordPhase.transform(
          coord,
          allLayers,
          Some(globalLogical),
          globalScales.registry
        )
      )
      globalPhysical <- requireRanges(globalCoordinates.ranges)
      sharedRegistry = registry(slice(0))
      sharedSpecs <- PhaseClock.timed(PhaseClock.Phase.Layout)(
        GuidePhase.specs(
          axisPolicy(options.guides),
          coord,
          sharedRegistry,
          Some(globalLogical),
          relativeLegend = true,
          labels = labels
        )
      )
      axisSpecs = sharedSpecs.collect { case axis: GuideSpec.Axis => axis }
      panels <- traverse(panelStats.zip(panelLayers)) { case (panel, layers) =>
        for
          coordinates <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
            CoordPhase.transform(coord, layers, Some(globalLogical), sharedRegistry)
          )
          physical <- requireRanges(coordinates.ranges)
        yield PanelResolution(
          panel.cell,
          coordinates.layers,
          sharedRegistry,
          physical,
          axisSpecs
        )
      }
    yield FacetResolution(panels, globalLogical, globalPhysical)

  /** Device-side facet phases: axis measurement, grid layout solve, and panel, axis, guide, and
    * label lowering for one effective layout policy.
    */
  private[intaglio] def place(
      trained: TrainedPlotData,
      faceted: TrainedStage.Faceted,
      options: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlot] =
    (options.layout, options.frame, options.policy) match
      case (None, None, Some(policy)) => placeWithPolicy(trained, faceted, options, policy)
      case _                          => Left(GraphicsError.FacetRequiresSolver)

  private def placeWithPolicy(
      trained: TrainedPlotData,
      faceted: TrainedStage.Faceted,
      options: PlotCompilerOptions,
      policy: LayoutPolicy
  ): Either[GraphicsError, TrainedPlot] =
    val sizingAxes = PhaseClock.timed(PhaseClock.Phase.Layout)(
      representativeAxes(faceted.panels.flatMap(_.specs), policy)
    )
    for
      expandedGlobal <- trained.coord.expandRanges(
        options.expansion,
        faceted.globalRanges._1,
        faceted.globalRanges._2
      )
      frames <- PhaseClock.timed(PhaseClock.Phase.Layout)(
        PlotLayoutSolver.solve(
          policy,
          LayoutPhase.layoutRequest(
            sizingAxes ++ faceted.nonPositionGuides,
            expandedGlobal._1,
            expandedGlobal._2,
            trained.labels,
            panelAspect = None,
            grid = Some(
              facetGridRequest(faceted.facetLayout, faceted.facetScales, faceted.panels, policy)
            )
          )
        )
      )
      resolvedPanels <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        lowerPanels(faceted.panels, frames, trained.coord, options)
      )
      axes <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        lowerAxes(
          resolvedPanels,
          faceted.panels,
          faceted.facetLayout,
          faceted.facetScales,
          policy,
          options
        )
      )
      globalGuides <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        GuidePhase.lower(
          resolvedPanels.headOption.map(_.layout),
          Some(frames),
          faceted.nonPositionGuides,
          policy,
          options.theme
        )
      )
      labels <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        PlotLabelPhase.lower(trained.labels, Some(frames), options.theme.plotText)
      )
    yield TrainedPlot(
      layers = resolvedPanels.flatMap(_.layers),
      layout = resolvedPanels.headOption.map(_.layout),
      guides = axes ++ globalGuides,
      scaleRegistry = faceted.registry,
      panelGrobs = Vector.empty,
      labelGrobs = labels,
      facetPanels = resolvedPanels,
      semantics = faceted.semantics
    )

  private def transformPanels[Row](
      plot: Plot[Row],
      facet: FacetSpec[Row],
      layout: FacetLayout
  ): Either[GraphicsError, Vector[PanelStats]] =
    PhaseClock
      .timed(PhaseClock.Phase.Mapping)(MappingPhase.planPanels(plot, facet, layout))
      .flatMap { plansByPanel =>
        traverse(layout.cells.zip(plansByPanel)) { case (cell, plans) =>
          PhaseClock
            .timed(PhaseClock.Phase.Stat)(StatPhase.transform(plans))
            .map(PanelStats(cell, _))
        }
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
          if panel.plans.forall(plan =>
              plan.data.isEmpty && plan.annotation.forall(
                _.reference.scalePolicy != AnnotationScalePolicy.Train
              )
            )
          then Right(global)
          else
            PhaseClock.timed(PhaseClock.Phase.ScaleTraining)(
              ScalePhase.trainFacetPositions(panel.plans, scales, options.theme)
            )
        merged = global.zip(localPlans).map { case (globalPlan, localPlan) =>
          PackedStatPlan.mergePositionScales(globalPlan, localPlan, scales)
        }
        layers <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
          PlotCompiler.resolveLayers(merged, options.theme, options.provenance)
        )
        localRanges <- localRangesOrGlobal(layers, globalRanges)
        selected = (
          if scales.xIsFree then localRanges._1 else globalRanges._1,
          if scales.yIsFree then localRanges._2 else globalRanges._2
        )
        panelRegistry = registry(merged)
        specs <- PhaseClock.timed(PhaseClock.Phase.Layout)(
          GuidePhase.specs(
            axisPolicy(options.guides),
            coord,
            panelRegistry,
            Some(selected),
            relativeLegend = true,
            labels = labels
          )
        )
        coordinates <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
          CoordPhase.transform(coord, layers, Some(selected), panelRegistry)
        )
        physical <- requireRanges(coordinates.ranges)
      yield PanelResolution(
        panel.cell,
        coordinates.layers,
        panelRegistry,
        physical,
        specs.collect { case axis: GuideSpec.Axis => axis }
      )
    }

  private def registry(plans: Vector[PackedStatPlan]): PlotScaleRegistry =
    val scales = ScalePhase.declaredAesthetics(plans).flatMap { aesthetic =>
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

  private def representativeAxes(
      specs: Vector[GuideSpec],
      policy: LayoutPolicy
  ): Vector[GuideSpec] =
    AxisSide.values.toVector.flatMap { side =>
      specs
        .collect { case axis: GuideSpec.Axis if axis.side == side => axis }
        .maxByOption(axisExtentPt(_, policy))
    }

  private def axisExtentPt(axis: GuideSpec.Axis, policy: LayoutPolicy): Double =
    val title = axis.title.fold(0.0)(_ =>
      policy.axisTitleGapPt + policy.metrics.heightPt(policy.axisTitleTextStyle)
    )
    val labels =
      if axis.side.isHorizontal then policy.metrics.heightPt(policy.axisTextStyle)
      else
        axis.ticks.getOrElse(Vector.empty).foldLeft(0.0) { (maximum, tick) =>
          math.max(maximum, policy.metrics.widthPt(tick.label, policy.axisTextStyle))
        }
    policy.tickLengthPt + policy.tickLabelGapPt + labels + title

  private def facetGridRequest(
      layout: FacetLayout,
      scales: FacetScales,
      panels: Vector[PanelResolution],
      policy: LayoutPolicy
  ): PanelGridRequest =
    val axes = panels.flatMap(_.specs).collect { case axis: GuideSpec.Axis => axis }
    val columnGap = Option.when(scales.yIsFree) {
      policy.panelGapPt +
        maximumAxisExtent(axes, AxisSide.Left, policy) +
        maximumAxisExtent(axes, AxisSide.Right, policy)
    }
    val rowGap = Option.when(scales.xIsFree) {
      policy.panelGapPt +
        maximumAxisExtent(axes, AxisSide.Bottom, policy) +
        maximumAxisExtent(axes, AxisSide.Top, policy)
    }
    PanelGridRequest(
      layout.rows,
      layout.columns,
      layout.cells.length,
      columnGapPt = columnGap,
      rowGapPt = rowGap
    )

  private def maximumAxisExtent(
      axes: Vector[GuideSpec.Axis],
      side: AxisSide,
      policy: LayoutPolicy
  ): Double =
    axes.iterator
      .filter(_.side == side)
      .map(axisExtentPt(_, policy))
      .maxOption
      .getOrElse(0.0)

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
          expanded <- coord.expandRanges(
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
      scales: FacetScales,
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
        case axis: GuideSpec.Axis
            if rendersAxis(axis.side, panel.cell, scales, bottomByColumn, rightByRow) =>
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

  private def rendersAxis(
      side: AxisSide,
      cell: FacetCell,
      scales: FacetScales,
      bottomByColumn: Map[Int, Int],
      rightByRow: Map[Int, Int]
  ): Boolean =
    val dimensionIsFree =
      if side.isHorizontal then scales.xIsFree else scales.yIsFree
    dimensionIsFree || isOuter(side, cell, bottomByColumn, rightByRow)

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
