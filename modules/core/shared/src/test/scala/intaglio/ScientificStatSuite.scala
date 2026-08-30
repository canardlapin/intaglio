package intaglio

class ScientificStatSuite extends munit.FunSuite:

  test("histogram matches ggplot2 explicit-break count contract and trains full bin extents") {
    val bins = HistogramBins.breaksUnsafe(ScientificStatParityFixture.histogramBreaks)
    val trained =
      Plot(ScientificStatParityFixture.histogramValues)
        .addLayer(Layer.histogram(identity, bins = bins))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)
    val layer = trained.layers.head

    assertEquals(
      layer.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Count)),
      ScientificStatParityFixture.histogramCounts
    )
    assertEquals(
      layer.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.BinMidpoint)),
      Vector(0.75, 3.25)
    )
    assertEquals(layer.rows.map(_.x), Vector(0.75, 3.25))
    assertEquals(layer.rows.map(_.y), Vector(1.0, 2.0))
    assertEquals(trained.layout.map(_.xScale), Some(Interval.unsafe(0.0, 5.0)))
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(0.0, 2.0)))
    assertEquals(layer.grobs.length, 2)
    assert(layer.grobs.forall(_.isInstanceOf[Grob.Rect]))
  }

  test("grouped summary matches R mean plus standard-error output and lowers intervals") {
    val trained =
      Plot(ScientificStatParityFixture.summaryValues)
        .addLayer(Layer.summary(_.x, _.y))
        .flatMap(
          PlotCompiler.resolve(
            _,
            PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
          )
        )
        .fold(error => fail(error.message), identity)
    val layer = trained.layers.head

    assertEquals(layer.rows.map(_.x), Vector(1.0, 2.0, 3.0))
    assertEquals(layer.rows.map(_.y), ScientificStatParityFixture.summaryMeans)
    assertEquals(
      layer.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Lower)),
      ScientificStatParityFixture.summaryLower
    )
    assertEquals(
      layer.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Upper)),
      ScientificStatParityFixture.summaryUpper
    )
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(0.0, 4.0)))
    assertEquals(layer.grobs.count(_.isInstanceOf[Grob.Segments]), 3)
    assertEquals(layer.grobs.count(_.isInstanceOf[Grob.Points]), 3)
  }

  test("fixed-bandwidth Gaussian density agrees with R density") {
    val config = DensityConfig.fixedUnsafe(
      bandwidth = 1.0,
      points = 9,
      domain = Some(Interval.unsafe(0.0, 4.0))
    )
    val trained =
      Plot(ScientificStatParityFixture.densityValues)
        .addLayer(Layer.density(identity, config = config))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    val layer = trained.layers.head

    assertEquals(layer.rows.map(_.x), ScientificStatParityFixture.densityGrid)
    layer.rows.map(_.y).zip(ScientificStatParityFixture.density).foreach {
      case (actual, expected) =>
        assertEqualsDouble(actual, expected, 6e-6)
    }
    assertEquals(layer.grobs.length, 1)
    assert(layer.grobs.head.isInstanceOf[Grob.Lines])
  }

  test("scientific stat constructors and compiler boundaries reject incoherent input") {
    assert(HistogramBins.count(0).isLeft)
    assert(HistogramBins.width(Double.NaN).isLeft)
    assert(HistogramBins.breaks(Vector(0.0, 2.0, 1.0)).isLeft)
    assert(DensityConfig.fixed(0.0).isLeft)
    assert(DensityConfig.automatic(points = 1).isLeft)

    val wrongGeom = Layer.fromMapping(
      Geom.Point,
      AesSpec.empty[Double],
      inheritMapping = false,
      stat = Stat.Bin(identity, HistogramBins.countUnsafe(2))
    )
    assertEquals(wrongGeom.left.toOption, Some(GraphicsError.InvalidStatGeom("bin", "point")))

    val nonFinite =
      Plot(Vector(1.0, Double.NaN))
        .addLayer(Layer.histogram(identity, bins = HistogramBins.countUnsafe(2)))
        .flatMap(PlotCompiler.resolve(_))
    nonFinite.left.toOption match
      case Some(GraphicsError.NonFiniteStatInput("bin", "x", value)) => assert(value.isNaN)
      case other => fail(s"expected typed non-finite bin input, found $other")

    val tooSmall =
      Plot(Vector(1.0))
        .addLayer(Layer.density(identity))
        .flatMap(PlotCompiler.resolve(_))
    assertEquals(tooSmall.left.toOption, Some(GraphicsError.InsufficientStatData("density", 2, 1)))
  }

  test("histogram closure and summary interval policies are explicit") {
    val histogram =
      Plot(Vector(0.0, 1.0, 2.0, 3.0, 4.0))
        .addLayer(Layer.histogram(identity, bins = HistogramBins.countUnsafe(2)))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    assertEquals(
      histogram.layers.head.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Count)),
      Vector(3.0, 2.0)
    )

    val summary =
      Plot(ScientificStatParityFixture.summaryValues)
        .addLayer(Layer.summary(_.x, _.y, interval = SummaryInterval.Range))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    assertEquals(
      summary.layers.head.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Lower)),
      Vector(0.0, 1.0, 2.0)
    )
    assertEquals(
      summary.layers.head.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Upper)),
      Vector(2.0, 3.0, 4.0)
    )
  }
