package intaglio

/** How the compiler determines the plot's guides.
  *
  *   - [[GuidePolicy.NoGuides]] — no guides; a layout is optional.
  *   - [[GuidePolicy.Explicit]] — exactly the given specs.
  *   - [[GuidePolicy.Derived]] — routine axes and legends derive from the layers' trained scales
  *     (or from the panel ranges when a position is unscaled). An explicit override axis suppresses
  *     the derived axis on the same side, and any explicit legend suppresses derived legends;
  *     overrides are always included.
  */
enum GuidePolicy:
  case NoGuides
  case Explicit(specs: Vector[GuideSpec])
  case Derived(overrides: Vector[GuideSpec] = Vector.empty, deriveLegends: Boolean = true)

  def requiresLayout: Boolean =
    this match
      case NoGuides        => false
      case Explicit(specs) => specs.nonEmpty
      case Derived(_, _)   => true

/** Padding applied to compiler-derived panel ranges after scale training and guide derivation.
  * `multiplicative` is a fraction of the trained width; `additive` is in panel-native units.
  * Degenerate ranges use `zeroWidth` as their reference width so a single point still receives
  * visible framing.
  */
final case class RangeExpansion private (
    multiplicative: Double,
    additive: Double,
    zeroWidth: Double
):
  def expand(interval: Interval): Either[GraphicsError, Interval] =
    if isNone then Right(interval)
    else
      val referenceWidth = if interval.width == 0.0 then zeroWidth else interval.width
      val padding = referenceWidth * multiplicative + additive
      Interval(interval.lower - padding, interval.upper + padding)

  def isNone: Boolean =
    multiplicative == 0.0 && additive == 0.0

object RangeExpansion:
  val default: RangeExpansion =
    new RangeExpansion(multiplicative = 0.05, additive = 0.0, zeroWidth = 1.0)

  val none: RangeExpansion =
    new RangeExpansion(multiplicative = 0.0, additive = 0.0, zeroWidth = 1.0)

  def apply(
      multiplicative: Double,
      additive: Double = 0.0,
      zeroWidth: Double = 1.0
  ): Either[GraphicsError, RangeExpansion] =
    if !multiplicative.isFinite || multiplicative < 0.0
      || !additive.isFinite || additive < 0.0
      || !zeroWidth.isFinite || zeroWidth <= 0.0
    then Left(GraphicsError.InvalidRangeExpansion(multiplicative, additive, zeroWidth))
    else Right(new RangeExpansion(multiplicative, additive, zeroWidth))

  def unsafe(
      multiplicative: Double,
      additive: Double = 0.0,
      zeroWidth: Double = 1.0
  ): RangeExpansion =
    apply(multiplicative, additive, zeroWidth).orThrow

final case class PlotCompilerOptions(
    layout: Option[PanelLayout] = None,
    frame: Option[PanelFrame] = None,
    policy: Option[LayoutPolicy] = None,
    margins: PanelMargins = PanelMargins.none,
    expansion: RangeExpansion = RangeExpansion.default,
    guides: GuidePolicy = GuidePolicy.NoGuides,
    theme: Theme = Theme.default,
    renderContext: Option[RenderContext] = None,
    provenance: ProvenancePolicy = ProvenancePolicy.Full
)

object PlotCompilerOptions:
  val default: PlotCompilerOptions =
    PlotCompilerOptions()

  /** Retain complete typed rows, statistic members, and diagnostics for interactive inspection. */
  val rich: PlotCompilerOptions =
    default

  /** Retain no source provenance after lowering. Ordinary rendering needs only the scene. */
  val lean: PlotCompilerOptions =
    PlotCompilerOptions(provenance = ProvenancePolicy.None)

/** Inspectable trained layer whose hidden row type remains attached to its mapping, statistic,
  * resolved rows, and dropped-row provenance.
  */
