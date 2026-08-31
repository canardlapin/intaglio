package intaglio

class CommonStatsSuite extends munit.FunSuite:
  private final case class Sample(position: Double, value: Double, group: String)

  // R: quantile(c(1, 2, 3, 4, 100), c(.25, .5, .75), type = 7)
  // R: quantile(c(0, 10, 20, 30), c(.25, .5, .75), type = 7)
  private val quantileFixture =
    Vector(
      Sample(1.0, 1.0, "a"),
      Sample(1.0, 2.0, "a"),
      Sample(1.0, 3.0, "a"),
      Sample(1.0, 4.0, "a"),
      Sample(1.0, 100.0, "a"),
      Sample(2.0, 0.0, "a"),
      Sample(2.0, 10.0, "a"),
      Sample(2.0, 20.0, "a"),
      Sample(2.0, 30.0, "a")
    )

  test("quantile summary matches independent R type-7 quartile fixtures") {
    val trained = Plot(quantileFixture)
      .addLayer(Layer.quantileSummary(_.position, _.value))
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
        )
      )
      .fold(error => fail(error.message), identity)
    val layer = trained.layers.head
    val output = layer.statFrame.rows.collect { case row: StatRow.QuantileSummary[?] => row }

    assertEquals(output.map(_.position), Vector(1.0, 2.0))
    assertEquals(output.map(_.lowerQuartile), Vector(2.0, 7.5))
    assertEquals(output.map(_.median), Vector(3.0, 15.0))
    assertEquals(output.map(_.upperQuartile), Vector(4.0, 22.5))
    assertEquals(output.map(_.count), Vector(5, 4))
    assertEquals(
      layer.statFrame.rows.flatMap(_.computed.get(ComputedAesthetic.Median)),
      Vector(3.0, 15.0)
    )
    assertEquals(layer.grobs.count(_.isInstanceOf[Grob.Segments]), 2)
    assertEquals(layer.grobs.count(_.isInstanceOf[Grob.Points]), 2)
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(2.0, 22.5)))
  }

  test("quantile summaries preserve order and translation laws") {
    def capture(rows: Vector[Sample]): Vector[(Double, Double, Double, Double)] =
      Plot(rows)
        .addLayer(Layer.quantileSummary(_.position, _.value))
        .flatMap(PlotCompiler.resolve(_))
        .fold(error => fail(error.message), identity)
        .layers
        .head
        .statFrame
        .rows
        .collect { case row: StatRow.QuantileSummary[?] =>
          (row.position, row.lowerQuartile, row.median, row.upperQuartile)
        }

    val expected = capture(quantileFixture)
    assertEquals(capture(quantileFixture.reverse), expected)
    val shifted = capture(quantileFixture.map(row => row.copy(value = row.value + 7.0)))
    assertEquals(
      shifted,
      expected.map { case (position, lower, median, upper) =>
        (position, lower + 7.0, median + 7.0, upper + 7.0)
      }
    )
  }

  test("ECDF collapses ties and computes groups independently") {
    val fixture =
      Vector(
        Sample(2.0, 0.0, "a"),
        Sample(1.0, 0.0, "a"),
        Sample(2.0, 0.0, "a"),
        Sample(4.0, 0.0, "a"),
        Sample(3.0, 0.0, "b"),
        Sample(3.0, 0.0, "b"),
        Sample(1.0, 0.0, "b")
      )
    val trained = Plot(fixture)
      .addLayer(Layer.ecdf(_.position, group = Some(_.group)))
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(policy = Some(LayoutPolicy()), expansion = RangeExpansion.none)
        )
      )
      .fold(error => fail(error.message), identity)
    val output = trained.layers.head.statFrame.rows.collect { case row: StatRow.Ecdf[?] => row }
    val a = output.filter(_.groupLevel.contains("a"))
    val b = output.filter(_.groupLevel.contains("b"))

    assertEquals(a.map(_.position), Vector(1.0, 2.0, 4.0))
    assertEquals(a.map(_.members.length), Vector(1, 2, 1))
    assertEquals(a.map(_.cumulativeCount), Vector(1, 3, 4))
    assertEquals(a.map(_.proportion), Vector(0.25, 0.75, 1.0))
    assertEquals(b.map(_.position), Vector(1.0, 3.0))
    assertEquals(b.map(_.members.length), Vector(1, 2))
    assertEquals(b.map(_.cumulativeCount), Vector(1, 3))
    assertEqualsDouble(b.head.proportion, 1.0 / 3.0, 1e-15)
    assertEquals(b.last.proportion, 1.0)
    assertEquals(trained.layers.head.grobs.count(_.isInstanceOf[Grob.Lines]), 2)
    val paths = trained.layers.head.grobs.collect { case line: Grob.Lines => line.points }
    assertEquals(
      paths.head,
      Vector(
        Point.nativeUnsafe(1.0, 0.0),
        Point.nativeUnsafe(1.0, 0.25),
        Point.nativeUnsafe(2.0, 0.25),
        Point.nativeUnsafe(2.0, 0.75),
        Point.nativeUnsafe(4.0, 0.75),
        Point.nativeUnsafe(4.0, 1.0)
      )
    )
    assertEquals(trained.layout.map(_.yScale), Some(Interval.unsafe(0.0, 1.0)))
  }

  test("ECDF obeys per-group mass and order laws") {
    val fixture = Vector.tabulate(60) { index =>
      Sample((index % 11).toDouble, 0.0, if index % 3 == 0 then "a" else "b")
    }
    val rows = Plot(fixture)
      .addLayer(Layer.ecdf(_.position, group = Some(_.group)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
      .layers
      .head
      .statFrame
      .rows
      .collect { case row: StatRow.Ecdf[?] => row }

    rows.groupBy(_.groupLevel).values.foreach { group =>
      val ordered = group.sortBy(_.position)
      assert(ordered.map(_.position).sliding(2).forall(pair => pair(0) < pair(1)))
      assert(ordered.map(_.proportion).sliding(2).forall(pair => pair(0) < pair(1)))
      assertEquals(ordered.map(_.members.length).sum, ordered.head.totalCount)
      assertEquals(ordered.last.cumulativeCount, ordered.head.totalCount)
      assertEquals(ordered.last.proportion, 1.0)
    }
  }

  test("common statistics reject non-finite mapped values through typed errors") {
    val quantile = Plot(Vector(Sample(1.0, Double.NaN, "a")))
      .addLayer(Layer.quantileSummary(_.position, _.value))
      .flatMap(PlotCompiler.resolve(_))
    assert(quantile.left.toOption.exists {
      case GraphicsError.NonFiniteStatInput("quantile-summary", "y", value) => value.isNaN
      case _                                                                => false
    })

    val ecdf = Plot(Vector(Sample(Double.PositiveInfinity, 1.0, "a")))
      .addLayer(Layer.ecdf(_.position))
      .flatMap(PlotCompiler.resolve(_))
    assertEquals(
      ecdf.left.toOption,
      Some(GraphicsError.NonFiniteStatInput("ecdf", "x", Double.PositiveInfinity))
    )
  }
