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
    val output = layer.statFrame.rows.collect { case row: StatRow.Binned[?] => row }

    assertEquals(output.length, layer.statFrame.rows.length)
    assertEquals(output.map(_.count), Vector(1, 2))
    assertEquals(output.map(_.binLower), Vector(0.0, 1.5))
    assertEquals(output.map(_.binUpper), Vector(1.5, 5.0))
    assertEquals(output.map(_.binMidpoint), Vector(0.75, 3.25))
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
    val output = layer.statFrame.rows.collect { case row: StatRow.Summarized[?] => row }

    assertEquals(output.length, layer.statFrame.rows.length)
    assertEquals(output.map(_.position), Vector(1.0, 2.0, 3.0))
    assertEquals(output.map(_.mean), ScientificStatParityFixture.summaryMeans)
    assertEquals(output.map(_.lower), ScientificStatParityFixture.summaryLower)
    assertEquals(output.map(_.upper), ScientificStatParityFixture.summaryUpper)
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
    val output = layer.statFrame.rows.collect { case row: StatRow.Density[?] => row }

    assertEquals(output.length, layer.statFrame.rows.length)
    assertEquals(output.map(_.position), ScientificStatParityFixture.densityGrid)
    assert(output.forall(_.sampleSize == ScientificStatParityFixture.densityValues.length))
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

  test(
    "regular and explicit histogram partitions bind constant-time and binary lookup strategies"
  ) {
    val regularSpec = HistogramBins.countUnsafe(4)
    val regularBreaks = HistogramBins.partition(regularSpec, 0.0, 8.0)
    val regular = HistogramBins.lookup(regularSpec, regularBreaks)
    val widthSpec = HistogramBins.widthUnsafe(2.0)
    val widthBreaks = HistogramBins.partition(widthSpec, -1.0, 5.0)
    val width = HistogramBins.lookup(widthSpec, widthBreaks)
    val explicitSpec = HistogramBins.breaksUnsafe(Vector(0.0, 0.5, 3.0, 8.0))
    val explicitBreaks = HistogramBins.partition(explicitSpec, 0.0, 8.0)
    val explicit = HistogramBins.lookup(explicitSpec, explicitBreaks)

    assertEquals(regular.strategy, HistogramLookupStrategy.RegularArithmetic)
    assertEquals(width.strategy, HistogramLookupStrategy.RegularArithmetic)
    assertEquals(explicit.strategy, HistogramLookupStrategy.ExplicitBinarySearch)
    assertEquals(
      Vector(0.0, 2.0, 2.0000000001, 4.0, 6.0, 8.0).map(regular.index),
      Vector(0, 0, 1, 1, 2, 3)
    )
    assertEquals(
      Vector(-2.0, 0.0, 0.0000000001, 2.0, 4.0, 6.0).map(width.index),
      Vector(0, 0, 1, 1, 2, 3)
    )
    assertEquals(
      Vector(0.0, 0.5, 0.5000000001, 3.0, 3.0000000001, 8.0).map(explicit.index),
      Vector(0, 0, 1, 1, 2, 2)
    )
    assertEquals(regular.index(-0.1), -1)
    assertEquals(explicit.index(8.1), -1)
  }

  test("optimized histogram lookup preserves boundary ownership and observation conservation") {
    val breaks = Vector(-2.0, -0.5, 0.0, 1.25, 5.0)
    val spec = HistogramBins.breaksUnsafe(breaks)
    val values = Vector(-2.0, -1.0, -0.5, -0.25, 0.0, 0.5, 1.25, 2.0, 5.0)
    val resolved =
      Plot(values)
        .addLayer(Layer.histogram(identity, bins = spec))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
    val counts = resolved.layers.head.statFrame.rows.collect { case row: StatRow.Binned[?] =>
      row.count
    }

    assertEquals(counts, Vector(3, 2, 2, 2))
    assertEquals(counts.sum, values.length)
  }