sealed trait TrainedLayer:
  type Row
  def value: ResolvedLayer[Row]

  final def layerIndex: Int = value.layerIndex
  final def geom: Geom = value.geom
  final def stat: Stat[Row] = value.stat
  final def position: Position = value.position
  final def dataSize: Int = value.dataSize
  final def mapping: AesSpec[Row] = value.mapping
  final def annotation: Option[ResolvedReferenceLine] = value.annotation
  final def grouping: GroupingDecision = value.grouping
  final def statFrame: StatFrame[Row] = value.statFrame
  final def inspection: LayerInspection[Row] = value.inspection
  final def scaleDeclarations: Vector[ScaleDeclaration] = value.scaleDeclarations
  final def trainedScales: Vector[TrainedScale] = value.trainedScales
  final def rows: Vector[ResolvedRow[Row]] = value.rows
  final def droppedRows: Vector[DroppedRow[Row]] = value.droppedRows
  final def grobs: Vector[Grob] = value.grobs
  final def semanticId: Option[SemanticId] = value.semanticId

  private[intaglio] final def packedDroppedRows: Vector[TrainedDroppedRow] =
    droppedRows.map(TrainedDroppedRow(_))

  private[intaglio] final def retainRequestedInspection: TrainedLayer =
    if inspection.policy == ProvenancePolicy.Full then this
    else
      TrainedLayer(
        value.copy(
          statFrame = StatFrame(Vector.empty, value.statFrame.computedAesthetics),
          rows = Vector.empty,
          droppedRows = Vector.empty
        )
      )

object TrainedLayer:
  type Aux[Row0] = TrainedLayer { type Row = Row0 }

  def apply[Row0](layer: ResolvedLayer[Row0]): Aux[Row0] =
    new TrainedLayer:
      type Row = Row0
      val value: ResolvedLayer[Row] = layer

/** A dropped row packaged with its source type for plot-wide inspection. */
sealed trait TrainedDroppedRow:
  type Row
  def value: DroppedRow[Row]

  final def layerIndex: Int = value.layerIndex
  final def rowIndex: Int = value.rowIndex
  final def source: Row = value.source
  final def reason: PlotDropReason = value.reason

object TrainedDroppedRow:
  type Aux[Row0] = TrainedDroppedRow { type Row = Row0 }

  def apply[Row0](row: DroppedRow[Row0]): Aux[Row0] =
    new TrainedDroppedRow:
      type Row = Row0
      val value: DroppedRow[Row] = row

/** The data-side stage of a compiled plot: statistics, trained scales, resolved rows, layer
  * geometry, and guide specifications — everything that depends only on the data, mappings, theme,
  * and provenance policy, and nothing that depends on the device.
  *
  * Produced by [[PlotCompiler.train]] and consumed by [[PlotCompiler.place]], which adds the
  * device-dependent remainder (layout solve, panel decoration, guide and label lowering) for one
  * [[RenderContext]]. `PlotCompiler.resolve(plot, context, options)` is exactly the composition of
  * the two, so placing one trained value at several contexts — a resize — yields scenes identical
  * to full recompiles while doing no statistical work, no scale training, and no row resolution.
  *
  * The internals are deliberately opaque: inspect the result of placement ([[TrainedPlot]]), not
  * this intermediate.
  */
final class TrainedPlotData private[intaglio] (
    private[intaglio] val coord: Coord,
    private[intaglio] val labels: PlotLabels,
    private[intaglio] val baseOptions: PlotCompilerOptions,
    private[intaglio] val stage: TrainedStage
):
  private[intaglio] def hasFacet: Boolean =
    stage match
      case _: TrainedStage.Faceted => true
      case _: TrainedStage.Single  => false

/** Data-side payload behind [[TrainedPlotData]]: one shape for standalone panels, one for facets.
  */
private[intaglio] enum TrainedStage:
  case Single(
      layers: Vector[TrainedLayer],
      registry: PlotScaleRegistry,
      specs: Vector[GuideSpec],
      ranges: Option[(Interval, Interval)],
      semantics: PlotSemantics
  )
  case Faceted(
      facetLayout: FacetLayout,
      facetScales: FacetScales,
      panels: Vector[FacetCompiler.PanelResolution],
      nonPositionGuides: Vector[GuideSpec],
      globalRanges: (Interval, Interval),
      registry: PlotScaleRegistry,
      semantics: PlotSemantics
  )

