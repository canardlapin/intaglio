package intaglio.interaction

import intaglio.*

/** An explicit filter recompiles from the kept rows and reports the input and statistical changes
  * separately, each checked against an independent bucketing; the selection is reconciled only when
  * the new plan is applied, as its own event.
  */
class FilterCommandSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)
  private val rows = Vector.tabulate(12)(i => Obs(i + 1, i * 2.5 + 0.5))
  private val breaks = Vector(0.0, 10.0, 20.0, 30.0)
  private val space = ok(KeySpace("obs", KeyCodec.integer))
  private def compile(
      data: Vector[Obs],
      d: DataRevision,
      p: PlanRevision
  ): Either[IntaglioError, InteractionPlan[Int]] =
    Plot(data)
      .addLayer(Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(breaks)))
      .flatMap(plot =>
        InteractionCompiler.compile(
          plot,
          space,
          d,
          SemanticId.unsafe("hist"),
          p,
          retention = MembershipRetention.ExactKeys
        )(_.id)
      )
  private val current = ok(compile(rows, ok(DataRevision("d1")), ok(PlanRevision("p1"))))
  private val command = FilterCommand[Obs, Int](rows, space, _.id, compile)
  private def key(i: Int) = ok(space.entity(i))

  /** Independently: non-empty bin counts of `data`. */
  private def counts(data: Vector[Obs]) =
    breaks.indices
      .dropRight(1)
      .map(j => data.count(o => o.x > breaks(j) && o.x <= breaks(j + 1)))
      .filter(_ > 0)
      .toVector

  test("keeping some observations reports rows removed and each bin's total before and after") {
    val keep = Set(1, 2, 3, 9, 12)
    val result =
      ok(command.keep(current, keep.map(key), ok(DataRevision("d2")), ok(PlanRevision("p2"))))
    assertEquals(result.input, InputChange(12, 5, (rows.map(_.id).toSet -- keep).map(key)))
    assertEquals(result.plan.sourceEntities.map(_.value).toSet, keep)
    val before = result.statistics.flatMap(_.totalsBefore).flatten
    val after = result.statistics.flatMap(_.totalsAfter).flatten
    assertEquals(before, counts(rows))
    assertEquals(after, counts(rows.filter(o => keep.contains(o.id))))
    assert(result.statistics.exists(_.changed))
  }

  test("applying the filtered plan reconciles the selection as its own event") {
    val domain = ok(InteractionDomain(Vector(current), current.revision))
    val selected = ok(
      InteractionState.reduce(
        ok(InteractionState.initial(domain)),
        InputStamp(domain.revision, SemanticId.unsafe("t"), 1, InputCause.Pointer),
        InteractionAction.Select(Selection(Set(key(1), key(5))), SelectionOperation.Replace)
      )
    ).state
    val result =
      ok(command.keep(current, Set(key(1), key(2)), ok(DataRevision("d2")), ok(PlanRevision("p2"))))
    val applied = ok(
      InteractionState.replaceDomain(
        selected,
        InputStamp(domain.revision, SemanticId.unsafe("t"), 2, InputCause.Programmatic),
        ok(InteractionDomain(Vector(result.plan), result.plan.revision)),
        MissingEntityPolicy.Drop
      )
    )
    assertEquals(applied.state.selection.entities, Set(key(1)))
    assertEquals(
      applied.events.map(_.event).collect { case r: InteractionEvent.Reconciled[Int] => r.removed },
      Vector(Set(key(5)))
    )
  }

  test("a filter needs new revisions, keys of its space, and keys that are rows") {
    assert(
      command.keep(current, Set(key(1)), current.sourceRevision, ok(PlanRevision("p2"))).isLeft
    )
    val other = ok(KeySpace("obs", KeyCodec.integer))
    assert(
      command
        .keep(current, Set(ok(other.entity(1))), ok(DataRevision("d2")), ok(PlanRevision("p2")))
        .isLeft
    )
    assert(
      command.keep(current, Set(key(99)), ok(DataRevision("d2")), ok(PlanRevision("p2"))).isLeft
    )
  }
