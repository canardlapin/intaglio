package intaglio

class GuideDerivationSuite extends munit.FunSuite:
  private final case class Obs(x: Double, y: Double, condition: String)

  private val data =
    Vector(
      Obs(0.0, 1.0, "A"),
      Obs(1.0, 2.0, "B"),
      Obs(2.0, 3.0, "A")
    )

  private val frame =
    PanelFrame.npcUnsafe(0.1, 0.1, 0.8, 0.8)

  private def colorScale: DiscreteScale[Rgba] =
    DiscreteScale(
      "condition-color",
      DiscreteDomain.ordered(Vector("A", "B")).fold(e => fail(e.message), identity),
      DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(40, 80, 120), Rgba.unsafe(210, 120, 40)))
    ).fold(e => fail(e.message), identity)

  private def directPlot: Plot[Obs] =
    Plot(data)
      .addLayer(Layer.point[Obs](_.x, _.y))
      .fold(e => fail(e.message), identity)

  private def coloredPlot: Plot[Obs] =
    Plot(data)
      .withScale(ScaleBinding[Obs, String, Rgba](Aesthetic.Color, _.condition, colorScale))
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

  test("frame-based layouts derive panel ranges from resolved rows") {
    val trained = PlotCompiler
      .resolve(directPlot, PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)
    val layout = trained.layout.getOrElse(fail("expected a derived layout"))

    assertEquals(layout.frame, frame)
    assertEquals(layout.xScale, Interval.unsafe(-0.1, 2.1))
    assertEquals(layout.yScale, Interval.unsafe(0.9, 3.1))
  }

  test("derived guides produce x/y axes and a legend from the discrete color scale") {
    val trained = PlotCompiler
      .resolve(
        coloredPlot,
        PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived())
      )
      .fold(e => fail(e.message), identity)

    val names = trained.guides.flatMap(_.spec.name).map(_.value)
    assertEquals(names, Vector("x-axis", "y-axis", "condition-color-legend"))

    val axisTitles = trained.guides.collect { case ResolvedGuide(axis: GuideSpec.Axis, _) =>
      axis.title
    }
    assertEquals(axisTitles, Vector(Some("x"), Some("y")))

    val legend = trained.guides
      .collectFirst { case ResolvedGuide(spec: GuideSpec.Legend, _) =>
        spec
      }
      .getOrElse(fail("expected a derived legend"))
    assertEquals(legend.title, Some("condition-color"))
    assertEquals(legend.entries.map(_.label), Vector("A", "B"))
    assertEquals(legend.entries.head.gp.fill, Some(Rgba.unsafe(40, 80, 120)))
  }

  test("continuous color scales derive transform-aware colorbars") {
    val logData = Vector(Obs(0.0, 1.0, "A"), Obs(1.0, 10.0, "B"), Obs(2.0, 100.0, "A"))
    val activation = ContinuousScale
      .train(
        "activation",
        logData.map(_.y),
        Palette.gradient(Rgba.unsafe(20, 30, 80), Rgba.unsafe(240, 210, 40)),
        transform = Transform.log10
      )
      .fold(e => fail(e.message), identity)
    val plot = Plot(logData)
      .withScale(ScaleBinding[Obs, Double, Rgba](Aesthetic.Color, _.y, activation))
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)
    val colorbar = trained.guides
      .collectFirst { case ResolvedGuide(spec: GuideSpec.Colorbar, _) =>
        spec
      }
      .getOrElse(fail("expected a derived colorbar"))

    assertEquals(colorbar.name.map(_.value), Some("activation-colorbar"))
    assertEquals(colorbar.title, Some("activation"))
    assertEquals(colorbar.colors.length, 32)
    assertEquals(colorbar.ticks.map(_.label), Vector("1", "10", "100"))
    assertEquals(colorbar.ticks.map(_.value), Vector(0.0, 0.5, 1.0))
    assert(!trained.guides.exists(_.spec.isInstanceOf[GuideSpec.Legend]))
  }

  test("scaled positions derive unit panel ranges and transform-aware ticks") {
    val xScale = ContinuousScale
      .train("x-log", Vector(1.0, 10.0, 100.0), Palette.numeric, transform = Transform.log10)
      .fold(e => fail(e.message), identity)
    val logData = Vector(Obs(1.0, 1.0, "A"), Obs(10.0, 2.0, "B"), Obs(100.0, 3.0, "A"))
    val plot = Plot(logData)
      .withScale(ScaleBinding[Obs, Double, Double](Aesthetic.X, _.x, xScale))
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)
    val layout = trained.layout.getOrElse(fail("expected a derived layout"))
    assertEquals(layout.xScale, Interval.unsafe(-0.05, 1.05))

    val axis = trained.guides
      .collectFirst {
        case ResolvedGuide(spec: GuideSpec.Axis, _) if spec.side == AxisSide.Bottom => spec
      }
      .getOrElse(fail("expected a derived bottom axis"))
    val ticks = axis.ticks.getOrElse(fail("expected transform-derived ticks"))
    assertEquals(ticks.map(_.label), Vector("1", "10", "100"))
    assertEqualsDouble(ticks(0).value, 0.0, 1e-12)
    assertEqualsDouble(ticks(1).value, 0.5, 1e-12)
    assertEqualsDouble(ticks(2).value, 1.0, 1e-12)
    assertEquals(axis.title, Some("x-log"))
  }

  test("band scales are ordinary typed position scales on the y aesthetic") {
    val padding = BandPadding.unsafe(0.2)
    val scale =
      BandScale("condition", DiscreteDomain.empty, padding).fold(e => fail(e.message), identity)
    val mapping = AesSpec
      .empty[Obs]
      .withPosition(_.x, _ => 0.0)
      .bindScale(ScaleBinding[Obs, String, Double](Aesthetic.Y, _.condition, scale))
      .fold(e => fail(e.message), identity)
    val layer = Layer
      .fromMapping(Geom.Point, mapping, inheritMapping = false)
      .fold(e => fail(e.message), identity)
    val plot = Plot(data).addLayer(layer).fold(e => fail(e.message), identity)
    val trained = PlotCompiler
      .resolve(
        plot,
        PlotCompilerOptions(
          frame = Some(frame),
          expansion = RangeExpansion.none,
          guides = GuidePolicy.Derived()
        )
      )
      .fold(e => fail(e.message), identity)
    val axis = trained.guides
      .collectFirst {
        case ResolvedGuide(spec: GuideSpec.Axis, _) if spec.side == AxisSide.Left => spec
      }
      .getOrElse(fail("expected a derived left band axis"))

    assertEquals(trained.layers.head.rows.map(_.y), Vector(0.0, 1.0, 0.0))
    assertEquals(trained.layers.head.rows.flatMap(_.yBand).map(_.width), Vector.fill(3)(0.8))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(-0.4, 1.4)))
    assertEquals(axis.ticks.toVector.flatten.map(_.label), Vector("A", "B"))
    assertEquals(axis.ticks.toVector.flatten.map(_.value), Vector(0.0, 1.0))
    assertEquals(axis.title, Some("condition"))
  }

  test("plot axis labels override derived scale names") {
    val plot = coloredPlot.withAxisTitles("Elapsed time", "Response")
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)

    val titles = trained.guides.collect { case ResolvedGuide(axis: GuideSpec.Axis, _) =>
      axis.title
    }
    assertEquals(titles, Vector(Some("Elapsed time"), Some("Response")))
  }

  test("explicit overrides suppress matching derived guides and are included") {
    val customAxis = GuideSpec.Axis(
      AxisSide.Bottom,
      breaks = Breaks.countUnsafe(2),
      name = Some(GraphicsName.unsafe("time-axis"))
    )
    val customLegend = GuideSpec.Legend(
      title = Some("condition"),
      entries = Vector(LegendEntry.colorUnsafe("A", Rgba.Black)),
      name = Some(GraphicsName.unsafe("custom-legend"))
    )
    val trained = PlotCompiler
      .resolve(
        coloredPlot,
        PlotCompilerOptions(
          frame = Some(frame),
          guides = GuidePolicy.Derived(overrides = Vector(customAxis, customLegend))
        )
      )
      .fold(e => fail(e.message), identity)

    val names = trained.guides.flatMap(_.spec.name).map(_.value)
    assertEquals(names, Vector("y-axis", "time-axis", "custom-legend"))
    val bottomAxes = trained.guides.map(_.spec).collect {
      case axis: GuideSpec.Axis if axis.side == AxisSide.Bottom => axis
    }
    assertEquals(bottomAxes.length, 1)
    assertEquals(bottomAxes.head.name.map(_.value), Some("time-axis"))
    val overrideTicks = bottomAxes.head.ticks.getOrElse(
      fail("override ticks must be materialized before panel expansion")
    )
    assertEquals(overrideTicks.map(_.value), Vector(0.0, 2.0))
    assertEquals(overrideTicks.map(_.label), Vector("0", "2"))
  }

  test("mixing scaled and unscaled position bindings across layers is a typed error") {
    val xScale = ContinuousScale
      .train("x-position", data.map(_.x), Palette.numeric)
      .fold(e => fail(e.message), identity)
    val scaledLayer = Layer
      .fromMapping(
        Geom.Point,
        AesSpec
          .empty[Obs]
          .withPosition(_.x, _.y)
          .bindScale(ScaleBinding[Obs, Double, Double](Aesthetic.X, _.x, xScale))
          .fold(e => fail(e.message), identity),
        inheritMapping = false
      )
      .fold(e => fail(e.message), identity)
    val plot = Plot(data)
      .addLayer(Layer.point[Obs](_.x, _.y))
      .flatMap(_.addLayer(scaledLayer))
      .fold(e => fail(e.message), identity)

    assertEquals(
      PlotCompiler
        .resolve(plot, PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived()))
        .left
        .toOption,
      Some(GraphicsError.MixedPositionScaling("x"))
    )
  }

  test("multiple derived legends stack instead of overprinting") {
    val fillScale = DiscreteScale(
      "condition-fill",
      DiscreteDomain.ordered(Vector("A", "B")).fold(e => fail(e.message), identity),
      DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(70, 145, 85), Rgba.unsafe(140, 90, 170)))
    ).fold(e => fail(e.message), identity)
    val plot = Plot(data)
      .withScale(ScaleBinding[Obs, String, Rgba](Aesthetic.Color, _.condition, colorScale))
      .flatMap(_.withScale(ScaleBinding[Obs, String, Rgba](Aesthetic.Fill, _.condition, fillScale)))
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

    val trained = PlotCompiler
      .resolve(
        plot,
        PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
      )
      .fold(e => fail(e.message), identity)
    val legends = trained.guides.map(_.spec).collect { case legend: GuideSpec.Legend => legend }

    assertEquals(
      legends.map(_.name.map(_.value)),
      Vector(Some("condition-color-legend"), Some("condition-fill-legend"))
    )
    val origins = legends.map(_.origin.y)
    assert(origins(0) != origins(1), "stacked legends must not share an origin")
  }

  test("solver stacks mixed legend and colorbar guides from measured point extents") {
    val legend = GuideSpec.Legend(
      title = Some("condition"),
      entries = Vector(LegendEntry.colorUnsafe("a long condition label", Rgba.Black)),
      name = Some(GraphicsName.unsafe("condition-legend"))
    )
    val colorbar = GuideSpec.Colorbar(
      title = Some("activation"),
      colors = Vector(Rgba.Black, Rgba.White),
      ticks = Vector(AxisTick.unsafe(0.0, "0"), AxisTick.unsafe(1.0, "100")),
      name = Some(GraphicsName.unsafe("activation-colorbar"))
    )
    val trained = PlotCompiler
      .resolve(
        directPlot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          guides = GuidePolicy.Explicit(Vector(legend, colorbar))
        )
      )
      .fold(e => fail(e.message), identity)
    val nonPosition = trained.guides.filterNot(_.spec.isInstanceOf[GuideSpec.Axis])
    val solvedLegend = nonPosition.head.spec.asInstanceOf[GuideSpec.Legend]
    val solvedColorbar = nonPosition(1).spec.asInstanceOf[GuideSpec.Colorbar]

    assert(solvedLegend.origin.y != solvedColorbar.origin.y)
    assertEquals(solvedColorbar.barHeight, ExtentExpr.pointsUnsafe(LayoutPolicy().colorbarHeightPt))
    nonPosition.foreach { guide =>
      assert(guide.grob.asInstanceOf[Grob.Group].viewport.nonEmpty)
    }
  }

  test("derived guides resolve the final theme layout when no layout is explicit") {
    val theme = Theme.minimal.copy(layout = Theme.minimal.layout.copy(outerMarginPt = 31.0))
    val options = PlotCompilerOptions(guides = GuidePolicy.Derived(), theme = theme)
    val trained =
      PlotCompiler.resolve(directPlot, options).fold(error => fail(error.message), identity)
    val effective = PlotCompiler.effectiveOptions(directPlot, options)

    assert(trained.layout.nonEmpty)
    assert(trained.guides.nonEmpty)
    assertEquals(effective.policy.map(_.outerMarginPt), Some(theme.layout.outerMarginPt))
  }

  test("explicit empty guides still compile without a layout") {
    val trained = PlotCompiler
      .resolve(directPlot, PlotCompilerOptions(guides = GuidePolicy.NoGuides))
      .fold(e => fail(e.message), identity)
    assertEquals(trained.guides, Vector.empty)
    assertEquals(trained.layout, None)
  }

  test("range expansion validates parameters and preserves translation laws") {
    assertEquals(
      RangeExpansion(-0.1).left.toOption,
      Some(GraphicsError.InvalidRangeExpansion(-0.1, 0.0, 1.0))
    )
    assert(RangeExpansion(0.1, additive = Double.NaN).isLeft)
    assert(RangeExpansion(0.1, zeroWidth = 0.0).isLeft)

    val expansion = RangeExpansion.unsafe(0.1, additive = 0.2)
    val base = expansion.expand(Interval.unsafe(2.0, 6.0)).fold(e => fail(e.message), identity)
    val translated =
      expansion.expand(Interval.unsafe(12.0, 16.0)).fold(e => fail(e.message), identity)
    assertEquals(base, Interval.unsafe(1.4, 6.6))
    assertEqualsDouble(translated.lower - base.lower, 10.0, 1e-12)
    assertEqualsDouble(translated.upper - base.upper, 10.0, 1e-12)
    assertEquals(
      RangeExpansion.none.expand(Interval.unsafe(2.0, 6.0)),
      Right(Interval.unsafe(2.0, 6.0))
    )
  }

  test("range expansion handles degenerate data and has an explicit opt-out") {
    val single = Vector(Obs(3.0, 4.0, "A"))
    val plot = Plot(single)
      .addLayer(Layer.point[Obs](_.x, _.y))
      .fold(e => fail(e.message), identity)
    val expanded = PlotCompiler
      .resolve(plot, PlotCompilerOptions(frame = Some(frame), guides = GuidePolicy.Derived()))
      .fold(e => fail(e.message), identity)
      .layout
      .getOrElse(fail("expected expanded layout"))
    val exact = PlotCompiler
      .resolve(
        plot,
        PlotCompilerOptions(
          frame = Some(frame),
          expansion = RangeExpansion.none,
          guides = GuidePolicy.Derived()
        )
      )
      .fold(e => fail(e.message), identity)
      .layout
      .getOrElse(fail("expected exact layout"))

    assertEquals(expanded.xScale, Interval.unsafe(2.95, 3.05))
    assertEquals(expanded.yScale, Interval.unsafe(3.95, 4.05))
    assertEquals(exact.xScale, Interval.unsafe(3.0, 3.0))
    assertEquals(exact.yScale, Interval.unsafe(4.0, 4.0))
  }

  test("explicit panel layouts remain authoritative under default expansion") {
    val explicit = PanelLayout(
      frame,
      xScale = Interval.unsafe(-1.0, 3.0),
      yScale = Interval.unsafe(0.0, 4.0)
    )
    val trained = PlotCompiler
      .resolve(
        directPlot,
        PlotCompilerOptions(layout = Some(explicit), guides = GuidePolicy.Derived())
      )
      .fold(e => fail(e.message), identity)

    assertEquals(trained.layout, Some(explicit))
  }