/** A compiled plot. Layers are packed existentially because an independent layer may carry a row
  * type of its own, so the plot as a whole has no single row type to name. Per-layer diagnostics
  * stay typed at each layer's own row via [[TrainedLayer.droppedRows]].
  */
final case class TrainedPlot(
    layers: Vector[TrainedLayer],
    layout: Option[PanelLayout],
    guides: Vector[ResolvedGuide],
    scaleRegistry: PlotScaleRegistry,
    panelGrobs: Vector[Grob],
    labelGrobs: Vector[Grob],
    facetPanels: Vector[ResolvedFacetPanel] = Vector.empty,
    semantics: PlotSemantics = PlotSemantics.empty
):
  def scene: Scene =
    val layerGrobs = layers.flatMap(_.grobs)
    val panelGroup =
      if facetPanels.nonEmpty then
        facetPanels.flatMap { panel =>
          Vector(
            Grob.group(
              panel.panelGrobs ++ panel.layers.flatMap(_.grobs),
              viewport = Some(panel.layout.viewport),
              name = Some(panel.cell.panelName)
            ),
            panel.stripGrob
          )
        }
      else
        layout match
          case None =>
            layerGrobs
          case Some(panel) =>
            Vector(
              Grob.group(
                panelGrobs ++ layerGrobs,
                viewport = Some(panel.viewport),
                name = Some(GraphicsName.unsafe("plot-panel"))
              )
            )
    val rendered = Scene(panelGroup ++ guides.map(_.grob) ++ labelGrobs)
    if rendered.isEmpty || semantics.isEmpty then rendered
    else rendered.withSemantics(SceneSemantics.single(semantics))

  def textSummary: String =
    semantics.textSummary

  def altText: Option[String] =
    semantics.altText

  def accessibilityDiagnostics: Vector[AccessibilityDiagnostic] =
    semantics.diagnostics

  def withAltText(value: String): TrainedPlot =
    copy(semantics = semantics.withAltText(value))

  def withDescription(value: String): TrainedPlot =
    copy(semantics = semantics.withDescription(value))

  def droppedRows: Vector[TrainedDroppedRow] =
    layers.flatMap(_.packedDroppedRows)

  def scaleDeclarations: Vector[ScaleDeclaration] =
    layers.flatMap(_.scaleDeclarations)

  def trainedScales: Vector[TrainedScale] =
    scaleRegistry.scales

  private[intaglio] def retainRequestedInspection: TrainedPlot =
    if layers.forall(_.inspection.policy == ProvenancePolicy.Full) then this
    else
      val retainedPanels = facetPanels.map { panel =>
        panel.copy(layers = panel.layers.map(_.retainRequestedInspection))
      }
      val retainedLayers =
        if retainedPanels.nonEmpty then retainedPanels.flatMap(_.layers)
        else layers.map(_.retainRequestedInspection)
      copy(layers = retainedLayers, facetPanels = retainedPanels)

final case class ResolvedFacetPanel(
    cell: FacetCell,
    layout: PanelLayout,
    layers: Vector[TrainedLayer],
    scaleRegistry: PlotScaleRegistry,
    panelGrobs: Vector[Grob],
    stripGrob: Grob
)

final case class ResolvedLayer[Row](
    layerIndex: Int,
    geom: Geom,
    stat: Stat[Row],
    position: Position,
    dataSize: Int,
    mapping: AesSpec[Row],
    annotation: Option[ResolvedReferenceLine],
    grouping: GroupingDecision,
    statFrame: StatFrame[Row],
    scaleDeclarations: Vector[ScaleDeclaration],
    trainedScales: Vector[TrainedScale],
    rows: Vector[ResolvedRow[Row]],
    droppedRows: Vector[DroppedRow[Row]],
    grobs: Vector[Grob],
    inspection: LayerInspection[Row],
    semanticId: Option[SemanticId] = None
)

