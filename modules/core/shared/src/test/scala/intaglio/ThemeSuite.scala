package intaglio

class ThemeSuite extends munit.FunSuite:

  private final case class Observation(x: Double, y: Double, condition: String)

  private val rows =
    Vector(
      Observation(0.0, 1.0, "A"),
      Observation(1.0, 2.0, "B"),
      Observation(2.0, 3.0, "A")
    )

  private val red = Rgba.unsafe(180, 35, 45)
  private val blue = Rgba.unsafe(35, 80, 180)
  private val green = Rgba.unsafe(35, 135, 80)

  private def text(sizePt: Double, color: Rgba, family: Option[String] = None): GraphicParams =
    GraphicParams.unsafe(
      stroke = None,
      fill = Some(color),
      fontFamily = family,
      fontSize = Length.pointsUnsafe(sizePt)
    )

  private val themed =
    Theme.minimal.copy(
      geom = GraphicParams.unsafe(stroke = Some(red), fill = Some(red), lineWidth = 2.0),
      pointSizePt = 7.0,
      axis = AxisTheme(
        line = GraphicParams.unsafe(stroke = Some(red), lineWidth = 1.5),
        tick = GraphicParams.unsafe(stroke = Some(blue), lineWidth = 0.75),
        text = text(9.0, blue),
        title = text(13.0, red)
      ),
      legend = LegendTheme(text = text(8.0, green), title = text(12.0, red)),
      plotText = PlotTextTheme(title = text(20.0, red), subtitle = text(14.0, blue))
    )

  test("theme typography is the layout typography") {
    val withFamilies = themed.copy(
      axis = themed.axis.copy(
        text = text(9.0, blue, Some("Axis Sans")),
        title = text(13.0, red, Some("Axis Title"))
      ),
      legend = LegendTheme(
        text(8.0, green, Some("Legend Sans")),
        text(12.0, red, Some("Legend Title"))
      ),
      plotText = PlotTextTheme(
        text(20.0, red, Some("Display")),
        text(14.0, blue, Some("Subtitle"))
      )
    )
    val policy = withFamilies.layoutPolicy

    assertEqualsDouble(policy.axisFontPt, 9.0, 1e-12)
    assertEquals(policy.axisFontFamily, Some("Axis Sans"))
    assertEqualsDouble(policy.axisTitleFontPt, 13.0, 1e-12)
    assertEquals(policy.axisTitleFontFamily, Some("Axis Title"))
    assertEqualsDouble(policy.legendFontPt, 8.0, 1e-12)
    assertEquals(policy.legendFontFamily, Some("Legend Sans"))
    assertEqualsDouble(policy.legendTitleFontPt, 12.0, 1e-12)
    assertEquals(policy.legendTitleFontFamily, Some("Legend Title"))
    assertEqualsDouble(policy.plotTitleFontPt, 20.0, 1e-12)
    assertEquals(policy.plotTitleFontFamily, Some("Display"))
    assertEqualsDouble(policy.plotSubtitleFontPt, 14.0, 1e-12)
    assertEquals(policy.plotSubtitleFontFamily, Some("Subtitle"))
  }

  test("one theme reaches marks, panel, axes, legends, and plot labels") {
    val colorScale =
      DiscreteDomain
        .ordered(Vector("A", "B"))
        .flatMap(domain => DiscreteScale("condition", domain, themed.palettes.discretePalette))
        .toOption
        .get
    val plot =
      Plot(rows)
        .withScale(ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, colorScale))
        .flatMap(_.addLayer(Layer.point[Observation](_.x, _.y)))
        .map(_.withTitle("Signal").withSubtitle("By condition").withAxisTitles("Time", "Value"))
        .toOption
        .get

    val trained =
      PlotCompiler
        .resolve(plot, PlotCompilerOptions(guides = GuidePolicy.Derived(), theme = themed))
        .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.head.rows.head.gp.fill, themed.geom.fill)
    assertEqualsDouble(trained.layers.head.rows.head.gp.lineWidth, themed.geom.lineWidth, 1e-12)
    assertEquals(trained.layers.head.rows.head.gp.stroke, Some(themed.palettes.discrete.head))
    assertEquals(trained.layers.head.rows.head.size, ExtentExpr.pointsUnsafe(7.0))
    assertEquals(
      trained.panelGrobs.flatMap(_.name.map(_.value)),
      Vector("plot-panel-background", "plot-panel-grid-x", "plot-panel-grid-y")
    )

    val bottom = trained.guides.collectFirst {
      case ResolvedGuide(axis: GuideSpec.Axis, grob: Grob.Group) if axis.side == AxisSide.Bottom => grob
    }.getOrElse(fail("missing bottom axis"))
    assertEquals(bottom.children.head.asInstanceOf[Grob.Segments].gp, themed.axis.line)
    assertEquals(bottom.children(1).asInstanceOf[Grob.Segments].gp, themed.axis.tick)
    assertEquals(bottom.children.collectFirst { case text: Grob.Text => text.gp }, Some(themed.axis.text))
    assertEquals(bottom.children.last.asInstanceOf[Grob.Text].gp, themed.axis.title)

    val legend = trained.guides.collectFirst {
      case ResolvedGuide(_: GuideSpec.Legend, grob: Grob.Group) => grob
    }.getOrElse(fail("missing legend"))
    assertEquals(legend.children.head.asInstanceOf[Grob.Text].gp, themed.legend.title)
    assertEquals(legend.children(2).asInstanceOf[Grob.Text].gp, themed.legend.text)
    assertEquals(trained.labelGrobs.head.asInstanceOf[Grob.Text].gp, themed.plotText.title)
    assertEquals(trained.labelGrobs(1).asInstanceOf[Grob.Text].gp, themed.plotText.subtitle)
  }

  test("explicit layer and guide styles override theme defaults") {
    val explicit = GraphicParams.unsafe(stroke = Some(green), fontSize = Length.pointsUnsafe(6.0))
    val plot =
      Plot(rows)
        .addLayer(Layer.point[Observation](_.x, _.y, params = Some(explicit)))
        .toOption
        .get
    val axis = GuideSpec.Axis(AxisSide.Bottom, labelGp = Some(explicit))
    val trained =
      PlotCompiler
        .resolve(
          plot,
          PlotCompilerOptions(
            policy = Some(LayoutPolicy()),
            guides = GuidePolicy.Explicit(Vector(axis)),
            theme = themed
          )
        )
        .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.head.rows.head.gp, explicit)
    val axisLabels = trained.guides.head.grob.asInstanceOf[Grob.Group].children.collect {
      case label: Grob.Text if label.name.exists(_.value.endsWith("-label")) => label
    }
    assert(axisLabels.nonEmpty)
    assert(axisLabels.forall(_.gp == explicit))
  }

  test("theme palettes are immutable scale inputs") {
    val discrete = themed.palettes.discretePalette
    val continuous = themed.palettes.continuousPalette

    assertEquals(discrete(0, 2), themed.palettes.discrete.head)
    assertEquals(continuous(0.0), themed.palettes.continuousLow)
    assertEquals(continuous(1.0), themed.palettes.continuousHigh)
  }
