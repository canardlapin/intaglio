package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.event.{Event, EventType}
import _root_.javafx.scene.input.{
  KeyCode,
  KeyEvent,
  MouseButton,
  MouseEvent,
  PickResult,
  ScrollEvent
}
import _root_.javafx.stage.Stage

/** Interaction 10 through the JavaFX host: the shared inspector, state subscription, named
  * selections and their algebra, undo and redo with the shared history boundaries (projected input
  * never recorded, a navigation run is one entry, new data clears history), and snapshots. Headless
  * Monocle evidence.
  */
class JavaFxHistorySuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def fx[A](body: => A): A = FxToolkit.fx(body)
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)

  final case class Obs(id: String, x: Double, y: Double)
  private val rows = Vector.tabulate(12)(i => Obs(s"r$i", 1.0 + i, 10.0 + (i * 7 % 11)))
  private val context = RenderContext.unsafe(480, 320)
  private val space = ok(KeySpace("obs", KeyCodec.text))
  private val options = PlotCompilerOptions(renderContext = Some(context))

  private def scatterPlan(
      data: Vector[Obs] = rows,
      revision: String = "d1",
      id: String = "scatter"
  ): InteractionPlan[String] =
    ok(
      InteractionCompiler.compile(
        ok(plot(data).aes(_.x, _.y).size(5).geomPoint().build).plot,
        space,
        ok(DataRevision(revision)),
        SemanticId.unsafe(id),
        ok(PlanRevision(revision)),
        options
      )(_.id)
    )

  private def histogramPlan: InteractionPlan[String] =
    ok(
      InteractionCompiler.compile(
        ok(plot(rows).aes(_.y).geomHistogram(bins = HistogramBins.countUnsafe(4)).build).plot,
        space,
        ok(DataRevision("d1")),
        SemanticId.unsafe("histogram"),
        ok(PlanRevision("d1")),
        options,
        MembershipRetention.ExactKeys
      )(_.id)
    )

  private def view(plan: InteractionPlan[String] = scatterPlan()) =
    ok(JavaFxInteractionView.compile(plan, context))

  private def keys(ids: String*): Set[EntityKey[String]] = ids.map(id => ok(space.entity(id))).toSet
  private def selected(host: JavaFxInteractionHost[String]): Set[String] =
    ok(host.state).selection.entities.map(_.value)

  private def mouse(
      host: JavaFxInteractionHost[?],
      kind: EventType[MouseEvent],
      x: Double,
      y: Double,
      shift: Boolean = false
  ): Unit =
    val p = host.node.localToScene(x, y)
    Event.fireEvent(
      host.node,
      new MouseEvent(
        kind,
        p.getX,
        p.getY,
        p.getX,
        p.getY,
        MouseButton.PRIMARY,
        1,
        shift,
        false,
        false,
        false,
        kind == MouseEvent.MOUSE_PRESSED || kind == MouseEvent.MOUSE_DRAGGED,
        false,
        false,
        false,
        false,
        true,
        new PickResult(host.node, p.getX, p.getY)
      )
    )

  private def click(host: JavaFxInteractionHost[String], id: String, shift: Boolean = false) =
    val g = ok(host.currentView).navigation.targets.find(_.target.entity.exists(_.value == id)).get
    val (x, y) = ok(host.toLocal(g.anchor)).get
    mouse(host, MouseEvent.MOUSE_MOVED, x, y)
    mouse(host, MouseEvent.MOUSE_PRESSED, x, y, shift)
    mouse(host, MouseEvent.MOUSE_RELEASED, x, y, shift)
    mouse(host, MouseEvent.MOUSE_CLICKED, x, y, shift)

  private def key(
      host: JavaFxInteractionHost[?],
      code: KeyCode,
      shift: Boolean = false,
      control: Boolean = false,
      meta: Boolean = false
  ): Unit =
    Event.fireEvent(
      host.node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, meta)
    )

  test("the inspector and state subscription report durable changes, projected ones included") {
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      var seen = Vector.empty[Set[String]]
      val stop = ok(host.subscribeState(s => seen :+= s.selection.entities.map(_.value)))
      assertEquals(seen, Vector(Set.empty[String]), "a subscriber hears the state now")
      val g = ok(host.currentView).navigation.targets.head
      val (x, y) = ok(host.toLocal(g.anchor)).get
      mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      key(host, KeyCode.HOME)
      assertEquals(seen.size, 1, "hover and focus are not news")
      click(host, "r1")
      ok(host.setSelection(Selection(keys("r1", "r2"))))
      assertEquals(seen.drop(1), Vector(Set("r1"), Set("r1", "r2")))
      val model = ok(host.inspector(1))
      assertEquals(model.observations, 2)
      assertEquals(model.observationSample.map(_.value), Vector("r1"))
      assert(host.inspector(-1).isLeft)
      stop()
      ok(host.setSelection(Selection(keys("r3"))))
      assertEquals(seen.size, 3, "an unsubscribed listener hears nothing more")
      ok(host.dispose())
    }
  }

  test("a JavaFX inspector follows linked hosts and words rows as the browser panel does") {
    val behavior = InteractionBehavior.default[String]
    fx {
      val a = ok(JavaFxInteractionHost.mount(view(), behavior))
      val h = ok(JavaFxInteractionHost.mount(view(histogramPlan), behavior))
      val link = ok(JavaFxLink.connect(space, Vector(a, h)))
      val inspector = ok(JavaFxInspector.mount(Vector("scatter" -> a, "bins" -> h), sample = 3))
      click(a, "r1")
      val rows = inspector.rows
      assert(
        rows.contains(InspectorRow("scatter: Selection", "Observations selected", "1: r1")),
        rows
      )
      // The bins plot hears r1 through the link and reports the bin that holds it.
      assert(rows.contains(InspectorRow("bins: Selection", "Observations selected", "1: r1")), rows)
      assert(
        rows.exists(r =>
          r.section == "bins: Aggregates the selection reaches" && r.value.startsWith("1 of ")
        ),
        rows
      )
      ok(a.saveSelection(SelectionName.unsafe("first")))
      assert(
        inspector.rows.contains(
          InspectorRow("scatter: Saved selections", "first", "1 observations, 0 targets")
        )
      )
      assert(inspector.node.getChildren.size > 0)
      inspector.dispose()
      assertEquals(inspector.node.getChildren.size, 0)
      link.dispose()
      ok(a.dispose())
      ok(h.dispose())
    }
  }

  test("named selections combine by the set laws and are refused when unknown") {
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      val (a, b, c) =
        (SelectionName.unsafe("a"), SelectionName.unsafe("b"), SelectionName.unsafe("c"))
      ok(host.setSelection(Selection(keys("r1", "r2"))))
      ok(host.saveSelection(a))
      ok(host.setSelection(Selection(keys("r2", "r3"))))
      ok(host.saveSelection(b))
      for (how, expected) <- Vector(
          SetCombination.Union -> Set("r1", "r2", "r3"),
          SetCombination.Intersection -> Set("r2"),
          SetCombination.Difference -> Set("r1")
        )
      do
        ok(host.combineSelections(a, b, how, c))
        ok(host.recallSelection(c, SelectionOperation.Replace))
        assertEquals(selected(host), expected, how.toString)
      assert(host.recallSelection(SelectionName.unsafe("none"), SelectionOperation.Replace).isLeft)
      ok(host.deleteSelection(c))
      assertEquals(ok(host.state).named.keySet, Set(a, b))
      ok(host.dispose())
    }
  }

  test("undo and redo follow the shared boundaries and never follow a link") {
    var followed = 0
    val linking = InteractionBehavior
      .default[String]
      .withLink(t => t.entity.map(k => ok(TargetLink(s"#${k.value}"))))
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), linking, onLink = Some(_ => followed += 1)))
      click(host, "r1")
      click(host, "r2", shift = true)
      assertEquals(followed, 2)
      // Projected input records nothing.
      ok(host.setSelection(Selection(keys("r2", "r1"))))
      assertEquals(selected(host), Set("r1", "r2"))
      assert(ok(host.undo()))
      assertEquals(selected(host), Set("r1"))
      key(host, KeyCode.Z, control = true)
      assertEquals(selected(host), Set.empty)
      assert(!host.canUndo)
      assertEquals(ok(host.undo()), false)
      key(host, KeyCode.Z, shift = true, meta = true)
      assertEquals(selected(host), Set("r1"))
      key(host, KeyCode.Y, control = true)
      assertEquals(selected(host), Set("r1", "r2"))
      assertEquals(followed, 2, "undo and redo activate nothing")
      ok(host.undo())
      click(host, "r5")
      assert(!host.canRedo, "a new recorded change clears redo")
      ok(host.dispose())
    }
  }

  test("a navigation run is one entry; undo and redo draw the recorded windows") {
    val (host, stage) = fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      val stage = new Stage()
      stage.setScene(new _root_.javafx.scene.Scene(host.node, 480, 320))
      stage.show()
      host.node.requestFocus()
      (host, stage)
    }
    try
      fx {
        ok(host.zoomBy(0.5))
        val zoomed = host.currentWindow
        ok(host.setGestureMode(GestureMode.Pan))
        mouse(host, MouseEvent.MOUSE_PRESSED, 240, 160)
        (1 to 20).foreach(i => mouse(host, MouseEvent.MOUSE_DRAGGED, 240 + i * 2, 160))
        mouse(host, MouseEvent.MOUSE_RELEASED, 280, 160)
        val panned = host.currentWindow
        assertNotEquals(panned, zoomed)
        assert(ok(host.undo()), "the pan is one entry")
        assertEquals(host.currentWindow, zoomed)
        assert(ok(host.undo()))
        assertEquals(host.currentWindow, PanelWindow.full)
        assert(!host.canUndo, "zoom and pan: two entries, not twenty-one")
        ok(host.redo())
        ok(host.redo())
        assertEquals(host.currentWindow, panned, "redo draws the recorded window")
        ok(host.setGestureMode(GestureMode.Inspect))
        ok(host.resetWindow())
        // Five wheel notches in a row are one run, recorded once it is committed.
        val (px, py) = (240.0, 160.0)
        (1 to 5).foreach(_ =>
          Event.fireEvent(
            host.node,
            new ScrollEvent(
              ScrollEvent.SCROLL,
              px,
              py,
              px,
              py,
              false,
              false,
              false,
              false,
              false,
              false,
              0,
              40,
              0,
              40,
              ScrollEvent.HorizontalTextScrollUnits.NONE,
              0,
              ScrollEvent.VerticalTextScrollUnits.NONE,
              0,
              0,
              new PickResult(host.node, px, py)
            )
          )
        )
        assert(!host.currentWindow.isFull)
        // undo() itself commits the open run; nothing else has asked about history here.
        ok(host.undo())
        assertEquals(host.currentWindow, PanelWindow.full, "one undo takes back the whole run")
      }
    finally fx { ok(host.dispose()); stage.close() }
  }

  test("new data clears history; the same revision keeps it") {
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      click(host, "r3")
      ok(host.update(view()))
      assert(host.canUndo, "a resize of the same data is not new data")
      ok(host.update(view(scatterPlan(rows.drop(1), "d2"))))
      assert(!host.canUndo && !host.canRedo)
      ok(host.dispose())
    }
  }

  test("a snapshot restores selection, saved selections and window, and refuses another revision") {
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      ok(host.setSelection(Selection(keys("r2", "r4"))))
      ok(host.saveSelection(SelectionName.unsafe("pair")))
      ok(host.zoomBy(0.5))
      val window = host.currentWindow
      val text = ok(host.snapshot).toJson
      ok(host.setSelection(Selection(keys("r7"))))
      ok(host.deleteSelection(SelectionName.unsafe("pair")))
      ok(host.resetWindow())
      ok(host.restore(ok(InteractionSnapshot.fromJson(text))))
      assertEquals(selected(host), Set("r2", "r4"))
      assertEquals(ok(host.state).named.keySet.map(_.value), Set("pair"))
      assertEquals(host.currentWindow, window, "the restored viewport is drawn")
      assert(ok(host.undo()), "a restore is one recorded change")
      assertEquals(selected(host), Set("r7"))
      // A snapshot of other data is refused with its reason, changing nothing.
      val other = ok(
        JavaFxInteractionHost.mount(
          view(scatterPlan(revision = "d9")),
          InteractionBehavior.default[String]
        )
      )
      val refused = other.restore(ok(InteractionSnapshot.fromJson(text)))
      assert(refused.left.exists(_.isInstanceOf[SnapshotError]), refused)
      assertEquals(selected(other), Set.empty)
      ok(other.dispose())
      ok(host.dispose())
    }
  }

  test("undo in one linked host is carried to the group; the receiving host records nothing") {
    fx {
      val a = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      val b = ok(
        JavaFxInteractionHost.mount(
          view(scatterPlan(id = "b")),
          InteractionBehavior.default[String]
        )
      )
      val link = ok(JavaFxLink.connect(space, Vector(a, b)))
      click(a, "r1")
      click(a, "r2", shift = true)
      assertEquals(selected(b), Set("r1", "r2"))
      assert(!b.canUndo)
      ok(a.undo())
      assertEquals(selected(b), Set("r1"))
      link.dispose()
      ok(a.dispose())
      ok(b.dispose())
    }
  }

  test("a filter to a selection updates the host and shows its report in the inspector") {
    val command = FilterCommand[Obs, String](
      rows,
      space,
      _.id,
      (kept, data, planRevision) =>
        InteractionCompiler.compile(
          ok(plot(kept).aes(_.x, _.y).size(5).geomPoint().build).plot,
          space,
          data,
          SemanticId.unsafe("scatter"),
          planRevision,
          options
        )(_.id)
    )
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      val inspector = ok(JavaFxInspector.mount(Vector("scatter" -> host)))
      ok(host.setSelection(Selection(keys("r1", "r2", "r3"))))
      val result = ok(
        command.keep(
          scatterPlan(),
          keys("r1", "r2", "r3"),
          ok(DataRevision("f1")),
          ok(PlanRevision("f1"))
        )
      )
      ok(host.update(view(result.plan)))
      inspector.showFilter("scatter", result)
      assertEquals(selected(host), Set("r1", "r2", "r3"))
      assert(inspector.rows.contains(InspectorRow("Filter applied to scatter", "Rows", "12 → 3")))
      assertEquals(ok(host.currentView).navigation.targets.size, 3)
      inspector.dispose()
      ok(host.dispose())
    }
  }
