package intaglio

class Bin2DSuite extends munit.FunSuite:
  private final case class Observation(x: Double, y: Double)

  private val domain = Some(Interval.unsafe(0.0, 2.0))
  private val config = Bin2DConfig.unsafe(2, 2, domain, domain)
  private val rows =
    Vector(
      Observation(0.0, 0.0),
      Observation(1.0, 1.0),
      Observation(1.5, 1.5),
      Observation(2.0, 2.0)
    )

  test("rectangular binning is right-closed and conserves observations") {
    val field = FieldStat
      .bin2D[Observation](_.x, _.y, config)
      .compute(rows)
      .fold(error => fail(error.message), identity)

    assertEquals(field.samples, Vector(2.0, 0.0, 0.0, 2.0))
    assertEqualsDouble(field.samples.sum, rows.length.toDouble, 0.0)
    assertEquals(field.xAxis.sampling, GridSampling.CellCentered)
    assertEquals(field.yAxis.sampling, GridSampling.CellCentered)
  }

  test("binning is permutation invariant") {
    val stat = FieldStat.bin2D[Observation](_.x, _.y, config)
    val forward = stat.compute(rows).fold(error => fail(error.message), identity)
    val reverse = stat.compute(rows.reverse).fold(error => fail(error.message), identity)

    assertEquals(reverse, forward)
  }

  test("proportions form a probability mass over the grid") {
    val probabilityConfig = Bin2DConfig.unsafe(2, 2, domain, domain, Bin2DValue.Proportion)
    val field = FieldStat
      .bin2D[Observation](_.x, _.y, probabilityConfig)
      .compute(rows)
      .fold(error => fail(error.message), identity)

    assertEquals(field.samples, Vector(0.5, 0.0, 0.0, 0.5))
    assertEqualsDouble(field.samples.sum, 1.0, 1e-15)
  }

  test("automatic domains remain valid for constant coordinates") {
    val field = FieldStat
      .bin2D[Observation](_.x, _.y, Bin2DConfig.unsafe(2, 2))
      .compute(Vector.fill(3)(Observation(4.0, -2.0)))
      .fold(error => fail(error.message), identity)

    assertEquals(field.xAxis.domain, Interval.unsafe(3.5, 4.5))
    assertEquals(field.yAxis.domain, Interval.unsafe(-2.5, -1.5))
    assertEqualsDouble(field.samples.sum, 3.0, 0.0)
  }

  test("configuration and computation expose typed boundary failures") {
    assert(Bin2DConfig(xBins = 0).isLeft)
    assert(Bin2DConfig(xBins = Int.MaxValue, yBins = 2).isLeft)
    assertEquals(
      FieldStat.bin2D[Observation](_.x, _.y).compute(Vector.empty).left.toOption,
      Some(GraphicsError.InsufficientStatData("bin2d", 1, 0))
    )

    val nonFinite =
      FieldStat.bin2D[Observation](_.x, _.y).compute(Vector(Observation(Double.NaN, 0.0)))
    nonFinite.left.toOption match
      case Some(GraphicsError.NonFiniteStatInput("bin2d", "x", value)) => assert(value.isNaN)
      case other => fail(s"expected non-finite bin2d input, found $other")

    assertEquals(
      FieldStat
        .bin2D[Observation](_.x, _.y, config)
        .compute(Vector(Observation(3.0, 1.0)))
        .left
        .toOption,
      Some(GraphicsError.StatInputOutsideGrid("bin2d", "x", 3.0, 0.0, 2.0))
    )
  }
