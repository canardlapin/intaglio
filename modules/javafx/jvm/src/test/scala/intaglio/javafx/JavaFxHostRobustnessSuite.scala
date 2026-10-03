package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.event.{Event, EventType}
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.input.{KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import _root_.javafx.scene.paint.Color

/** Defects two reviews found, each pinned by a test that failed before its fix: a pan drag held
  * still stays one history entry; listener, tooltip, description and link-callback failures are
  * reported, not dropped or thrown, and cannot leave a link half-projected; the outline cache is
  * bounded across resizes; the accessible text leaves with the focused mark; state listeners are
  * neither muted by one subscribed during delivery nor called after unsubscribing during it; a
  * restore whose viewport cannot be drawn is refused, and one that draws the window already shown
  * records nothing; a viewport a view cannot draw is reported once and dropped. Three guards passed
  * before any fix and stay as guards: a pan abandoned with Escape, a pan right after a key zoom,
  * and an out-of-bounds restore that moves the window. Then the surface the first review found
  * untested: link disposal, linked emphasis, drawn entities, redo at the end of history, undo
  * across new data, and projection into an updated member. Headless Monocle evidence.
  */
class JavaFxHostRobustnessSuite extends munit.FunSuite:
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
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())

  private def scatter(
      data: Vector[Obs] = rows,
      revision: String = "d1",
      id: String = "scatter",
      at: RenderContext = context
  ): InteractionPlan[String] =
    ok(
      InteractionCompiler.compile(
        ok(
          plot(data)
            .aes(_.x, _.y)
            .scaleColorDiscrete(_.group, levels = Vector("a", "b"), name = "group")
            .size(5)
            .geomPoint()
            .build
        ).plot,
        space,
        ok(DataRevision(revision)),
        SemanticId.unsafe(id),
        ok(PlanRevision(revision)),
        options.copy(renderContext = Some(at))
      )(_.id)
    )

  private def histogram =
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

  private def view(plan: InteractionPlan[String] = scatter()) =
    ok(JavaFxInteractionView.compile(plan, context))

  private def plain(
      plan: InteractionPlan[String] = scatter(),
      onError: IntaglioError => Unit = _ => ()
  ) =
    ok(
      JavaFxInteractionHost.mount(
        view(plan),
        InteractionBehavior.default[String],
        onError = onError
      )
    )

  private def keys(ids: String*) = ids.map(id => ok(space.entity(id))).toSet
  private def selected(host: JavaFxInteractionHost[String]) =
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

  private def key(host: JavaFxInteractionHost[?], code: KeyCode): Unit =
    Event.fireEvent(
      host.node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)
    )

  private def at(host: JavaFxInteractionHost[String], id: String): (Double, Double) =
    val g = ok(host.currentView).navigation.targets.find(_.target.entity.exists(_.value == id)).get
    ok(host.toLocal(g.anchor)).get

  private def click(host: JavaFxInteractionHost[String], id: String, shift: Boolean = false) =
    val (x, y) = at(host, id)
    mouse(host, MouseEvent.MOUSE_MOVED, x, y)
    mouse(host, MouseEvent.MOUSE_PRESSED, x, y, shift)
    mouse(host, MouseEvent.MOUSE_RELEASED, x, y, shift)
    mouse(host, MouseEvent.MOUSE_CLICKED, x, y, shift)

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

  private def callbackFailed(errors: Vector[IntaglioError]): Boolean =
    errors.exists(_.isInstanceOf[InteractionError.CallbackFailed])

  // ---------------------------------------------------------------------------------------------
  // A pan drag held still is still one entry

  test("a pan drag held still past the navigation pause is one history entry") {
    val (host, zoomed) = fx {
      val host = plain()
      host.node.resize(480, 320)
      ok(host.zoomBy(0.5))
      val zoomed = host.currentWindow
      ok(host.setGestureMode(GestureMode.Pan))
      mouse(host, MouseEvent.MOUSE_PRESSED, 240, 160)
      (1 to 10).foreach(i => mouse(host, MouseEvent.MOUSE_DRAGGED, 240 + i * 2, 160))
      (host, zoomed)
    }
    // The reader holds the button still for longer than the pause that ends wheel and key runs.
    Thread.sleep((JavaFxInteractionHost.navigationPauseMs * 2).toLong)
    fx {
      (11 to 20).foreach(i => mouse(host, MouseEvent.MOUSE_DRAGGED, 240 + i * 2, 160))
      mouse(host, MouseEvent.MOUSE_RELEASED, 280, 160)
      assertNotEquals(host.currentWindow, zoomed, "the pan moved the window")
      assert(ok(host.undo()))
      assertEquals(host.currentWindow, zoomed, "one undo takes back the whole drag")
      assert(ok(host.undo()), "the zoom is still to be undone")
      assertEquals(host.currentWindow, PanelWindow.full)
      assert(!host.canUndo, "zoom and one pan: two entries, not three")
      ok(host.dispose())
    }
  }

  test("a pan drag abandoned with Escape is its own entry, not merged into the next zoom") {
    fx {
      val host = plain()
      host.node.resize(480, 320)
      ok(host.zoomBy(0.5))
      ok(host.setGestureMode(GestureMode.Pan))
      mouse(host, MouseEvent.MOUSE_PRESSED, 240, 160)
      (1 to 10).foreach(i => mouse(host, MouseEvent.MOUSE_DRAGGED, 240 + i * 2, 160))
      key(host, KeyCode.ESCAPE)
      val panned = host.currentWindow
      key(host, KeyCode.EQUALS)
      assertNotEquals(host.currentWindow, panned, "the key zoomed")
      assert(ok(host.undo()))
      assertEquals(host.currentWindow, panned, "undo takes back the key zoom only")
      ok(host.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Failures in application callbacks are reported

  test("a throwing event listener is reported through lastError and onError") {
    fx {
      var errors = Vector.empty[IntaglioError]
      val host = plain(onError = e => errors :+= e)
      assertEquals(host.lastError, None)
      ok(host.subscribe(_ => throw new RuntimeException("listener")))
      click(host, "r3")
      assertEquals(selected(host), Set("r3"), "the committed change stands")
      assert(callbackFailed(errors), errors)
      assert(host.lastError.exists(_.isInstanceOf[InteractionError.CallbackFailed]), host.lastError)
      ok(host.dispose())
    }
  }

  test("a throwing tooltip, hover listener or part listener is reported") {
    val behavior =
      InteractionBehavior.default[String].withTooltip(_ => throw new RuntimeException("tooltip"))
    fx {
      var errors = Vector.empty[IntaglioError]
      val host = ok(JavaFxInteractionHost.mount(view(), behavior, onError = e => errors :+= e))
      val (x, y) = at(host, "r2")
      mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      assert(callbackFailed(errors), s"tooltip: $errors")
      ok(host.dispose())

      errors = Vector.empty
      val hovered = plain(onError = e => errors :+= e)
      ok(hovered.subscribeHover(_ => throw new RuntimeException("hover")))
      val (hx, hy) = at(hovered, "r2")
      mouse(hovered, MouseEvent.MOUSE_MOVED, hx, hy)
      assert(callbackFailed(errors), s"hover listener: $errors")
      ok(hovered.dispose())

      errors = Vector.empty
      val parts = plain(onError = e => errors :+= e)
      ok(parts.subscribeParts(_ => throw new RuntimeException("part")))
      val (lx, ly) = legendCentre(parts)
      mouse(parts, MouseEvent.MOUSE_MOVED, lx, ly)
      assert(callbackFailed(errors), s"part listener: $errors")
      ok(parts.dispose())
    }
  }

  test("a throwing onMissing neither stops projection nor leaves the link half-updated") {
    fx {
      val a = plain(scatter(id = "a"))
      val b = plain(scatter(rows.filterNot(_.id == "r4"), id = "b"))
      val c = plain(scatter(rows.reverse, id = "c"))
      var errors = Vector.empty[IntaglioError]
      val link = ok(
        JavaFxLink.connect(
          space,
          Vector(a, b, c),
          onMissing = (_, _) => throw new RuntimeException("onMissing"),
          onError = (_, e) => errors :+= e
        )
      )
      click(a, "r4")
      assertEquals(selected(c), Set("r4"), "the member after the throwing report is projected")
      assertEquals(link.selection.map(_.value), Set("r4"))
      assert(callbackFailed(errors), errors)
      click(a, "r6", shift = true)
      assertEquals(selected(b), Set("r6"))
      assertEquals(selected(c), Set("r4", "r6"))
      link.dispose()
      Vector(a, b, c).foreach(h => ok(h.dispose()))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Bounded outline cache, accessible text, state listeners

  test("geometry outlines are cached for the current scale only, however often the node resizes") {
    fx {
      val host = plain()
      host.node.resize(480, 320)
      ok(host.setOverlayStyle(host.overlayStyle.withOutline(OverlayOutline.Geometry)))
      ok(host.setSelection(Selection(keys("r1", "r2", "r3"))))
      val oneScale = host.outlineCacheSize
      assert(oneScale > 0, "selection outlines are traced")
      (1 to 30).foreach(i => host.node.resize(480 + i * 7, 320 + i * 5))
      host.node.resize(480, 320)
      assert(
        host.outlineCacheSize <= oneScale,
        s"${host.outlineCacheSize} outlines kept, $oneScale at one scale"
      )
      ok(host.dispose())
    }
  }

  test("the accessible text returns to the plot's description when the focused mark goes") {
    fx {
      val host = plain()
      val initial = host.node.getAccessibleText
      key(host, KeyCode.HOME)
      val focused = ok(host.state).focus.get
      assertNotEquals(host.node.getAccessibleText, initial, "the focused mark is described")
      val focusedId = ok(host.currentView).navigation.targets
        .find(_.target.id == focused)
        .flatMap(_.target.entity)
        .get
        .value
      ok(host.update(view(scatter(rows.filterNot(_.id == focusedId), "d2"))))
      assertEquals(ok(host.state).focus, None)
      assertEquals(host.node.getAccessibleText, initial)
      ok(host.dispose())
    }
  }

  test("a state listener subscribed during delivery does not mute the listeners before it") {
    fx {
      val host = plain()
      var heard = Vector.empty[Set[String]]
      ok(host.subscribeState(s => heard :+= s.selection.entities.map(_.value)))
      var late = Vector.empty[Set[String]]
      ok(host.subscribe { record =>
        record.event match
          case InteractionEvent.SelectionChanged(_) if late.isEmpty =>
            ok(host.subscribeState(s => late :+= s.selection.entities.map(_.value)))
          case _ => ()
      })
      click(host, "r3")
      assertEquals(heard, Vector(Set.empty, Set("r3")), "the first listener hears the change")
      assertEquals(late, Vector(Set("r3")), "the late listener hears the state once")
      click(host, "r5")
      assertEquals(heard.last, Set("r5"))
      assertEquals(late.last, Set("r5"))
      ok(host.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // A successful restore draws what it records

  test("restore refuses a viewport this host cannot draw, changing nothing") {
    val names = ok(NamedInteraction.keySpace("marks"))
    val ink = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
    val named = ok(
      JavaFxInteractionView.named(
        Scene(
          Vector(
            Grob.circleUnsafe(
              Point.npcUnsafe(0.5, 0.5),
              ExtentExpr.pointsUnsafe(8),
              ink,
              name = Some(GraphicsName.unsafe("dot"))
            )
          )
        ),
        context,
        names,
        SemanticId.unsafe("named"),
        ok(PlanRevision("v1"))
      )
    )
    val panel = JavaFxInteractionHost.panelId.value
    fx {
      val host = ok(JavaFxInteractionHost.mount(named, InteractionBehavior.default[GraphicsName]))
      val windowed = ok(host.snapshot)
        .copy(viewports = Vector(SnapshotViewport(panel, 0.1, 0.5, 0.1, 0.5)))
      val refused = host.restore(windowed)
      assert(refused.left.exists(_.isInstanceOf[SnapshotError]), refused)
      assertEquals(ok(host.state).viewports, Map.empty[SemanticId, PanelViewport])
      assert(!host.canUndo)
      ok(host.dispose())

      // A navigable plot draws its one panel; a viewport for a panel it does not have is refused.
      val plotHost = plain()
      val elsewhere = ok(plotHost.snapshot)
        .copy(viewports = Vector(SnapshotViewport("another-panel", 0.1, 0.5, 0.1, 0.5)))
      val other = plotHost.restore(elsewhere)
      assert(other.left.exists(_.isInstanceOf[SnapshotError]), other)
      assertEquals(ok(plotHost.state).viewports, Map.empty[SemanticId, PanelViewport])
      ok(plotHost.dispose())
    }
  }

  test("a restored viewport outside the bounds is drawn shifted inside and recorded as drawn") {
    fx {
      val host = plain()
      ok(host.zoomBy(0.5))
      val (x0, x1) = host.currentWindow.x.get
      val (y0, y1) = host.currentWindow.y.get
      val far = ok(host.snapshot).copy(viewports =
        Vector(
          SnapshotViewport(JavaFxInteractionHost.panelId.value, x0 + 1e3, x1 + 1e3, y0, y1)
        )
      )
      ok(host.resetWindow())
      ok(host.restore(far))
      val drawn = host.currentWindow
      assert(!drawn.isFull)
      val recorded = ok(host.state).viewports(JavaFxInteractionHost.panelId)
      assertEqualsDouble(recorded.xMin, drawn.x.get._1, 1e-9)
      assertEqualsDouble(recorded.xMax, drawn.x.get._2, 1e-9)
      assertEqualsDouble(drawn.x.get._2 - drawn.x.get._1, x1 - x0, 1e-6)
      ok(host.dispose())
    }
  }

  test("a restore whose viewport draws the window already shown records nothing") {
    fx {
      val host = plain()
      // A viewport wider than the data is brought back to the full window, which is what is shown.
      val everything = ok(host.snapshot).copy(viewports =
        Vector(SnapshotViewport(JavaFxInteractionHost.panelId.value, -1e9, 1e9, -1e9, 1e9))
      )
      ok(host.restore(everything))
      assert(host.currentWindow.isFull)
      assertEquals(
        ok(host.state).viewports.get(JavaFxInteractionHost.panelId),
        None,
        "the state records the window drawn"
      )
      assert(!host.canUndo, "a restore that changes nothing is not an entry")
      ok(host.dispose())
    }
  }

  test("a throwing tooltip on the keyboard-focus path is reported; the host stays usable") {
    val behavior =
      InteractionBehavior.default[String].withTooltip(_ => throw new RuntimeException("tooltip"))
    fx {
      var errors = Vector.empty[IntaglioError]
      val host = ok(JavaFxInteractionHost.mount(view(), behavior, onError = e => errors :+= e))
      var heard = Vector.empty[Set[String]]
      ok(host.subscribeState(s => heard :+= s.selection.entities.map(_.value)))
      val once = Vector(InteractionError.CallbackFailed("describing a mark"))
      key(host, KeyCode.HOME)
      val focused = ok(host.state).focus
      assert(focused.nonEmpty, "the first mark is focused")
      assertEquals(errors, once, "one report, naming what failed")
      assertEquals(host.lastError, once.headOption)
      assertEquals(host.node.getAccessibleText, "mark 1 of 12")
      // Overlay redraws while the mark keeps focus describe it again without reporting again.
      ok(host.setOverlayStyle(host.overlayStyle))
      ok(host.setLinkedEmphasis(LinkedEmphasis[String](entities = keys("r2"))))
      key(host, KeyCode.ENTER)
      val chosen = ok(host.state).selection.entities.map(_.value)
      assertEquals(chosen.size, 1, "Enter still selects the focused mark")
      assertEquals(heard.last, chosen, "and state listeners still hear it")
      ok(host.setSelection(Selection(keys("r7"))))
      assertEquals(selected(host), Set("r7"))
      assertEquals(errors, once)
      assert(host.companionRows.left.exists(_.isInstanceOf[InteractionError.CallbackFailed]))
      // A new focus is a new description, reported once more.
      key(host, KeyCode.END)
      assertEquals(errors, once ++ once)
      ok(host.dispose())
    }
  }

  test("a viewport undone into a view that cannot draw it is reported once and dropped") {
    val wide = RenderContext.unsafe(900, 320)
    // The same data revision, hosted as a figure that cannot navigate.
    val figure = ok(
      JavaFxInteractionView.compileComposition(
        ok(
          InteractionComposition.row(
            Vector(scatter(id = "left", at = wide), scatter(id = "right", at = wide)),
            wide
          )
        ),
        ok(PlanRevision("d1"))
      )
    )
    fx {
      var errors = Vector.empty[IntaglioError]
      val host = plain(onError = e => errors :+= e)
      ok(host.zoomBy(0.5))
      ok(host.resetWindow())
      ok(host.update(figure))
      assert(!host.isNavigable)
      assert(ok(host.undo()), "the reset is undone into a state with a viewport")
      def refusals = errors.count(_.isInstanceOf[InteractionError.UnsupportedCapability])
      assertEquals(refusals, 1)
      assertEquals(
        ok(host.state).viewports.get(JavaFxInteractionHost.panelId),
        None,
        "the state is brought back to what is drawn"
      )
      ok(host.setSelection(Selection(keys("r1"))))
      ok(host.setSelection(Selection(keys("r2"))))
      assertEquals(refusals, 1, "and not reported again on every later dispatch")
      ok(host.dispose())
    }
  }

  test("a pan drag right after a key zoom is its own entry") {
    fx {
      val host = plain()
      host.node.resize(480, 320)
      ok(host.setGestureMode(GestureMode.Pan))
      key(host, KeyCode.EQUALS)
      val zoomed = host.currentWindow
      assert(!zoomed.isFull)
      // Well within the 400 ms that would end the key zoom's run.
      mouse(host, MouseEvent.MOUSE_PRESSED, 240, 160)
      (1 to 10).foreach(i => mouse(host, MouseEvent.MOUSE_DRAGGED, 240 + i * 2, 160))
      mouse(host, MouseEvent.MOUSE_RELEASED, 260, 160)
      assertNotEquals(host.currentWindow, zoomed)
      assert(ok(host.undo()))
      assertEquals(host.currentWindow, zoomed, "undo takes back the pan only")
      assert(ok(host.undo()))
      assertEquals(host.currentWindow, PanelWindow.full)
      ok(host.dispose())
    }
  }

  test("a state listener unsubscribed during delivery is not called for that change") {
    fx {
      val host = plain()
      var late = Vector.empty[Set[String]]
      var stopLate: () => Unit = () => ()
      ok(host.subscribeState { s =>
        if s.selection.entities.nonEmpty then stopLate()
      })
      stopLate = ok(host.subscribeState(s => late :+= s.selection.entities.map(_.value)))
      click(host, "r3")
      assertEquals(late, Vector(Set.empty), "only the call made when it subscribed")
      click(host, "r5")
      assertEquals(late, Vector(Set.empty))
      ok(host.dispose())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Surface the review found untested

  test("a disposed link stops propagating selection and emphasis, and disposes idempotently") {
    fx {
      val a = plain(scatter(id = "a"))
      val b = plain(scatter(rows.reverse, id = "b"))
      val link = ok(JavaFxLink.connect(space, Vector(a, b)))
      click(a, "r2")
      assertEquals(selected(b), Set("r2"))
      link.dispose()
      link.dispose()
      assertEquals(selected(b), Set("r2"), "disposal leaves each selection as it is")
      click(a, "r5")
      assertEquals(selected(a), Set("r5"))
      assertEquals(selected(b), Set("r2"), "no projection after disposal")
      val inkBefore = overlayInk(b)
      val (x, y) = at(a, "r7")
      mouse(a, MouseEvent.MOUSE_MOVED, x, y)
      assertEquals(overlayInk(b), inkBefore, "no linked emphasis after disposal")
      assert(a.link.isEmpty && b.link.isEmpty)
      val again = ok(JavaFxLink.connect(space, Vector(a, b)))
      click(a, "r8")
      // A new group starts from both members' selections, as a fresh link always does.
      assertEquals(selected(b), Set("r2", "r8"), "the hosts can join a new link")
      again.dispose()
      ok(a.dispose())
      ok(b.dispose())
    }
  }

  test("linked emphasis is display only: it draws, clears, and changes no state or events") {
    fx {
      val host = plain()
      var events = Vector.empty[InteractionEvent[String]]
      ok(host.subscribe(e => events :+= e.event))
      val before = ok(host.state)
      assertEquals(overlayInk(host), 0)
      ok(host.setLinkedEmphasis(LinkedEmphasis[String](entities = keys("r3", "r4"))))
      assert(overlayInk(host) > 0, "the emphasized marks are ringed")
      assertEquals(ok(host.state), before)
      ok(host.setLinkedEmphasis(LinkedEmphasis.none[String]))
      assertEquals(overlayInk(host), 0)
      assertEquals(events, Vector.empty)
      ok(host.dispose())
      assertEquals(
        host.setLinkedEmphasis(LinkedEmphasis.none[String]).left.toOption,
        Some(JavaFxHostError.Disposed)
      )
    }
  }

  test("isDisposed, drawnEntities and the capability lookup report what the host is") {
    fx {
      val scatterHost = plain()
      assert(!scatterHost.isDisposed)
      assertEquals(scatterHost.drawnEntities.map(_.value), rows.map(_.id).toSet)
      ok(scatterHost.dispose())
      assert(scatterHost.isDisposed)
      assertEquals(scatterHost.drawnEntities, Set.empty)
      val bins = plain(histogram)
      assertEquals(bins.drawnEntities, Set.empty, "a histogram draws no observation of its own")
      ok(bins.dispose())
    }
    assertEquals(JavaFxCapabilities.support(HostCapability.Pan), CapabilitySupport.Supported)
    JavaFxCapabilities.support(HostCapability.Toolbar) match
      case CapabilitySupport.Unsupported(reason, instead) =>
        assert(instead.contains("setGestureMode"), instead)
        assert(reason.nonEmpty)
      case other => fail(s"toolbar: $other")
    JavaFxCapabilities.matrix.foreach((c, s) => assertEquals(JavaFxCapabilities.support(c), s))
  }

  test("redo with nothing to redo is false; undo after new data has nothing to take back") {
    fx {
      val host = plain()
      assertEquals(host.redo(), Right(false))
      click(host, "r1")
      val saved = ok(host.snapshot)
      click(host, "r2")
      ok(host.restore(saved))
      assertEquals(selected(host), Set("r1"))
      assertEquals(host.redo(), Right(false), "a restore is a new change and clears redo")
      // New data clears history: the restore before it cannot be undone into the old revision.
      ok(host.update(view(scatter(rows.filterNot(_.id == "r9"), "d2"))))
      assertEquals(host.undo(), Right(false))
      assertEquals(selected(host), Set("r1"))
      // And the old snapshot is refused against the new data.
      assert(host.restore(saved).left.exists(_.isInstanceOf[SnapshotError]))
      ok(host.dispose())
    }
  }

  test("a linked member updated with new data still receives the group's selection") {
    fx {
      val a = plain(scatter(id = "a"))
      val b = plain(scatter(id = "b"))
      var missing = Vector.empty[Set[String]]
      val link = ok(JavaFxLink.connect(space, Vector(a, b), (_, k) => missing :+= k.map(_.value)))
      click(a, "r1")
      assertEquals(selected(b), Set("r1"))
      ok(b.update(view(scatter(rows.filterNot(_.id == "r1"), "d2", id = "b"))))
      assertEquals(selected(b), Set.empty, "the dropped key is reconciled away")
      click(a, "r3", shift = true)
      assertEquals(selected(b), Set("r3"), "projection follows into the new data")
      assertEquals(missing, Vector(Set("r1")), "the key b no longer has is reported missing")
      link.dispose()
      ok(a.dispose())
      ok(b.dispose())
    }
  }
