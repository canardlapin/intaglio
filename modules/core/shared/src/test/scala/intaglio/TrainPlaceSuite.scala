package intaglio

/** The data/device split contract: `resolve(plot, context, options)` is exactly
  * `train(plot, options)` composed with `place(_, context)`; placing is pure and repeatable; and a
  * placement does no statistical work, no scale training, and no row resolution.
  */
class TrainPlaceSuite extends munit.FunSuite:

  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  private final case class Observation(
      x: Double,
      y: Double,
      lo: Double,
      hi: Double,
      condition: String,
      group: String
  )

  private val observations: Vector[Observation] =
    Vector.tabulate(240) { index =>
      val x = (index % 20).toDouble
      val y = math.sin(x / 3.0 + index.toDouble / 40.0) * 5.0 + (index % 6).toDouble
      Observation(
        x,
        y,
        y - 1.0 - (index % 3).toDouble / 2.0,
        y + 1.0 + (index % 5).toDouble / 3.0,
        s"cond-${index % 3}",
        s"grp-${index % 2}"
      )
    }

  private val contexts =
    Vector(
      RenderContext.unsafe(width = 640, height = 480),
      RenderContext.unsafe(width = 1280, height = 720),
      RenderContext.unsafe(width = 900, height = 900)
    )

  private def scatterPlot: Plot[Observation] =
    ok(Plot(observations).addLayer(Layer.point[Observation](_.x, _.y)))

  private def histogramPlot: Plot[Double] =
    ok(Plot(observations.map(_.y)).addLayer(Layer.histogram[Double](identity)))

  private def labelledLinePlot: Plot[Observation] =
    ok(
      Plot(observations)
        .withLabels(PlotLabels(title = Some("Signal"), x = Some("time"), y = Some("response")))
        .addLayer(
          Layer.line[Observation](
            _.x,
            _.y,
            mapping = AesSpec.empty[Observation].withGroup(_.condition)
          )
        )
    )

  private def facetWrapPlot: Plot[Observation] =
    val facet = ok(FacetSpec.wrap[Observation](_.condition))
    ok(
      Plot(observations)
        .withFacet(facet)
        .addLayer(
          Layer.ribbon[Observation](
            _.x,
            _.lo,
            _.hi,
            mapping = AesSpec.empty[Observation].withGroup(_.group)
          )
        )
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

  private def facetGridFreePlot: Plot[Observation] =
    val facet = ok(
      FacetSpec.grid[Observation](
        _.condition,
        _.group,
        rowLevels = Vector("cond-0", "cond-1", "cond-2", "cond-empty"),
        columnLevels = Vector("grp-0", "grp-1"),
        scales = FacetScales.Free
      )
    )
    ok(
      Plot(observations)
        .withFacet(facet)
        .addLayer(Layer.point[Observation](_.x, _.y))
    )

  private val derivedGuides = PlotCompilerOptions(guides = GuidePolicy.Derived())
  private val leanDerived =
    PlotCompilerOptions(guides = GuidePolicy.Derived(), provenance = ProvenancePolicy.None)

  private def assertScenesMatch[Row](
      name: String,
      plot: Plot[Row],
      options: PlotCompilerOptions
  ): Unit =
    val trained = ok(PlotCompiler.train(plot, options))
    contexts.foreach { context =>
      val direct = ok(PlotCompiler.resolve(plot, context, options))
      val placed = ok(PlotCompiler.place(trained, context))
      assertEquals(placed.scene, direct.scene, clues(name, context.width, context.height))
      assertEquals(
        placed.facetPanels.map(_.cell),
        direct.facetPanels.map(_.cell),
        clues(name)
      )
      assertEquals(placed.guides.length, direct.guides.length, clues(name))
    }

  test("placing a trained plot equals a full recompile at every context") {
    assertScenesMatch("scatter-lean", scatterPlot, leanDerived)
    assertScenesMatch("scatter-rich", scatterPlot, derivedGuides)
    assertScenesMatch("histogram", histogramPlot, derivedGuides)
    assertScenesMatch("labelled-line", labelledLinePlot, leanDerived)
    assertScenesMatch("facet-wrap", facetWrapPlot, leanDerived)
    assertScenesMatch("facet-grid-free-sparse", facetGridFreePlot, leanDerived)
  }

  test("placing twice at one context is deterministic") {
    val trained = ok(PlotCompiler.train(facetWrapPlot, leanDerived))
    val first = ok(PlotCompiler.place(trained, contexts.head))
    val second = ok(PlotCompiler.place(trained, contexts.head))
    assertEquals(first.scene, second.scene)
  }

  test("placement does no mapping, statistics, scale training, or row resolution") {
    val trained = ok(PlotCompiler.train(facetWrapPlot, leanDerived))
    contexts.foreach { context =>
      val (placed, readings) = PhaseClock.profile(PlotCompiler.place(trained, context))
      ok(placed)
      val dataPhases = Set[PhaseClock.Phase](
        PhaseClock.Phase.Mapping,
        PhaseClock.Phase.Stat,
        PhaseClock.Phase.ScaleTraining,
        PhaseClock.Phase.Resolve
      )
      readings.keys.foreach { phase =>
        assert(!dataPhases.contains(phase), clues(phase.toString, readings(phase).calls))
      }
      assert(
        readings.contains(PhaseClock.Phase.Layout) || readings.contains(PhaseClock.Phase.Lowering)
      )
    }
  }

  test("training does the data work exactly once regardless of later placements") {
    val (trained, readings) = PhaseClock.profile(PlotCompiler.train(facetWrapPlot, leanDerived))
    ok(trained)
    assert(readings.contains(PhaseClock.Phase.Stat), clues(readings.keySet.map(_.toString)))
    assert(readings.contains(PhaseClock.Phase.ScaleTraining))
    assert(readings.contains(PhaseClock.Phase.Resolve))
  }

  test("a faceted plot trained with an explicit layout still reports the solver requirement") {
    val layout = PanelLayout(
      PanelFrame.npcUnsafe(0.1, 0.1, 0.8, 0.8),
      xScale = Interval.unsafe(0.0, 1.0),
      yScale = Interval.unsafe(0.0, 1.0),
      clip = Clip.On
    )
    val result = PlotCompiler.train(facetWrapPlot, leanDerived.copy(layout = Some(layout)))
    assert(result.isLeft)
  }
