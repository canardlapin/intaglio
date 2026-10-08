package intaglio.interaction

import intaglio.*

/** The navigation-run contract both interactive hosts share, driven by a manual clock: a held run
  * (a pan drag) is one entry however long the reader holds still, an unheld run (wheel, pinch or
  * key zoom) ends at a pause, and a cancelled pause ends nothing.
  */
class NavigationRunSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)
  private val data = Vector.tabulate(6)(i => Obs(i + 1, i * 5.0 + 1))
  private val space = ok(KeySpace("obs", KeyCodec.integer))
  private val plan = ok(
    InteractionCompiler.compile(
      ok(Plot(data).addLayer(Layer.point[Obs](_.x, _.x))),
      space,
      ok(DataRevision("d")),
      SemanticId.unsafe("points"),
      ok(PlanRevision("r"))
    )(_.id)
  )
  private val domain = ok(InteractionDomain(Vector(plan), plan.revision))
  private val panel = SemanticId.unsafe("panel")
  private val pauseMs = 400.0

  /** A host's navigation, with a clock the test advances by hand. */
  private final class Host:
    val controller = InteractionController(ok(InteractionState.initial(domain)))
    var history = ok(InteractionHistory.empty[Int]())
    var now = 0.0
    private var timers = Vector.empty[(Double, () => Unit, Long)]
    private var nextTimer = 0L
    private var cancelled = Set.empty[Long]
    private var sequence = 0L
    val run = new NavigationRun[Int](
      pauseMs,
      (ms, task) =>
        val id = nextTimer
        nextTimer += 1
        timers :+= ((now + ms, task, id))
        () => cancelled += id
    )

    def pending: Int = timers.count((_, _, id) => !cancelled(id))

    /** Advance the clock, running every timer that falls due, in order. */
    def advance(ms: Double): Unit =
      now += ms
      val (due, later) = timers.partition(_._1 <= now)
      timers = later
      due.sortBy(_._1).foreach((_, task, id) => if !cancelled(id) then task())

    def commit(): Unit =
      run.end().foreach { (before, cause) =>
        controller.state.foreach(after => history = history.record(before, after, cause))
      }

    /** One frame of navigation: the run is told, then the window is shown (not recorded). */
    def frame(x0: Double, held: Boolean, cause: InputCause = InputCause.Pointer): Unit =
      run.frame(controller.state.toOption, cause, held)(() => commit())
      sequence += 1
      val stamp = InputStamp(domain.revision, SemanticId.unsafe("host"), sequence, cause)
      val viewport = ok(PanelViewport(x0, x0 + 10, 0, 10))
      ok(controller.dispatch(stamp, InteractionAction.SetViewport(panel, Some(viewport))))

    def viewport: Option[PanelViewport] = ok(controller.state).viewports.get(panel)

  test("a held run (a pan drag) is one entry however long the reader holds still") {
    val host = new Host
    (1 to 5).foreach(i => host.frame(i.toDouble, held = true))
    assertEquals(host.pending, 0, "a held frame arms no pause")
    host.advance(pauseMs * 3)
    assert(host.run.isOpen, "holding still does not end a held run")
    (6 to 10).foreach(i => host.frame(i.toDouble, held = true))
    host.commit() // release
    assertEquals(host.history.undoSize, 1, "press to release is one entry")
    assert(!host.run.isOpen)
    val (restore, _) = host.history.undo(ok(host.controller.state)).get
    restore match
      case InteractionAction.RestoreSnapshot(saved) =>
        assertEquals(saved.viewports, Map.empty[SemanticId, PanelViewport], "back to before it")
      case other => fail(s"expected a restore, got $other")
  }

  test(
    "an unheld run (wheel, pinch or key zoom) ends at a pause and the next frames start another"
  ) {
    val host = new Host
    (1 to 3).foreach { i =>
      host.frame(i.toDouble, held = false, InputCause.Keyboard)
      host.advance(pauseMs / 2)
    }
    assertEquals(host.history.undoSize, 0, "frames closer than the pause are one open run")
    host.advance(pauseMs)
    assertEquals(host.history.undoSize, 1, "the pause records the run")
    assert(!host.run.isOpen)
    host.frame(20, held = false)
    host.advance(pauseMs)
    assertEquals(host.history.undoSize, 2, "a run after the pause is its own entry")
    assertEquals(host.pending, 0)
  }

  test("a run is opened with the state before its first frame and the cause of that frame") {
    val host = new Host
    host.frame(1, held = false, InputCause.Keyboard)
    host.frame(2, held = false, InputCause.Pointer)
    val (before, cause) = host.run.end().get
    assertEquals(before.viewports, Map.empty[SemanticId, PanelViewport])
    assertEquals(cause, InputCause.Keyboard)
    assertEquals(host.run.end(), None, "ending twice ends nothing")
  }

  test("a pause cancelled by a later frame or by ending the run ends nothing when it fires") {
    val host = new Host
    host.frame(1, held = false)
    host.frame(2, held = true) // the reader starts a pan in the same run: the pause is cancelled
    host.advance(pauseMs * 2)
    assert(host.run.isOpen, "the earlier pause did not end the held run")
    host.commit()
    host.frame(3, held = false)
    host.commit() // another change ends the run first
    host.frame(4, held = true)
    host.advance(pauseMs * 2)
    assert(host.run.isOpen, "the first run's pause did not end the second run")
    assertEquals(host.history.undoSize, 2)
  }

  test("changed says whether ending the open run would record an entry") {
    val host = new Host
    assert(!host.run.changed(ok(host.controller.state)), "no run is open")
    host.frame(1, held = true)
    assert(host.run.changed(ok(host.controller.state)))
    // A drag brought back to where it began changes nothing.
    val sequence = 1000L
    ok(
      host.controller.dispatch(
        InputStamp(domain.revision, SemanticId.unsafe("host"), sequence, InputCause.Pointer),
        InteractionAction.SetViewport(panel, None)
      )
    )
    assert(!host.run.changed(ok(host.controller.state)))
    host.commit()
    assertEquals(host.history.undoSize, 0, "a run that ends where it began is no entry")
  }
