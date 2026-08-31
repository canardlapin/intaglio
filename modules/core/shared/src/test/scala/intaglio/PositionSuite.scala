package intaglio

class PositionSuite extends munit.FunSuite:
  private final case class BarDatum(category: String, value: Double, group: String)
  private final case class PointDatum(x: Double, y: Double)

  /** A hostile ecosystem scale used to prove that position adjustment keys categorical rows by
    * their typed domain identity, not merely by the mapped coordinate. Both levels intentionally
    * occupy the same numeric band.
    */
  private final case class CollidingBandScale(name: GraphicsName) extends Scale[String, Double]:
    override val descriptor: ScaleDescriptor =
      ScaleDescriptor(
        name,
        ScaleKind.Band,
        ScaleDomain.Band(Vector("A", "B"), ordered = true, BandPadding.default),
        ScaleTraining.Fixed
      )

    override def mapValue(value: String): Option[Double] =
      Option.when(value == "A" || value == "B")(0.0)

    private[intaglio] override def observation(value: String): Option[ScaleObservation] =
      Option.when(value == "A" || value == "B")(
        ScaleObservation.discrete(value, CategoryIdentity.strings)
      )

    private[intaglio] override def mappedBand(value: String): Option[Band] =
      Option.when(value == "A" || value == "B")(Band.unsafe(0.0, 0.9))

  private val bars =
    Vector(
      BarDatum("A", 3.0, "red"),
      BarDatum("A", 2.0, "blue"),
      BarDatum("B", 1.0, "red"),
      BarDatum("B", 4.0, "blue")
    )

  private def barPlot(position: Position, data: Vector[BarDatum] = bars): TrainedPlot =
    val band = BandScale("category", DiscreteDomain.empty).fold(e => fail(e.message), identity)
    val fill = DiscreteScale(
      "group-fill",
      DiscreteDomain.empty,
      DiscretePalette.valuesUnsafe(
        Vector(
          Rgba.unsafe(70, 125, 180),
          Rgba.unsafe(220, 135, 65),
          Rgba.unsafe(90, 160, 95),
          Rgba.unsafe(150, 100, 180)
        )
      )
    ).fold(e => fail(e.message), identity)
    val mapping = AesSpec
      .empty[BarDatum]
      .withPosition(_ => 0.0, _.value)
      .bindScale(ScaleBinding[BarDatum, String, Double](Aesthetic.X, _.category, band))
      .flatMap(
        _.bindScale(ScaleBinding[BarDatum, String, Rgba](Aesthetic.Fill, _.group, fill))
      )
      .fold(e => fail(e.message), identity)
    val layer = Layer
      .fromMapping(Geom.Bar, mapping, inheritMapping = false, position = position)
      .fold(e => fail(e.message), identity)
    Plot(data)
      .addLayer(layer)
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(
            policy = Some(LayoutPolicy()),
            expansion = RangeExpansion.none,
            guides = GuidePolicy.Derived()
          )
        )
      )
      .fold(e => fail(e.message), identity)

  test("dodge divides each categorical band into deterministic group slots") {
    val trained = barPlot(Position.Dodge())
    val rows = trained.layers.head.rows

    Vector(-0.225, 0.225, 0.775, 1.225).zip(rows.map(_.x)).foreach { case (expected, actual) =>
      assertEqualsDouble(actual, expected, 1e-12)
    }
    rows.flatMap(_.xBand).foreach(band => assertEqualsDouble(band.width, 0.45, 1e-12))
    assertEquals(trained.layers.head.position, Position.Dodge())
    assertEquals(
      trained.layers.head.grouping,
      GroupingDecision.Inferred(Vector(Aesthetic.Fill))
    )
    assertEquals(rows.map(_.group), Vector(Some("red"), Some("blue"), Some("red"), Some("blue")))
    val range = trained.layout.map(_.xScale).getOrElse(fail("missing dodged panel range"))
    assertEqualsDouble(range.lower, -0.45, 1e-12)
    assertEqualsDouble(range.upper, 1.45, 1e-12)
  }

  test("dodge preserve single reserves stable global group slots") {
    val data = Vector(
      BarDatum("A", 3.0, "red"),
      BarDatum("A", 2.0, "blue"),
      BarDatum("B", 4.0, "blue")
    )
    val rows = barPlot(
      Position.Dodge(DodgeConfig(preserve = DodgePreserve.Single)),
      data
    ).layers.head.rows

    Vector(-0.225, 0.225, 1.225).zip(rows.map(_.x)).foreach { case (expected, actual) =>
      assertEqualsDouble(actual, expected, 1e-12)
    }
    rows.flatMap(_.xBand).foreach(band => assertEqualsDouble(band.width, 0.45, 1e-12))
  }

  test("dodge distinguishes categorical identities with colliding mapped coordinates") {
    val data = Vector(
      BarDatum("A", 3.0, "red"),
      BarDatum("B", 4.0, "blue")
    )
    val fill = DiscreteScale(
      "group-fill",
      DiscreteDomain.empty,
      DiscretePalette.valuesUnsafe(
        Vector(Rgba.unsafe(70, 125, 180), Rgba.unsafe(220, 135, 65))
      )
    ).fold(e => fail(e.message), identity)
    val mapping = AesSpec
      .empty[BarDatum]
      .withPosition(_ => 0.0, _.value)
      .bindScale(
        ScaleBinding[BarDatum, String, Double](
          Aesthetic.X,
          _.category,
          CollidingBandScale(GraphicsName.unsafe("colliding-band"))
        )
      )
      .flatMap(
        _.bindScale(ScaleBinding[BarDatum, String, Rgba](Aesthetic.Fill, _.group, fill))
      )
      .fold(e => fail(e.message), identity)
    val layer = Layer
      .fromMapping(Geom.Bar, mapping, inheritMapping = false, position = Position.Dodge())
      .fold(e => fail(e.message), identity)
    val rows = Plot(data)
      .addLayer(layer)
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(
            policy = Some(LayoutPolicy()),
            expansion = RangeExpansion.none,
            guides = GuidePolicy.Derived()
          )
        )
      )
      .fold(e => fail(e.message), identity)
      .layers
      .head
      .rows

    assertEquals(rows.map(_.x), Vector(0.0, 0.0))
    assertEquals(
      rows.flatMap(_.xCategoryIdentity.map(_.label)),
      Vector("A", "B")
    )
  }

  test("stack keeps source order while handling positive and negative groups separately") {
    val data = Vector(
      BarDatum("A", 3.0, "red"),
      BarDatum("A", -2.0, "blue"),
      BarDatum("A", 1.0, "green")
    )
    val rows = barPlot(Position.Stack(), data).layers.head.rows

    assertEquals(
      rows.map(_.grouping).distinct,
      Vector(GroupingDecision.Inferred(Vector(Aesthetic.Fill)))
    )
    assertEquals(rows.map(_.group), Vector(Some("red"), Some("blue"), Some("green")))
    assertEquals(rows.map(_.yMin), Vector(Some(1.0), Some(-2.0), Some(0.0)))
    assertEquals(rows.map(_.yMax), Vector(Some(4.0), Some(0.0), Some(1.0)))
    assertEquals(rows.map(_.y), Vector(4.0, -2.0, 1.0))

    val encountered = barPlot(Position.Stack(StackOrder.Encountered), data).layers.head.rows
    assertEquals(encountered.map(_.yMin), Vector(Some(0.0), Some(-2.0), Some(3.0)))
    assertEquals(encountered.map(_.yMax), Vector(Some(3.0), Some(0.0), Some(4.0)))
    assertEquals(encountered.map(_.y), Vector(3.0, -2.0, 4.0))
  }

  test("seeded jitter is byte-stable in principle across JVM and Scala.js") {
    val data =
      Vector(PointDatum(0.0, 1.0), PointDatum(0.0, 1.0), PointDatum(1.0, 2.0), PointDatum(1.0, 2.0))
    val jitter = Position.jitterUnsafe(42L, width = Some(0.25), height = Some(0.1))
    def resolve(position: Position): Vector[ResolvedRow[?]] =
      Plot(data)
        .addLayer(Layer.point[PointDatum](_.x, _.y, position = position))
        .flatMap(PlotCompiler.resolve(_))
        .fold(e => fail(e.message), identity)
        .layers
        .head
        .rows

    val first = resolve(jitter)
    val second = resolve(jitter)
    val expectedUnitOffsets = Vector(
      (0.4831297575436466, -0.6801792142461598),
      (-0.4427977394897227, -0.31161856695272494),
      (-0.9239396629195076, 0.7364561530930647),
      (-0.5631896125756313, 0.6012637534270067)
    )

    assertEquals(first.map(row => (row.x, row.y)), second.map(row => (row.x, row.y)))
    first.zip(data).zip(expectedUnitOffsets).foreach {
      case ((row, source), (expectedX, expectedY)) =>
        assertEqualsDouble((row.x - source.x) / 0.25, expectedX, 1e-15)
        assertEqualsDouble((row.y - source.y) / 0.1, expectedY, 1e-14)
    }
    assertNotEquals(
      first.map(row => (row.x, row.y)),
      resolve(Position.jitterUnsafe(43L, width = Some(0.25), height = Some(0.1))).map(row =>
        (row.x, row.y)
      )
    )
  }

  test("position constructors and geom compatibility reject invalid states") {
    assertEquals(
      DodgeWidth(0.0).left.toOption,
      Some(GraphicsError.InvalidPositionParameter("dodge", "width", 0.0, "finite and > 0"))
    )
    assertEquals(
      JitterAmount(-0.1).left.toOption,
      Some(GraphicsError.InvalidPositionParameter("jitter", "amount", -0.1, "finite and >= 0"))
    )

    val stackedPoint = Plot(Vector(PointDatum(0.0, 1.0)))
      .addLayer(Layer.point[PointDatum](_.x, _.y, position = Position.Stack()))
      .flatMap(PlotCompiler.resolve(_))
    assertEquals(
      stackedPoint.left.toOption,
      Some(GraphicsError.InvalidPositionGeom("stack", "point"))
    )
  }
