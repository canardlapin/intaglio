package intaglio

class GrammarSuite extends munit.FunSuite:

  private final case class Observation(time: Double, value: Double, condition: String)

  private val data =
    Vector(
      Observation(0.0, 1.0, "A"),
      Observation(1.0, 2.0, "A"),
      Observation(2.0, 3.0, "B")
    )

  test("row mappings expose total, checked, and throwing contracts") {
    val total = RowMapping.total[Observation, Double](_.time)
    val checked = RowMapping.checkedMessage[Observation, Double] { row =>
      Either.cond(row.time >= 0.0, row.time, "negative time")
    }
    val throwing = RowMapping.throwing[Observation, Double] { row =>
      if row.condition == "B" then throw new IllegalStateException("bad condition")
      else row.value
    }

    assertEquals(total.contract, MappingContract.Total)
    assertEquals(total.evaluate(data.head), Right(0.0))
    assertEquals(checked.contract, MappingContract.Checked)
    assertEquals(checked.evaluate(data.head), Right(0.0))
    assertEquals(
      checked.evaluate(Observation(-1.0, 0.0, "bad")),
      Left(MappingFailure.Rejected("negative time"))
    )
    assertEquals(throwing.contract, MappingContract.Throwing)
    assert(throwing.evaluate(data.last) match
      case Left(MappingFailure.Threw(_, "bad condition")) => true
      case _                                              => false)
  }

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

  test("every built-in geom publishes its complete aesthetic contract") {
    def labels(values: Vector[Aesthetic[?]]): Vector[String] =
      values.map(_.label)

    val actual = Geom.values.toVector.map { geom =>
      geom.label -> (
        geom.contract.required.map(_.label),
        labels(geom.contract.optional),
        labels(geom.contract.groupConstant)
      )
    }.toMap
    val markStyles = Vector("color", "fill", "alpha", "group")

    assertEquals(
      actual,
      Map(
        "point" -> (Vector("x", "y"), markStyles.patch(3, Vector("size"), 0), Vector.empty),
        "line" -> (Vector("x", "y"), Vector("color", "alpha", "group"), Vector("color", "alpha")),
        "text" -> (Vector("x", "y", "label"), markStyles, Vector.empty),
        "rect" -> (
          Vector("x", "y", "xmin", "xmax", "ymin", "ymax"),
          markStyles,
          Vector.empty
        ),
        "bar" -> (Vector("x", "y"), markStyles, Vector.empty),
        "segment" -> (
          Vector("x", "y", "xend", "yend"),
          Vector("color", "alpha", "group"),
          Vector.empty
        ),
        "errorbar" -> (
          Vector("x", "y", "ymin", "ymax"),
          Vector("color", "alpha", "group"),
          Vector.empty
        ),
        "ribbon" -> (
          Vector("x", "y", "ymin", "ymax"),
          markStyles,
          Vector("color", "fill", "alpha")
        ),
        "area" -> (
          Vector("x", "y", "ymin", "ymax"),
          markStyles,
          Vector("color", "fill", "alpha")
        ),
        "hline" -> (Vector.empty, Vector.empty, Vector.empty),
        "vline" -> (Vector.empty, Vector.empty, Vector.empty),
        "tile" -> (
          Vector("x", "y", "xmin", "xmax", "ymin", "ymax"),
          markStyles,
          Vector.empty
        ),
        "polygon" -> (
          Vector("x", "y"),
          markStyles :+ "subpath",
          Vector("color", "fill", "alpha")
        )
      )
    )
  }

  test("geom aesthetic contracts reject inconsistent declarations") {
    assertEquals(
      GeomAestheticContract
        .checked(
          Vector(RequiredAesthetic.X),
          Vector(Aesthetic.X)
        )
        .left
        .toOption,
      Some(
        GraphicsError.InvalidGeomAestheticContract(
          "an aesthetic cannot be both required and optional"
        )
      )
    )
    assertEquals(
      GeomAestheticContract
        .checked(
          Vector(RequiredAesthetic.X, RequiredAesthetic.Y),
          Vector(Aesthetic.Color),
          groupConstant = Vector(Aesthetic.Fill)
        )
        .left
        .toOption,
      Some(
        GraphicsError.InvalidGeomAestheticContract(
          "group-constant aesthetics must also be optional"
        )
      )
    )
  }

  test("unsupported geom mappings fail at the typed layer boundary") {
    val line = Layer.line[Observation](
      _.time,
      _.value,
      mapping = AesSpec.empty[Observation].withFill(Rgba.Black),
      inheritMapping = false
    )

    assertEquals(
      Plot(data).addLayer(line).left.toOption,
      Some(GraphicsError.UnsupportedGeomAesthetic("line", "fill"))
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
