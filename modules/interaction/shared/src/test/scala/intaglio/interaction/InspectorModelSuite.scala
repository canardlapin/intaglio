package intaglio.interaction

import intaglio.*

/** The inspector separates what a selection means: observations, selected marks and aggregates,
  * coverage of each aggregate (checked against an independent bucketing), exact members of selected
  * aggregates, uncountable aggregates, unresolved keys and saved selections.
  */
class InspectorModelSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)
  private val data = Vector.tabulate(12)(i => Obs(i + 1, i * 2.5 + 0.5))
  private val breaks = Vector(0.0, 10.0, 20.0, 30.0)
  private val space = ok(KeySpace("obs", KeyCodec.integer))

  private def compile(
      plot: Plot[Obs],
      id: String,
      retention: MembershipRetention,
      rev: String = "d"
  ) =
    ok(
      InteractionCompiler.compile(
        plot,
        space,
        ok(DataRevision(rev)),
        SemanticId.unsafe(id),
        ok(PlanRevision(rev)),
        retention = retention
      )(_.id)
    )
  private def histogram(rows: Vector[Obs]) =
    ok(Plot(rows).addLayer(Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(breaks))))
  private val points = compile(
    ok(Plot(data).addLayer(Layer.point[Obs](_.x, _.x))),
    "points",
    MembershipRetention.CountOnly
  )
  private val exact = compile(histogram(data), "exact", MembershipRetention.ExactKeys)
  private val counted = compile(histogram(data), "counted", MembershipRetention.CountOnly)
  private val domain = ok(
    InteractionDomain(Vector(points, exact, counted), ok(PlanRevision("all")))
  )
  private def infos(p: InteractionPlan[Int]) =
    p.groups.flatMap(g => Vector.tabulate(g.size)(i => ok(g.at(i))))
  private def key(i: Int) = ok(space.entity(i))

  /** Independently: observations in (b(j), b(j+1)]. */
  private def bin(j: Int) =
    data.filter(o => o.x > breaks(j) && o.x <= breaks(j + 1)).map(_.id).toSet

  private var sequence = 0L
  private def run(state: InteractionState[Int], action: InteractionAction[Int]) =
    sequence += 1
    ok(
      InteractionState.reduce(
        state,
        InputStamp(
          state.domain.revision,
          SemanticId.unsafe("t"),
          sequence,
          InputCause.Programmatic
        ),
        action
      )
    ).state

  test("coverage, selected targets, exact members and uncountable aggregates are kept apart") {
    val chosen = Set(1, 2, 5, 9)
    val firstBin = infos(exact).head
    val state = run(
      ok(InteractionState.initial(domain)),
      InteractionAction.Select(
        Selection(chosen.map(key), Set(firstBin.id)),
        SelectionOperation.Replace
      )
    )
    val model = InspectorModel.of(state)
    assertEquals(model.observations, 4)
    assertEquals(model.targets.map(_.id), Vector(firstBin.id))
    assertEquals(model.targets.head.entity, None, "a bin is an aggregate, not a mark")
    assertEquals(model.targets.head.capability, MembershipCapability.Exact)
    // Every exact bin the selection reaches, with the oracle's counts.
    val expected = breaks.indices.dropRight(1).map(bin).filter(_.nonEmpty).collect {
      case members if (members intersect chosen).nonEmpty =>
        MemberCoverage.Known((members intersect chosen).size, members.size)
    }
    assertEquals(model.aggregates.map(_.coverage).toSet, expected.toSet)
    val selectedBin = model.aggregates.find(_.id == firstBin.id).get
    assertEquals(selectedBin.members.map(_.value).toSet, bin(0))
    assertEquals(selectedBin.memberCount, Some(bin(0).size))
    // The count-only histogram's bins cannot be counted; they are reported as such, never as zero.
    assertEquals(model.uncountedAggregates, infos(counted).size)
    assert(model.aggregates.forall(_.id.plan.value != "counted"))
  }

  test("saved selections and unresolved keys are listed") {
    val start = ok(InteractionState.initial(domain))
    val withSaved = run(
      run(
        start,
        InteractionAction.Select(Selection(Set(key(1), key(2))), SelectionOperation.Replace)
      ),
      InteractionAction.SaveSelection(SelectionName.unsafe("pair"))
    )
    assertEquals(InspectorModel.of(withSaved).named, Vector((SelectionName.unsafe("pair"), 2, 0)))
    // New data without observation 1, preserving the selection: 1 is unresolved.
    val fewer = compile(
      ok(Plot(data.tail).addLayer(Layer.point[Obs](_.x, _.x))),
      "points",
      MembershipRetention.CountOnly,
      "d2"
    )
    sequence += 1
    val replaced = ok(
      InteractionState.replaceDomain(
        withSaved,
        InputStamp(
          withSaved.domain.revision,
          SemanticId.unsafe("t"),
          sequence,
          InputCause.Programmatic
        ),
        ok(InteractionDomain(Vector(fewer), ok(PlanRevision("fewer")))),
        MissingEntityPolicy.Preserve
      )
    ).state
    assertEquals(InspectorModel.of(replaced).unresolved, Vector(key(1)))
  }
