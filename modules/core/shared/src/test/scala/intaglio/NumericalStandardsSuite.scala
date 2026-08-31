package intaglio

class NumericalStandardsSuite extends munit.FunSuite:
  private final case class Observation(position: Double, value: Double)

  test("summary moments retain a small term across catastrophic cancellation") {
    val rows = Vector(
      Observation(0.0, 1.0e16),
      Observation(0.0, 1.0),
      Observation(0.0, -1.0e16)
    )
    val result = Stat
      .Summary[Observation](_.position, _.value)
      .compute(
        StatBatch(rows, AesSpec.empty),
        StatContext(0, Geom.Point, StatScope.Plot)
      )
      .fold(error => fail(error.message), identity)
    val summary = result.rows.head

    assertEqualsDouble(summary.mean, 1.0 / 3.0, 1e-16)
    assertEquals(summary.lower, summary.mean - 1.0e16 / math.sqrt(3.0))
    assertEquals(summary.upper, summary.mean + 1.0e16 / math.sqrt(3.0))
    assertEquals(summary.count, 3)
  }

  test("automatic density bandwidth is stable under a large translation") {
    val values = Array(-2.0, -0.25, 0.5, 1.25, 4.0)
    val translated = values.map(_ + 1.0e9)

    assertEqualsDouble(DensityMath.nrd0(translated), DensityMath.nrd0(values), 2e-9)
  }

  test("histogram density conserves mass under the documented right-closed partition") {
    val breaks = Vector(-3.0, -1.0, 0.5, 4.0)
    val values = Vector(-3.0, -2.0, -1.0, 0.0, 0.5, 1.0, 4.0)
    val result = Stat
      .Bin[Double](identity, HistogramBins.breaksUnsafe(breaks))
      .compute(
        StatBatch(values, AesSpec.empty),
        StatContext(0, Geom.Bar, StatScope.Plot)
      )
      .fold(error => fail(error.message), identity)
    val rows = result.rows
    val mass = rows.map(row => row.density * row.binWidth).sum

    assertEquals(rows.map(_.count), Vector(3, 2, 2))
    assertEquals(rows.map(_.members), Vector(values.take(3), values.slice(3, 5), values.drop(5)))
    assertEqualsDouble(mass, 1.0, 1e-15)
  }

  test("wide-grid one-dimensional density meets the documented normalization tolerance") {
    val values = Vector(-1.5, -0.25, 0.75, 2.0)
    val config = DensityConfig.fixedUnsafe(
      bandwidth = 0.6,
      points = 2049,
      domain = Some(Interval.unsafe(-8.0, 8.0))
    )
    val result = Stat
      .Density[Double](identity, config)
      .compute(
        StatBatch(values, AesSpec.empty),
        StatContext(0, Geom.Line, StatScope.Plot)
      )
      .fold(error => fail(error.message), identity)
    val integral = result.rows.sliding(2).foldLeft(0.0) {
      case (sum, Vector(left, right)) =>
        sum + (left.density + right.density) * 0.5 * (right.position - left.position)
      case (sum, _) => sum
    }

    assertEqualsDouble(integral, 1.0, 1e-6)
  }

  test("KDE strategy is explicit and unavailable FFT plans fail through typed errors") {
    assertEquals(DensityConfig.default.strategy, KdeStrategy.Direct)
    assertEquals(Kde2DConfig.default.strategy, KdeStrategy.Direct)

    val oneDimensional = DensityConfig.fixedUnsafe(
      bandwidth = 1.0,
      points = 8,
      strategy = KdeStrategy.Fft
    )
    val oneDimensionalResult = Stat
      .Density[Double](identity, oneDimensional)
      .compute(
        StatBatch(Vector(0.0, 1.0), AesSpec.empty),
        StatContext(0, Geom.Line, StatScope.Plot)
      )
    assertEquals(oneDimensionalResult.left.toOption, Some(StatError.UnsupportedStrategy("fft")))

    val twoDimensional = Kde2DConfig.fixedUnsafe(
      bandwidthX = 1.0,
      bandwidthY = 1.0,
      xPoints = 8,
      yPoints = 8,
      strategy = KdeStrategy.Fft
    )
    val twoDimensionalResult = FieldStat
      .kde2D[Observation](_.position, _.value, twoDimensional)
      .compute(Vector(Observation(0.0, 0.0), Observation(1.0, 1.0)))
    assertEquals(
      twoDimensionalResult.left.toOption,
      Some(GraphicsError.UnsupportedStatStrategy("kde2d", "fft"))
    )
  }
