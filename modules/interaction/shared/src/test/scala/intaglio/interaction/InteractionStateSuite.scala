package intaglio.interaction

import intaglio.*

class InteractionStateSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val space = ok(KeySpace("observations", KeyCodec.integer))
  private val origin = SemanticId.unsafe("widget")
  private def revision(value: String): PlanRevision = ok(PlanRevision(value))
  private def key(value: Int): EntityKey[Int] = ok(space.entity(value))
  private def compiled(
      data: Vector[Int] = Vector(1, 2, 3),
      rev: String = "v1"
  ): InteractionPlan[Int] =
    val plot = ok(Plot(data).addLayer(Layer.point[Int](_.toDouble, _.toDouble)))
    ok(
      InteractionCompiler.compile(
        plot,
        space,
        ok(DataRevision(rev)),
        SemanticId.unsafe("plot"),
        revision(rev)
      )(identity)
    )
  private val plan = compiled()
  private val domain = ok(InteractionDomain(Vector(plan), revision("domain-1")))
  private val ids = (0 until 3).map(index => ok(plan.groups.head.at(index)).id).toVector
  private def stamp(
      sequence: Long,
      cause: InputCause = InputCause.Pointer,
      rev: PlanRevision = domain.revision,
      source: SemanticId = origin
  ): InputStamp =
    InputStamp(rev, source, sequence, cause)
  private def initial(mode: SelectionMode = SelectionMode.Multiple): InteractionState[Int] =
    ok(InteractionState.initial(domain, mode))
  private def select(values: Int*): Selection[Int] = Selection(values.map(key).toSet)
  private def step(
      state: InteractionState[Int],
      sequence: Long,
      action: InteractionAction[Int],
      cause: InputCause = InputCause.Pointer
  ): StateTransition[Int] =
    ok(InteractionState.reduce(state, stamp(sequence, cause, state.domain.revision), action))
  private def selected(state: InteractionState[Int]): Set[Int] =
    state.selection.entities.map(_.value)

  test("an explicit trace has the same independently stated selection results on both platforms") {
    val trace = Vector(
      (SelectionOperation.Replace, select(1, 2), Set(1, 2)),
      (SelectionOperation.Add, select(2, 3), Set(1, 2, 3)),
      (SelectionOperation.Subtract, select(2), Set(1, 3)),
      (SelectionOperation.Toggle, select(1, 2), Set(2, 3)),
      (SelectionOperation.Clear, select(), Set.empty[Int])
    )
    def replay(): Vector[EventRecord[Int]] =
      var state = initial()
      val events = Vector.newBuilder[EventRecord[Int]]
      trace.zipWithIndex.foreach { case ((operation, value, expected), index) =>
        val next = step(state, index, InteractionAction.Select(value, operation))
        assertEquals(selected(next.state), expected)
        assertEquals(
          next.events.map(_.event),
          Vector(InteractionEvent.SelectionChanged(next.state.selection))
        )
        events ++= next.events
        state = next.state
      }
      events.result()
    assertEquals(replay(), replay())
  }

  test("set operations satisfy idempotence and toggle involution over every subset") {
    val subsets =
      (0 until 8).map(mask => select((1 to 3).filter(i => (mask & (1 << (i - 1))) != 0)*))
    for start <- subsets; operand <- subsets do
      val state = ok(InteractionState.initial(domain, selected = start))
      for operation <- Vector(
          SelectionOperation.Replace,
          SelectionOperation.Add,
          SelectionOperation.Subtract
        )
      do
        val once = step(state, 1, InteractionAction.Select(operand, operation))
        val twice = step(once.state, 2, InteractionAction.Select(operand, operation))
        assertEquals(twice.state.selection, once.state.selection)
        assertEquals(twice.events, Vector.empty)
      val once = step(state, 1, InteractionAction.Select(operand, SelectionOperation.Toggle))
      val twice = step(once.state, 2, InteractionAction.Select(operand, SelectionOperation.Toggle))
      assertEquals(twice.state.selection, start)
  }

  test(
    "activation preserves typed identity and payload for pointer, keyboard, and programmatic input"
  ) {
    val target = ok(plan.groups.head.at(1))
    Vector(InputCause.Pointer, InputCause.Keyboard, InputCause.Programmatic).foreach { cause =>
      val result = step(initial(), 0, InteractionAction.Activate(ids(1)), cause)
      assertEquals(
        result.events,
        Vector(EventRecord(stamp(0, cause), InteractionEvent.Activated(target)))
      )
      result.events.head.event match
        case InteractionEvent.Activated(info) =>
          val typed: Option[EntityKey[Int]] = info.entity
          assertEquals(typed, Some(key(2)))
        case _ => fail("expected activation")
    }
  }

  test("duplicate, out-of-order, and stale input cannot toggle a selection twice") {
    val action = InteractionAction.Select(select(1), SelectionOperation.Toggle)
    val once = step(initial(), 8, action).state
    assert(
      InteractionState
        .reduce(once, stamp(8), action)
        .left
        .exists(_.isInstanceOf[StateError.DuplicateOrOutOfOrder])
    )
    assert(InteractionState.reduce(once, stamp(7), action).isLeft)
    assert(
      InteractionState
        .reduce(once, stamp(9, rev = revision("obsolete")), action)
        .left
        .exists(_.isInstanceOf[StateError.StaleInput])
    )
    assert(InteractionState.reduce(once, stamp(-1), action).isLeft)
    assertEquals(selected(once), Set(1))
    val other =
      ok(InteractionState.reduce(once, stamp(0, source = SemanticId.unsafe("other")), action))
    assertEquals(selected(other.state), Set.empty[Int])
  }

  test("projected deliveries update state without returning application events") {
    val result = step(
      initial(),
      0,
      InteractionAction.Select(select(2), SelectionOperation.Replace),
      InputCause.Projected
    )
    assertEquals(selected(result.state), Set(2))
    assertEquals(result.events, Vector.empty)
    assert(
      InteractionState
        .reduce(result.state, stamp(0, InputCause.Projected), InteractionAction.Activate(ids.head))
        .isLeft
    )
  }

  test("single and disabled modes reject invalid states instead of choosing an arbitrary key") {
    assertEquals(
      InteractionState.initial(domain, SelectionMode.Single, select(1, 2)).left.toOption,
      Some(StateError.MultipleSelectionInSingleMode)
    )
    assertEquals(
      InteractionState.initial(domain, SelectionMode.Disabled, select(1)).left.toOption,
      Some(StateError.SelectionDisabled)
    )
    val one = step(
      initial(SelectionMode.Single),
      0,
      InteractionAction.Select(select(1), SelectionOperation.Replace)
    ).state
    assert(
      InteractionState
        .reduce(one, stamp(1), InteractionAction.Select(select(2), SelectionOperation.Add))
        .isLeft
    )
    assertEquals(
      selected(step(one, 1, InteractionAction.Select(select(2), SelectionOperation.Replace)).state),
      Set(2)
    )
    assert(
      InteractionState
        .reduce(one, stamp(1), InteractionAction.SetSelectionMode(SelectionMode.Disabled))
        .isLeft
    )
    val cleared = step(one, 1, InteractionAction.Select(select(), SelectionOperation.Clear)).state
    assertEquals(
      step(
        cleared,
        2,
        InteractionAction.SetSelectionMode(SelectionMode.Disabled)
      ).state.selectionMode,
      SelectionMode.Disabled
    )
  }

  test("displayed targets and entities remain separate selection domains") {
    val chosen = Selection(Set(key(1)), Set(ids(1)))
    val result = step(initial(), 0, InteractionAction.Select(chosen, SelectionOperation.Replace))
    assertEquals(result.state.selection, chosen)
    assertEquals(result.state.selection.entities, Set(key(1)))
    assertEquals(result.state.selection.targets, Set(ids(1)))
    assert(InteractionState.initial(domain, SelectionMode.Single, chosen).isLeft)
  }

  test("same labels in a foreign key space and obsolete target geometry are rejected") {
    val foreign = ok(KeySpace("observations", KeyCodec.integer))
    val selection = Selection(Set(ok(foreign.entity(1))))
    assertEquals(
      InteractionState.initial(domain, selected = selection).left.toOption,
      Some(StateError.UnknownEntity)
    )
    val stale = ok(compiled(rev = "old").groups.head.at(0)).id
    assert(domain.target(stale).isLeft)
    assert(
      InteractionState.reduce(initial(), stamp(0), InteractionAction.Hover(Some(stale))).isLeft
    )
    assert(
      InteractionState
        .reduce(
          initial(),
          stamp(0),
          InteractionAction.Select(Selection(targets = Set(stale)), SelectionOperation.Subtract)
        )
        .isLeft
    )
  }

  test("hover and focus are independent and changing the viewport preserves all selections") {
    val selectedState =
      step(initial(), 0, InteractionAction.Select(select(1, 2), SelectionOperation.Replace)).state
    val focused = step(selectedState, 1, InteractionAction.Focus(Some(ids.head))).state
    val hovered = step(focused, 2, InteractionAction.Hover(Some(ids(1)))).state
    val panel = SemanticId.unsafe("panel")
    val window = ok(PanelViewport(1, 2, 1, 2))
    val zoomed = step(hovered, 3, InteractionAction.SetViewport(panel, Some(window))).state
    assertEquals(zoomed.selection, selectedState.selection)
    assertEquals(zoomed.focus, Some(ids.head))
    assertEquals(zoomed.hover, Some(ids(1)))
    assertEquals(zoomed.viewports, Map(panel -> window))
    assertEquals(
      step(zoomed, 4, InteractionAction.SetViewport(panel, None)).state.viewports,
      Map.empty
    )
    assert(PanelViewport(1, 1, 0, 1).isLeft)
    assert(PanelViewport(0, Double.PositiveInfinity, 0, 1).isLeft)
  }

  test("gesture cancellation releases the pointer without committing or clearing selection") {
    val chosen =
      step(initial(), 0, InteractionAction.Select(select(1), SelectionOperation.Replace)).state
    val mode = step(chosen, 1, InteractionAction.SetGestureMode(GestureMode.Lasso)).state
    val active = step(mode, 2, InteractionAction.BeginGesture(12)).state
    assertEquals(active.gesture, Some(ActiveGesture(12, GestureMode.Lasso)))
    assert(InteractionState.reduce(active, stamp(3), InteractionAction.BeginGesture(13)).isLeft)
    assert(
      InteractionState
        .reduce(active, stamp(3), InteractionAction.SetGestureMode(GestureMode.Pan))
        .isLeft
    )
    val cancelled = step(active, 3, InteractionAction.EndGesture(true))
    assertEquals(cancelled.state.gesture, None)
    assertEquals(cancelled.state.selection, chosen.selection)
    assertEquals(
      cancelled.events.map(_.event),
      Vector(InteractionEvent.GestureEnded[Int](12, true))
    )
    assert(
      InteractionState.reduce(cancelled.state, stamp(4), InteractionAction.EndGesture(true)).isLeft
    )
  }

  test(
    "replacement explicitly drops or preserves absent observations and invalidates visual targets"
  ) {
    val chosen =
      ok(InteractionState.initial(domain, selected = Selection(Set(key(1), key(2)), Set(ids.head))))
    val next = ok(InteractionDomain(Vector(compiled(Vector(2, 3), "v2")), revision("domain-2")))
    val drop = ok(InteractionState.replaceDomain(chosen, stamp(1), next, MissingEntityPolicy.Drop))
    assertEquals(selected(drop.state), Set(2))
    assertEquals(drop.state.selection.targets, Set.empty[VisualTargetId])
    assertEquals(
      drop.events.map(_.event),
      Vector(InteractionEvent.Reconciled(Set(key(1)), Set.empty[EntityKey[Int]], Set(ids.head)))
    )
    val keep =
      ok(InteractionState.replaceDomain(chosen, stamp(1), next, MissingEntityPolicy.Preserve))
    assertEquals(selected(keep.state), Set(1, 2))
    assertEquals(keep.state.unresolved, Set(key(1)))
    val restored =
      ok(InteractionDomain(Vector(compiled(Vector(3, 1, 2), "v3")), revision("domain-3")))
    val resolved = ok(
      InteractionState.replaceDomain(
        keep.state,
        stamp(2, rev = next.revision),
        restored,
        MissingEntityPolicy.Preserve
      )
    )
    assertEquals(resolved.state.unresolved, Set.empty[EntityKey[Int]])
    assertEquals(selected(resolved.state), Set(1, 2))
    assert(
      InteractionState.replaceDomain(chosen, stamp(1), domain, MissingEntityPolicy.Drop).isLeft
    )
    assert(
      InteractionState.reduce(keep.state, stamp(2), InteractionAction.Activate(ids.head)).isLeft
    )
  }

  test(
    "source identity includes undrawn non-finite rows and is not inferred from visible targets"
  ) {
    final case class Row(id: Int, x: Double)
    val rows = Vector(Row(1, 1), Row(2, Double.NaN), Row(3, 3))
    val plot = ok(Plot(rows).addLayer(Layer.point[Row](_.x, _.id.toDouble)))
    val compiled = ok(
      InteractionCompiler.compile(
        plot,
        space,
        ok(DataRevision("hidden")),
        SemanticId.unsafe("hidden"),
        revision("hidden")
      )(_.id)
    )
    val hiddenDomain = ok(InteractionDomain(Vector(compiled), revision("hidden-domain")))
    assertEquals(compiled.groups.map(_.size).sum, 2)
    assertEquals(hiddenDomain.entities, Set(key(1), key(2), key(3)))
    assertEquals(selected(ok(InteractionState.initial(hiddenDomain, selected = select(2)))), Set(2))
  }

  test("duplicate plans cannot overwrite the domain routing index") {
    assert(InteractionDomain(Vector(plan, plan), revision("duplicate")).isLeft)
  }
