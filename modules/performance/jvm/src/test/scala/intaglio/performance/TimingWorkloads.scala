package intaglio.performance

import intaglio.*
import intaglio.interaction.*

/** Interactive-rate timing workloads.
  *
  * These model how consumers actually drive the library: many-panel scientific trellises, repeated
  * device-size changes over unchanged data, and dense hover picking. They are executed by
  * [[TimingHarness]] on the JVM to produce the elapsed-time receipt in
  * `performance/timings/v1.tsv`. They are deliberately not part of the CI-gated deterministic
  * suite: wall-clock numbers stay out of hosted pass/fail decisions (see `performance/README.md`).
  *
  * Every workload is a pure function of fixed synthetic data, so two runs on one machine measure
  * the same work.
  */
private[performance] object TimingWorkloads:

  final case class TrialRow(
      condition: String,
      group: String,
      series: Int,
      x: Double,
      y: Double,
      lo: Double,
      hi: Double
  )

  final case class MarkRow(panel: String, id: Int, x: Double, y: Double)

  val conditionCount: Int = 6
  val groupCount: Int = 5
  val seriesPerPanel: Int = 20
  val pointsPerSeries: Int = 40
  val pickPanels: Int = 20
  val pickMarksPerPanel: Int = 1000
  val pickMarks: Int = pickPanels * pickMarksPerPanel
  val pickQueries: Int = 200

  val conditionLevels: Vector[String] = Vector.tabulate(conditionCount)(i => s"cond-$i")
  val groupLevels: Vector[String] = Vector.tabulate(groupCount)(i => s"grp-$i")

  val context: RenderContext = RenderContext.unsafe(width = 1200, height = 900)

  val resizeContexts: Vector[RenderContext] =
    Vector(
      RenderContext.unsafe(width = 1000, height = 750),
      RenderContext.unsafe(width = 1100, height = 800),
      RenderContext.unsafe(width = 1200, height = 850),
      RenderContext.unsafe(width = 1300, height = 900),
      RenderContext.unsafe(width = 1400, height = 950)
    )

  private def orFail[A](result: Either[IntaglioError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

  def leanOptions: PlotCompilerOptions =
    PlotCompilerOptions(guides = GuidePolicy.Derived(), provenance = ProvenancePolicy.None)

  def richOptions: PlotCompilerOptions =
    PlotCompilerOptions(guides = GuidePolicy.Derived(), provenance = ProvenancePolicy.Full)

  /** Deterministic trellis rows; `occupied` selects which (condition, group) cells hold data. */
  def trellisRows(occupied: (Int, Int) => Boolean): Vector[TrialRow] =
    val rows = Vector.newBuilder[TrialRow]
    var conditionIndex = 0
    while conditionIndex < conditionCount do
      var groupIndex = 0
      while groupIndex < groupCount do
        if occupied(conditionIndex, groupIndex) then
          var series = 0
          while series < seriesPerPanel do
            var point = 0
            while point < pointsPerSeries do
              val x = point.toDouble
              val y =
                math.sin(x / 7.0 + series.toDouble / 3.0) * 10.0 +
                  series.toDouble + conditionIndex.toDouble * 2.0 + groupIndex.toDouble
              val spread = 1.5 + ((series + point) % 7).toDouble / 4.0
              rows += TrialRow(
                conditionLevels(conditionIndex),
                groupLevels(groupIndex),
                series,
                x,
                y,
                y - spread,
                y + spread
              )
              point += 1
            series += 1
        groupIndex += 1
      conditionIndex += 1
    rows.result()

  val denseRows: Vector[TrialRow] = trellisRows((_, _) => true)

  val sparseRows: Vector[TrialRow] = trellisRows((c, g) => (c + g) % 2 == 0)

  /** 30-panel grid: `geomRibbon` under `geomLine`, 20 series per panel. */
  def trellisPlot(rows: Vector[TrialRow], scales: FacetScales): Plot[TrialRow] =
    val facet = orFail(
      FacetSpec.grid[TrialRow](
        _.condition,
        _.group,
        rowLevels = conditionLevels,
        columnLevels = groupLevels,
        scales = scales
      )
    )
    orFail(
      Plot(rows)
        .withFacet(facet)
        .addLayer(
          Layer.ribbon[TrialRow](
            _.x,
            _.lo,
            _.hi,
            mapping = AesSpec.empty[TrialRow].withGroup(_.series.toString)
          )
        )
        .flatMap(
          _.addLayer(
            Layer.line[TrialRow](
              _.x,
              _.y,
              mapping = AesSpec.empty[TrialRow].withGroup(_.series.toString)
            )
          )
        )
    )

  lazy val densePlot: Plot[TrialRow] = trellisPlot(denseRows, FacetScales.Shared)
  lazy val sparsePlot: Plot[TrialRow] = trellisPlot(sparseRows, FacetScales.Shared)
  lazy val freeScalesPlot: Plot[TrialRow] = trellisPlot(denseRows, FacetScales.Free)

  def resolveTrellis(plot: Plot[TrialRow], options: PlotCompilerOptions): TrainedPlot =
    orFail(PlotCompiler.resolve(plot, context, options))

  /** Full recompiles at five successive sizes over unchanged data; each pays every phase. */
  def resizeSweep(plot: Plot[TrialRow]): Long =
    var checksum = 0L
    resizeContexts.foreach { resized =>
      val trained = orFail(PlotCompiler.resolve(plot, resized, leanOptions))
      checksum += trained.scene.grobs.length.toLong
    }
    checksum

  def trainTrellis(plot: Plot[TrialRow]): TrainedPlotData =
    orFail(PlotCompiler.train(plot, leanOptions))

  /** The cached unsplit path: `resolve` through a caller-owned [[PlotCompileCache]] at the same
    * five sizes as [[resizeSweep]]. The cache keys plots and options by reference, so the caller
    * must hold one stable options value alongside the cache; after the first sweep warms it, every
    * resolve is a training hit plus a placement hit.
    */
  def cachedResizeSweep(
      plot: Plot[TrialRow],
      options: PlotCompilerOptions,
      cache: PlotCompileCache
  ): Long =
    var checksum = 0L
    resizeContexts.foreach { resized =>
      val trained = orFail(PlotCompiler.resolve(plot, resized, options, cache))
      checksum += trained.scene.grobs.length.toLong
    }
    checksum

  /** The split path: five placements of one trained plot; only layout and lowering re-run. */
  def placeSweep(trained: TrainedPlotData): Long =
    var checksum = 0L
    resizeContexts.foreach { resized =>
      val placed = orFail(PlotCompiler.place(trained, resized))
      checksum += placed.scene.grobs.length.toLong
    }
    checksum

  val pickRows: Vector[MarkRow] =
    Vector.tabulate(pickMarks) { id =>
      val panel = id / pickMarksPerPanel
      val within = id % pickMarksPerPanel
      MarkRow(
        s"panel-$panel",
        id,
        (within % 50).toDouble + (id % 7).toDouble / 10.0,
        (within / 50).toDouble + (id % 11).toDouble / 12.0
      )
    }

  lazy val pickPlot: Plot[MarkRow] =
    val facet = orFail(
      FacetSpec.wrap[MarkRow](
        _.panel,
        columns = 5,
        levels = Vector.tabulate(pickPanels)(i => s"panel-$i")
      )
    )
    orFail(
      Plot(pickRows)
        .withFacet(facet)
        .addLayer(Layer.point[MarkRow](_.x, _.y))
    )

  def pickInteractionPlan: InteractionPlan[Int] =
    val space = orFail(KeySpace("marks", KeyCodec.integer))
    orFail(
      InteractionCompiler.compile(
        pickPlot,
        space,
        orFail(DataRevision("r1")),
        SemanticId.unsafe("pick-dense"),
        orFail(PlanRevision("one")),
        options = leanOptions.copy(renderContext = Some(context))
      )(_.id)
    )

  def compilePicking(plan: InteractionPlan[Int]): PickingPlan[Int] =
    orFail(Picking.compile(plan, context))

  /** Deterministic query points spread over the full device surface. */
  val queryPoints: Vector[DevicePoint] =
    Vector.tabulate(pickQueries) { index =>
      DevicePoint(
        30.0 + (index * 37 % 1140).toDouble,
        30.0 + (index * 53 % 840).toDouble
      )
    }

  def runHitQueries(plan: PickingPlan[Int]): Long =
    var found = 0L
    queryPoints.foreach { point =>
      found += orFail(plan.hits(point, toleranceDevicePx = 3.0)).length.toLong
    }
    found

  def runNearestQueries(plan: PickingPlan[Int]): Long =
    var found = 0L
    queryPoints.foreach { point =>
      if orFail(plan.nearest(point, maximumDistanceDevicePx = 10.0)).nonEmpty then found += 1L
    }
    found

  def runSelectQueries(plan: PickingPlan[Int]): Long =
    var found = 0L
    val areas = Vector.tabulate(10) { index =>
      val left = 40.0 + index * 100.0
      orFail(PickArea.rectangle(left, 100.0, left + 150.0, 400.0))
    }
    areas.foreach { area =>
      found += plan.select(area, AreaRule.Intersecting).length.toLong
    }
    found
