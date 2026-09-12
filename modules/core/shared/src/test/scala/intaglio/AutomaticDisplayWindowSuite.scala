package intaglio

class AutomaticDisplayWindowSuite extends munit.FunSuite:
  private def estimate(
      values: IterableOnce[Double],
      config: AutomaticWindowConfig = AutomaticWindowConfig()
  ) =
    AutomaticDisplayWindow.estimate(values, config).fold(e => fail(e.message), identity)

  test("exact quantiles exclude declared zero background without discarding negative values") {
    val values =
      Vector.fill(100)(0.0) ++ Vector(-10.0, -5.0, 5.0, 10.0, Double.NaN, Double.PositiveInfinity)
    val result =
      estimate(values, AutomaticWindowConfig(0.25, 0.75, DisplaySampleDomain.FiniteNonzero))
    assertEqualsDouble(result.window.lower, -6.25, 0)
    assertEqualsDouble(result.window.upper, 6.25, 0)
    assertEquals(
      (result.observed, result.nonFinite, result.excludedZero, result.eligible, result.retained),
      (106L, 2L, 100L, 4L, 4)
    )
    assert(!result.sampled)
  }
  test("finite-domain zeros are data and constant windows have explicit expansion") {
    val zero = estimate(Vector.fill(10)(0.0))
    assertEquals((zero.window.lower, zero.window.upper), (-0.5, 0.5))
    assert(zero.expandedConstant)
    val negative = estimate(Vector.fill(10)(-20.0))
    assertEqualsDouble(negative.window.lower, -20.2, 1e-12)
    assertEqualsDouble(negative.window.upper, -19.8, 1e-12)
    assert(
      AutomaticDisplayWindow
        .estimate(
          Vector(0.0, Double.NaN),
          AutomaticWindowConfig(domain = DisplaySampleDomain.FiniteNonzero)
        )
        .isLeft
    )
  }
  test("single outlier does not dictate a 2-98 percent display window") {
    val result = estimate((1 to 100).map(_.toDouble) :+ 1000000.0)
    assertEqualsDouble(result.window.lower, 3.0, 0)
    assertEqualsDouble(result.window.upper, 99.0, 0)
  }
  test("full-sample windows commute with affine rescaling and reverse under sign change") {
    val values = Vector(-11.0, -3.0, 2.0, 6.0, 19.0)
    val base = estimate(values).window
    val shifted = estimate(values.map(_ * 3 + 7)).window
    assertEqualsDouble(shifted.lower, base.lower * 3 + 7, 1e-12)
    assertEqualsDouble(shifted.upper, base.upper * 3 + 7, 1e-12)
    val reversed = estimate(values.map(-_)).window
    assertEqualsDouble(reversed.lower, -base.upper, 1e-12)
    assertEqualsDouble(reversed.upper, -base.lower, 1e-12)
  }
  test(
    "reservoir is bounded, deterministic, and admits late input rather than retaining a prefix"
  ) {
    val config = AutomaticWindowConfig(maxSamples = 1024)
    val first = estimate((1 to 100000).iterator.map(_.toDouble), config)
    val second = estimate((1 to 100000).iterator.map(_.toDouble), config)
    assertEquals(first, second)
    assertEquals(first.retained, 1024)
    assertEquals(first.eligible, 100000L)
    assert(first.sampled)
    assert(first.window.upper > 90000 && first.window.lower < 10000)
  }
  test("invalid settings, empty streams and unrepresentable finite widths are explicit failures") {
    intercept[IllegalArgumentException](AutomaticWindowConfig(0.5, 0.5))
    intercept[IllegalArgumentException](AutomaticWindowConfig(maxSamples = 1))
    assert(AutomaticDisplayWindow.estimate(Vector.empty[Double]).isLeft)
    assert(
      AutomaticDisplayWindow
        .estimate(Vector(-Double.MaxValue, Double.MaxValue), AutomaticWindowConfig(0, 1))
        .isLeft
    )
  }

  test("batch consumes a one-shot 206000-value input once and shares exact p50 p90 p98") {
    val configs = Vector(0.5, 0.9).map(p => AutomaticWindowConfig(p, 0.98, maxSamples = 262144))
    var iterators = 0
    var visits = 0
    val input = new IterableOnce[Double]:
      def iterator: Iterator[Double] =
        iterators += 1
        require(iterators == 1, "Input was traversed twice")
        (1 to 206000).iterator.map { i => visits += 1; i.toDouble }
    val results = AutomaticDisplayWindow.estimateMany(input, configs).map(_.fold(e => fail(e.message), identity))
    assertEquals((iterators, visits), (1, 206000))
    // Independent order statistics of the consecutive integers, using r = p * (n-1).
    assertEqualsDouble(results(0).window.lower, 103000.5, 1e-9)
    assertEqualsDouble(results(1).window.lower, 185400.1, 1e-9)
    results.foreach { result =>
      assertEqualsDouble(result.window.upper, 201880.02, 1e-9)
      assertEquals((result.observed, result.eligible, result.retained), (206000L, 206000L, 206000))
      assert(!result.sampled)
    }
    assertEquals(results, configs.map(c => estimate((1 to 206000).iterator.map(_.toDouble), c)))
  }

  test("sampled windows share a deterministic reservoir with independent retained-index oracle") {
    val count = 206000
    val capacity = 257
    val seed = 731L
    val configs = Vector(0.5, 0.9).map(p => AutomaticWindowConfig(p, 0.98, maxSamples = capacity, seed = seed))
    // Sample indices separately from values. Interspersed ineligible values must neither enter
    // the reservoir nor advance its RNG. This oracle does not call the estimator for quantiles.
    val random = new scala.util.Random(seed)
    var indices = Vector.tabulate(capacity)(identity)
    for i <- capacity until count do
      val selected = random.nextLong(i.toLong + 1)
      if selected < capacity then indices = indices.updated(selected.toInt, i)
    def value(i: Int): Double = (i.toLong * i + 1).toDouble
    val ordered = indices.map(value).sorted
    def oracle(p: Double): Double =
      val rank = BigDecimal(p.toString) * (capacity - 1)
      val lower = rank.toInt
      val fraction = rank - lower
      (BigDecimal(ordered(lower)) * (1 - fraction) +
        BigDecimal(ordered(math.min(lower + 1, capacity - 1))) * fraction).toDouble
    def input: Iterator[Double] = (0 until count).iterator.flatMap(i => Iterator(value(i), Double.NaN))
    val results = AutomaticDisplayWindow.estimateMany(input, configs).map(_.fold(e => fail(e.message), identity))
    results.zip(configs).foreach { (result, config) =>
      assertEqualsDouble(result.window.lower, oracle(config.lowerProbability), 1e-5)
      assertEqualsDouble(result.window.upper, oracle(config.upperProbability), 1e-5)
      assertEquals((result.observed, result.nonFinite, result.eligible, result.retained),
        (count.toLong * 2, count.toLong, count.toLong, capacity))
      assert(result.sampled)
    }
    assertEquals(results, configs.map(c => estimate(input, c)))
    assertEquals(AutomaticDisplayWindow.estimateMany(input, configs),
      AutomaticDisplayWindow.estimateMany(input, configs))
  }

  test("batch keeps independent constant expansion, signed extremes, refusals and request order") {
    val configs = Vector(AutomaticWindowConfig(0, 0.5), AutomaticWindowConfig(0.5, 1))
    val values = Vector(0.0, 0.0, 0.0, 10.0)
    val results = AutomaticDisplayWindow.estimateMany(values, configs).map(_.fold(e => fail(e.message), identity))
    assertEquals((results(0).window.lower, results(0).window.upper, results(0).expandedConstant), (-0.5, 0.5, true))
    assertEquals((results(1).window.lower, results(1).window.upper, results(1).expandedConstant), (0.0, 10.0, false))
    assertEquals(results, configs.map(c => estimate(values, c)))
    val extremes = Vector(-Double.MaxValue, 0.0, Double.MaxValue)
    val mixed = Vector(AutomaticWindowConfig(0.25, 0.75), AutomaticWindowConfig(0, 1), AutomaticWindowConfig(0.25, 0.75))
    val accepted = AutomaticDisplayWindow.estimateMany(extremes, mixed)
    assert(accepted(0).isRight)
    assert(accepted(1).isLeft)
    assertEquals(accepted(0), accepted(2))
    assertEquals(accepted, mixed.map(c => AutomaticDisplayWindow.estimate(extremes, c)))
    assert(AutomaticDisplayWindow.estimateMany(Vector(Double.MaxValue), configs).forall(_.isLeft))
  }

  test("batch preserves zero-domain counts and no-eligible refusal for every request") {
    val configs = Vector(0.5, 0.9).map(p => AutomaticWindowConfig(p, 0.98, DisplaySampleDomain.FiniteNonzero))
    val values = Vector(0.0, -0.0, Double.NaN, Double.NegativeInfinity, -3.0, 5.0, 7.0)
    val results = AutomaticDisplayWindow.estimateMany(values, configs).map(_.fold(e => fail(e.message), identity))
    results.foreach(r => assertEquals((r.observed, r.nonFinite, r.excludedZero, r.eligible), (7L, 2L, 2L, 3L)))
    assertEquals(results, configs.map(c => estimate(values, c)))
    assertEquals(AutomaticDisplayWindow.estimateMany(values.take(4), configs),
      Vector.fill(2)(Left(AutomaticWindowError.NoEligibleValues)))
  }

  test("incompatible and empty batches refuse before obtaining an input iterator") {
    val input = new IterableOnce[Double]:
      def iterator: Iterator[Double] = fail("Invalid batch consumed input")
    val base = AutomaticWindowConfig()
    intercept[IllegalArgumentException](AutomaticDisplayWindow.estimateMany(input, Vector.empty))
    Vector(base.copy(domain = DisplaySampleDomain.FiniteNonzero), base.copy(seed = 1),
      base.copy(maxSamples = 20)).foreach { other =>
      intercept[IllegalArgumentException](AutomaticDisplayWindow.estimateMany(input, Vector(base, other)))
    }
  }
