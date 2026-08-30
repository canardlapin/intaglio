package intaglio

class Kde2DSuite extends munit.FunSuite:
  private final case class Observation(x: Double, y: Double)

  test("Cartesian-product data factor into independent one-dimensional densities") {
    val xs = Vector(-1.0, 1.0)
    val ys = Vector(-2.0, 2.0)
    val rows = for x <- xs; y <- ys yield Observation(x, y)
    val xDomain = Interval.unsafe(-2.0, 2.0)
    val yDomain = Interval.unsafe(-3.0, 3.0)
    val config = Kde2DConfig.fixedUnsafe(0.5, 0.75, 5, 5, Some(xDomain), Some(yDomain))
    val field = FieldStat
      .kde2D[Observation](_.x, _.y, config)
      .compute(rows)
      .fold(error => fail(error.message), identity)

    var yIndex = 0
    while yIndex < field.height do
      val y = field.yAxis.coordinate(yIndex).get
      var xIndex = 0
      while xIndex < field.width do
        val x = field.xAxis.coordinate(xIndex).get
        val expected = density1D(xs, x, 0.5) * density1D(ys, y, 0.75)
        assertEqualsDouble(field.value(xIndex, yIndex).toOption.get, expected, 1e-15)
        xIndex += 1
      yIndex += 1
  }

  test("KDE is permutation and translation invariant") {
    val rows = Vector(Observation(-1.0, 0.0), Observation(0.5, 1.0), Observation(1.0, -0.5))
    val config = Kde2DConfig.fixedUnsafe(
      0.6,
      0.8,
      9,
      7,
      Some(Interval.unsafe(-3.0, 3.0)),
      Some(Interval.unsafe(-3.0, 3.0))
    )
    val shiftedConfig = Kde2DConfig.fixedUnsafe(
      0.6,
      0.8,
      9,
      7,
      Some(Interval.unsafe(7.0, 13.0)),
      Some(Interval.unsafe(-7.0, -1.0))
    )
    val stat = FieldStat.kde2D[Observation](_.x, _.y, config)
    val original = stat.compute(rows).fold(error => fail(error.message), identity)
    val reversed = stat.compute(rows.reverse).fold(error => fail(error.message), identity)
    val shifted = FieldStat
      .kde2D[Observation](_.x, _.y, shiftedConfig)
      .compute(rows.map(row => Observation(row.x + 10.0, row.y - 4.0)))
      .fold(error => fail(error.message), identity)

    original.samples.zip(reversed.samples).foreach { case (left, right) =>
      assertEqualsDouble(left, right, 1e-15)
    }
    original.samples.zip(shifted.samples).foreach { case (left, right) =>
      assertEqualsDouble(left, right, 1e-15)
    }
  }

  test("a wide vertex grid numerically integrates a Gaussian density to one") {
    val domain = Some(Interval.unsafe(-5.0, 5.0))
    val config = Kde2DConfig.fixedUnsafe(1.0, 1.0, 101, 101, domain, domain)
    val field = FieldStat
      .kde2D[Observation](_.x, _.y, config)
      .compute(Vector(Observation(0.0, 0.0), Observation(0.0, 0.0)))
      .fold(error => fail(error.message), identity)

    val integral = trapezoidIntegral(field)
    assertEqualsDouble(integral, 1.0, 2e-6)
  }

  test("automatic bandwidths produce finite fields and invalid plans fail early") {
    val rows = Vector(
      Observation(-1.0, -2.0),
      Observation(0.0, 1.0),
      Observation(2.0, 3.0),
      Observation(4.0, 2.0)
    )
    val config = Kde2DConfig.automatic(8, 7).fold(error => fail(error.message), identity)
    val field = FieldStat
      .kde2D[Observation](_.x, _.y, config)
      .compute(rows)
      .fold(error => fail(error.message), identity)

    assertEquals(field.samples.length, 56)
    assert(field.samples.forall(value => value.isFinite && value >= 0.0))
    assert(Kde2DConfig.fixed(0.0, 1.0).isLeft)
    assert(Kde2DConfig.automatic(xPoints = 1).isLeft)
    assertEquals(
      FieldStat
        .kde2D[Observation](_.x, _.y, config)
        .compute(Vector(Observation(0.0, 0.0)))
        .left
        .toOption,
      Some(GraphicsError.InsufficientStatData("kde2d", 2, 1))
    )
  }

  private def density1D(values: Vector[Double], position: Double, bandwidth: Double): Double =
    val normalizer = values.length.toDouble * bandwidth * math.sqrt(2.0 * math.Pi)
    values.map { value =>
      val z = (position - value) / bandwidth
      math.exp(-0.5 * z * z)
    }.sum / normalizer

  private def trapezoidIntegral(field: ScalarField2D): Double =
    var sum = 0.0
    var yIndex = 0
    while yIndex < field.height do
      val yWeight = if yIndex == 0 || yIndex == field.height - 1 then 0.5 else 1.0
      var xIndex = 0
      while xIndex < field.width do
        val xWeight = if xIndex == 0 || xIndex == field.width - 1 then 0.5 else 1.0
        sum += field.value(xIndex, yIndex).toOption.get * xWeight * yWeight
        xIndex += 1
      yIndex += 1
    sum * field.xAxis.step * field.yAxis.step
