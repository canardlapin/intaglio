package intaglio.interaction

import intaglio.*

/** Partial-selection coverage: how many of a bin's exact members a linked selection holds, checked
  * against an independent bucketing oracle, and the any/all/fraction rules that turn coverage into
  * emphasis. Membership that is not exact never yields a count.
  */
class MemberCoverageSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)

  private val data = Vector.tabulate(30)(i => Obs(i + 1, (i * 13 % 40).toDouble + 0.25))
  private val breaks = Vector(0.0, 10.0, 20.0, 30.0, 40.0)
  private val space = ok(KeySpace("obs", KeyCodec.integer))

  private def plan(retention: MembershipRetention): InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(
        ok(
          Plot(data).addLayer(Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(breaks)))
        ),
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe("hist"),
        ok(PlanRevision("p")),
        retention = retention
      )(_.id)
    )
  private val exact = plan(MembershipRetention.ExactKeys)
  private val domain = ok(InteractionDomain(Vector(exact), exact.revision))
  private def bins(p: InteractionPlan[Int]) =
    p.groups.flatMap(group => Vector.tabulate(group.size)(i => ok(group.at(i))))
  private def keys(ids: Iterable[Int]) = ids.map(i => ok(space.entity(i))).toSet

  /** Independently: which observations fall in (lower, lower + 10]. */
  private def members(lower: Double): Set[Int] =
    data.filter(o => o.x > lower && o.x <= lower + 10).map(_.id).toSet

  test("each bin's coverage of a linked selection matches the oracle's intersection") {
    // A selection such as a lasso in a linked scatter would project: every third observation.
    val selected = data.map(_.id).filter(_ % 3 == 0).toSet
    val coverage = bins(exact).map(bin => MemberCoverage.of(bin, keys(selected), domain)).toSet
    val expected = breaks
      .dropRight(1)
      .map(lower => members(lower))
      .filter(_.nonEmpty)
      .map { m =>
        MemberCoverage.Known((m intersect selected).size, m.size)
      }
      .toSet
    assertEquals(coverage, expected)
  }

  test("coverage of membership that is not exact is unknown, never zero of n") {
    val counted = plan(MembershipRetention.CountOnly)
    val countedDomain = ok(InteractionDomain(Vector(counted), counted.revision))
    bins(counted).foreach { bin =>
      assertEquals(
        MemberCoverage.of(bin, keys(data.map(_.id)), countedDomain),
        MemberCoverage.Unknown(MembershipCapability.CountOnly)
      )
      assert(
        !EmphasisRule.AnyMember.triggered(
          MemberCoverage.of(bin, keys(data.map(_.id)), countedDomain)
        )
      )
    }
  }

  test("any, all and fraction rules trigger exactly at their thresholds") {
    val known = (s: Int) => MemberCoverage.Known(s, 4)
    assert(!EmphasisRule.AnyMember.triggered(known(0)))
    assert(EmphasisRule.AnyMember.triggered(known(1)))
    assert(!EmphasisRule.AllMembers.triggered(known(3)))
    assert(EmphasisRule.AllMembers.triggered(known(4)))
    val half = ok(EmphasisRule.fraction(0.5))
    assert(!half.triggered(known(1)))
    assert(half.triggered(known(2)), "exactly the fraction triggers")
    assert(
      !EmphasisRule.AllMembers.triggered(MemberCoverage.Known(0, 0)),
      "an empty aggregate never"
    )
    Vector(0.0, -0.1, 1.5, Double.NaN).foreach(p =>
      assert(EmphasisRule.fraction(p).isLeft, p.toString)
    )
    assertEquals(ok(EmphasisRule.fraction(1.0)), ok(EmphasisRule.fraction(1.0)))
    assert(!half.triggered(MemberCoverage.Stale))
  }

  test("linked emphasis covers an aggregate by rule, and keyed marks by key as before") {
    val bin = bins(exact).maxBy(_.membership.total.getOrElse(0))
    val all = ok(bin.membership.exactKeys(exact.sourceRevision)).toSet
    val some = LinkedEmphasis(entities = all.take(1))
    assert(some.covers(bin, EmphasisRule.AnyMember, domain))
    assert(!some.covers(bin, EmphasisRule.AllMembers, domain))
    assert(LinkedEmphasis(entities = all).covers(bin, EmphasisRule.AllMembers, domain))
    assert(!LinkedEmphasis.none[Int].covers(bin, EmphasisRule.AnyMember, domain))
  }
