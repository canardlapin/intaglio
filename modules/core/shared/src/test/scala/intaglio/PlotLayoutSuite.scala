package intaglio

class PlotLayoutSuite extends munit.FunSuite:
  private val tol = 1e-9

  private val policy = LayoutPolicy()

  // Reference device 640x480 at 96ppi: 1pt = 4/3 px.
  private def npcX(pt: Double): Double = pt * (4.0 / 3.0) / 640.0
  private def npcY(pt: Double): Double = pt * (4.0 / 3.0) / 480.0

  private def originX(frame: PanelFrame): Double =
    frame.origin.x match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected npc constant, got $other")

  private def originY(frame: PanelFrame): Double =
    frame.origin.y match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected npc constant, got $other")

  private def width(frame: PanelFrame): Double =
    frame.size.width.expr match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected npc constant, got $other")

  private def height(frame: PanelFrame): Double =
    frame.size.height.expr match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected npc constant, got $other")

  test("panel-only requests allocate margins and nothing else") {
    val frames =
      PlotLayoutSolver.solve(policy, PlotLayoutRequest()).fold(e => fail(e.message), identity)
    assertEqualsDouble(originX(frames.panel), npcX(10.0), tol)
    assertEqualsDouble(originY(frames.panel), npcY(10.0), tol)
    assertEqualsDouble(width(frames.panel), 1.0 - 2.0 * npcX(10.0), tol)
    assertEqualsDouble(height(frames.panel), 1.0 - 2.0 * npcY(10.0), tol)
    assertEquals(frames.axes, Map.empty[AxisSide, PanelFrame])
    assertEquals(frames.legend, None)
  }

  test("axis requests allocate strips sized from tick labels via text metrics") {
    val request = PlotLayoutRequest(
      axes = Map(
        AxisSide.Bottom -> AxisRequest(Vector("0", "5", "10")),
        AxisSide.Left -> AxisRequest(Vector("-1", "1"))
      )
    )
    val frames = PlotLayoutSolver.solve(policy, request).fold(e => fail(e.message), identity)

    // Bottom strip: tick 4 + gap 4 + line height 12.5 = 20.5pt.
    val bottomStrip = npcY(20.5)
    // Left strip: tick 4 + gap 4 + max label width ("-1": 2 chars * 10pt * 0.62 = 12.4) = 20.4pt.
    val leftStrip = npcX(20.4)

    assertEqualsDouble(originY(frames.panel), npcY(10.0) + bottomStrip, tol)
    assertEqualsDouble(originX(frames.panel), npcX(10.0) + leftStrip, tol)

    val bottom = frames.axes(AxisSide.Bottom)
    assertEqualsDouble(originY(bottom), npcY(10.0), tol)
    assertEqualsDouble(height(bottom), bottomStrip, tol)
    assertEqualsDouble(width(bottom), width(frames.panel), tol)

    val left = frames.axes(AxisSide.Left)
    assertEqualsDouble(originX(left), npcX(10.0), tol)
    assertEqualsDouble(width(left), leftStrip, tol)
    assertEqualsDouble(height(left), height(frames.panel), tol)
  }

  test("axis titles and plot labels receive solver-owned regions") {
    val request = PlotLayoutRequest(
      axes = Map(
        AxisSide.Bottom -> AxisRequest(Vector("0", "10"), Some("Time")),
        AxisSide.Left -> AxisRequest(Vector("-1", "1"), Some("Signal"))
      ),
      labels = PlotLabels(title = Some("Activation"), subtitle = Some("Subject mean"))
    )
    val frames = PlotLayoutSolver.solve(policy, request).fold(e => fail(e.message), identity)

    val axisTitleExtent = 6.0 + 11.0 * 1.25
    assertEqualsDouble(height(frames.axes(AxisSide.Bottom)), npcY(20.5 + axisTitleExtent), tol)
    assertEqualsDouble(width(frames.axes(AxisSide.Left)), npcX(20.4 + axisTitleExtent), tol)

    val title = frames.title.getOrElse(fail("expected title frame"))
    val subtitle = frames.subtitle.getOrElse(fail("expected subtitle frame"))
    assertEqualsDouble(height(title), npcY(16.0 * 1.25), tol)
    assertEqualsDouble(height(subtitle), npcY(12.0 * 1.25), tol)
    assertEqualsDouble(originY(title), originY(subtitle) + height(subtitle) + npcY(4.0), tol)
    assertEqualsDouble(
      originY(subtitle),
      originY(frames.panel) + height(frames.panel) + npcY(4.0),
      tol
    )
  }

  test("legend requests allocate a right-hand column beside the panel") {
    val request = PlotLayoutRequest(
      legend = Some(LegendRequest(title = Some("condition"), labels = Vector("A", "B")))
    )
    val frames = PlotLayoutSolver.solve(policy, request).fold(e => fail(e.message), identity)
    val legend = frames.legend.getOrElse(fail("expected a legend frame"))

    // Title width 9 chars * 10pt * 0.62 = 55.8pt dominates entries; plus 12pt padding.
    val legendWidth = npcX(2.0 * 6.0 + 55.8)
    assertEqualsDouble(width(legend), legendWidth, tol)
    assertEqualsDouble(
      originX(legend),
      originX(frames.panel) + width(frames.panel) + npcX(10.0),
      tol
    )
    assertEqualsDouble(originY(legend), originY(frames.panel), tol)
    assertEqualsDouble(height(legend), height(frames.panel), tol)
    assertEqualsDouble(
      originX(frames.panel) + width(frames.panel) + npcX(10.0) + legendWidth + npcX(10.0),
      1.0,
      tol
    )
  }

  test("colorbar requests reserve their wider swatch and tick-label offset") {
    val ordinary = PlotLayoutSolver
      .solve(policy, PlotLayoutRequest(legend = Some(LegendRequest(None, Vector("100")))))
      .fold(e => fail(e.message), identity)
    val colorbar = PlotLayoutSolver
      .solve(
        policy,
        PlotLayoutRequest(legend = Some(LegendRequest(None, Vector("100"), extraKeyWidthPt = 5.0)))
      )
      .fold(e => fail(e.message), identity)

    assertEqualsDouble(
      width(colorbar.legend.getOrElse(fail("expected colorbar frame"))) -
        width(ordinary.legend.getOrElse(fail("expected guide frame"))),
      npcX(5.0),
      tol
    )
  }

  test("legend columns clear a right-axis strip instead of overlapping it") {
    val request = PlotLayoutRequest(
      axes = Map(AxisSide.Right -> AxisRequest(Vector("-1", "1"))),
      legend = Some(LegendRequest(title = None, labels = Vector("A", "B")))
    )
    val frames = PlotLayoutSolver.solve(policy, request).fold(e => fail(e.message), identity)
    val right = frames.axes(AxisSide.Right)
    val legend = frames.legend.getOrElse(fail("expected a legend frame"))

    assertEqualsDouble(originX(right), originX(frames.panel) + width(frames.panel), tol)
    assertEqualsDouble(originX(legend), originX(right) + width(right) + npcX(10.0), tol)
  }

  test("impossible layouts are typed errors") {
    val hugeLegend = PlotLayoutRequest(
      legend = Some(LegendRequest(title = None, labels = Vector("x" * 200)))
    )
    assertEquals(
      PlotLayoutSolver.solve(policy, hugeLegend).left.toOption,
      Some(GraphicsError.LayoutOverflow("panel width"))
    )
  }

  test("guide stacks derive row, title, and inter-guide spacing from policy") {
    val plan = GuideStackSolver.plan(
      policy,
      LegendRequest(
        Vector(
          GuideLayoutRequest.Legend(Some("group"), Vector("A", "B")),
          GuideLayoutRequest.Colorbar(Some("value"), Vector("0", "100"))
        )
      )
    )
    val legend = plan.placements.head match
      case placement: GuidePlacement.Legend => placement
      case other                            => fail(s"expected a legend placement, got $other")
    val colorbar = plan.placements(1) match
      case placement: GuidePlacement.Colorbar => placement
      case other                              => fail(s"expected a colorbar placement, got $other")
    val textHeight = policy.metrics.heightPt(policy.legendTextStyle)

    assertEqualsDouble(
      legend.rowPitchPt,
      math.max(policy.legendKeyPt, textHeight) + policy.legendRowGapPt,
      tol
    )
    assertEqualsDouble(
      legend.firstRowOffsetPt,
      textHeight + policy.legendTitleGapPt + math.max(policy.legendKeyPt, textHeight) / 2.0,
      tol
    )
    assert(colorbar.topPt > legend.topPt)
    assert(
      plan.widthPt >= policy.metrics
        .widthPt("value", policy.legendTitleTextStyle) + 2.0 * policy.legendPaddingPt
    )
  }

  test("guide stacks that exceed the reserved viewport fail with typed overflow") {
    val shortDevice = policy.copy(referenceDevice = DeviceContext.unsafe(240.0, 120.0))
    val request = PlotLayoutRequest(
      legend = Some(
        LegendRequest(Vector(GuideLayoutRequest.Colorbar(Some("value"), Vector("0", "100"))))
      )
    )

    assertEquals(
      PlotLayoutSolver.solve(shortDevice, request).left.toOption,
      Some(GraphicsError.LayoutOverflow("guide stack height"))
    )
  }

  test("panel grids allocate stable row-major data and strip frames") {
    val request = PlotLayoutRequest(grid = Some(PanelGridRequest(rows = 2, columns = 2, count = 4)))
    val frames = PlotLayoutSolver.solve(policy, request).fold(e => fail(e.message), identity)

    assertEquals(
      frames.grid.map(frame => (frame.row, frame.column)),
      Vector((0, 0), (0, 1), (1, 0), (1, 1))
    )
    assertEquals(frames.panelFrames, frames.grid.map(_.panel))
    assert(originY(frames.grid(0).panel) > originY(frames.grid(2).panel))
    frames.grid.foreach { frame =>
      assertEqualsDouble(originY(frame.strip), originY(frame.panel) + height(frame.panel), tol)
      assertEqualsDouble(width(frame.strip), width(frame.panel), tol)
      assertEqualsDouble(height(frame.strip), npcY(policy.facetStripPt), tol)
    }
    assertEqualsDouble(
      originX(frames.grid(1).panel) - originX(frames.grid(0).panel) - width(frames.grid(0).panel),
      npcX(policy.panelGapPt),
      tol
    )

    val measured = PlotLayoutSolver
      .solve(
        policy,
        PlotLayoutRequest(
          grid = Some(
            PanelGridRequest(
              rows = 2,
              columns = 2,
              count = 4,
              columnGapPt = Some(37.0),
              rowGapPt = Some(41.0)
            )
          )
        )
      )
      .fold(e => fail(e.message), identity)
    assertEqualsDouble(
      originX(measured.grid(1).panel) - originX(measured.grid(0).panel) -
        width(measured.grid(0).panel),
      npcX(37.0),
      tol
    )
    assertEqualsDouble(
      originY(measured.grid(0).panel) - originY(measured.grid(2).strip) -
        height(measured.grid(2).strip),
      npcY(41.0),
      tol
    )
  }

  test("solver-driven compilation places the legend in its own named viewport") {
    final case class Obs(x: Double, y: Double, condition: String)
    val data = Vector(Obs(0.0, 1.0, "A"), Obs(1.0, 2.0, "B"), Obs(2.0, 3.0, "A"))
    val colorScale = DiscreteScale(
      "condition",
      DiscreteDomain.ordered(Vector("A", "B")).fold(e => fail(e.message), identity),
      DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(40, 80, 120), Rgba.unsafe(210, 120, 40)))
    ).fold(e => fail(e.message), identity)
    val plot = Plot(data)
      .withLabels(PlotLabels(title = Some("Activation"), subtitle = Some("Subject mean")))
      .withScale(ScaleBinding[Obs, String, Rgba](Aesthetic.Color, _.condition, colorScale))
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(policy = Some(policy), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)

    val layout = trained.layout.getOrElse(fail("expected a solved layout"))
    assertEquals(layout.xScale, Interval.unsafe(-0.1, 2.1))

    val legendGuide = trained.guides
      .collectFirst { case guide @ ResolvedGuide(_: GuideSpec.Legend, _) =>
        guide
      }
      .getOrElse(fail("expected a derived legend"))
    val legendGroup = legendGuide.grob.asInstanceOf[Grob.Group]
    assertEquals(legendGroup.name.map(_.value), Some("condition-legend"))
    assert(legendGroup.viewport.nonEmpty, "legend must live in its allocated viewport")

    val scene = trained.scene
    assert(scene.grobs.head.name.map(_.value).contains("plot-panel"))
    assertEquals(
      trained.labelGrobs.flatMap(_.name).map(_.value),
      Vector("plot-title", "plot-subtitle")
    )
    assertEquals(
      scene.grobs.takeRight(2).flatMap(_.name).map(_.value),
      Vector("plot-title", "plot-subtitle")
    )
  }

  test("solver-driven compilation places a continuous colorbar in the guide viewport") {
    final case class Obs(x: Double, y: Double, activation: Double)
    val data = Vector(Obs(0.0, 1.0, 1.0), Obs(1.0, 2.0, 10.0), Obs(2.0, 3.0, 100.0))
    val scale = ContinuousScale
      .train(
        "activation",
        data.map(_.activation),
        Palette.gradient(Rgba.Black, Rgba.White),
        transform = Transform.log10
      )
      .fold(e => fail(e.message), identity)
    val plot = Plot(data)
      .withScale(ScaleBinding[Obs, Double, Rgba](Aesthetic.Fill, _.activation, scale))
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(policy = Some(policy), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)
    val colorbar = trained.guides
      .collectFirst { case guide @ ResolvedGuide(_: GuideSpec.Colorbar, _) =>
        guide
      }
      .getOrElse(fail("expected a derived colorbar"))
    val group = colorbar.grob.asInstanceOf[Grob.Group]

    assertEquals(group.name.map(_.value), Some("activation-colorbar"))
    assert(group.viewport.nonEmpty, "colorbar must live in its allocated guide viewport")
  }

  test("solved scenes lower to identical device scenes across runs") {
    final case class Obs(x: Double, y: Double)
    val data = Vector(Obs(0.0, 1.0), Obs(1.0, 2.0), Obs(2.0, 3.0))
    val plot = Plot(data)
      .addLayer(Layer.point[Obs](_.x, _.y))
      .fold(e => fail(e.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(policy = Some(policy), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)

    val device = DeviceContext.unsafe(640.0, 480.0)
    val first = DeviceScene.fromScene(trained.scene, device).fold(e => fail(e.message), identity)
    val second = DeviceScene.fromScene(trained.scene, device).fold(e => fail(e.message), identity)
    assertEquals(first, second)
  }

  test("default derived ranges keep scaled and unscaled corner discs inside the panel clip") {
    final case class Obs(x: Double, y: Double)
    val data = Vector(Obs(0.0, 0.0), Obs(0.0, 1.0), Obs(1.0, 0.0), Obs(1.0, 1.0))

    def plot(scaled: Boolean): Plot[Obs] =
      val base =
        if scaled then
          val xScale = ContinuousScale
            .train("x", data.map(_.x), Palette.numeric)
            .fold(e => fail(e.message), identity)
          val yScale = ContinuousScale
            .train("y", data.map(_.y), Palette.numeric)
            .fold(e => fail(e.message), identity)
          Plot(data)
            .withScale(ScaleBinding[Obs, Double, Double](Aesthetic.X, _.x, xScale))
            .flatMap(_.withScale(ScaleBinding[Obs, Double, Double](Aesthetic.Y, _.y, yScale)))
            .fold(e => fail(e.message), identity)
        else Plot(data)
      base.addLayer(Layer.point[Obs](_.x, _.y)).fold(e => fail(e.message), identity)

    def panel(
        scaled: Boolean,
        expansion: RangeExpansion
    ): (DeviceClip, Vector[DevicePrimitive.Disc]) =
      val trained = PlotCompiler
        .resolve(
          plot(scaled),
          PlotCompilerOptions(
            policy = Some(policy),
            expansion = expansion,
            guides = GuidePolicy.Derived()
          )
        )
        .fold(e => fail(e.message), identity)
      val scene = DeviceScene
        .fromScene(trained.scene, policy.referenceDevice)
        .fold(e => fail(e.message), identity)
      scene.elements
        .collectFirst {
          case DeviceElement.Group(name, Some(clip), _, children)
              if name.exists(_.value == "plot-panel") =>
            val discs = children.collect { case DeviceElement.Mark(disc: DevicePrimitive.Disc) =>
              disc
            }
            (clip, discs)
        }
        .getOrElse(fail(s"missing clipped panel for scaled=$scaled"))

    Vector(false, true).foreach { scaled =>
      val (clip, discs) = panel(scaled, RangeExpansion.default)
      assertEquals(discs.length, 4)
      discs.foreach { disc =>
        assert(disc.centerX - disc.radius >= clip.x - tol)
        assert(disc.centerX + disc.radius <= clip.x + clip.width + tol)
        assert(disc.centerY - disc.radius >= clip.y - tol)
        assert(disc.centerY + disc.radius <= clip.y + clip.height + tol)
      }
    }

    val (exactClip, exactDiscs) = panel(scaled = false, RangeExpansion.none)
    assert(
      exactDiscs.exists(disc => disc.centerX - disc.radius < exactClip.x),
      "the explicit no-expansion policy must retain exact edge-centered framing"
    )
  }
