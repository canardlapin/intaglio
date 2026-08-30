package intaglio

class CompilerPhasesSuite extends munit.FunSuite:
  private final case class Obs(x: Double, y: Double, condition: String)

  private val data =
    Vector(
      Obs(0.0, 1.0, "A"),
      Obs(1.0, 2.0, "A"),
      Obs(2.0, 3.0, "B"),
      Obs(3.0, 4.0, "B"),
      Obs(4.0, 5.0, "A")
    )

  private def colorScale: DiscreteScale[String, Rgba] =
    DiscreteScale(
      "condition-color",
      DiscreteDomain.ordered(Vector("A", "B")).fold(e => fail(e.message), identity),
      DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(10, 20, 30), Rgba.unsafe(200, 100, 50)))
    ).fold(e => fail(e.message), identity)

  private def collapsedColorScale(name: String): DiscreteScale[String, Rgba] =
    DiscreteScale(
      name,
      DiscreteDomain.empty,
      DiscretePalette.valuesUnsafe(Vector.fill(4)(Rgba.unsafe(90, 100, 110)))
    ).fold(e => fail(e.message), identity)

  private def groupedLinePlot: Plot[Obs] =
    Plot(data)
      .withScale(ScaleBinding[Obs, String, Rgba](Aesthetic.Color, _.condition, colorScale))
      .flatMap { plot =>
        plot.withMapping(plot.mapping.withGroup(_.condition))
      }
      .flatMap(_.addLayer(Layer.line[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

  private def autoGroupedLinePlot: Plot[Obs] =
    Plot(data)
      .withScale(
        ScaleBinding[Obs, String, Rgba](
          Aesthetic.Color,
          _.condition,
          collapsedColorScale("collapsed-condition-color")
        )
      )
      .flatMap(_.addLayer(Layer.line[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)

  private val compilerPattern =
    PatternPaint(
      PatternRecipe.angledHatch(37.0, 7.0, 1.25).fold(error => fail(error.message), identity),
      Rgba.unsafe(44, 55, 66, 0.8),
      Some(Rgba.unsafe(210, 220, 230, 0.4))
    )

  private val nonDefaultGraphicParams =
    GraphicParams
      .unsafe(
        stroke = Some(Rgba.unsafe(11, 22, 33)),
        fill = None,
        lineWidth = 2.75,
        lineType = LineType.Dashed,
        lineCap = LineCap.Round,
        lineJoin = LineJoin.Bevel,
        alpha = 0.65,
        fontFamily = Some("CompilerPhasesSuite"),
        fontSize = Length.pointsUnsafe(17.0)
      )
      .withPatternFill(compilerPattern)

  private def resolvePointLayer(mapping: AesSpec[Obs]): TrainedLayer =
    val plot =
      Plot(data.take(1))
        .addLayer(
          Layer.point[Obs](
            _.x,
            _.y,
            mapping = mapping,
            inheritMapping = false,
            params = Some(nonDefaultGraphicParams)
          )
        )
        .fold(error => fail(error.message), identity)
    PlotCompiler.resolve(plot).fold(error => fail(error.message), identity).layers.head

  test("mapping phase produces per-layer plans with one canonical effective mapping") {
    val plot = groupedLinePlot
    val plans = MappingPhase.plan(plot).fold(e => fail(e.message), identity)

    assertEquals(plans.length, 1)
    val plan = plans.head
    assertEquals(plan.layerIndex, 0)
    assertEquals(plan.data.length, data.length)
    assert(plan.mapping.isBound(Aesthetic.X))
    assert(plan.mapping.isBound(Aesthetic.Group))
    assert(plan.mapping.get(Aesthetic.Color).exists(_.isScaled))
  }

  test("mapping phase rejects invalid stat-geom combinations and incomplete geom mappings") {
    val plot = Plot(data)
    val statLayer = Layer
      .fromMapping(
        Geom.Point,
        AesSpec.empty[Obs].withPosition(_.x, _.y),
        stat = Stat.Count(_.condition)
      )
      .fold(e => fail(e.message), identity)
    assertEquals(
      MappingPhase.planLayer(plot, statLayer, 0).left.toOption,
      Some(GraphicsError.InvalidStatGeom("count", "point"))
    )

    val rectLayer = Layer
      .fromMapping(
        Geom.Rect,
        AesSpec.empty[Obs].withPosition(_.x, _.y)
      )
      .fold(e => fail(e.message), identity)
    assertEquals(
      MappingPhase.planLayer(plot, rectLayer, 0).left.toOption,
      Some(GraphicsError.MissingAesthetic("rect", "xmin"))
    )
  }

  test("scale phase trains one declaration from distinct layer extractors") {
    val first = Vector(Obs(0.0, 10.0, "A"))
    val second = Vector(Obs(100.0, 200.0, "B"))
    var trainingCalls = 0
    val scale = new ScaleSpec[Double, Double]:
      override val name: GraphicsName = GraphicsName.unsafe("shared-x")
      override val kind: ScaleKind = ScaleKind.Continuous

      private[intaglio] override def observation(value: Double): Option[ScaleObservation] =
        Some(ScaleObservation.Continuous(value))

      private[intaglio] override def trainSpec(
          observations: Vector[ScaleObservation],
          theme: Theme,
          facetLocal: Boolean
      ): Either[GraphicsError, Scale[Double, Double]] =
        trainingCalls += 1
        assertEquals(theme, Theme.default)
        assertEquals(facetLocal, false)
        ContinuousScale.train(
          name.value,
          observations.collect { case ScaleObservation.Continuous(value) => value },
          Palette.numeric
        )

    def layer(rows: Vector[Obs], x: Obs => Double): Layer[Obs] =
      val mapping =
        AesSpec
          .empty[Obs]
          .withPosition(x, _.y)
          .bindScale(ScaleBinding[Obs, Double, Double](Aesthetic.X, x, scale))
          .fold(error => fail(error.message), identity)
      Layer
        .fromMapping(Geom.Point, mapping, data = Some(rows), inheritMapping = false)
        .fold(error => fail(error.message), identity)

    val plot =
      Plot(Vector.empty[Obs])
        .addLayer(layer(first, _.x))
        .flatMap(_.addLayer(layer(second, _.y)))
        .fold(error => fail(error.message), identity)
    val plans = MappingPhase.plan(plot).fold(error => fail(error.message), identity)
    val statPlans = StatPhase.transform(plans).fold(error => fail(error.message), identity)
    val scales = ScalePhase.train(statPlans).fold(error => fail(error.message), identity)

    assertEquals(trainingCalls, 1)
    assertEquals(scales.registry.scales.length, 1)
    assertEquals(
      scales.registry.scales.head.descriptor.domain,
      ScaleDomain.Continuous(Interval.unsafe(0.0, 200.0), Interval.unsafe(0.0, 200.0))
    )
    val installed = scales.plans.map { plan =>
      ScalePhase
        .registry(plan)
        .forAesthetic(Aesthetic.X)
        .fold(fail("trained x scale was not rebound to a layer"))(_.trained.scale)
    }
    assert(installed.head.asInstanceOf[AnyRef] eq installed(1).asInstanceOf[AnyRef])
    assert(
      installed.head.asInstanceOf[AnyRef] eq scales.registry.scales.head.scale.asInstanceOf[AnyRef]
    )
    val resolved = scales.plans.map { plan =>
      RowPhase.resolve(plan.value).fold(error => fail(error.message), identity)._1.head.x
    }
    assertEqualsDouble(resolved(0), 0.0, 1e-12)
    assertEqualsDouble(resolved(1), 1.0, 1e-12)
  }

  test("row phase records the evaluated group value on each row") {
    val plot = groupedLinePlot
    val mapped = MappingPhase.plan(plot).fold(e => fail(e.message), identity).head
    val plan = StatPhase.transform(mapped.value).fold(e => fail(e.message), identity)
    val (rows, dropped) = RowPhase.resolve(plan.value).fold(e => fail(e.message), identity)

    assertEquals(dropped, Vector.empty)
    assertEquals(rows.map(_.group), Vector(Some("A"), Some("A"), Some("B"), Some("B"), Some("A")))
    assertEquals(rows.map(_.grouping).distinct, Vector(GroupingDecision.Explicit))
    assertEquals(
      rows.map(_.groupKey).distinct,
      Vector(Some(GroupKey.Explicit("A")), Some(GroupKey.Explicit("B")))
    )
  }

  test("discrete style categories infer structural pre-palette group keys") {
    val trained = PlotCompiler.resolve(autoGroupedLinePlot).fold(e => fail(e.message), identity)
    val layer = trained.layers.head

    assertEquals(layer.grouping, GroupingDecision.Inferred(Vector(Aesthetic.Color)))
    assertEquals(layer.rows.map(_.grouping).distinct, Vector(layer.grouping))
    assertEquals(
      layer.rows.map(_.groupKey),
      Vector("A", "A", "B", "B", "A").map(category =>
        Some(GroupKey.Inferred(Vector(DiscreteGroupValue(Aesthetic.Color, category))))
      )
    )
    assertEquals(layer.rows.map(_.gp.stroke).distinct, Vector(Some(Rgba.unsafe(90, 100, 110))))
    assertEquals(layer.grobs.length, 2)
  }

  test("multiple discrete style mappings form a composite raw category key") {
    val fillScale = collapsedColorScale("collapsed-phase-fill")
    val trained = Plot(data.take(4))
      .withScale(
        ScaleBinding[Obs, String, Rgba](
          Aesthetic.Color,
          _.condition,
          collapsedColorScale("collapsed-composite-color")
        )
      )
      .flatMap(
        _.withScale(
          ScaleBinding[Obs, String, Rgba](
            Aesthetic.Fill,
            row => if row.x.toInt % 2 == 0 then "even" else "odd",
            fillScale
          )
        )
      )
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(e => fail(e.message), identity)
    val layer = trained.layers.head

    assertEquals(
      layer.grouping,
      GroupingDecision.Inferred(Vector(Aesthetic.Color, Aesthetic.Fill))
    )
    assertEquals(
      layer.rows.map(_.groupKey),
      Vector(
        Some(
          GroupKey.Inferred(
            Vector(
              DiscreteGroupValue(Aesthetic.Color, "A"),
              DiscreteGroupValue(Aesthetic.Fill, "even")
            )
          )
        ),
        Some(
          GroupKey.Inferred(
            Vector(
              DiscreteGroupValue(Aesthetic.Color, "A"),
              DiscreteGroupValue(Aesthetic.Fill, "odd")
            )
          )
        ),
        Some(
          GroupKey.Inferred(
            Vector(
              DiscreteGroupValue(Aesthetic.Color, "B"),
              DiscreteGroupValue(Aesthetic.Fill, "even")
            )
          )
        ),
        Some(
          GroupKey.Inferred(
            Vector(
              DiscreteGroupValue(Aesthetic.Color, "B"),
              DiscreteGroupValue(Aesthetic.Fill, "odd")
            )
          )
        )
      )
    )
  }

  test("an explicit group mapping overrides discrete style inference") {
    val plot = Plot(data)
      .withScale(
        ScaleBinding[Obs, String, Rgba](
          Aesthetic.Color,
          _.condition,
          collapsedColorScale("collapsed-explicit-color")
        )
      )
      .flatMap(current => current.withMapping(current.mapping.withGroup(_ => "all")))
      .flatMap(_.addLayer(Layer.line[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)
    val layer = PlotCompiler.resolve(plot).fold(e => fail(e.message), identity).layers.head

    assertEquals(layer.grouping, GroupingDecision.Explicit)
    assertEquals(layer.rows.map(_.groupKey).distinct, Vector(Some(GroupKey.Explicit("all"))))
    assertEquals(layer.grobs.length, 1)
  }

  test("discrete scale descriptors without raw categories fail grouping closed") {
    val incompleteScale = new Scale[String, Rgba]:
      val name: GraphicsName = GraphicsName.unsafe("incomplete-discrete")
      override val descriptor: ScaleDescriptor =
        ScaleDescriptor(name, ScaleKind.Discrete, ScaleDomain.Unspecified)
      def mapValue(value: String): Option[Rgba] =
        Some(Rgba.unsafe(90, 100, 110))

    val layer = Plot(data.take(1))
      .withScale(
        ScaleBinding[Obs, String, Rgba](Aesthetic.Color, _.condition, incompleteScale)
      )
      .flatMap(_.addLayer(Layer.point[Obs](_.x, _.y)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(e => fail(e.message), identity)
      .layers
      .head

    assertEquals(layer.grouping, GroupingDecision.Inferred(Vector(Aesthetic.Color)))
    assertEquals(layer.rows, Vector.empty)
    assertEquals(
      layer.droppedRows.map(_.reason),
      Vector(PlotDropReason.GroupingCategoryUnavailable("color"))
    )
  }

  test("row style resolution preserves the complete base GraphicParams value") {
    val layer = resolvePointLayer(AesSpec.empty[Obs])

    assertEquals(layer.droppedRows, Vector.empty)
    assertEquals(layer.rows.map(_.gp), Vector(nonDefaultGraphicParams))
  }

  test("row style resolution changes only explicitly mapped GraphicParams channels") {
    val mappedStroke = Rgba.unsafe(101, 102, 103)
    val mappedFill = Rgba.unsafe(201, 202, 203)

    val colorOnly = resolvePointLayer(AesSpec.empty[Obs].withColor(mappedStroke)).rows.head.gp
    assertEquals(colorOnly.stroke, Some(mappedStroke))
    assertEquals(colorOnly.fill, nonDefaultGraphicParams.fill)
    assertEquals(colorOnly.fillPattern, nonDefaultGraphicParams.fillPattern)
    assertEqualsDouble(colorOnly.alpha, nonDefaultGraphicParams.alpha, 1e-12)

    val fillOnly = resolvePointLayer(AesSpec.empty[Obs].withFill(mappedFill)).rows.head.gp
    assertEquals(fillOnly.stroke, nonDefaultGraphicParams.stroke)
    assertEquals(fillOnly.fill, Some(mappedFill))
    assertEquals(fillOnly.fillPattern, None)
    assertEqualsDouble(fillOnly.alpha, nonDefaultGraphicParams.alpha, 1e-12)

    val alphaOnly = resolvePointLayer(AesSpec.empty[Obs].withAlpha(0.25)).rows.head.gp
    assertEquals(alphaOnly.stroke, nonDefaultGraphicParams.stroke)
    assertEquals(alphaOnly.fill, nonDefaultGraphicParams.fill)
    assertEquals(alphaOnly.fillPattern, nonDefaultGraphicParams.fillPattern)
    assertEqualsDouble(alphaOnly.alpha, 0.25, 1e-12)

    val expectedUnmappedFields = (
      nonDefaultGraphicParams.lineWidth,
      nonDefaultGraphicParams.lineType,
      nonDefaultGraphicParams.lineCap,
      nonDefaultGraphicParams.lineJoin,
      nonDefaultGraphicParams.fontFamily,
      nonDefaultGraphicParams.fontSize
    )
    Vector(colorOnly, fillOnly, alphaOnly).foreach { params =>
      assertEquals(
        (
          params.lineWidth,
          params.lineType,
          params.lineCap,
          params.lineJoin,
          params.fontFamily,
          params.fontSize
        ),
        expectedUnmappedFields
      )
    }
  }

  test("invalid mapped alpha remains a typed compiler drop") {
    val layer = resolvePointLayer(AesSpec.empty[Obs].withAlpha(Double.NaN))

    assertEquals(layer.rows, Vector.empty)
    assertEquals(layer.droppedRows.length, 1)
    layer.droppedRows.head.reason match
      case PlotDropReason.InvalidAesthetic("gp", message) =>
        assertEquals(message, GraphicsError.InvalidAlpha(Double.NaN).message)
      case other =>
        fail(s"unexpected drop reason: $other")
  }

  test("geom lowering emits one line grob per group with that group's params") {
    val plot = groupedLinePlot
    val trained = PlotCompiler.resolve(plot).fold(e => fail(e.message), identity)
    val grobs = trained.layers.head.grobs

    assertEquals(grobs.length, 2)
    val first = grobs(0).asInstanceOf[Grob.Lines]
    val second = grobs(1).asInstanceOf[Grob.Lines]
    assertEquals(first.points.length, 3)
    assertEquals(second.points.length, 2)
    assertEquals(first.gp.stroke, Some(Rgba.unsafe(10, 20, 30)))
    assertEquals(second.gp.stroke, Some(Rgba.unsafe(200, 100, 50)))
  }

  test("group-constant styles are rejected before grouped lowering") {
    val varyingColor = AesSpec
      .empty[Obs]
      .withGroup("all")
      .withColor(row => if row.condition == "A" then Rgba.Black else Rgba.White)
    val varyingFill = AesSpec
      .empty[Obs]
      .withGroup("all")
      .withFill(row => if row.condition == "A" then Rgba.Black else Rgba.White)
    val cases = Vector(
      Layer.line[Obs](_.x, _.y, mapping = varyingColor) -> "color",
      Layer.ribbon[Obs](_.x, row => row.y - 0.5, row => row.y + 0.5, mapping = varyingFill) ->
        "fill",
      Layer.area[Obs](_.x, _.y, mapping = varyingFill) -> "fill",
      Layer.polygon[Obs](_.x, _.y, mapping = varyingFill) -> "fill"
    )

    cases.foreach { case (layer, aesthetic) =>
      val error = Plot(data)
        .addLayer(layer)
        .flatMap(PlotCompiler.resolve(_))
        .left
        .toOption
      assertEquals(
        error,
        Some(GraphicsError.VaryingGroupAesthetic(layer.geom.label, aesthetic, "all", 0, 2))
      )
    }
  }

  test("single-row groups draw nothing, matching whole-layer semantics") {
    val sparse = Vector(Obs(0.0, 0.0, "A"), Obs(1.0, 1.0, "B"), Obs(2.0, 2.0, "B"))
    val plot = Plot(sparse)
      .withMapping(AesSpec.empty[Obs].withGroup(_.condition))
      .flatMap(_.addLayer(Layer.line[Obs](_.x, _.y)))
      .fold(e => fail(e.message), identity)
    val trained = PlotCompiler.resolve(plot).fold(e => fail(e.message), identity)

    assertEquals(trained.layers.head.grobs.length, 1)
    assertEquals(trained.layers.head.grobs.head.asInstanceOf[Grob.Lines].points.length, 2)
  }

  test("ungrouped line layers still lower to a single polyline") {
    val plot = Plot(data)
      .addLayer(Layer.line[Obs](_.x, _.y))
      .fold(e => fail(e.message), identity)
    val trained = PlotCompiler.resolve(plot).fold(e => fail(e.message), identity)

    assertEquals(trained.layers.head.grobs.length, 1)
    assertEquals(trained.layers.head.grobs.head.asInstanceOf[Grob.Lines].points.length, data.length)
  }

  test("guide phase still requires a layout before lowering guides") {
    assertEquals(
      GuidePhase.lower(None, None, Vector(GuideSpec.Axis(AxisSide.Bottom))).left.toOption,
      Some(GraphicsError.MissingLayout("guides"))
    )
    assertEquals(GuidePhase.lower(None, None, Vector.empty).toOption, Some(Vector.empty))
  }
