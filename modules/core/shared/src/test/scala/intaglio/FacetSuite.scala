package intaglio

class FacetSuite extends munit.FunSuite:
  private final case class Observation(
      x: Double,
      y: Double,
      condition: String,
      session: String = "one"
  )

  private val rows =
    Vector(
      Observation(0.0, 0.0, "control"),
      Observation(1.0, 1.0, "control"),
      Observation(10.0, 100.0, "task"),
      Observation(20.0, 200.0, "task")
    )

  private final case class Annotation(x: Double, y: Double, panel: String)

  test("independent layers require and obey explicit facet participation policies") {
    val annotations = Vector(Annotation(-1.0, -1.0, "control"), Annotation(30.0, 300.0, "task"))
    val annotationLayer = Layer.point[Annotation](_.x, _.y, inheritMapping = false)
    val facet =
      FacetSpec.wrap[Observation](_.condition).fold(error => fail(error.message), identity)

    def resolve(policy: LayerFacetPolicy[Annotation]): TrainedPlot =
      Plot(rows)
        .withFacet(facet)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .flatMap(_.addIndependentLayer(annotations, annotationLayer, policy))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
          )
        )
        .fold(error => fail(error.message), identity)

    val repeated = resolve(LayerFacetPolicy.Repeat)
    assertEquals(repeated.facetPanels.map(_.layers(1).dataSize), Vector(2, 2))

    val selected = resolve(LayerFacetPolicy.Select((cell, row) => cell.label == row.panel))
    assertEquals(selected.facetPanels.map(_.layers(1).dataSize), Vector(1, 1))
    assert(
      selected.facetPanels.zip(annotations).forall { case (panel, annotation) =>
        panel.layers(1).rows.head.source == annotation
      }
    )

    val excluded = resolve(LayerFacetPolicy.Exclude)
    assertEquals(excluded.facetPanels.map(_.layers(1).dataSize), Vector(0, 0))
    assert(excluded.facetPanels.forall(_.layers(1).rows.isEmpty))
  }

  test("facet wrap partitions rows before statistics and assigns stable scene names") {
    val counted = Vector(
      Observation(0.0, 0.0, "control"),
      Observation(0.0, 0.0, "control"),
      Observation(0.0, 0.0, "task")
    )
    val facet =
      FacetSpec.wrap[Observation](_.condition).fold(error => fail(error.message), identity)
    val plot = Plot(counted)
      .withFacet(facet)
      .addLayer(Layer.count[Observation](_ => "all"))
      .fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(
        plot,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
      .fold(error => fail(error.message), identity)

    assertEquals(trained.facetPanels.map(_.cell.label), Vector("control", "task"))
    assertEquals(trained.facetPanels.map(_.layers.head.dataSize), Vector(2, 1))
    assertEquals(
      trained.facetPanels.map(_.layers.head.rows.head.computed.get(ComputedAesthetic.Count)),
      Vector(Some(2.0), Some(1.0))
    )
    assertEquals(
      trained.scene.grobs.take(4).flatMap(_.name).map(_.value),
      Vector("panel-0-0", "strip-0-0", "panel-0-1", "strip-0-1")
    )
  }

  test("shared ranges union panels while free position scales train per panel") {
    def resolve(scales: FacetScales, scaled: Boolean): TrainedPlot =
      val builder =
        plot(rows)
          .aes(_.x, _.y)
          .facetWrap(_.condition, scales = scales)
      val withScales =
        if scaled then builder.scaleXContinuous().scaleYContinuous()
        else builder
      withScales.geomPoint().resolve.fold(error => fail(error.message), identity)

    val shared = resolve(FacetScales.Shared, scaled = false)
    assertEquals(shared.facetPanels.map(_.layout.xScale).distinct.length, 1)
    assertEquals(shared.facetPanels.map(_.layout.yScale).distinct.length, 1)
    assert(shared.facetPanels.head.layout.xScale.contains(20.0))
    assert(shared.facetPanels.last.layout.yScale.contains(0.0))

    val free = resolve(FacetScales.Free, scaled = false)
    assertEquals(free.facetPanels.map(_.layout.xScale).distinct.length, 2)
    assertEquals(free.facetPanels.map(_.layout.yScale).distinct.length, 2)
    assert(!free.facetPanels.head.layout.xScale.contains(20.0))
    assert(!free.facetPanels.last.layout.yScale.contains(0.0))

    val freeScaled = resolve(FacetScales.FreeX, scaled = true)
    val domains = freeScaled.facetPanels.map { panel =>
      panel.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor.domain)
    }
    assertEquals(
      domains,
      Vector(
        Some(ScaleDomain.Continuous(Interval.unsafe(0.0, 1.0), Interval.unsafe(0.0, 1.0))),
        Some(ScaleDomain.Continuous(Interval.unsafe(10.0, 20.0), Interval.unsafe(10.0, 20.0)))
      )
    )

    val colored =
      plot(rows)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.condition, levels = Vector("control", "task"))
        .facetWrap(_.condition, scales = FacetScales.Free)
        .geomPoint()
        .resolve
        .fold(error => fail(error.message), identity)
    assertEquals(colored.guides.count(_.spec.isInstanceOf[GuideSpec.Legend]), 1)
    assertEquals(
      colored.facetPanels
        .map(_.scaleRegistry.forAesthetic(Aesthetic.Color).map(_.descriptor.domain))
        .distinct
        .length,
      1
    )

    val activationScale = ContinuousScale
      .train(
        "activation",
        rows.map(_.y),
        Palette.gradient(Rgba.unsafe(20, 30, 80), Rgba.unsafe(240, 210, 40))
      )
      .fold(error => fail(error.message), identity)
    val continuousFacet = Plot(rows)
      .withFacet(
        FacetSpec.wrap[Observation](_.condition).fold(error => fail(error.message), identity)
      )
      .withScale(ScaleBinding[Observation, Double, Rgba](Aesthetic.Color, _.y, activationScale))
      .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      .fold(error => fail(error.message), identity)
    val continuous = PlotCompiler
      .resolve(
        continuousFacet,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
      .fold(error => fail(error.message), identity)
    assertEquals(continuous.guides.count(_.spec.isInstanceOf[GuideSpec.Colorbar]), 1)
  }

  test("facet grid forms the row-column product and rejects incompatible layout modes") {
    val gridRows =
      Vector(
        Observation(0.0, 0.0, "control", "one"),
        Observation(1.0, 1.0, "task", "one"),
        Observation(2.0, 2.0, "control", "two")
      )
    val program =
      plot(gridRows)
        .aes(_.x, _.y)
        .facetGrid(_.session, _.condition)
        .geomPoint()
        .build
        .fold(error => fail(error.message), identity)
    val trained = program.resolve.fold(error => fail(error.message), identity)

    assertEquals(
      trained.facetPanels.map(panel => (panel.cell.row, panel.cell.column, panel.cell.label)),
      Vector(
        (0, 0, "one | control"),
        (0, 1, "one | task"),
        (1, 0, "two | control"),
        (1, 1, "two | task")
      )
    )
    assertEquals(trained.facetPanels.map(_.layers.head.dataSize), Vector(1, 1, 1, 0))
    assertEquals(trained.guides.count(_.spec.isInstanceOf[GuideSpec.Axis]), 4)

    val explicit = program.compilerOptions.copy(
      layout = Some(PanelLayout.unit(Interval.unsafe(0.0, 1.0), Interval.unsafe(0.0, 1.0)))
    )
    assertEquals(
      PlotCompiler.resolve(program.plot, explicit).left.toOption,
      Some(GraphicsError.FacetRequiresSolver)
    )
    assertEquals(
      FacetSpec.wrap[Observation](_.condition, columns = 0).left.toOption,
      Some(GraphicsError.InvalidFacetColumns(0))
    )
    assertEquals(
      FacetSpec.wrap[Observation](_.condition, levels = Vector("task", "task")).left.toOption,
      Some(GraphicsError.DuplicateLevel("task"))
    )
  }

  test("fixed coordinates fail explicitly for facet grids") {
    val result =
      plot(rows)
        .aes(_.x, _.y)
        .facetWrap(_.condition)
        .geomPoint()
        .coordFixed()
        .resolve
    assertEquals(result.left.toOption, Some(GraphicsError.FacetFixedCoordinates))
  }
