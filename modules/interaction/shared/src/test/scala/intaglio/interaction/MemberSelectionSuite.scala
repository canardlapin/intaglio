package intaglio.interaction

import intaglio.*

/** Selecting an aggregate's members: a histogram bin selects exactly the observations an
  * independent bucketing oracle assigns to it, only when its membership is exact; selecting the bin
  * itself stays a separate, plot-local selection.
  */
class MemberSelectionSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)

  // Repeated equal values and values on the breaks, each a distinct observation.
  private val data = Vector(
    Obs(1, 0),
    Obs(2, 10),
    Obs(3, 10),
    Obs(4, 10.5),
    Obs(5, 20),
    Obs(6, 33),
    Obs(7, 33),
    Obs(8, 40),
    Obs(9, 12),
    Obs(10, 27)
  )
  private val breaks = Vector(0.0, 10.0, 20.0, 30.0, 40.0)
  private val space = ok(KeySpace("obs", KeyCodec.integer))
  private val context = RenderContext.unsafe(300, 200)

  /** Bins recomputed from raw values by the documented right-closed rule: bin j holds (b(j),
    * b(j+1)], and the first bin also holds b(0).
    */
  private val oracle: Set[Set[Int]] =
    data
      .groupBy { o =>
        if o.x == breaks.head then 0
        else breaks.indices.dropRight(1).find(j => breaks(j) < o.x && o.x <= breaks(j + 1)).get
      }
      .values
      .map(_.map(_.id).toSet)
      .toSet

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
        PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived()),
        retention
      )(_.id)
    )

  private val exact = plan(MembershipRetention.ExactKeys)
  private val counted = plan(MembershipRetention.CountOnly)

  private def domain(p: InteractionPlan[Int]) = ok(InteractionDomain(Vector(p), p.revision))
  private def bins(p: InteractionPlan[Int]): Vector[TargetInfo[Int]] =
    p.groups.flatMap(group => Vector.tabulate(group.size)(i => ok(group.at(i))))

  private var sequence = 0L
  private def reduce(
      state: InteractionState[Int],
      action: InteractionAction[Int]
  ): Either[StateError, InteractionState[Int]] =
    sequence += 1
    InteractionState
      .reduce(
        state,
        InputStamp(state.domain.revision, SemanticId.unsafe("test"), sequence, InputCause.Pointer),
        action
      )
      .map(_.state)

  private def initial(p: InteractionPlan[Int], mode: SelectionMode = SelectionMode.Multiple) =
    ok(InteractionState.initial(domain(p), mode))

  private def ids(state: InteractionState[Int]): Set[Int] = state.selection.entities.map(_.value)

  test("each bin's members, selected, are exactly the observations the oracle assigns to it") {
    val start = initial(exact)
    val selected = bins(exact).map { bin =>
      val state =
        ok(reduce(start, InteractionAction.SelectMembers(Set(bin.id), SelectionOperation.Replace)))
      assertEquals(state.selection.targets, Set.empty[VisualTargetId], "members, not the bin")
      assertEquals(Some(ids(state).size), bin.membership.total)
      ids(state)
    }
    assertEquals(selected.toSet, oracle)
  }

  test("members combine under add, subtract and toggle, and with directly selected marks") {
    val Vector(a, b, _*) = bins(exact): @unchecked
    val keysA = ok(a.membership.exactKeys(exact.sourceRevision)).map(_.value).toSet
    val keysB = ok(b.membership.exactKeys(exact.sourceRevision)).map(_.value).toSet
    val start = initial(exact)
    val one =
      ok(reduce(start, InteractionAction.SelectMembers(Set(a.id), SelectionOperation.Replace)))
    val both = ok(reduce(one, InteractionAction.SelectMembers(Set(b.id), SelectionOperation.Add)))
    assertEquals(ids(both), keysA ++ keysB)
    val less =
      ok(reduce(both, InteractionAction.SelectMembers(Set(a.id), SelectionOperation.Subtract)))
    assertEquals(ids(less), keysB)
    val toggled =
      ok(reduce(less, InteractionAction.SelectMembers(Set(a.id, b.id), SelectionOperation.Toggle)))
    assertEquals(ids(toggled), keysA)
    // A bin selected as a target alongside another bin's members: one change, both kinds.
    val mixed = ok(
      reduce(
        start,
        InteractionAction.SelectMembers(
          Set(a.id),
          SelectionOperation.Replace,
          Selection(targets = Set(b.id))
        )
      )
    )
    assertEquals(ids(mixed), keysA)
    assertEquals(mixed.selection.targets, Set(b.id))
  }

  test("membership that is not exact is refused, never returned as a partial selection") {
    val bin = bins(counted).head
    val start = initial(counted)
    assertEquals(
      reduce(start, InteractionAction.SelectMembers(Set(bin.id), SelectionOperation.Replace))
        .map(ids),
      Left(StateError.MembershipNotExact(MembershipCapability.CountOnly))
    )
    // One inexact target spoils the whole request: nothing is selected.
    val mixedPlans =
      ok(InteractionDomain(Vector(exact, renamedPlan), ok(PlanRevision("both"))))
    val state = ok(InteractionState.initial(mixedPlans))
    val request = Set(bins(exact).head.id, bins(renamedPlan).head.id)
    assert(
      reduce(state, InteractionAction.SelectMembers(request, SelectionOperation.Replace)).isLeft
    )
  }

  /** A count-only plan under another plan id, so it can share a domain with the exact one. */
  private lazy val renamedPlan: InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(
        ok(
          Plot(data).addLayer(Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(breaks)))
        ),
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe("hist-count"),
        ok(PlanRevision("p")),
        PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
      )(_.id)
    )

  test("single selection refuses a bin with several members; unknown targets are refused") {
    val crowded = bins(exact).find(_.membership.total.exists(_ > 1)).get
    assertEquals(
      reduce(
        initial(exact, SelectionMode.Single),
        InteractionAction.SelectMembers(Set(crowded.id), SelectionOperation.Replace)
      ),
      Left(StateError.MultipleSelectionInSingleMode)
    )
    val foreign = bins(renamedPlan).head.id
    assertEquals(
      reduce(
        initial(exact),
        InteractionAction.SelectMembers(Set(foreign), SelectionOperation.Replace)
      ),
      Left(StateError.UnknownTarget(foreign))
    )
  }

  private val picking = ok(Picking.compile(exact, context))
  private val navigation = picking.prepareNavigation()
  private val viewport = ok(PickViewport.fit(300, 200, 0, 0, 300, 200))
  private def host(behavior: InteractionBehavior[Int]) =
    HostInput(picking, navigation, viewport, behavior)
  private def run(state: InteractionState[Int], actions: Vector[HostAction[Int]]) =
    actions.foldLeft(state)((current, a) => ok(reduce(current, a.action)))

  test("a click on a bin selects the bin by default, and its members in Members mode") {
    val target = navigation.targets.head
    val (x, y) = (target.anchor.x, target.anchor.y)
    val start = initial(exact)
    val byDefault = run(
      start,
      ok(
        host(InteractionBehavior.default[Int])
          .pointer(start, PointerInput.Click(x, y, additive = false))
      )
    )
    assertEquals(byDefault.selection.targets, Set(target.target.id))
    assertEquals(byDefault.selection.entities, Set.empty[EntityKey[Int]])
    val members =
      InteractionBehavior.default[Int].withAggregateSelection(_ => AggregateSelection.Members)
    val chosen =
      run(start, ok(host(members).pointer(start, PointerInput.Click(x, y, additive = false))))
    assertEquals(chosen.selection.targets, Set.empty[VisualTargetId])
    assertEquals(
      ids(chosen),
      ok(target.target.membership.exactKeys(exact.sourceRevision)).map(_.value).toSet
    )
  }

  test("a sweep over bins in Members mode selects the union of their members in one change") {
    val area = ok(PickArea.rectangle(0, 0, 300, 200))
    val members =
      InteractionBehavior.default[Int].withAggregateSelection(_ => AggregateSelection.Members)
    val actions =
      host(members).region(initial(exact), area, AreaRule.Intersecting, SelectionOperation.Replace)
    assertEquals(actions.size, 1)
    val swept = run(initial(exact), actions)
    assertEquals(ids(swept), data.map(_.id).toSet)
  }
