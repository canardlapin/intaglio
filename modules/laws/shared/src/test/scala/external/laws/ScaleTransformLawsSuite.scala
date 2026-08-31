package external.laws

import intaglio.*
import intaglio.laws.*

class ScaleTransformLawsSuite extends munit.FunSuite:
  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  test("built-in transforms satisfy round-trip, monotonicity, and endpoint laws") {
    val fixtures = Vector(
      TransformLaws(
        Transform.identity,
        Vector(-10.0, -1.0, 0.0, 4.0),
        TransformMonotonicity.Increasing
      ),
      TransformLaws(
        Transform.reverse,
        Vector(-10.0, -1.0, 0.0, 4.0),
        TransformMonotonicity.Decreasing
      ),
      TransformLaws(
        Transform.log10,
        Vector(0.01, 0.1, 1.0, 100.0),
        TransformMonotonicity.Increasing
      ),
      TransformLaws(
        Transform.sqrt,
        Vector(0.0, 1.0, 4.0, 100.0),
        TransformMonotonicity.Increasing
      )
    )

    fixtures.foreach(assertValid)
  }

  test("transform laws report incorrect inverse and monotonicity claims") {
    val brokenInverse = Transform
      .apply("broken-inverse", _ * 2.0, identity)
      .fold(error => fail(error.message), identity)
    val inverseFailures = TransformLaws(
      brokenInverse,
      Vector(1.0, 2.0, 3.0),
      TransformMonotonicity.Increasing
    ).failures
    val directionFailures = TransformLaws(
      Transform.identity,
      Vector(1.0, 2.0, 3.0),
      TransformMonotonicity.Decreasing
    ).failures

    assert(inverseFailures.exists(_.law == "round trip"))
    assert(directionFailures.exists(_.law == "monotonicity"))
  }

  test("continuous training is associative, permutation invariant, and endpoint preserving") {
    assertValid(
      ContinuousScaleTrainingLaws(
        Vector(
          Vector(2.0, Double.NaN, 8.0),
          Vector(-1.0, Double.PositiveInfinity),
          Vector(4.0, 16.0)
        )
      )
    )
    assertValid(
      ContinuousScaleTrainingLaws(
        Vector(
          Vector(1.0, 10.0),
          Vector(100.0, 0.0),
          Vector(1000.0)
        ),
        transform = Transform.log10
      )
    )
    assertValid(OobPolicyLaws())
  }

  test("every built-in fixed scale family ignores later observations") {
    val continuous = ContinuousScale
      .fixed("fixed-x", Vector(0.0, 10.0), Palette.numeric)
      .fold(error => fail(error.message), identity)
    val domain = DiscreteDomain
      .ordered(Vector("declared-a", "declared-b"))
      .fold(error => fail(error.message), identity)
    val discrete = DiscreteScale
      .fixed("fixed-color", domain, DiscretePalette.indices)
      .fold(error => fail(error.message), identity)
    val band = BandScale
      .fixed("fixed-position", domain)
      .fold(error => fail(error.message), identity)
    val dateLimits = Vector(
      CalendarDate.unsafe(2025, 1, 1),
      CalendarDate.unsafe(2025, 1, 31)
    )
    val date = DateScale
      .fixed("fixed-date", dateLimits)
      .fold(error => fail(error.message), identity)
    val dateTimeLimits = Vector(
      UtcDateTime.unsafe(0L),
      UtcDateTime.unsafe(1000L)
    )
    val dateTime = DateTimeScale
      .fixed("fixed-date-time", dateTimeLimits)
      .fold(error => fail(error.message), identity)

    assertValid(FixedScaleLaws(continuous, Vector(-5.0, 5.0, 20.0)))
    assertValid(FixedScaleLaws(discrete, Vector("declared-a", "novel")))
    assertValid(FixedScaleLaws(band, Vector("declared-b", "novel")))
    assertValid(FixedScaleLaws(date, Vector(CalendarDate.unsafe(2025, 2, 1))))
    assertValid(FixedScaleLaws(dateTime, Vector(UtcDateTime.unsafe(2000L))))
  }

  test("fixed-scale applicability fails closed for a plot-wide scale") {
    val plotWide = ContinuousScale
      .train("plot-wide", Vector(0.0, 1.0), Palette.numeric)
      .fold(error => fail(error.message), identity)
    val failures = FixedScaleLaws(plotWide, Vector(2.0)).failures

    assert(failures.exists(_.law == "fixed applicability"))
  }

  test("ordered domains execute encounter-order but not permutation laws") {
    val suite = DiscreteDomainLaws.ordered(
      declared = Vector("declared"),
      batches = Vector(Vector("third", "declared"), Vector("second", "third"))
    )

    assertValid(suite)
    assert(suite.laws.exists(_.name == "encounter order"))
    assert(!suite.laws.exists(_.name == "permutation invariance"))
  }

  test("unordered domains execute permutation but not encounter-order laws") {
    val suite = DiscreteDomainLaws.unordered(
      declared = Vector("declared"),
      batches = Vector(Vector("third", "declared"), Vector("second", "third"))
    )

    assertValid(suite)
    assert(suite.laws.exists(_.name == "permutation invariance"))
    assert(!suite.laws.exists(_.name == "encounter order"))
  }
