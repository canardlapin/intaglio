package intaglio

class AutomaticDisplayWindowSuite extends munit.FunSuite:
  private def batch(
      values: IterableOnce[Double],
      configs: Vector[AutomaticWindowConfig]
  ): Vector[Either[AutomaticWindowError, AutomaticWindowEstimate]] =
    AutomaticDisplayWindow.estimateMany(values, configs).fold(e => fail(e.message), identity)

  private def estimate(
      values: IterableOnce[Double],
      config: AutomaticWindowConfig = AutomaticWindowConfig()
  ) =
    AutomaticDisplayWindow.estimate(values, config).fold(e => fail(e.message), identity)

  /** Deliberately full-sort reference implementation for selection properties. */
  private def fullSortOracle(
      values: Vector[Double],
      config: AutomaticWindowConfig
  ): Either[AutomaticWindowError, AutomaticWindowEstimate] =
    var nonFinite = 0L
    var excludedZero = 0L
    val eligible = values.filter { value =>
      if !value.isFinite then
        nonFinite += 1
        false
      else if value == 0 && config.domain == DisplaySampleDomain.FiniteNonzero then
        excludedZero += 1
        false
      else true
    }
    if eligible.isEmpty then Left(AutomaticWindowError.NoEligibleValues)
    else
      val sorted = eligible.toArray
      scala.util.Sorting.quickSort(sorted)
      def quantile(probability: Double): Double =
        val position = probability * (sorted.length - 1)
        val index = position.toInt
        val fraction = position - index
        sorted(index) * (1 - fraction) + sorted(math.min(index + 1, sorted.length - 1)) * fraction
      val lower = quantile(config.lowerProbability)
      val upper = quantile(config.upperProbability)
      val constant = lower == upper
      val delta = if lower == 0 then 0.5 else math.abs(lower) * 0.01
      val lo = if constant then lower - delta else lower
      val hi = if constant then upper + delta else upper
      if !lo.isFinite || !hi.isFinite || !(lo < hi) || !(hi - lo).isFinite then
        Left(AutomaticWindowError.UnrepresentableWindow(lo, hi))
      else
        Right(
          AutomaticWindowEstimate(
            DisplayWindow.unsafe(lo, hi),
            config,
            values.length.toLong,
            nonFinite,
            excludedZero,
            eligible.length.toLong,
            eligible.length,
            constant
          )
        )

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
  test("multi-order selection agrees with an independent full-sort oracle") {
    val probabilities = Vector(
      (0.0, 1.0),
      (Double.MinPositiveValue, 0.01),
      (0.125, 0.875),
      (0.499999999999, 0.500000000001),
      (0.99, 1.0)
    )
    val randomLike = Vector.tabulate(513) { index =>
      if index % 23 == 0 then 4.5
      else (((index * 48271) % 104729) - 52364).toDouble / 17.0
    }
    val adversarial = Vector(
      Double.NegativeInfinity,
      Double.NaN,
      Double.PositiveInfinity,
      -0.0,
      0.0,
      -Double.MinPositiveValue,
      Double.MinPositiveValue,
      -Double.MaxValue / 8,
      Double.MaxValue / 8
    ) ++ Vector.fill(37)(-2.0) ++ Vector.fill(41)(4.5)
    val duplicate = Vector.fill(73)(-17.25)
    Vector(DisplaySampleDomain.Finite, DisplaySampleDomain.FiniteNonzero).foreach { domain =>
      val configs = probabilities.map { (lower, upper) =>
        AutomaticWindowConfig(lower, upper, domain, maxSamples = 1024, seed = 91L)
      }
      Vector(randomLike, adversarial, duplicate).foreach { values =>
        val expected = configs.map(fullSortOracle(values, _))
        assertEquals(batch(values, configs), expected)
      }
    }
  }
  test("selection guard keeps adversarial retained samples exact and stack-bounded") {
    val size = 65537
    val config = AutomaticWindowConfig(0.5, 0.98, maxSamples = 131072, seed = 4L)
    val sorted = Vector.tabulate(size)(_.toDouble)
    val reverse = sorted.reverse
    // The first, middle, and last values are repeatedly near the low end, which exhausts the
    // median-of-three depth guard for the median rank and exercises the primitive fallback.
    val organPipe = Vector.tabulate(size)(index => math.min(index, size - 1 - index).toDouble)
    val medianKiller = Vector.tabulate(size) { index =>
      if index < size / 2 then (index * 2).toDouble
      else ((size - 1 - index) * 2 + 1).toDouble
    }
    Vector(sorted, reverse, organPipe, medianKiller).foreach { values =>
      assertEquals(batch(values, Vector(config)), Vector(fullSortOracle(values, config)))
    }
  }
  test("selected ranks preserve full-sort signed-zero endpoint bits") {
    val values = Vector(-0.0, 0.0, -0.0, 0.0, 1.0, -1.0)
    val configs = Vector(
      AutomaticWindowConfig(0.0, 0.25),
      AutomaticWindowConfig(0.25, 0.75),
      AutomaticWindowConfig(0.75, 1.0)
    )
    batch(values, configs).zip(configs.map(fullSortOracle(values, _))).foreach {
      case (Right(actual), Right(expected)) =>
        assertEquals(
          java.lang.Double.doubleToRawLongBits(actual.window.lower),
          java.lang.Double.doubleToRawLongBits(expected.window.lower)
        )
        assertEquals(
          java.lang.Double.doubleToRawLongBits(actual.window.upper),
          java.lang.Double.doubleToRawLongBits(expected.window.upper)
        )
      case (actual, expected) => fail(s"expected comparable windows, got $actual and $expected")
    }
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
    val results = batch(input, configs)
      .map(_.fold(e => fail(e.message), identity))
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
    val configs =
      Vector(0.5, 0.9).map(p => AutomaticWindowConfig(p, 0.98, maxSamples = capacity, seed = seed))
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
    def input: Iterator[Double] =
      (0 until count).iterator.flatMap(i => Iterator(value(i), Double.NaN))
    val results = batch(input, configs)
      .map(_.fold(e => fail(e.message), identity))
    results.zip(configs).foreach { (result, config) =>
      assertEqualsDouble(result.window.lower, oracle(config.lowerProbability), 1e-5)
      assertEqualsDouble(result.window.upper, oracle(config.upperProbability), 1e-5)
      assertEquals(
        (result.observed, result.nonFinite, result.eligible, result.retained),
        (count.toLong * 2, count.toLong, count.toLong, capacity)
      )
      assert(result.sampled)
    }
    assertEquals(results, configs.map(c => estimate(input, c)))
    assertEquals(
      batch(input, configs),
      batch(input, configs)
    )
  }

  test("batch keeps independent constant expansion, signed extremes, refusals and request order") {
    val configs = Vector(AutomaticWindowConfig(0, 0.5), AutomaticWindowConfig(0.5, 1))
    val values = Vector(0.0, 0.0, 0.0, 10.0)
    val results = batch(values, configs)
      .map(_.fold(e => fail(e.message), identity))
    assertEquals(
      (results(0).window.lower, results(0).window.upper, results(0).expandedConstant),
      (-0.5, 0.5, true)
    )
    assertEquals(
      (results(1).window.lower, results(1).window.upper, results(1).expandedConstant),
      (0.0, 10.0, false)
    )
    assertEquals(results, configs.map(c => estimate(values, c)))
    val extremes = Vector(-Double.MaxValue, 0.0, Double.MaxValue)
    val mixed = Vector(
      AutomaticWindowConfig(0.25, 0.75),
      AutomaticWindowConfig(0, 1),
      AutomaticWindowConfig(0.25, 0.75)
    )
    val accepted = batch(extremes, mixed)
    assert(accepted(0).isRight)
    assert(accepted(1).isLeft)
    assertEquals(accepted(0), accepted(2))
    assertEquals(accepted, mixed.map(c => AutomaticDisplayWindow.estimate(extremes, c)))
    assert(batch(Vector(Double.MaxValue), configs).forall(_.isLeft))
  }

  test("batch preserves zero-domain counts and no-eligible refusal for every request") {
    val configs =
      Vector(0.5, 0.9).map(p => AutomaticWindowConfig(p, 0.98, DisplaySampleDomain.FiniteNonzero))
    val values = Vector(0.0, -0.0, Double.NaN, Double.NegativeInfinity, -3.0, 5.0, 7.0)
    val results = batch(values, configs)
      .map(_.fold(e => fail(e.message), identity))
    results.foreach(r =>
      assertEquals((r.observed, r.nonFinite, r.excludedZero, r.eligible), (7L, 2L, 2L, 3L))
    )
    assertEquals(results, configs.map(c => estimate(values, c)))
    assertEquals(
      batch(values.take(4), configs),
      Vector.fill(2)(Left(AutomaticWindowError.NoEligibleValues))
    )
  }

  test("incompatible and empty batches refuse before obtaining an input iterator") {
    val input = new IterableOnce[Double]:
      def iterator: Iterator[Double] = fail("Invalid batch consumed input")
    val base = AutomaticWindowConfig()
    assertEquals(
      AutomaticDisplayWindow.estimateMany(input, Vector.empty).left.toOption,
      Some(AutomaticWindowError.EmptyBatch)
    )
    Vector(
      base.copy(domain = DisplaySampleDomain.FiniteNonzero),
      base.copy(seed = 1),
      base.copy(maxSamples = 20)
    ).foreach { other =>
      assertEquals(
        AutomaticDisplayWindow.estimateMany(input, Vector(base, other)).left.toOption,
        Some(AutomaticWindowError.IncompatibleBatchSampling)
      )
    }
  }
