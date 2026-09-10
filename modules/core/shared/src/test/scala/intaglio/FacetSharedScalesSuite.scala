package intaglio

/** The shared-scale facet fast path must be indistinguishable, panel by panel, from the general
  * per-panel path it replaces. `PlotCompiler.trainFacetedExhaustive` forces the general path and
  * serves as the oracle; declared-but-empty grid cells must still render a panel and strip.
  */
class FacetSharedScalesSuite extends munit.FunSuite:

  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  private final case class Observation(x: Double, y: Double, condition: String, group: String)

  private val observations: Vector[Observation] =
    Vector.tabulate(360) { index =>
      val x = (index % 24).toDouble
      val y = math.cos(x / 4.0 + (index % 3).toDouble) * 4.0 + (index % 5).toDouble
      Observation(x, y, s"cond-${index % 3}", s"grp-${index % 2}")
    }

  /** Unbalanced design: one cell absent from the data, one declared row never observed. */
  private val sparseObservations: Vector[Observation] =
    observations.filterNot(row => row.condition == "cond-2" && row.group == "grp-1")

  private val contexts =
    Vector(
      RenderContext.unsafe(width = 960, height = 640),
      RenderContext.unsafe(width = 500, height = 800)
    )

  private val richDerived = PlotCompilerOptions(guides = GuidePolicy.Derived())
  private val leanDerived =
    PlotCompilerOptions(guides = GuidePolicy.Derived(), provenance = ProvenancePolicy.None)

  private def gridPlot(
      data: Vector[Observation],
      rowLevels: Vector[String] = Vector.empty
  ): Plot[Observation] =
    val facet = ok(
      FacetSpec.grid[Observation](
        _.condition,
        _.group,
        rowLevels = rowLevels,
        columnLevels = Vector("grp-0", "grp-1")
      )
    )
    ok(
      Plot(data)
        .withFacet(facet)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .flatMap(
          _.addLayer(
            Layer.line[Observation](
              _.x,
              _.y,
              mapping = AesSpec.empty[Observation].withGroup(_.group)
            )
          )
        )
    )

  private def wrapPlot: Plot[Observation] =
    val facet = ok(FacetSpec.wrap[Observation](_.condition))
    ok(
      Plot(observations)
        .withFacet(facet)
        .addLayer(Layer.point[Observation](_.x, _.y))
    )

  private val sparseRowLevels = Vector("cond-0", "cond-1", "cond-2", "cond-3")

  private def assertMatchesOracle[Row](
      name: String,
      plot: Plot[Row],
      options: PlotCompilerOptions
  ): Unit =
    val fast = ok(PlotCompiler.train(plot, options))
    val oracle = ok(PlotCompiler.trainFacetedExhaustive(plot, options))
    contexts.foreach { context =>
      val fastPlaced = ok(PlotCompiler.place(fast, context))
      val oraclePlaced = ok(PlotCompiler.place(oracle, context))
      assertEquals(
        fastPlaced.facetPanels.length,
        oraclePlaced.facetPanels.length,
        clues(name, context.width)
      )
      fastPlaced.facetPanels.zip(oraclePlaced.facetPanels).foreach {
        case (fastPanel, oraclePanel) =>
          val where = clues(name, context.width, oraclePanel.cell.label)
          assertEquals(fastPanel.cell, oraclePanel.cell, where)
          assertEquals(fastPanel.layout, oraclePanel.layout, where)
          assertEquals(fastPanel.panelGrobs, oraclePanel.panelGrobs, where)
          assertEquals(fastPanel.stripGrob, oraclePanel.stripGrob, where)
          assertEquals(
            fastPanel.layers.map(_.grobs),
            oraclePanel.layers.map(_.grobs),
            where
          )
          assertEquals(
            fastPanel.layers.map(_.rows),
            oraclePanel.layers.map(_.rows),
            where
          )
      }
      assertEquals(fastPlaced.guides.length, oraclePlaced.guides.length, clues(name))
      assertEquals(fastPlaced.scene, oraclePlaced.scene, clues(name, context.width))
    }

  test("a dense shared grid matches the exhaustive oracle panel by panel") {
    assertMatchesOracle("dense-lean", gridPlot(observations), leanDerived)
    assertMatchesOracle("dense-rich", gridPlot(observations), richDerived)
  }

  test("a sparse shared grid with declared empty cells matches the oracle panel by panel") {
    val plot = gridPlot(sparseObservations, rowLevels = sparseRowLevels)
    assertMatchesOracle("sparse-lean", plot, leanDerived)
    assertMatchesOracle("sparse-rich", plot, richDerived)
  }

  test("a shared facet wrap matches the oracle panel by panel") {
    assertMatchesOracle("wrap-lean", wrapPlot, leanDerived)
  }

  test("declared-but-empty grid cells still render a panel and a strip") {
    val plot = gridPlot(sparseObservations, rowLevels = sparseRowLevels)
    val placed = ok(PlotCompiler.resolve(plot, contexts.head, leanDerived))
    assertEquals(placed.facetPanels.length, 8, clues(placed.facetPanels.map(_.cell.label)))
    val (empty, occupied) = placed.facetPanels.partition(_.layers.forall(_.dataSize == 0))
    assertEquals(empty.length, 3)
    empty.foreach { panel =>
      // An empty cell gets the same decoration as its occupied siblings, plus its own strip.
      assertEquals(
        panel.panelGrobs.length,
        occupied.head.panelGrobs.length,
        clues(panel.cell.label)
      )
      assertEquals(panel.stripGrob.name.map(_.value), Some(panel.cell.stripName.value))
    }
  }

  test("the shared fast path trains scales in one global pass") {
    val (trained, readings) =
      PhaseClock.profile(PlotCompiler.train(gridPlot(observations), leanDerived))
    ok(trained)
    assertEquals(readings(PhaseClock.Phase.ScaleTraining).calls, 1L)
  }

  test("the general path is still selected when a dimension is free") {
    val facet = ok(
      FacetSpec.grid[Observation](
        _.condition,
        _.group,
        scales = FacetScales.FreeY
      )
    )
    val plot = ok(
      Plot(observations)
        .withFacet(facet)
        .addLayer(Layer.point[Observation](_.x, _.y))
    )
    val (trained, readings) = PhaseClock.profile(PlotCompiler.train(plot, leanDerived))
    ok(trained)
    assert(readings(PhaseClock.Phase.ScaleTraining).calls > 1L, clues(readings.toString))
  }