/** A row-independent annotation after any requested position-scale mapping. */
final case class ResolvedReferenceLine(
    reference: ReferenceLine,
    coordinate: Double,
    trainedScale: Option[TrainedScale]
):
  def isMapped: Boolean =
    trainedScale.nonEmpty

  private[intaglio] def flipped: ResolvedReferenceLine =
    copy(reference = reference.flipped)

final case class ScaleDeclaration(
    layerIndex: Int,
    key: Aesthetic[?],
    scaleName: GraphicsName,
    kind: ScaleKind
):
  def aesthetic: String =
    key.label

final case class TrainedScale(
    key: Aesthetic[?],
    descriptor: ScaleDescriptor,
    scale: Scale[?, ?]
):
  def aesthetic: String =
    key.label

final case class ResolvedRow[Row](
    rowIndex: Int,
    source: Row,
    statRow: StatRow[Row],
    x: Double,
    y: Double,
    xBand: Option[Band],
    yBand: Option[Band],
    xEnd: Option[Double],
    yEnd: Option[Double],
    xMin: Option[Double],
    xMax: Option[Double],
    yMin: Option[Double],
    yMax: Option[Double],
    point: Point,
    label: Option[String],
    grouping: GroupingDecision,
    groupKey: Option[GroupKey],
    group: Option[String],
    subpath: Option[String],
    gp: GraphicParams,
    size: ExtentExpr,
    xCategoryIdentity: Option[CategoryToken] = None,
    yCategoryIdentity: Option[CategoryToken] = None,
    shape: PointShape = PointShape.Circle,
    textAnchor: Anchor = Anchor.Center,
    rotationDegrees: Double = 0.0
):
  /** Generic computed-aesthetic inspection derived from the retained typed stat output. */
  def computed: ComputedValues =
    statRow.computed

final case class DroppedRow[+Row](
    layerIndex: Int,
    rowIndex: Int,
    source: Row,
    reason: PlotDropReason
)

enum PlotDropReason:
  case MissingAesthetic(aesthetic: String)
  case MissingPosition
  case MissingLabel
  case MappingEvaluationFailed(
      aesthetic: String,
      rowIndex: Int,
      contract: MappingContract,
      failure: MappingFailure
  )
  case NonFinitePosition(x: Double, y: Double)
  case NonFiniteAesthetic(aesthetic: String, value: Double)
  case InvalidBounds(axis: String, minimum: Double, maximum: Double)
  case TransformDomain(aesthetic: String, transform: String, value: Double)
  case ScaleOutOfDomain(aesthetic: String, scale: String, value: String)
  case PaletteOverflow(aesthetic: String, scale: String, levels: Int, capacity: Int)
  case GroupingCategoryUnavailable(aesthetic: String)
  case InvalidAesthetic(aesthetic: String, value: String)

/** Facade over the compiler phases: mapping resolution, plot-wide scale training, row evaluation,
  * geom lowering, and guide resolution. Each phase lives in [[CompilerPhases]] and is independently
  * testable.
  */
