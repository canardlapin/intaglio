package intaglio.interaction

import intaglio.*

/** Undo and redo over the durable state, with the documented boundaries: reader and application
  * changes are history, projected input is not, new input clears redo, new data clears both, and
  * undo and redo never activate a target or ask a resolver.
  */
class InteractionHistorySuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)
  private val data = Vector.tabulate(6)(i => Obs(i + 1, i * 5.0 + 1))
  private val space = ok(KeySpace("obs", KeyCodec.integer))
  private def plan(revision: String, rows: Vector[Obs] = data) =
    ok(
      InteractionCompiler.compile(
        ok(Plot(rows).addLayer(Layer.point[Obs](_.x, _.x))),
        space,
        ok(DataRevision(revision)),
        SemanticId.unsafe("points"),
        ok(PlanRevision(revision))
      )(_.id)
    )
  private val base = plan("r1")
  private val domain = ok(InteractionDomain(Vector(base), base.revision))
  private def keys(ids: Int*) = ids.map(i => ok(space.entity(i))).toSet

  private var sequence = 0L
  private def stamp(controller: InteractionController[Int], cause: InputCause) =
    sequence += 1
    InputStamp(ok(controller.state).domain.revision, SemanticId.unsafe("host"), sequence, cause)
  private def fresh(depth: Int = 100) =
    val controller = InteractionController(ok(InteractionState.initial(domain)))
    val events = Vector.newBuilder[InteractionEvent[Int]]
    controller.subscribe(record => events += record.event)
    (ok(HistoryController(controller, depth)), events)
  private def select(
      h: HistoryController[Int],
      ids: Set[Int],
      cause: InputCause = InputCause.Pointer
  ) =
    ok(
      h.dispatch(
        stamp(h.controller, cause),
        InteractionAction.Select(Selection(keys(ids.toSeq*)), SelectionOperation.Replace)
      )
    )
  private def selected(h: HistoryController[Int]) =
    ok(h.controller.state).selection.entities.map(_.value)
  private def undo(h: HistoryController[Int]) =
    h.undo(stamp(h.controller, InputCause.Keyboard)).map(ok(_))
  private def redo(h: HistoryController[Int]) =
    h.redo(stamp(h.controller, InputCause.Keyboard)).map(ok(_))

  test("undo and redo walk back and forth through reader and application changes") {
    val (h, _) = fresh()
    select(h, Set(1))
    select(h, Set(1, 2), InputCause.Programmatic)
    ok(
      h.dispatch(
        stamp(h.controller, InputCause.Keyboard),
        InteractionAction.SaveSelection(SelectionName.unsafe("pair"))
      )
    )
    assertEquals(h.history.undoSize, 3)
    undo(h)
    assertEquals(ok(h.controller.state).named, Map.empty[SelectionName, Selection[Int]])
    undo(h)
    assertEquals(selected(h), Set(1))
    undo(h)
    assertEquals(selected(h), Set.empty[Int])
    assertEquals(undo(h), None, "nothing more to undo")
    redo(h); redo(h)
    assertEquals(selected(h), Set(1, 2))
    assertEquals(h.history.redoSize, 1)
  }

  test("projected input is not history, and a new change clears redo") {
    val (h, _) = fresh()
    select(h, Set(1))
    select(h, Set(2, 3), InputCause.Projected)
    assertEquals(h.history.undoSize, 1, "the linked projection made no entry")
    // Undo restores the state before this plot's own change, overriding the projection.
    undo(h)
    assertEquals(selected(h), Set.empty[Int])
    assert(h.history.canRedo)
    select(h, Set(4))
    assert(!h.history.canRedo, "new input clears redo")
  }

  test("depth bounds the history; new data clears it; refused input records nothing") {
    val (h, _) = fresh(depth = 3)
    (1 to 6).foreach(i => select(h, Set(i)))
    assertEquals(h.history.undoSize, 3)
    // Refused input (an unknown key space) changes nothing and records nothing.
    val foreign = ok(KeySpace("other", KeyCodec.integer))
    assert(
      h.dispatch(
        stamp(h.controller, InputCause.Pointer),
        InteractionAction.Select(Selection(Set(ok(foreign.entity(1)))), SelectionOperation.Replace)
      ).isLeft
    )
    assertEquals(h.history.undoSize, 3)
    val next = plan("r2")
    ok(
      h.replaceDomain(
        stamp(h.controller, InputCause.Programmatic),
        ok(InteractionDomain(Vector(next), next.revision)),
        MissingEntityPolicy.Drop
      )
    )
    assert(!h.history.canUndo && !h.history.canRedo, "new data clears history")
    assert(InteractionHistory.empty[Int](0).isLeft)
  }

  test("undo and redo restore, never activate or ask for members") {
    val (h, events) = fresh()
    select(h, Set(1))
    ok(
      h.dispatch(
        stamp(h.controller, InputCause.Pointer),
        InteractionAction.SetViewport(
          SemanticId.unsafe("panel"),
          Some(ok(PanelViewport(0, 1, 0, 1)))
        )
      )
    )
    val before = events.result().size
    undo(h); undo(h); redo(h)
    val later = events.result().drop(before).map(_.getClass.getSimpleName).toSet
    assert(later.subsetOf(Set("SelectionChanged", "ViewportChanged")), later.toString)
    assertEquals(selected(h), Set(1))
    assertEquals(ok(h.controller.state).viewports, Map.empty[SemanticId, PanelViewport])
  }
