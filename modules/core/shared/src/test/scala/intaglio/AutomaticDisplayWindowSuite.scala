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
