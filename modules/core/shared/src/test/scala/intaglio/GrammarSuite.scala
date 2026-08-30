package intaglio

class GrammarSuite extends munit.FunSuite:

  private final case class Observation(time: Double, value: Double, condition: String)

  private val data =
    Vector(
      Observation(0.0, 1.0, "A"),
      Observation(1.0, 2.0, "A"),
      Observation(2.0, 3.0, "B")
    )

  test("typed layers carry required position aesthetics by construction") {
    val layer = Layer.point[Observation](_.time, _.value)
    val mapping = layer.effectiveMapping(AesSpec.empty)

    assertEquals(layer.geom, Geom.Point)
    assert(mapping.position.nonEmpty)
    assertEquals(mapping.position.flatMap(_.map(data.head)), Some((0.0, 1.0)))
  }

  test("plot validates required aesthetics after layer mapping inheritance") {
    val plotMapping =
      AesSpec.empty[Observation].withPosition(_.time, _.value)

    val inheritedPoint =
      Layer
        .fromMapping[Observation](Geom.Point, AesSpec.empty[Observation])
        .toOption
        .get

    val missingPoint =
      Plot(data).addLayer(inheritedPoint)

    val inheritedPlot =
      Plot(data)
        .withMapping(plotMapping)
        .flatMap(_.addLayer(inheritedPoint))

    val missingTextLabel =
      Layer
        .fromMapping[Observation](
          Geom.Text,
          AesSpec.empty[Observation].withPosition(_.time, _.value)
        )
        .flatMap(layer => Plot(data).addLayer(layer))

    assertEquals(missingPoint.left.toOption, Some(GraphicsError.MissingAesthetic("point", "x")))
    assert(inheritedPlot.isRight)
    assertEquals(
      missingTextLabel.left.toOption,
      Some(GraphicsError.MissingAesthetic("text", "label"))
    )
  }

  test("plot layers inherit plot data and mappings without mutating either") {
    val plotMapping =
      AesSpec
        .empty[Observation]
        .withPosition(_.time, _.value)
        .withGroup(_.condition)

    val layer =
      Layer
        .fromMapping[Observation](
          Geom.Line,
          AesSpec.empty[Observation].withPosition(_.time, _.value)
        )
        .toOption
        .get

    val plot =
      Plot(data)
        .withMapping(plotMapping)
        .flatMap(_.addLayer(layer))
        .toOption
        .get

    assertEquals(plot.layerData(layer), data)
    assertEquals(plot.layerMapping(layer).group.flatMap(_.map(data.last)), Some("B"))
    assertEquals(plot.layers.map(_.layer), Vector(layer))
  }

  test("plot labels compose without rebuilding the plot specification") {
    val plot = Plot(data)
      .withTitle("Activation")
      .withSubtitle("Subject mean")
      .withAxisTitles("Time", "Signal")

    assertEquals(
      plot.labels,
      PlotLabels(
        title = Some("Activation"),
        subtitle = Some("Subject mean"),
        x = Some("Time"),
        y = Some("Signal")
      )
    )
  }

  test("plot rejects duplicate scale bindings for the same aesthetic") {
    val scale =
      ContinuousScale
        .train("x", data.map(_.time), Palette.numeric)
        .toOption
        .get

    val binding = ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, scale)
    val plot = Plot(data).withScale(binding).toOption.get

    assertEquals(plot.withScale(binding).left.toOption, Some(GraphicsError.DuplicateScale("x")))
    assertEquals(plot.mapping.x.flatMap(_.map(data.last)), Some(1.0))
  }

  test("plot scale bindings are not shadowed by direct layer mappings") {
    val scale =
      ContinuousScale
        .train("x", data.map(_.time), Palette.numeric)
        .toOption
        .get
    val binding = ScaleBinding[Observation, Double, Double](Aesthetic.X, _.time, scale)
    val layer = Layer.point[Observation](_.time, _.value)

    val plot =
      Plot(data)
        .withScale(binding)
        .flatMap(_.addLayer(layer))
        .toOption
        .get
    val mapping = plot.layerMapping(layer)

    assertEquals(mapping.x.flatMap(_.map(data.last)), Some(1.0))
    assertEquals(mapping.y.flatMap(_.map(data.last)), Some(3.0))
    assertEquals(mapping.position.flatMap(_.map(data.last)), Some((1.0, 3.0)))
  }

  test("plot mapping replacement revalidates already-added inherited layers") {
    val inheritedPoint =
      Layer
        .fromMapping[Observation](Geom.Point, AesSpec.empty[Observation])
        .toOption
        .get
    val plot =
      Plot(data)
        .withMapping(AesSpec.empty[Observation].withPosition(_.time, _.value))
        .flatMap(_.addLayer(inheritedPoint))
        .toOption
        .get

    assertEquals(
      plot.withMapping(AesSpec.empty[Observation]).left.toOption,
      Some(GraphicsError.MissingAesthetic("point", "x"))
    )
  }

  test("scale bindings carry the row extractor used to map an aesthetic") {
    val domain = DiscreteDomain.ordered(Vector("A", "B")).toOption.get
    val palette = DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.White))
    val scale = DiscreteScale("condition", domain, palette).toOption.get
    val binding = ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, scale)

    assertEquals(binding.map(data.head), Some(Rgba.Black))
    assertEquals(binding.map(data.last), Some(Rgba.White))
  }

  test("aesthetic specs unify direct, constant, and scaled values") {
    val domain = DiscreteDomain.ordered(Vector("A", "B")).toOption.get
    val palette = DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.White))
    val scale = DiscreteScale("condition", domain, palette).toOption.get
    val binding = ScaleBinding[Observation, String, Rgba](Aesthetic.Color, _.condition, scale)

    val mapping =
      AesSpec
        .empty[Observation]
        .withPosition(_.time, _.value)
        .withAlpha(0.5)
        .bindScale(binding)
        .toOption
        .get

    assertEquals(mapping.alpha.flatMap(_.map(data.head)), Some(0.5))
    assertEquals(mapping.color.flatMap(_.map(data.last)), Some(Rgba.White))
  }
