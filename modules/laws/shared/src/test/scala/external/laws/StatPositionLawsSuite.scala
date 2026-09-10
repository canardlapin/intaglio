package external.laws

import intaglio.laws.*

class StatPositionLawsSuite extends munit.FunSuite:
  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  test("native statistic invariants pass for every reproducible seed") {
    assertValid(NativeStatLaws())
  }

  test("native position invariants pass for every reproducible seed") {
    assertValid(NativePositionLaws())
  }

  test("seeded counterexamples and exceptions retain their replay seeds") {
    val failures = LawSuite(
      "seed-receipts",
      Vector(
        SeededLaw("returned", Vector(17L, 23L)) { seed =>
          if seed == 23L then Vector("synthetic counterexample") else Vector.empty
        },
        SeededLaw("thrown", Vector(29L)) { _ =>
          throw new IllegalStateException("synthetic exception")
        }
      )
    ).failures

    assertEquals(failures.length, 2)
    assertEquals(failures.head.detail, "seed=23: synthetic counterexample")
    assert(failures.last.detail.startsWith("seed=29: threw java.lang.IllegalStateException"))
  }
