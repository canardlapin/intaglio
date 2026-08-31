package intaglio

class CompositionSuite extends munit.FunSuite:
  private final case class Observation(x: Double, y: Double, condition: String)

  private val context = RenderContext.unsafe(width = 800, height = 600)

  test("rows and grids align panels even when source guide margins differ") {
    val shortLabels = trainedPlot(
      "short-plot",
      "condition",
      Vector(1.0, 2.0)
    )
    val longLabels = trainedPlot(
      "long-plot",
      "condition",
      Vector(100000.0, 200000.0)
    )
    val third = trainedPlot(
      "third-plot",
      "condition",
      Vector(-50.0, 50.0)
    )

    val shortSource = normalized(shortLabels.layout.get.frame)
    val longSource = normalized(longLabels.layout.get.frame)
    assert(
      math.abs(shortSource._1 - longSource._1) > 1.0e-6,
      "fixture must exercise unequal source margins"
    )

    val row = PlotComposition
      .row(Vector(shortLabels, longLabels), context)
      .fold(error => fail(error.message), identity)
    assertEquals(row.cells.map(cell => (cell.row, cell.column)), Vector((0, 0), (0, 1)))
    val rowPanels = row.cells.map(cell => normalized(cell.panel))
    assertClose(rowPanels(0)._2, rowPanels(1)._2)
    assertClose(rowPanels(0)._3, rowPanels(1)._3)
    assertClose(rowPanels(0)._4, rowPanels(1)._4)
    assert(rowPanels(0)._1 < rowPanels(1)._1)

    val grid = PlotComposition
      .grid(Vector(shortLabels, longLabels, third), columns = 2, context)
      .fold(error => fail(error.message), identity)
    assertEquals(
      grid.cells.map(cell => (cell.row, cell.column)),
      Vector((0, 0), (0, 1), (1, 0))
    )
    val panels = grid.cells.map(cell => normalized(cell.panel))
    assertClose(panels(0)._1, panels(2)._1)
    assertClose(panels(0)._2, panels(1)._2)
    assertClose(panels(0)._3, panels(1)._3)
    assertClose(panels(0)._4, panels(2)._4)
    assert(panels(0)._2 > panels(2)._2, "row zero must be the visual top row")

    val lowered = grid.renderPlan.deviceScene.fold(error => fail(error.message), identity)
    assertEquals(lowered.semantics, grid.scene.semantics)
    assertEquals(lowered.semantics.plots.length, 3)
    val cellGroups = lowered.elements.collect { case group: DeviceElement.Group => group }
    assertEquals(cellGroups.length, 3)
    cellGroups.zip(grid.cells).foreach { case (group, cell) =>
      val clip = findGroup(group.children, GraphicsName.unsafe("plot-panel"))
        .flatMap {
          case DeviceElement.Group(_, value, _, _) => value
          case _                                   => None
        }
        .getOrElse(fail("composed plot panel must retain its device clip"))
      val panel = normalized(cell.panel)
      assertClose(clip.x, panel._1 * context.width.toDouble)
      assertClose(clip.y, (1.0 - panel._2 - panel._4) * context.height.toDouble)
      assertClose(clip.width, panel._3 * context.width.toDouble)
      assertClose(clip.height, panel._4 * context.height.toDouble)
    }
  }

  test("compatible guides are shared and incompatible guides remain collected") {
    val first = trainedPlot("first-guide-plot", "condition", Vector(1.0, 2.0))
    val compatible = trainedPlot("second-guide-plot", "condition", Vector(2.0, 3.0))
    val incompatible = trainedPlot("third-guide-plot", "cohort", Vector(3.0, 4.0))
    val legendName = first.guides
      .collectFirst { case ResolvedGuide(legend: GuideSpec.Legend, _) =>
        legend.name.get
      }
      .getOrElse(fail("fixture must derive a legend"))

    val kept = PlotComposition
      .row(Vector(first, compatible), context)
      .fold(error => fail(error.message), identity)
    assertEquals(countName(kept.scene.grobs, legendName), 2)

    val options = CompositionOptions.unsafe(
      guides = CompositionGuidePolicy.CollectCompatible
    )
    val shared = PlotComposition
      .row(Vector(first, compatible), context, options)
      .fold(error => fail(error.message), identity)
    assertEquals(shared.collectedGuides.count(_.spec.isInstanceOf[GuideSpec.Legend]), 1)
    assertEquals(countName(shared.scene.grobs, legendName), 1)

    val collected = PlotComposition
      .grid(Vector(first, compatible, incompatible), 2, context, options)
      .fold(error => fail(error.message), identity)
    assertEquals(collected.collectedGuides.count(_.spec.isInstanceOf[GuideSpec.Legend]), 2)
    assertEquals(collected.scene.semantics.plots.length, 3)
    collected.renderPlan.deviceScene.fold(error => fail(error.message), identity)

    val firstColorbar = continuousPlot("first-colorbar-plot", "activation")
    val secondColorbar = continuousPlot("second-colorbar-plot", "activation")
    val mixed = PlotComposition
      .grid(Vector(first, firstColorbar, secondColorbar), 2, context, options)
      .fold(error => fail(error.message), identity)
    assertEquals(mixed.collectedGuides.count(_.spec.isInstanceOf[GuideSpec.Legend]), 1)
    assertEquals(mixed.collectedGuides.count(_.spec.isInstanceOf[GuideSpec.Colorbar]), 1)
    mixed.renderPlan.deviceScene.fold(error => fail(error.message), identity)
  }

  test("insets retain explicit viewport and clipping semantics") {
    val basePlot = trainedPlot("base-plot", "condition", Vector(1.0, 2.0))
    val insetPlot = trainedPlot("inset-plot", "cohort", Vector(3.0, 4.0))
    val base = PlotComposition
      .row(Vector(basePlot), context)
      .fold(error => fail(error.message), identity)
    val clipped = PlotInset.npcUnsafe(0.58, 0.55, 0.36, 0.35, Clip.On)
    val composed = base.withInset(insetPlot, clipped)

    assertEquals(composed.insetCount, 1)
    assertEquals(composed.scene.semantics.plots.length, 2)
    composed.scene.grobs.last match
      case group: Grob.Group => assertEquals(group.viewport, Some(clipped.viewport))
      case other             => fail(s"expected inset group, found $other")

    val lowered =
      composed.renderPlan.deviceScene.fold(error => fail(error.message), identity)
    lowered.elements.last match
      case DeviceElement.Group(name, clip, _, _) =>
        assertEquals(name.map(_.value), Some("composition-inset-0"))
        assert(clip.nonEmpty, "Clip.On must become a device clip")
      case other => fail(s"expected lowered inset group, found $other")

    val open = base.withInset(
      insetPlot,
      PlotInset.npcUnsafe(0.58, 0.55, 0.36, 0.35, Clip.Off)
    )
    val openDevice =
      open.renderPlan.deviceScene.fold(error => fail(error.message), identity)
    openDevice.elements.last match
      case DeviceElement.Group(_, clip, _, _) => assertEquals(clip, None)
      case other                              => fail(s"expected lowered inset group, found $other")
  }

  test("invalid grids, gaps, inset bounds, and layout-free plots fail through typed errors") {
    assertEquals(
      PlotComposition.row(Vector.empty, context).left.toOption,
      Some(GraphicsError.InvalidCompositionGrid(0, 0))
    )
    assert(CompositionOptions(columnGapPt = Some(Double.NaN)).left.toOption.exists {
      case GraphicsError.InvalidCompositionGap("column", value) => value.isNaN
      case _                                                    => false
    })
    assertEquals(
      PlotInset.npc(0.8, 0.8, 0.3, 0.3, Clip.On).left.toOption,
      Some(GraphicsError.InvalidInsetBounds(0.8, 0.8, 0.3, 0.3))
    )

    val data = Vector(Observation(0.0, 1.0, "control"))
    val plot = Plot(data)
      .addLayer(Layer.point[Observation](_.x, _.y))
      .fold(error => fail(error.message), identity)
    val layoutFree = PlotCompiler.resolve(plot).fold(error => fail(error.message), identity)
    assertEquals(
      PlotComposition.row(Vector(layoutFree), context).left.toOption,
      Some(
        GraphicsError.InvalidCompositionPanel(0, "plot was compiled without a layout")
      )
    )
  }

  private def trainedPlot(
      semanticName: String,
      scaleName: String,
      ys: Vector[Double]
  ): TrainedPlot =
    val conditions = Vector("control", "treatment")
    val data = ys.zip(conditions).zipWithIndex.map { case ((y, condition), index) =>
      Observation(index.toDouble, y, condition)
    }
    val domain = DiscreteDomain
      .ordered(conditions)
      .fold(error => fail(error.message), identity)
    val scale = DiscreteScale(
      scaleName,
      domain,
      DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.White))
    ).fold(error => fail(error.message), identity)
    val plot = Plot(data)
      .withSemanticId(SemanticId.unsafe(semanticName))
      .withTitle(semanticName)
      .withScale(ScaleBinding(Aesthetic.Color, _.condition, scale))
      .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      .fold(error => fail(error.message), identity)
    PlotCompiler
      .resolve(
        plot,
        context,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
      .fold(error => fail(error.message), identity)

  private def continuousPlot(semanticName: String, scaleName: String): TrainedPlot =
    val data = Vector(
      Observation(0.0, 1.0, "control"),
      Observation(1.0, 10.0, "treatment"),
      Observation(2.0, 100.0, "treatment")
    )
    val scale = ContinuousScale
      .train(
        scaleName,
        data.map(_.y),
        Palette.gradient(Rgba.unsafe(20, 30, 80), Rgba.unsafe(240, 210, 40)),
        transform = Transform.log10
      )
      .fold(error => fail(error.message), identity)
    val plot = Plot(data)
      .withSemanticId(SemanticId.unsafe(semanticName))
      .withTitle(semanticName)
      .withScale(ScaleBinding[Observation, Double, Rgba](Aesthetic.Color, _.y, scale))
      .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
      .fold(error => fail(error.message), identity)
    PlotCompiler
      .resolve(
        plot,
        context,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
      .fold(error => fail(error.message), identity)

  private def normalized(frame: PanelFrame): (Double, Double, Double, Double) =
    val device = context.deviceContext
    val resolver = new LengthResolver(
      device,
      DeviceFrame.root(device),
      context.fontRegistry,
      context.lineHeightPt
    )
    val x = resolver.x(frame.origin.x).fold(error => fail(error.message), identity)
    val lowerY = resolver.y(frame.origin.y).fold(error => fail(error.message), identity)
    val width = resolver.width(frame.size.width).fold(error => fail(error.message), identity)
    val height = resolver.height(frame.size.height).fold(error => fail(error.message), identity)
    (
      x / context.width.toDouble,
      (context.height.toDouble - lowerY) / context.height.toDouble,
      width / context.width.toDouble,
      height / context.height.toDouble
    )

  private def countName(grobs: Vector[Grob], name: GraphicsName): Int =
    grobs.map { grob =>
      val here = if grob.name.contains(name) then 1 else 0
      here + countName(grob.children, name)
    }.sum

  private def findGroup(
      elements: Vector[DeviceElement],
      name: GraphicsName
  ): Option[DeviceElement] =
    elements.iterator
      .map {
        case group @ DeviceElement.Group(groupName, _, _, children) =>
          if groupName.contains(name) then Some(group) else findGroup(children, name)
        case _: DeviceElement.Mark => None
      }
      .collectFirst { case Some(group) => group }

  private def assertClose(actual: Double, expected: Double): Unit =
    assertEqualsDouble(actual, expected, 1.0e-12)
