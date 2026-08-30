package intaglio

class StatSuite extends munit.FunSuite:

  test("stat count matches R table and ggplot2 stat_count parity fixture") {
    val plot =
      Plot(StatCountParityFixture.carb)
        .addLayer(Layer.count(identity, order = CountOrder.Lexicographic))
        .fold(error => fail(error.message), identity)
    val mapped = MappingPhase.plan(plot).fold(error => fail(error.message), identity)
    val stat = StatPhase.transform(mapped).fold(error => fail(error.message), identity).head.frame

    assertEquals(stat.rows.flatMap(_.category), StatCountParityFixture.levels)
    assertEquals(
      stat.rows.flatMap(_.computed.get(ComputedAesthetic.Count)),
      StatCountParityFixture.counts
    )
    stat.rows
      .flatMap(_.computed.get(ComputedAesthetic.Proportion))
      .zip(StatCountParityFixture.proportions)
      .foreach { case (actual, expected) => assertEqualsDouble(actual, expected, 1e-12) }
    assertEquals(
      stat.computedAesthetics,
      Set[ComputedAesthetic[?]](ComputedAesthetic.Count, ComputedAesthetic.Proportion)
    )
    assertEquals(stat.rows.map(_.members.length), Vector(7, 10, 3, 10, 1, 1))
  }

  test("computed count output trains x scale, derives labels, and lowers bars") {
    val plot =
      Plot(StatCountParityFixture.carb)
        .addLayer(Layer.count(identity, order = CountOrder.Lexicographic))
        .fold(error => fail(error.message), identity)
    val trained =
      PlotCompiler
        .resolve(
          plot,
          PlotCompilerOptions(
            policy = Some(LayoutPolicy()),
            expansion = RangeExpansion.none,
            guides = GuidePolicy.Derived()
          )
        )
        .fold(error => fail(error.message), identity)
    val layer = trained.layers.head

    assertEquals(layer.dataSize, 32)
    assertEquals(layer.rows.map(_.x), Vector(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
    assertEquals(layer.rows.map(_.y), StatCountParityFixture.counts)
    assertEquals(layer.rows.flatMap(_.xBand).map(_.width), Vector.fill(6)(0.9))
    assertEquals(layer.grobs.length, 6)
    assert(layer.grobs.forall(_.isInstanceOf[Grob.Rect]))
    assertEquals(layer.statFrame.rows.length, 6)
    assertEquals(
      trained.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor.domain),
      Some(ScaleDomain.Band(StatCountParityFixture.levels, ordered = true, BandPadding.default))
    )
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(-0.45, 5.45)))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(0.0, 10.0)))

    val xAxis = trained.guides
      .collectFirst {
        case ResolvedGuide(axis: GuideSpec.Axis, _) if axis.side == AxisSide.Bottom => axis
      }
      .getOrElse(fail("missing count x axis"))
    assertEquals(xAxis.ticks.toVector.flatten.map(_.label), StatCountParityFixture.levels)
    assertEquals(xAxis.ticks.toVector.flatten.map(_.value), Vector(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
  }

  test("count bars and panel ranges consume explicit band padding") {
    val padding = BandPadding.unsafe(0.2)
    val plot =
      Plot(Vector("control", "task", "task"))
        .addLayer(Layer.count(identity, order = CountOrder.Lexicographic, padding = padding))
        .fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(
        plot,
        PlotCompilerOptions(
          policy = Some(LayoutPolicy()),
          expansion = RangeExpansion.none,
          guides = GuidePolicy.Derived()
        )
      )
      .fold(error => fail(error.message), identity)
    val layer = trained.layers.head
    val bars = layer.grobs.collect { case rect: Grob.Rect => rect }

    assertEquals(layer.rows.flatMap(_.xBand), Vector(Band.unsafe(0.0, 0.8), Band.unsafe(1.0, 0.8)))
    assertEquals(bars.map(_.size.width), Vector.fill(2)(ExtentExpr.nativeUnsafe(0.8)))
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(-0.4, 1.4)))
  }

  test("declared count order is preserved before undeclared observed levels") {
    val data = Vector("control", "task", "other", "task", "control")
    val plot =
      Plot(data)
        .addLayer(
          Layer.count(
            identity,
            order = CountOrder.declaredUnsafe(Vector("task", "control"))
          )
        )
        .fold(error => fail(error.message), identity)
    val frame =
      MappingPhase
        .plan(plot)
        .flatMap(StatPhase.transform)
        .fold(error => fail(error.message), identity)
        .head
        .frame

    assertEquals(frame.rows.flatMap(_.category), Vector("task", "control", "other"))
    assertEquals(frame.rows.flatMap(_.computed.get(ComputedAesthetic.Count)), Vector(2.0, 2.0, 1.0))
    assertEquals(
      CountOrder.declared(Vector("task", "task")).left.toOption,
      Some(GraphicsError.DuplicateLevel("task"))
    )
  }

  test("grouped count dodge matches ggplot2 total and single width contracts") {
    final case class Observation(category: String, group: String)
    val data = Vector(Observation("a", "u"), Observation("b", "u"), Observation("b", "v"))

    def positions(preserve: DodgePreserve): Vector[Double] =
      val layer = Layer.count[Observation](
        _.category,
        padding = BandPadding.unsafe(0.0),
        group = Some(_.group),
        position = Position.Dodge(DodgeConfig(preserve = preserve))
      )
      Plot(data)
        .addLayer(layer)
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
        .layers
        .head
        .rows
        .map(_.x)

    assertEquals(positions(DodgePreserve.Total), Vector(0.0, 0.75, 1.25))
    assertEquals(positions(DodgePreserve.Single), Vector(-0.25, 0.75, 1.25))
  }

  test("grouped count stack uses the trained band and reverse group order") {
    final case class Observation(category: String, group: String)
    val data =
      Vector.fill(3)(Observation("A", "red")) ++
        Vector.fill(2)(Observation("A", "blue")) ++
        Vector(Observation("B", "red")) ++
        Vector.fill(4)(Observation("B", "blue"))
    val trained = Plot(data)
      .addLayer(Layer.count[Observation](_.category, group = Some(_.group)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
    val rows = trained.layers.head.rows

    assertEquals(rows.map(_.group), Vector(Some("red"), Some("blue"), Some("red"), Some("blue")))
    assertEquals(rows.map(_.yMin), Vector(Some(2.0), Some(0.0), Some(4.0), Some(0.0)))
    assertEquals(rows.map(_.yMax), Vector(Some(5.0), Some(2.0), Some(5.0), Some(4.0)))
    assert(rows.forall(_.xBand.nonEmpty))
  }

  test("count owns position aesthetics and rejects incompatible specs") {
    val mapping = AesSpec.empty[String].withPosition(_.length.toDouble, _.length.toDouble)
    val result = Layer.fromMapping(
      Geom.Bar,
      mapping,
      inheritMapping = false,
      stat = Stat.Count(identity)
    )

    assertEquals(
      result.left.toOption,
      Some(GraphicsError.StatAestheticConflict("count", "x"))
    )

    val color = Layer.fromMapping(
      Geom.Bar,
      AesSpec.empty[String].withColor(Rgba.Black),
      inheritMapping = false,
      stat = Stat.Count(identity)
    )
    assertEquals(
      color.left.toOption,
      Some(GraphicsError.UnsupportedStatAesthetic("count", "color"))
    )
  }

  test("single and empty count layers retain valid typed boundaries") {
    val single =
      Plot(Vector("only", "only", "only"))
        .addLayer(Layer.count(identity))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)
    assertEquals(single.layout.map(_.xScale), Some(Interval.unsafe(-0.45, 0.45)))
    assertEquals(single.layout.map(_.yScale), Some(Interval.unsafe(0.0, 3.0)))

    val empty =
      Plot(Vector.empty[String])
        .addLayer(Layer.count(identity))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    assertEquals(empty.layers.head.statFrame.rows, Vector.empty)
    assertEquals(empty.scene, Scene.empty)
  }
