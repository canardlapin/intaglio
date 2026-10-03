package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.event.{Event, EventType}
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import _root_.javafx.scene.layout.VBox
import _root_.javafx.scene.paint.Color

/** Contracts a review asked to pin down: `update` refuses what mounting refuses, atomically;
  * `canUndo`/`canRedo` are pure reads that never split a navigation run; a link ignores a disposed
  * member; FX-thread enforcement for Unit-returning mutators; linked hover and legend emphasis and
  * their recovery; disabled and initial selection; pointer and anchored tooltip placement. Headless
  * Monocle evidence.
  */
class JavaFxHostContractSuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def fx[A](body: => A): A = FxToolkit.fx(body)
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)

  final case class Obs(id: String, x: Double, y: Double, group: String)
  private val rows =
    Vector.tabulate(12)(i =>
      Obs(s"r$i", 1.0 + i, 10.0 + (i * 7 % 11), if i % 2 == 0 then "a" else "b")
    )
  private val context = RenderContext.unsafe(480, 320)
  private val space = ok(KeySpace("obs", KeyCodec.text))
  private val groups = ok(KeySpace("group", KeyCodec.text))
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())

  private def scatter(
      data: Vector[Obs] = rows,
      revision: String = "d1",
      id: String = "scatter",
      legend: Boolean = true
  ): InteractionPlan[String] =
    val spec =
      if legend then
        plot(data)
          .aes(_.x, _.y)
          .scaleColorDiscrete(_.group, levels = Vector("a", "b"), name = "group")
          .size(5)
          .geomPoint()
      else plot(data).aes(_.x, _.y).size(5).geomPoint()
    val program = ok(spec.build)
    ok(
      InteractionCompiler.compileBound(
        program.plot,
        Vector(
          LayerBinding(program.plot.layers.head, space)(row => row.asInstanceOf[Obs].id)
            .withLinks(groups)(row => row.asInstanceOf[Obs].group)
        ),
        ok(DataRevision(revision)),
        SemanticId.unsafe(id),
        ok(PlanRevision(revision)),
        options
      )
    )

  private def histogram(retention: MembershipRetention, revision: String = "d1") =
    ok(
      InteractionCompiler.compile(
        ok(plot(rows).aes(_.y).geomHistogram(bins = HistogramBins.countUnsafe(4)).build).plot,
        space,
        ok(DataRevision(revision)),
        SemanticId.unsafe("histogram"),
        ok(PlanRevision(revision)),
        options,
        retention
      )(_.id)
    )

  private def view(plan: InteractionPlan[String] = scatter()) =
    ok(JavaFxInteractionView.compile(plan, context))

  private def keys(ids: String*) = ids.map(id => ok(space.entity(id))).toSet
  private def selected(host: JavaFxInteractionHost[String]) =
    ok(host.state).selection.entities.map(_.value)

  private def mouse(
      host: JavaFxInteractionHost[?],
      kind: EventType[MouseEvent],
      x: Double,
      y: Double
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
        false,
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

  private def at(host: JavaFxInteractionHost[String], id: String): (Double, Double) =
    val g = ok(host.currentView).navigation.targets.find(_.target.entity.exists(_.value == id)).get
    ok(host.toLocal(g.anchor)).get

  private def click(host: JavaFxInteractionHost[String], id: String): Unit =
    val (x, y) = at(host, id)
    mouse(host, MouseEvent.MOUSE_MOVED, x, y)
    mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
    mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
    mouse(host, MouseEvent.MOUSE_CLICKED, x, y)

  private def overlayInk(host: JavaFxInteractionHost[?]): Int =
    val parameters = new SnapshotParameters()
    parameters.setFill(Color.TRANSPARENT)
    val image = host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(parameters, null)
    var n = 0
    for y <- 0 until image.getHeight.toInt; x <- 0 until image.getWidth.toInt do
      if (image.getPixelReader.getArgb(x, y) >>> 24) != 0 then n += 1
    n

  private def legendCentre(host: JavaFxInteractionHost[String]): (Double, Double) =
    val v = ok(host.currentView)
    val names = ok(NamedPicking.fromResolved(v.deviceScene, v.context))
    val ring = ok(names.outline(GraphicsName.unsafe("group-legend-entry-0-key"), 0)).get.rings.head
    ok(
      host.toLocal(
        DevicePoint(
          (ring.map(_.x).min + ring.map(_.x).max) / 2,
          (ring.map(_.y).min + ring.map(_.y).max) / 2
        )
      )
    ).get

  // ---------------------------------------------------------------------------------------------
  // update refuses what mounting refuses, atomically

  test("update refuses a deferred view without a resolver, changing nothing") {
    val members =
      InteractionBehavior.default[String].withAggregateSelection(_ => AggregateSelection.Members)
    fx {
      val host =
        ok(JavaFxInteractionHost.mount(view(histogram(MembershipRetention.ExactKeys)), members))
      ok(host.setSelection(Selection(keys("r1"))))
      val before = ok(host.currentView)
      val refused = host.update(view(histogram(MembershipRetention.Deferred, "d2")))
      assert(refused.left.exists(_.message.contains("resolver")), refused)
      assert(ok(host.currentView) eq before, "the old view stays")
      assertEquals(selected(host), Set("r1"))
      assertEquals(ok(host.state).domain.revision, before.domain.revision)
      ok(host.dispose())
    }
  }

  test("update refuses a view whose targets now carry links when there is no onLink handler") {
    val linking = InteractionBehavior
      .default[String]
      .withLink(t => t.entity.filter(_.value == "z1").map(_ => ok(TargetLink("#z1"))))
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), linking))
      val withZ = view(scatter(rows :+ Obs("z1", 5, 15, "a"), "d2"))
      host.update(withZ) match
        case Left(InteractionError.UnsupportedCapability(component)) =>
          assert(component.contains("onLink"), component)
        case other => fail(s"expected a refusal, got $other")
      assertEquals(ok(host.currentView).navigation.targets.size, rows.size)
      ok(host.dispose())
    }
  }

  test("update refuses a view that no longer draws a linked legend") {
    val behavior =
      InteractionBehavior.default[String].withLegendLink(LegendLink("group-legend", groups))
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), behavior))
      val refused = host.update(view(scatter(revision = "d2", legend = false)))
      assert(refused.left.exists(_.message.contains("group-legend")), refused)
      assertEquals(ok(host.state).domain.revision, ok(PlanRevision("d1")))
      ok(host.update(view(scatter(revision = "d3"))))
      ok(host.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // canUndo and canRedo never split a navigation run

  test("a listener asking canUndo on every frame leaves a 20-frame pan one entry") {
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      host.node.resize(480, 320)
      ok(host.zoomBy(0.5))
      val zoomed = host.currentWindow
      var asked = Vector.empty[(Boolean, Boolean)]
      ok(host.subscribeState(_ => asked :+= (host.canUndo -> host.canRedo)))
      ok(host.setGestureMode(GestureMode.Pan))
      mouse(host, MouseEvent.MOUSE_PRESSED, 240, 160)
      (1 to 20).foreach(i => mouse(host, MouseEvent.MOUSE_DRAGGED, 240 + i * 2, 160))
      assert(asked.size >= 20, s"the listener heard each frame (${asked.size})")
      assert(asked.drop(1).forall((u, r) => u && !r), "a run in progress can be undone, not redone")
      mouse(host, MouseEvent.MOUSE_RELEASED, 280, 160)
      ok(host.undo())
      assertEquals(host.currentWindow, zoomed, "one undo takes back the whole pan")
      assert(host.canRedo)
      ok(host.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Links, threads

  test("a link ignores a disposed member instead of reporting it on every hover") {
    fx {
      val hosts = Vector("a", "b", "c").map(id =>
        ok(JavaFxInteractionHost.mount(view(scatter(id = id)), InteractionBehavior.default[String]))
      )
      var errors = Vector.empty[IntaglioError]
      val link = ok(JavaFxLink.connect(space, hosts, onError = (_, e) => errors :+= e))
      ok(hosts(2).dispose())
      val (x, y) = at(hosts(0), "r4")
      mouse(hosts(0), MouseEvent.MOUSE_MOVED, x, y)
      mouse(hosts(0), MouseEvent.MOUSE_EXITED, 0, 0)
      click(hosts(0), "r4")
      assertEquals(errors, Vector.empty)
      assertEquals(selected(hosts(1)), Set("r4"), "the live member still follows")
      link.dispose()
      hosts.take(2).foreach(h => ok(h.dispose()))
    }
  }

  test("Unit-returning mutators throw off the FX thread; Either methods return WrongThread") {
    val (host, link, inspector, stops) = fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      val link = ok(JavaFxLink.connect(space, Vector(host)))
      val inspector = ok(JavaFxInspector.mount(Vector("p" -> host)))
      val stops = Vector(
        ok(host.subscribeParts(_ => ())),
        ok(host.subscribeHover(_ => ())),
        ok(host.subscribeState(_ => ()))
      )
      (host, link, inspector, stops)
    }
    stops.foreach(stop => intercept[IllegalStateException](stop()))
    intercept[IllegalStateException](link.dispose())
    intercept[IllegalStateException](inspector.clearFilter())
    intercept[IllegalStateException](inspector.dispose())
    val wrong = Some(JavaFxHostError.WrongThread)
    assertEquals(host.inspector().left.toOption, wrong)
    assertEquals(host.undo().left.toOption, wrong)
    assertEquals(host.snapshot.left.toOption, wrong)
    assertEquals(host.saveSelection(SelectionName.unsafe("x")).left.toOption, wrong)
    assertEquals(host.subscribeState(_ => ()).left.toOption, wrong)
    assertEquals(JavaFxInspector.mount(Vector("p" -> host)).left.toOption, wrong)
    fx {
      stops.foreach(_())
      inspector.dispose()
      link.dispose()
      ok(host.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Linked hover and legend emphasis, and their recovery

  test("hover and a linked legend entry emphasize marks in the other host, and recover") {
    val behavior =
      InteractionBehavior.default[String].withLegendLink(LegendLink("group-legend", groups))
    fx {
      val a = ok(JavaFxInteractionHost.mount(view(scatter(id = "a")), behavior))
      val b = ok(JavaFxInteractionHost.mount(view(scatter(rows.reverse, id = "b")), behavior))
      val link = ok(JavaFxLink.connect(space, Vector(a, b)))
      assertEquals(overlayInk(b), 0)
      val (x, y) = at(a, "r3")
      mouse(a, MouseEvent.MOUSE_MOVED, x, y)
      val oneMark = overlayInk(b)
      assert(oneMark > 0, "the hovered observation is ringed in the other host")
      mouse(a, MouseEvent.MOUSE_EXITED, 0, 0)
      assertEquals(overlayInk(b), 0, "and the ring goes when the pointer leaves")
      val (lx, ly) = legendCentre(a)
      mouse(a, MouseEvent.MOUSE_MOVED, lx, ly)
      assert(overlayInk(b) > 3 * oneMark, "a legend entry rings its whole category over there")
      mouse(a, MouseEvent.MOUSE_EXITED, 0, 0)
      assertEquals(overlayInk(b), 0, "legend emphasis recovers")
      link.dispose()
      ok(a.dispose())
      ok(b.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Selection modes and tooltip placement

  test("disabled selection activates without selecting; an initial selection holds before input") {
    fx {
      val disabled = ok(
        JavaFxInteractionHost.mount(
          view(),
          InteractionBehavior.default[String].withSelection(SelectionMode.Disabled)
        )
      )
      var events = Vector.empty[InteractionEvent[String]]
      ok(disabled.subscribe(e => events :+= e.event))
      click(disabled, "r2")
      assertEquals(selected(disabled), Set.empty)
      assert(events.exists(_.isInstanceOf[InteractionEvent.Activated[?]]))
      assert(!events.exists(_.isInstanceOf[InteractionEvent.SelectionChanged[?]]))
      ok(disabled.dispose())
      val initial = ok(
        JavaFxInteractionHost.mount(
          view(),
          InteractionBehavior.default[String],
          selection = Selection(keys("r1", "r5"))
        )
      )
      assertEquals(selected(initial), Set("r1", "r5"))
      ok(initial.dispose())
    }
  }

  test("pointer and anchored placement put the tooltip where the shared layout says") {
    def box(host: JavaFxInteractionHost[String]) =
      val b = host.node.getChildren.get(2).asInstanceOf[VBox]
      (b.getLayoutX, b.getLayoutY, b.getWidth, b.getHeight)
    for placement <- Vector(TooltipPlacement.Pointer(12), TooltipPlacement.Anchored(9)) do
      val behavior = ok(
        InteractionBehavior
          .describingEntities[String](id => s"obs $id")
          .withPlacement(placement)
          .withTooltipDelay(0)
      )
      fx {
        val host = ok(JavaFxInteractionHost.mount(view(), behavior))
        val (x, y) = at(host, "r2")
        // Hover a little off the anchor, so pointer and anchor placements differ.
        mouse(host, MouseEvent.MOUSE_MOVED, x + 1.5, y + 1.0)
        val (left, top, w, h) = box(host)
        val expected = TooltipLayout.place(
          placement,
          Some((x + 1.5, y + 1.0)),
          (x, y),
          w,
          h,
          host.node.getWidth,
          host.node.getHeight
        )
        assertEqualsDouble(left, expected.left, 1e-4) // layout coordinates are stored as floats
        assertEqualsDouble(top, expected.top, 1e-4) // layout coordinates are stored as floats
        // Keyboard focus has no pointer: both placements anchor at the mark.
        Event.fireEvent(
          host.node,
          new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.HOME, false, false, false, false)
        )
        val focusedId = ok(host.state).focus.get
        val g = ok(host.currentView).navigation.targets.find(_.target.id == focusedId).get
        val anchor = ok(host.toLocal(g.anchor)).get
        val (fl, ft, fw, fh) = box(host)
        val keyed = TooltipLayout.place(
          placement,
          None,
          anchor,
          fw,
          fh,
          host.node.getWidth,
          host.node.getHeight
        )
        assertEqualsDouble(fl, keyed.left, 1e-4) // layout coordinates are stored as floats
        assertEqualsDouble(ft, keyed.top, 1e-4) // layout coordinates are stored as floats
        ok(host.dispose())
      }
  }
