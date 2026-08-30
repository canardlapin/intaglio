package intaglio

class FacetSuite extends munit.FunSuite:
  private val tolerance = 1e-9

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

  private def npcX(points: Double): Double =
    points * (4.0 / 3.0) / 640.0

  private def originX(frame: PanelFrame): Double =
    frame.origin.x match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected npc constant, got $other")

  private def width(frame: PanelFrame): Double =
    frame.size.width.expr match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected npc constant, got $other")

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

  test("reference annotation facet participation is explicit and row-independent") {
    val facet =
      FacetSpec.wrap[Observation](_.condition).fold(error => fail(error.message), identity)

    def resolve(policy: AnnotationFacetPolicy): TrainedPlot =
      Plot(rows)
        .withFacet(facet)
        .addLayer(Layer.point[Observation](_.x, _.y))
        .flatMap(
          _.addLayer(
            Layer.hline[Observation](50.0, facets = policy)
          )
        )
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
          )
        )
        .fold(error => fail(error.message), identity)

    val repeated = resolve(AnnotationFacetPolicy.Repeat)
    assert(repeated.facetPanels.forall(_.layers(1).annotation.nonEmpty))
    assert(repeated.facetPanels.forall(_.layers(1).dataSize == 0))
    assert(repeated.facetPanels.forall(_.layers(1).rows.isEmpty))
    assert(repeated.facetPanels.forall(_.layers(1).grobs.length == 1))

    val excluded = resolve(AnnotationFacetPolicy.Exclude)
    assert(excluded.facetPanels.forall(_.layers(1).annotation.isEmpty))
    assert(excluded.facetPanels.forall(_.layers(1).grobs.isEmpty))
  }

  test("declared empty facets render repeated reference annotations") {
    val facet =
      FacetSpec
        .wrap[Observation](_.condition, levels = Vector("control", "task"))
        .fold(error => fail(error.message), identity)
    val trained =
      Plot(Vector.empty[Observation])
        .withFacet(facet)
        .addLayer(Layer.hline[Observation](0.5))
        .flatMap(_.addLayer(Layer.vline[Observation](0.5)))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
          )
        )
        .fold(error => fail(error.message), identity)

    assertEquals(trained.facetPanels.map(_.cell.label), Vector("control", "task"))
    assert(trained.facetPanels.forall(_.layers.map(_.dataSize) == Vector(0, 0)))
    assert(trained.facetPanels.forall(_.layers.map(_.grobs.length) == Vector(1, 1)))
  }

  test("free facet scales train and map repeated annotations panel-locally") {
    val trained =
      plot(rows)
        .aes(_.x, _.y)
        .scaleYContinuous()
        .facetWrap(_.condition, scales = FacetScales.FreeY)
        .geomPoint()
        .hline(50.0)
        .resolve
        .fold(error => fail(error.message), identity)

    val domains = trained.facetPanels.map { panel =>
      panel.scaleRegistry.forAesthetic(Aesthetic.Y).map(_.descriptor.domain)
    }
    assertEquals(
      domains,
      Vector(
        Some(ScaleDomain.Continuous(Interval.unsafe(0.0, 50.0), Interval.unsafe(0.0, 50.0))),
        Some(ScaleDomain.Continuous(Interval.unsafe(50.0, 200.0), Interval.unsafe(50.0, 200.0)))
      )
    )
    assertEquals(
      trained.facetPanels.map(_.layers(1).annotation.map(_.coordinate)),
      Vector(Some(1.0), Some(0.0))
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

  test("free dimensions render panel-local axes while shared dimensions suppress inner axes") {
    val gridRows =
      Vector(
        Observation(0.0, 0.0, "control", "one"),
        Observation(1.0, 1.0, "control", "one"),
        Observation(10.0, 100.0, "task", "one"),
        Observation(20.0, 200.0, "task", "one"),
        Observation(100.0, 10.0, "control", "two"),
        Observation(110.0, 20.0, "control", "two"),
        Observation(1000.0, 1000.0, "task", "two"),
        Observation(1100.0, 1200.0, "task", "two")
      )

    def resolve(scales: FacetScales): TrainedPlot =
      plot(gridRows)
        .aes(_.x, _.y)
        .facetGrid(_.session, _.condition, scales = scales)
        .geomPoint()
        .resolve
        .fold(error => fail(error.message), identity)

    def axisCells(plot: TrainedPlot, side: AxisSide): Set[(Int, Int)] =
      plot.guides.flatMap { guide =>
        guide.spec match
          case axis: GuideSpec.Axis if axis.side == side =>
            axis.name.map { name =>
              val coordinates = name.value.split("-").takeRight(2).map(_.toInt)
              coordinates(0) -> coordinates(1)
            }
          case _ => None
      }.toSet

    val allCells = Set((0, 0), (0, 1), (1, 0), (1, 1))
    val bottomCells = Set((1, 0), (1, 1))
    val leftCells = Set((0, 0), (1, 0))
    val shared = resolve(FacetScales.Shared)
    val freeX = resolve(FacetScales.FreeX)
    val freeY = resolve(FacetScales.FreeY)
    val free = resolve(FacetScales.Free)

    assertEquals(axisCells(shared, AxisSide.Bottom), bottomCells)
    assertEquals(axisCells(shared, AxisSide.Left), leftCells)
    assertEquals(axisCells(freeX, AxisSide.Bottom), allCells)
    assertEquals(axisCells(freeX, AxisSide.Left), leftCells)
    assertEquals(axisCells(freeY, AxisSide.Bottom), bottomCells)
    assertEquals(axisCells(freeY, AxisSide.Left), allCells)
    assertEquals(axisCells(free, AxisSide.Bottom), allCells)
    assertEquals(axisCells(free, AxisSide.Left), allCells)

    val panels = free.facetPanels.map(panel => (panel.cell.row, panel.cell.column) -> panel).toMap
    free.guides.foreach { guide =>
      guide.spec match
        case axis: GuideSpec.Axis =>
          val coordinates = axis.name.toVector.flatMap(_.value.split("-").takeRight(2)).map(_.toInt)
          val panel = panels(coordinates(0) -> coordinates(1))
          val range = if axis.side.isHorizontal then panel.layout.xScale else panel.layout.yScale
          assert(
            axis.ticks
              .getOrElse(fail("expected materialized facet ticks"))
              .forall(tick => range.contains(tick.value))
          )
        case _ => ()
    }
  }

  test("free-axis sizing uses target text metrics for outer strips and panel gaps") {
    val metrics = new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        if text == "W" then 100.0 else 1.0

      override def heightPt(fontSizePt: Double): Double =
        fontSizePt

    val breaks: Breaks = range => Vector(range.lower, range.upper)
    val labels: Labeler =
      values => values.map(value => if value < 50.0 then "W" else "iiiiiiiiiiii")
    val axis = GuideSpec.Axis(
      AxisSide.Left,
      breaks = breaks,
      labeler = labels,
      title = Some("y")
    )
    val theme = Theme.default.copy(layout = Theme.default.layout.copy(metrics = metrics))
    val trained =
      plot(rows)
        .aes(_.x, _.y)
        .facetWrap(_.condition, columns = 2, scales = FacetScales.FreeY)
        .geomPoint()
        .guides(GuidePolicy.Derived(overrides = Vector(axis)))
        .theme(theme)
        .resolve
        .fold(error => fail(error.message), identity)

    val expectedAxisPt =
      theme.layout.tickLengthPt + theme.layout.tickLabelGapPt + 100.0 +
        theme.layout.axisTitleGapPt + metrics.heightPt(theme.layout.axisTitleTextStyle)
    val first = trained.facetPanels(0).layout.frame
    val second = trained.facetPanels(1).layout.frame
    assertEqualsDouble(
      originX(first),
      npcX(theme.layout.outerMarginPt + expectedAxisPt),
      tolerance
    )
    assertEqualsDouble(
      originX(second) - originX(first) - width(first),
      npcX(theme.layout.panelGapPt + expectedAxisPt),
      tolerance
    )
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