object PlotCompiler:
  def compile[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions = PlotCompilerOptions.lean
  ): Either[GraphicsError, Scene] =
    resolve(plot, options).map(_.scene)

  def compile[Row](
      plot: Plot[Row],
      context: RenderContext
  ): Either[GraphicsError, RenderPlan] =
    compile(plot, context, PlotCompilerOptions.lean)

  def compile[Row](
      plot: Plot[Row],
      context: RenderContext,
      options: PlotCompilerOptions
  ): Either[GraphicsError, RenderPlan] =
    resolve(plot, context, options).map(trained => RenderPlan(trained.scene, context))

  def resolve[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions = PlotCompilerOptions.default
  ): Either[GraphicsError, TrainedPlot] =
    resolveBeforeRetention(plot, options).map(_.retainRequestedInspection)

  /** Run only the data-dependent phases — mapping, statistics, scale training, row resolution,
    * coordinate transforms, layer geometry, and guide specification — and return the reusable
    * intermediate. Pair with [[place]] to add the device-dependent remainder; a pure resize then
    * calls `place` alone. `train` assumes the result will be placed, so a layout policy is implied
    * when the options carry none.
    */
  def train[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions = PlotCompilerOptions.lean
  ): Either[GraphicsError, TrainedPlotData] =
    val prepared =
      if options.layout.isEmpty && options.frame.isEmpty && options.policy.isEmpty then
        options.copy(policy = Some(options.theme.layout))
      else options
    trainStage(plot, effectiveOptions(plot, prepared), options)

  /** Add the device-dependent phases — layout solve, panel decoration, guide and label lowering —
    * to a trained plot for one [[RenderContext]]. Placing the same value at the same context twice
    * yields identical scenes; placing at a new context repeats none of the data-side work.
    */
  def place(
      trained: TrainedPlotData,
      context: RenderContext
  ): Either[GraphicsError, TrainedPlot] =
    val effective = effectiveOptionsFor(
      trained.labels.isEmpty,
      trained.hasFacet,
      trained.baseOptions.copy(renderContext = Some(context))
    )
    placeStage(trained, effective).map(_.retainRequestedInspection)

  /** Test oracle: identical to [[train]] but forces the general facet path even when both position
    * dimensions share scales, so the shared-scale fast path can be verified panel by panel.
    */
  private[intaglio] def trainFacetedExhaustive[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlotData] =
    val prepared =
      if options.layout.isEmpty && options.frame.isEmpty && options.policy.isEmpty then
        options.copy(policy = Some(options.theme.layout))
      else options
    val resolvedOptions = effectiveOptions(plot, prepared)
    plot.facet match
      case Some(facet) => FacetCompiler.trainExhaustive(plot, facet, resolvedOptions, options)
      case None        => trainSingle(plot, resolvedOptions, options)

  /** Optional compilation consumers may extract portable metadata before source rows are released.
    * Geometry still follows the requested policy, including lean point batching.
    */
  private[intaglio] def resolveBeforeRetention[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlot] =
    val resolvedOptions = effectiveOptions(plot, options)
    trainStage(plot, resolvedOptions, options).flatMap(placeStage(_, resolvedOptions))

  private def trainStage[Row](
      plot: Plot[Row],
      resolvedOptions: PlotCompilerOptions,
      baseOptions: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlotData] =
    plot.facet match
      case Some(facet) => FacetCompiler.train(plot, facet, resolvedOptions, baseOptions)
      case None        => trainSingle(plot, resolvedOptions, baseOptions)

  private def placeStage(
      trained: TrainedPlotData,
      resolvedOptions: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlot] =
    trained.stage match
      case single: TrainedStage.Single =>
        placeSingle(trained, single, resolvedOptions)
      case faceted: TrainedStage.Faceted =>
        FacetCompiler.place(trained, faceted, resolvedOptions)

  def resolve[Row](
      plot: Plot[Row],
      context: RenderContext
  ): Either[GraphicsError, TrainedPlot] =
    resolve(plot, context, PlotCompilerOptions.default)

  def resolve[Row](
      plot: Plot[Row],
      context: RenderContext,
      options: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlot] =
    resolve(plot, options.copy(renderContext = Some(context)))

  /** [[train]] through a caller-owned [[PlotCompileCache]]: an unchanged plot compiled with
    * unchanged options returns the retained trained value without redoing any data-side work. Keys
    * are the references passed here; a miss computes and stores, an error stores nothing.
    */
  def train[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions,
      cache: PlotCompileCache
  ): Either[GraphicsError, TrainedPlotData] =
    cache match
      case PlotCompileCache.Disabled         => train(plot, options)
      case bounded: PlotCompileCache.Bounded =>
        bounded.trainedFor(plot, options)(train(plot, options))

  /** [[place]] through a caller-owned [[PlotCompileCache]]: the same trained value placed at a
    * value-equal [[RenderContext]] returns the retained placement.
    */
  def place(
      trained: TrainedPlotData,
      context: RenderContext,
      cache: PlotCompileCache
  ): Either[GraphicsError, TrainedPlot] =
    cache match
      case PlotCompileCache.Disabled         => place(trained, context)
      case bounded: PlotCompileCache.Bounded =>
        bounded.placedFor(trained, context)(place(trained, context))

  /** [[resolve]] through a caller-owned [[PlotCompileCache]], routed through [[train]] and
    * [[place]] so that an unchanged plot memoizes its data-side work across contexts and an
    * unchanged (plot, context) pair memoizes the placement too. Scenes are identical to an uncached
    * [[resolve]] (the `train`/`place` composition contract; see `TrainPlaceSuite`).
    */
  def resolve[Row](
      plot: Plot[Row],
      context: RenderContext,
      options: PlotCompilerOptions,
      cache: PlotCompileCache
  ): Either[GraphicsError, TrainedPlot] =
    cache match
      case PlotCompileCache.Disabled         => resolve(plot, context, options)
      case bounded: PlotCompileCache.Bounded =>
        train(plot, options, bounded).flatMap(place(_, context, bounded))

  private[intaglio] def effectiveOptions[Row](
      plot: Plot[Row],
      options: PlotCompilerOptions
  ): PlotCompilerOptions =
    effectiveOptionsFor(plot.labels.isEmpty, plot.facet.nonEmpty, options)

  private[intaglio] def effectiveOptionsFor(
      labelsEmpty: Boolean,
      hasFacet: Boolean,
      options: PlotCompilerOptions
  ): PlotCompilerOptions =
    val themeNeedsLayout =
      options.theme.panel.background.nonEmpty || options.theme.panel.grid.nonEmpty
    val effectiveOptions =
      if (
          !labelsEmpty || themeNeedsLayout || hasFacet || options.guides.requiresLayout || options.renderContext.nonEmpty
        )
        && options.layout.isEmpty && options.frame.isEmpty && options.policy.isEmpty
      then options.copy(policy = Some(options.theme.layout))
      else options
    val themedLayoutPolicy =
      options.theme.layoutPolicy(effectiveOptions.policy.getOrElse(options.theme.layout))
    val layoutPolicy =
      options.renderContext.fold(themedLayoutPolicy)(_.layoutPolicy(themedLayoutPolicy))
    effectiveOptions.copy(
      policy =
        if options.renderContext.nonEmpty then Some(layoutPolicy)
        else effectiveOptions.policy.map(_ => layoutPolicy)
    )

  private def trainSingle[Row](
      plot: Plot[Row],
      resolvedOptions: PlotCompilerOptions,
      baseOptions: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlotData] =
    for
      plans <- PhaseClock.timed(PhaseClock.Phase.Mapping)(MappingPhase.plan(plot))
      statPlans <- PhaseClock.timed(PhaseClock.Phase.Stat)(StatPhase.transform(plans))
      scales <- PhaseClock.timed(PhaseClock.Phase.ScaleTraining)(
        ScalePhase.train(statPlans, resolvedOptions.theme)
      )
      logicalLayers <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
        resolveLayers(
          scales.plans,
          resolvedOptions.theme,
          resolvedOptions.provenance
        )
      )
      logicalRanges <- LayoutPhase.panelRangesFor(resolvedOptions, logicalLayers)
      specs <- PhaseClock.timed(PhaseClock.Phase.Layout)(
        GuidePhase.specs(
          resolvedOptions.guides,
          plot.coord,
          scales.registry,
          logicalRanges,
          relativeLegend = resolvedOptions.policy.nonEmpty,
          labels = plot.labels
        )
      )
      coordinates <- PhaseClock.timed(PhaseClock.Phase.Resolve)(
        CoordPhase.transform(
          plot.coord,
          logicalLayers,
          logicalRanges,
          scales.registry
        )
      )
      semantics <- PlotSemantics.build(
        plot.accessibility,
        plot.labels,
        coordinates.layers,
        scales.registry
      )
    yield TrainedPlotData(
      plot.coord,
      plot.labels,
      baseOptions,
      TrainedStage.Single(
        coordinates.layers,
        scales.registry,
        specs,
        coordinates.ranges,
        semantics
      )
    )

  private def placeSingle(
      trained: TrainedPlotData,
      single: TrainedStage.Single,
      resolvedOptions: PlotCompilerOptions
  ): Either[GraphicsError, TrainedPlot] =
    val layoutPolicy = resolvedOptions.policy.getOrElse(resolvedOptions.theme.layoutPolicy)
    for
      resolution <- PhaseClock.timed(PhaseClock.Phase.Layout)(
        LayoutPhase.assemble(
          trained.coord,
          resolvedOptions,
          single.ranges,
          single.specs,
          trained.labels
        )
      )
      panelGrobs <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        PanelPhase.lower(resolution.layout, single.specs, resolvedOptions.theme.panel)
      )
      guides <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        GuidePhase.lower(
          resolution.layout,
          resolution.frames,
          single.specs,
          layoutPolicy,
          resolvedOptions.theme
        )
      )
      labelGrobs <- PhaseClock.timed(PhaseClock.Phase.Lowering)(
        PlotLabelPhase.lower(trained.labels, resolution.frames, resolvedOptions.theme.plotText)
      )
    yield TrainedPlot(
      single.layers,
      resolution.layout,
      guides,
      single.registry,
      panelGrobs,
      labelGrobs,
      Vector.empty[ResolvedFacetPanel],
      single.semantics
    )

  private[intaglio] def resolveLayers(
      plans: Vector[PackedStatPlan],
      theme: Theme,
      provenance: ProvenancePolicy
  ): Either[GraphicsError, Vector[TrainedLayer]] =
    val out = Vector.newBuilder[TrainedLayer]
    var idx = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while idx < plans.length && result.isRight do
      result = resolveLayer(plans(idx), theme, provenance).map { layer =>
        out += layer
        ()
      }
      idx += 1
    result.map(_ => out.result())

  private def resolveLayer(
      plan: PackedStatPlan,
      theme: Theme,
      provenance: ProvenancePolicy
  ): Either[GraphicsError, TrainedLayer] =
    resolveTypedLayer(plan.value, theme, provenance)

  private def resolveTypedLayer[Row, Output <: StatRow[Row]](
      plan: StatPlan[Row, Output],
      theme: Theme,
      provenance: ProvenancePolicy
  ): Either[GraphicsError, TrainedLayer] =
    val registry = ScaleRegistry.fromMapping(plan.mapping)
    val annotation = plan.annotation.map(_.resolved)
    val grouping = plan.mapping.groupingDecision
    val trainedScales = annotation.flatMap(_.trainedScale).fold(registry.trained) { scale =>
      if registry.trained.exists(_.key eq scale.key) then registry.trained
      else registry.trained :+ scale
    }
    RowPhase.resolve(plan, theme).flatMap { case (rows, droppedRows) =>
      val inspection =
        LayerInspection.capture(plan.source.data, plan.frame, droppedRows, provenance)
      PositionPhase.adjust(plan.layer, rows).flatMap { adjusted =>
        PhaseClock
          .timed(PhaseClock.Phase.Lowering)(
            GeomPhase.lower(
              plan.layerIndex,
              plan.layer,
              plan.layer.stat.contract.lowering,
              adjusted,
              annotation,
              theme,
              batchPointMarks = provenance != ProvenancePolicy.Full
            )
          )
          .map { grobs =>
            TrainedLayer(
              ResolvedLayer(
                layerIndex = plan.layerIndex,
                geom = plan.layer.geom,
                stat = plan.layer.stat,
                position = plan.layer.position,
                dataSize = plan.source.data.length,
                mapping = plan.source.mapping,
                annotation = annotation,
                grouping = grouping,
                statFrame = plan.frame,
                scaleDeclarations = registry.declarations(plan.layerIndex),
                trainedScales = trainedScales,
                rows = adjusted,
                droppedRows = droppedRows,
                grobs = grobs,
                inspection = inspection,
                semanticId = plan.layer.semanticId
              )
            )
          }
      }
    }
