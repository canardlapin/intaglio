package intaglio

class PlotCompilerSuite extends munit.FunSuite:

  private final case class Observation(time: Double, value: Double, condition: String)
  private final case class Marker(time: Double, value: Double, name: String)

  private val data =
    Vector(
      Observation(0.0, 1.0, "A"),
      Observation(1.0, 2.0, "A"),
      Observation(2.0, 3.0, "B")
    )

  test("independent row types share plot-wide scales and retain typed provenance") {
    val markers = Vector(Marker(100.0, 5.0, "kept"), Marker(200.0, Double.NaN, "dropped"))
    val scale =
      ContinuousScale
        .train("shared-x", data.map(_.time), Palette.numeric)
        .fold(error => fail(error.message), identity)
    val baseMapping = AesSpec
      .empty[Observation]
      .withPosition(_.time, _.value)
      .bindScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, scale))
      .fold(error => fail(error.message), identity)
    val markerMapping = AesSpec
      .empty[Marker]
      .withPosition(_.time, _.value)
      .bindScale(ScaleBinding[Marker, Double, Double](Aesthetic.X, _.time, scale))
      .fold(error => fail(error.message), identity)
    val baseLayer = Layer
      .fromMapping(Geom.Point, baseMapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val markerLayer = Layer
      .fromMapping(Geom.Point, markerMapping, inheritMapping = false)
      .fold(error => fail(error.message), identity)
    val plot = Plot(data)
      .addLayer(baseLayer)
      .flatMap(_.addIndependentLayer(markers, markerLayer, LayerFacetPolicy.Repeat))
      .fold(error => fail(error.message), identity)
    val trained = PlotCompiler.resolve(plot).fold(error => fail(error.message), identity)

    assertEquals(trained.layers.length, 2)
    assertEquals(
      trained.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor.domain),
      Some(ScaleDomain.Continuous(Interval.unsafe(0.0, 200.0), Interval.unsafe(0.0, 200.0)))
    )
    val resolvedMarkers = trained.layers(1)
    val typedSource: resolvedMarkers.Row = resolvedMarkers.rows.head.source
    assert(typedSource == markers.head)
    assert(resolvedMarkers.droppedRows.head.reason match
      case PlotDropReason.NonFinitePosition(x, y) => x == 1.0 && y.isNaN
      case _                                      => false)
    assert(resolvedMarkers.droppedRows.head.source == markers.last)
    assert(trained.droppedRows.head.source == markers.last)
  }

  test("compiles point layers through resolved scaled and direct aesthetics") {
    val xScale =
      ContinuousScale
        .train("x", data.map(_.time), Palette.numeric)
        .toOption
        .get
    val plot =
      Plot(data)
        .withScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, xScale))
        .flatMap(
          _.addLayer(
            Layer.point(
              _.time,
              _.value,
              mapping = AesSpec.empty[Observation].withFill(Rgba.White).withAlpha(0.5)
            )
          )
        )
        .toOption
        .get

    val trained = PlotCompiler.resolve(plot).toOption.get
    val layer = trained.layers.head
    val lastPoint = trained.scene.grobs.last.asInstanceOf[Grob.Points]

    assertEquals(layer.dataSize, 3)
    assertEquals(layer.rows.length, 3)
    assertEquals(layer.droppedRows, Vector.empty)
    assertEquals(trained.scene.size, 3)
    assertEquals(lastPoint.points, Vector(Point.nativeUnsafe(1.0, 3.0)))
    assertEquals(lastPoint.gp.fill, Some(Rgba.White))
    assertEqualsDouble(lastPoint.gp.alpha, 0.5, 1e-12)
  }

  test("resolved plots expose scale declarations separately from trained scale descriptors") {
    val domain = DiscreteDomain.ordered(Vector("A", "B")).toOption.get
    val colorScale =
      DiscreteScale(
        "condition-color",
        domain,
        DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.White))
      ).toOption.get
    val fillScale =
      DiscreteScale(
        "condition-fill",
        domain,
        DiscretePalette.valuesUnsafe(Vector(Rgba.White, Rgba.Black))
      ).toOption.get
    val xScale =
      ContinuousScale
        .train("x", data.map(_.time), Palette.numeric)
        .toOption
        .get
    val yScale =
      ContinuousScale
        .train("y", data.map(_.value), Palette.numeric)
        .toOption
        .get
    val sizeScale =
      ContinuousScale
        .train("size", data.map(_.value), Palette.numeric)
        .toOption
        .get
    val plot =
      Plot(data)
        .withScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, xScale))
        .flatMap(
          _.withScale(ScaleBinding[Observation, Double, Double](Aesthetic.Y, _.value, yScale))
        )
        .flatMap(
          _.withScale(
            ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, colorScale)
          )
        )
        .flatMap(
          _.withScale(
            ScaleBinding[Observation, String, Rgba](Aesthetic.Fill, _.condition, fillScale)
          )
        )
        .flatMap(
          _.withScale(ScaleBinding[Observation, Double, Double](Aesthetic.Size, _.value, sizeScale))
        )
        .flatMap(_.addLayer(Layer.point[Observation](_.time, _.value)))
        .toOption
        .get

    val trained = PlotCompiler.resolve(plot).toOption.get

    assertEquals(
      trained.scaleDeclarations.map(_.aesthetic),
      Vector("x", "y", "color", "fill", "size")
    )
    assertEquals(
      trained.scaleDeclarations.map(_.kind),
      Vector(
        ScaleKind.Continuous,
        ScaleKind.Continuous,
        ScaleKind.Discrete,
        ScaleKind.Discrete,
        ScaleKind.Continuous
      )
    )
    assertEquals(
      trained.trainedScales.map(_.descriptor.name.value),
      Vector("x", "y", "condition-color", "condition-fill", "size")
    )
    assertEquals(
      trained.trainedScales.map(_.descriptor.domain),
      Vector(
        ScaleDomain.Continuous(Interval.unsafe(0.0, 2.0), Interval.unsafe(0.0, 2.0)),
        ScaleDomain.Continuous(Interval.unsafe(1.0, 3.0), Interval.unsafe(1.0, 3.0)),
        ScaleDomain.Discrete(Vector("A", "B"), ordered = true),
        ScaleDomain.Discrete(Vector("A", "B"), ordered = true),
        ScaleDomain.Continuous(Interval.unsafe(1.0, 3.0), Interval.unsafe(1.0, 3.0))
      )
    )
  }

  test("one plot scale trains over the union of every layer before row mapping") {
    val first =
      Vector(
        Observation(0.0, 1.0, "A"),
        Observation(10.0, 2.0, "A")
      )
    val second =
      Vector(
        Observation(100.0, 3.0, "B"),
        Observation(200.0, 4.0, "B")
      )
    val xScale =
      ContinuousScale
        .train("shared-x", first.map(_.time), Palette.numeric)
        .fold(error => fail(error.message), identity)
    val plot =
      Plot(first)
        .withScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, xScale))
        .flatMap(_.addLayer(Layer.point[Observation](_.time, _.value)))
        .flatMap(
          _.addLayer(
            Layer.point[Observation](_.time, _.value, data = Some(second))
          )
        )
        .fold(error => fail(error.message), identity)

    val trained = PlotCompiler.resolve(plot).fold(error => fail(error.message), identity)
    val trainedX = trained.scaleRegistry.forAesthetic(Aesthetic.X).get

    assertEquals(trained.trainedScales.length, 1)
    assertEquals(
      trainedX.descriptor.domain,
      ScaleDomain.Continuous(Interval.unsafe(0.0, 200.0), Interval.unsafe(0.0, 200.0))
    )
    assertEqualsDouble(trained.layers(0).rows(0).x, 0.0, 1e-12)
    assertEqualsDouble(trained.layers(0).rows(1).x, 0.05, 1e-12)
    assertEqualsDouble(trained.layers(1).rows(0).x, 0.5, 1e-12)
    assertEqualsDouble(trained.layers(1).rows(1).x, 1.0, 1e-12)
    assertEquals(
      trained.layers.flatMap(_.trainedScales).map(_.descriptor.domain).distinct,
      Vector(trainedX.descriptor.domain)
    )
  }

  test("different scale declarations for one aesthetic are a typed plot error") {
    val first = Vector(Observation(0.0, 1.0, "A"), Observation(10.0, 2.0, "A"))
    val second = Vector(Observation(100.0, 3.0, "B"), Observation(200.0, 4.0, "B"))
    val firstScale =
      ContinuousScale
        .train("first-x", first.map(_.time), Palette.numeric)
        .fold(error => fail(error.message), identity)
    val secondScale =
      ContinuousScale
        .train("second-x", second.map(_.time), Palette.numeric)
        .fold(error => fail(error.message), identity)

    def layer(rows: Vector[Observation], scale: ContinuousScale[Double]): Layer[Observation] =
      val mapping =
        AesSpec
          .empty[Observation]
          .withPosition(_.time, _.value)
          .bindScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, scale))
          .fold(error => fail(error.message), identity)
      Layer
        .fromMapping(Geom.Point, mapping, data = Some(rows), inheritMapping = false)
        .fold(error => fail(error.message), identity)

    val plot =
      Plot(Vector.empty[Observation])
        .addLayer(layer(first, firstScale))
        .flatMap(_.addLayer(layer(second, secondScale)))
        .fold(error => fail(error.message), identity)

    assertEquals(
      PlotCompiler.resolve(plot).left.toOption,
      Some(GraphicsError.ConflictingPlotScales("x", 0, "first-x", 1, "second-x"))
    )
  }

  test("compiles line layers in row order and drops non-finite positions") {
    val rows =
      Vector(
        Observation(0.0, 1.0, "A"),
        Observation(Double.NaN, 2.0, "bad"),
        Observation(2.0, 3.0, "B")
      )
    val layer = Layer.line[Observation](_.time, _.value)
    val plot = Plot(rows).addLayer(layer).toOption.get

    val trained = PlotCompiler.resolve(plot).toOption.get
    val resolved = trained.layers.head
    val line = trained.scene.grobs.head.asInstanceOf[Grob.Lines]

    assertEquals(resolved.rows.map(_.rowIndex), Vector(0, 2))
    assertEquals(resolved.droppedRows.map(_.rowIndex), Vector(1))
    assert(resolved.droppedRows.head.reason match
      case PlotDropReason.NonFinitePosition(x, _) => x.isNaN
      case _                                      => false)
    assertEquals(line.points, Vector(Point.nativeUnsafe(0.0, 1.0), Point.nativeUnsafe(2.0, 3.0)))
  }

  test("scaled label mappings can drop text rows without failing the whole plot") {
    val domain = DiscreteDomain.ordered(Vector("A")).toOption.get
    val scale =
      DiscreteScale
        .fixed(
          "condition-label",
          domain,
          DiscretePalette.valuesUnsafe(Vector("alpha"))
        )
        .toOption
        .get
    val binding = ScaleBinding[Observation, String, String](Aesthetic.Label, _.condition, scale)
    val layer = Layer.text[Observation](_.time, _.value, row => row.condition)
    val plot =
      Plot(data)
        .withScale(binding)
        .flatMap(_.addLayer(layer))
        .toOption
        .get

    val trained = PlotCompiler.resolve(plot).toOption.get
    val resolved = trained.layers.head

    assertEquals(resolved.rows.map(_.label), Vector(Some("alpha"), Some("alpha")))
    assertEquals(
      resolved.droppedRows.map(_.reason),
      Vector(PlotDropReason.ScaleOutOfDomain("label", "condition-label", "B"))
    )
    assertEquals(trained.scene.grobs.length, 2)
  }

  test("log transform domain failures are typed row-drop diagnostics") {
    val rows =
      Vector(
        Observation(-1.0, 1.0, "bad-negative"),
        Observation(0.0, 2.0, "bad-zero"),
        Observation(10.0, 3.0, "good")
      )
    val xScale =
      ContinuousScale
        .train("x-log", Vector(1.0, 10.0, 100.0), Palette.numeric, transform = Transform.log10)
        .toOption
        .get
    val plot =
      Plot(rows)
        .withScale(ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, xScale))
        .flatMap(_.addLayer(Layer.point[Observation](_.time, _.value)))
        .toOption
        .get

    val trained = PlotCompiler.resolve(plot).toOption.get

    assertEquals(trained.layers.head.rows.map(_.rowIndex), Vector(2))
    assertEquals(
      trained.droppedRows.map(_.reason),
      Vector(
        PlotDropReason.TransformDomain("x", "log10", -1.0),
        PlotDropReason.TransformDomain("x", "log10", 0.0)
      )
    )
  }

  test("discrete unknown levels are typed scale out-of-domain row drops") {
    val domain = DiscreteDomain.ordered(Vector("A")).toOption.get
    val colorScale =
      DiscreteScale
        .fixed(
          "condition-color",
          domain,
          DiscretePalette.valuesUnsafe(Vector(Rgba.Black))
        )
        .toOption
        .get
    val plot =
      Plot(data)
        .withScale(
          ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, colorScale)
        )
        .flatMap(_.addLayer(Layer.point[Observation](_.time, _.value)))
        .toOption
        .get

    val trained = PlotCompiler.resolve(plot).toOption.get

    assertEquals(trained.layers.head.rows.map(_.rowIndex), Vector(0, 1))
    assertEquals(
      trained.droppedRows.map(_.reason),
      Vector(PlotDropReason.ScaleOutOfDomain("color", "condition-color", "B"))
    )
  }

  test("empty layer data resolves to an empty scene without an empty-geometry error") {
    val layer =
      Layer.point[Observation](
        _.time,
        _.value,
        data = Some(Vector.empty)
      )
    val plot = Plot(data).addLayer(layer).toOption.get

    val trained = PlotCompiler.resolve(plot).toOption.get

    assertEquals(trained.layers.head.dataSize, 0)
    assertEquals(trained.layers.head.rows, Vector.empty)
    assertEquals(trained.scene, Scene.empty)
  }

  test("compiler options wrap layer grobs in a panel viewport and append guides") {
    val layout =
      PanelLayout(
        PanelFrame.npcUnsafe(0.12, 0.08, 0.76, 0.78),
        xScale = Interval.unsafe(0.0, 2.0),
        yScale = Interval.unsafe(1.0, 3.0),
        clip = Clip.On
      )
    val guide =
      GuideSpec.Axis(
        AxisSide.Bottom,
        breaks = Breaks.countUnsafe(3),
        tickLength = Some(ExtentExpr.nativeUnsafe(0.1)),
        labelOffset = Some(ExtentExpr.nativeUnsafe(0.2)),
        name = Some(GraphicsName.unsafe("x-guide"))
      )
    val plot =
      Plot(data)
        .withCoord(Coord.Cartesian(Clip.Off))
        .addLayer(Layer.point[Observation](_.time, _.value))
        .toOption
        .get

    val trained =
      PlotCompiler
        .resolve(
          plot,
          PlotCompilerOptions(layout = Some(layout), guides = GuidePolicy.Explicit(Vector(guide)))
        )
        .toOption
        .get
    val panel = trained.scene.grobs.head.asInstanceOf[Grob.Group]
    val axis = trained.scene.grobs(1).asInstanceOf[Grob.Group]

    assertEquals(trained.layout.map(_.clip), Some(Clip.Off))
    assertEquals(trained.guides.length, 1)
    assertEquals(trained.scene.size, 2)
    assertEquals(panel.name.map(_.value), Some("plot-panel"))
    assertEquals(panel.viewport.map(_.clip), Some(Clip.Off))
    assertEquals(panel.children.length, 3)
    assertEquals(axis.name.map(_.value), Some("x-guide"))
    assertEquals(axis.viewport.map(_.clip), Some(Clip.Off))
  }

  test("guide options require an explicit layout") {
    val plot =
      Plot(data)
        .addLayer(Layer.point[Observation](_.time, _.value))
        .toOption
        .get

    assertEquals(
      PlotCompiler
        .resolve(
          plot,
          PlotCompilerOptions(guides = GuidePolicy.Explicit(Vector(GuideSpec.Axis(AxisSide.Left))))
        )
        .left
        .toOption,
      Some(GraphicsError.MissingLayout("guides"))
    )
  }

  test("incomplete geom mappings fail at the typed layer boundary") {
    val layer = Layer.fromMapping[Observation](
      Geom.Rect,
      AesSpec.empty[Observation].withPosition(_.time, _.value),
      inheritMapping = false
    )

    assertEquals(layer.left.toOption, Some(GraphicsError.MissingAesthetic("rect", "xmin")))
  }
