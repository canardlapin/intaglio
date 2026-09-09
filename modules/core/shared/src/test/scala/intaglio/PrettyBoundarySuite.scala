package intaglio

class PrettyBoundarySuite extends munit.FunSuite:
  test("sparse pretty grids retain two truthful endpoints after ordinary axis filtering") {
    val extent = 0.49999999999999983
    for
      target <- Vector(2, 3)
      factor <- Vector(1.0e-9, 1.0, 1.0e9)
    do
      val range = Interval.unsafe(-extent * factor, extent * factor)
      val policy = Breaks.pretty(target).toOption.get
      val values = policy.generate(range).toOption.get
      assertEquals(values, Vector(range.lower, range.upper))
      val ticks = Axis.ticks(range, policy).toOption.get
      assertEquals(ticks.map(_.value), values)
      assertEquals(ticks.map(_.label).distinct.size, 2)
      assert(values.forall(range.contains))
  }

  test("sparse fallback respects signed and translated nondegenerate ranges") {
    val ranges = Vector(
      Interval.unsafe(-0.49, 0.49),
      Interval.unsafe(1.07, 1.14),
      Interval.unsafe(-1.14, -1.07)
    )
    ranges.foreach { range =>
      val values = Breaks.prettyUnsafe(2)(range)
      assert(values.size >= 2)
      assert(values.forall(v => v.isFinite && range.contains(v)))
      assertEquals(values, values.distinct.sorted)
    }
  }

  test("ordinary zero-anchored nice grids and explicitly single ticks remain unchanged") {
    assertEquals(Breaks.prettyUnsafe(3)(Interval.unsafe(-0.5, 0.5)), Vector(-0.5, 0.0, 0.5))
    assertEquals(Breaks.prettyUnsafe(1)(Interval.unsafe(-0.49, 0.49)), Vector(0.0))
    assertEquals(Breaks.prettyUnsafe(3)(Interval.unsafe(7.0, 7.0)), Vector(7.0))
  }
