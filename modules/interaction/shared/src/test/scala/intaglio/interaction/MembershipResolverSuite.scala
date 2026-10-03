package intaglio.interaction

import intaglio.*

/** Deferred membership: a plan keeps only member counts and a resolver supplies the exact keys
  * later. A reply is applied only when it answers the latest request at the current revisions with
  * exactly the target's members; every other reply is reported as rejected and changes nothing.
  */
class MembershipResolverSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)

  private val data = Vector.tabulate(24)(i => Obs(i + 1, (i * 7 % 40).toDouble + 0.5))
  private val breaks = Vector(0.0, 10.0, 20.0, 30.0, 40.0)
  private val space = ok(KeySpace("obs", KeyCodec.integer))
  private val other = ok(KeySpace("other", KeyCodec.integer))
  // A deferred plan names itself as the resolver of its members.
  private val server = SemanticId.unsafe("hist")

  /** The members of each bin, by an independent right-closed bucketing of the raw values. */
  private def oracle(binLower: Double): Set[Int] =
    data.filter(o => o.x > binLower && o.x <= binLower + 10).map(_.id).toSet

  private def plan(retention: MembershipRetention, name: String = "hist"): InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(
        ok(
          Plot(data).addLayer(Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(breaks)))
        ),
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe(name),
        ok(PlanRevision("p")),
        retention = retention
      )(_.id)
    )

  private val deferred = plan(MembershipRetention.Deferred)
  private val domain = ok(InteractionDomain(Vector(deferred), deferred.revision))
  private val bins = deferred.groups.flatMap(g => Vector.tabulate(g.size)(i => ok(g.at(i))))
  private val bin = bins.head
  private def keys(ids: Iterable[Int], in: KeySpace[Int] = space) =
    ids.toVector.map(i => ok(in.entity(i)))
  private val binMembers = oracle(0.0)

  private var sequence = 0L
  private def step(state: InteractionState[Int], action: InteractionAction[Int]) =
    sequence += 1
    InteractionState.reduce(
      state,
      InputStamp(state.domain.revision, SemanticId.unsafe("host"), sequence, InputCause.Pointer),
      action
    )
  private def run(state: InteractionState[Int], action: InteractionAction[Int]) = ok(
    step(state, action)
  )
  private def initial(mode: SelectionMode = SelectionMode.Multiple) =
    ok(InteractionState.initial(domain, mode))
  private def request(state: InteractionState[Int], id: Long) =
    run(state, InteractionAction.RequestMembers(bin.id, id, SelectionOperation.Replace)).state
  private def reply(state: InteractionState[Int], id: Long, value: MembershipReply[Int]) =
    run(state, InteractionAction.ResolveMembers(bin.id, id, value))
  private def outcomes(transition: StateTransition[Int]) =
    transition.events.map(_.event).collect { case InteractionEvent.MembershipResolved(_, _, o) =>
      o
    }

  test(
    "deferred retention keeps counts and names the resolver; members cannot be selected directly"
  ) {
    assert(bins.forall(_.membership.capability == MembershipCapability.Deferred))
    assert(bins.forall(_.membership.resolver.contains(server)))
    assertEquals(bins.map(_.membership.total.get).sum, data.size)
    assert(bins.forall(_.membership.retainedKeys.isEmpty))
    assertEquals(
      step(initial(), InteractionAction.SelectMembers(Set(bin.id), SelectionOperation.Replace))
        .map(_.state.selection),
      Left(StateError.MembershipNotExact(MembershipCapability.Deferred))
    )
  }

  test(
    "a request is recorded with its revisions, a pending reply keeps it, a complete reply selects"
  ) {
    val requested =
      run(initial(), InteractionAction.RequestMembers(bin.id, 1, SelectionOperation.Replace))
    val recorded = requested.state.pendingMembers(bin.id)
    assertEquals(
      (recorded.planRevision, recorded.sourceRevision, recorded.total, recorded.resolver),
      (domain.revision, deferred.sourceRevision, Some(binMembers.size), Some(server))
    )
    assertEquals(
      requested.events.map(_.event),
      Vector(InteractionEvent.MembershipRequested(recorded))
    )
    val waiting = reply(requested.state, 1, MembershipReply.Pending())
    assertEquals(outcomes(waiting), Vector(MembershipOutcome.Pending))
    assert(waiting.state.pendingMembers.contains(bin.id))
    val done = reply(waiting.state, 1, MembershipReply.Complete(keys(binMembers)))
    assertEquals(done.state.selection.entities.map(_.value), binMembers)
    assertEquals(done.state.pendingMembers, Map.empty[VisualTargetId, MembershipRequest[Int]])
    assertEquals(outcomes(done), Vector(MembershipOutcome.Complete(binMembers.size)))
    assert(done.events.exists(_.event.isInstanceOf[InteractionEvent.SelectionChanged[?]]))
    // The same reply again is late: rejected, nothing changes.
    val late = reply(done.state, 1, MembershipReply.Complete(keys(binMembers)))
    assert(outcomes(late).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(late.state.selection, done.state.selection)
  }

  test("a superseded request's reply is rejected; the newer request still completes") {
    val twice = request(request(initial(), 1), 2)
    val stale = reply(twice, 1, MembershipReply.Complete(keys(binMembers)))
    assert(outcomes(stale).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(stale.state.selection, Selection[Int]())
    assertEquals(stale.state.pendingMembers(bin.id).id, 2L)
    val current = reply(stale.state, 2, MembershipReply.Complete(keys(binMembers)))
    assertEquals(current.state.selection.entities.map(_.value), binMembers)
    // Request ids must increase per target.
    assert(
      step(twice, InteractionAction.RequestMembers(bin.id, 2, SelectionOperation.Replace)).isLeft
    )
  }

  test("no partial, repeated, foreign or invented reply is ever applied as a selection") {
    val pending = request(initial(), 1)
    val ordered = binMembers.toVector.sorted
    val malformed =
      ordered.indices.map(k => keys(ordered.take(k))) ++ // every strict prefix
        Vector(
          keys(ordered :+ ordered.head), // a repeated key
          keys(ordered.tail) ++ keys(Vector(ordered.head), other), // a foreign key space
          keys(ordered.tail :+ 999) // a key that is no source observation
        )
    malformed.foreach { value =>
      val result = reply(pending, 1, MembershipReply.Complete(value))
      assert(outcomes(result).head.isInstanceOf[MembershipOutcome.Rejected], value.toString)
      assertEquals(result.state.selection, Selection[Int](), value.toString)
      assertEquals(result.state.pendingMembers, Map.empty[VisualTargetId, MembershipRequest[Int]])
    }
  }

  test("failed and unavailable replies leave the selection as it was") {
    val before = run(
      initial(),
      InteractionAction.Select(Selection(targets = Set(bins(1).id)), SelectionOperation.Replace)
    ).state
    Vector(MembershipReply.Failed[Int]("timeout"), MembershipReply.Unavailable[Int]("rows purged"))
      .foreach { value =>
        val result = reply(request(before, 1), 1, value)
        assertEquals(result.state.selection, before.selection)
        assert(result.state.pendingMembers.isEmpty)
      }
  }

  test("replacing the domain cancels pending requests, so their replies are rejected") {
    val pending = request(initial(), 1)
    sequence += 1
    val replaced = ok(
      InteractionState.replaceDomain(
        pending,
        InputStamp(
          pending.domain.revision,
          SemanticId.unsafe("host"),
          sequence,
          InputCause.Programmatic
        ),
        ok(InteractionDomain(Vector(deferred), ok(PlanRevision("p2")))),
        MissingEntityPolicy.Drop
      )
    ).state
    assert(replaced.pendingMembers.isEmpty)
    val late = reply(replaced, 1, MembershipReply.Complete(keys(binMembers)))
    assert(outcomes(late).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(late.state.selection, Selection[Int]())
  }

  test(
    "requests are refused where a resolver cannot help, and replies respect the selection mode"
  ) {
    val exactPlan = plan(MembershipRetention.ExactKeys, "exact")
    val exactBin = ok(exactPlan.groups.head.at(0))
    val exactState =
      ok(InteractionState.initial(ok(InteractionDomain(Vector(exactPlan), exactPlan.revision))))
    assert(
      step(
        exactState,
        InteractionAction.RequestMembers(exactBin.id, 1, SelectionOperation.Replace)
      ).isLeft
    )
    val countPlan = plan(MembershipRetention.CountOnly, "count")
    val countBin = ok(countPlan.groups.head.at(0))
    val countState =
      ok(InteractionState.initial(ok(InteractionDomain(Vector(countPlan), countPlan.revision))))
    assertEquals(
      step(countState, InteractionAction.RequestMembers(countBin.id, 1, SelectionOperation.Replace))
        .map(_.state.selection),
      Left(StateError.MembershipNotExact(MembershipCapability.CountOnly))
    )
    assertEquals(
      step(
        initial(SelectionMode.Disabled),
        InteractionAction.RequestMembers(bin.id, 1, SelectionOperation.Replace)
      ).map(_.state.selection),
      Left(StateError.SelectionDisabled)
    )
    // A complete reply of several members cannot enter a single-selection plot.
    val single = reply(
      request(initial(SelectionMode.Single), 1),
      1,
      MembershipReply.Complete(keys(binMembers))
    )
    assert(outcomes(single).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(single.state.selection, Selection[Int]())
  }

  test("request ids are never reused, so a duplicate of an old reply cannot answer a new request") {
    // Scenario: request 1 fails; the host must not reuse id 1, and a duplicate of reply 1 is late.
    val failed = reply(request(initial(), 1), 1, MembershipReply.Failed("timeout")).state
    assert(
      step(failed, InteractionAction.RequestMembers(bin.id, 1, SelectionOperation.Replace)).isLeft
    )
    val renewed = request(failed, 2)
    val duplicate = reply(renewed, 1, MembershipReply.Complete(keys(binMembers)))
    assert(outcomes(duplicate).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(duplicate.state.selection, Selection[Int]())
    // Scenario: the domain is replaced while request 3 is pending; ids stay spent across it.
    val pending = request(duplicate.state, 3)
    sequence += 1
    val replaced = ok(
      InteractionState.replaceDomain(
        pending,
        InputStamp(
          pending.domain.revision,
          SemanticId.unsafe("host"),
          sequence,
          InputCause.Programmatic
        ),
        ok(InteractionDomain(Vector(deferred), ok(PlanRevision("p3")))),
        MissingEntityPolicy.Drop
      )
    ).state
    assert(
      step(replaced, InteractionAction.RequestMembers(bin.id, 3, SelectionOperation.Replace)).isLeft
    )
    val fresh = request(replaced, 4)
    val oldReply = reply(fresh, 3, MembershipReply.Complete(keys(binMembers)))
    assert(outcomes(oldReply).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(
      reply(fresh, 4, MembershipReply.Complete(keys(binMembers))).state.selection.entities
        .map(_.value),
      binMembers
    )
  }

  test("a projected request is refused: no resolver would ever see it") {
    sequence += 1
    val projected = InteractionState.reduce(
      initial(),
      InputStamp(domain.revision, SemanticId.unsafe("link"), sequence, InputCause.Projected),
      InteractionAction.RequestMembers(bin.id, 1, SelectionOperation.Replace)
    )
    assert(projected.isLeft)
  }

  test("a reply is checked against the target's own plot, not every plot in the domain") {
    final case class Other(id: Int, x: Double)
    val others = Vector.tabulate(binMembers.size)(i => Other(1000 + i, 5.0))
    val otherPlan = ok(
      InteractionCompiler.compile(
        ok(Plot(others).addLayer(Layer.point[Other](_.x, _.x))),
        space,
        ok(DataRevision("d2")),
        SemanticId.unsafe("other"),
        ok(PlanRevision("p")),
        retention = MembershipRetention.ExactKeys
      )(_.id)
    )
    val both = ok(InteractionDomain(Vector(deferred, otherPlan), ok(PlanRevision("both"))))
    sequence += 1
    val asked = ok(
      InteractionState.reduce(
        ok(InteractionState.initial(both)),
        InputStamp(both.revision, SemanticId.unsafe("host"), sequence, InputCause.Pointer),
        InteractionAction.RequestMembers(bin.id, 1, SelectionOperation.Replace)
      )
    ).state
    // As many keys as the bin counts, all source observations of the domain, but of the other plot.
    val borrowed = reply(asked, 1, MembershipReply.Complete(keys(others.map(_.id))))
    assert(outcomes(borrowed).head.isInstanceOf[MembershipOutcome.Rejected])
    assertEquals(borrowed.state.selection, Selection[Int]())
  }
