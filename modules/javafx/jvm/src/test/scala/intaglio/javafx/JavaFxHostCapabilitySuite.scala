package intaglio.javafx

import intaglio.*
import intaglio.interaction.*
import _root_.javafx.event.{Event, EventType}
import _root_.javafx.scene.SnapshotParameters
import _root_.javafx.scene.canvas.Canvas
import _root_.javafx.scene.input.{
  KeyCode,
  KeyEvent,
  MouseButton,
  MouseEvent,
  PickResult,
  ScrollEvent,
  ZoomEvent
}
import _root_.javafx.scene.paint.Color
import _root_.javafx.stage.Stage
import java.lang.ref.WeakReference

/** Interaction 12 host behaviour beyond the minimal host: tooltips, navigation through the shared
  * DataWindowNavigator and InteractionCompiler.rezoom, region selection, linked groups, plot parts,
  * aggregate members, explicit capability refusals, thread ownership and disposal. Headless Monocle
  * evidence (a simulated 2x screen); the desktop runtime is exercised by NativeEvidence.
  */
class JavaFxHostCapabilitySuite extends munit.FunSuite:
  override def beforeAll(): Unit = FxToolkit.start()

  private def fx[A](body: => A): A = FxToolkit.fx(body)
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)

  final case class Obs(id: String, x: Double, y: Double, group: String)
  private val rows = Vector.tabulate(12)(i =>
    Obs(s"r$i", 1.0 + i, 10.0 + (i * 7 % 11), if i % 2 == 0 then "a" else "b")
  )
  private val context = RenderContext.unsafe(480, 320)
  private val space = ok(KeySpace("obs", KeyCodec.text))
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())

  private def scatterPlan(
      id: String = "scatter",
      data: Vector[Obs] = rows,
      revision: String = "d1",
      at: RenderContext = context
  ) =
    ok(
      InteractionCompiler.compile(
        ok(
          plot(data)
            .aes(_.x, _.y)
            .scaleColorDiscrete(_.group, levels = Vector("a", "b"), name = "group")
            .size(5)
            .geomPoint()
            .title("Scatter")
            .build
        ).plot,
        space,
        ok(DataRevision(revision)),
        SemanticId.unsafe(id),
        ok(PlanRevision(revision)),
        options.copy(renderContext = Some(at))
      )(_.id)
    )

  private def histogramPlan(retention: MembershipRetention = MembershipRetention.ExactKeys) =
    ok(
      InteractionCompiler.compile(
        ok(plot(rows).aes(_.y).geomHistogram(bins = HistogramBins.countUnsafe(4)).build).plot,
        space,
        ok(DataRevision("d1")),
        SemanticId.unsafe("histogram"),
        ok(PlanRevision("d1")),
        options,
        retention
      )(_.id)
    )

  private def view(plan: InteractionPlan[String] = scatterPlan()) =
    ok(JavaFxInteractionView.compile(plan, context))

  private def mouse(
      host: JavaFxInteractionHost[?],
      kind: EventType[MouseEvent],
      x: Double,
      y: Double,
      shift: Boolean = false,
      alt: Boolean = false
  ): Unit =
    val scene = host.node.localToScene(x, y)
    Event.fireEvent(
      host.node,
      new MouseEvent(
        kind,
        scene.getX,
        scene.getY,
        scene.getX,
        scene.getY,
        MouseButton.PRIMARY,
        1,
        shift,
        false,
        alt,
        false,
        kind == MouseEvent.MOUSE_PRESSED || kind == MouseEvent.MOUSE_DRAGGED,
        false,
        false,
        false,
        false,
        true,
        new PickResult(host.node, scene.getX, scene.getY)
      )
    )

  private def key(host: JavaFxInteractionHost[?], code: KeyCode, shift: Boolean = false): Unit =
    Event.fireEvent(
      host.node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false)
    )

  private def local(host: JavaFxInteractionHost[String], id: String): (Double, Double) =
    val g = ok(host.currentView).navigation.targets.find(_.target.entity.exists(_.value == id)).get
    ok(host.toLocal(g.anchor)).get

  private def selected(host: JavaFxInteractionHost[String]): Set[String] =
    ok(host.state).selection.entities.map(_.value)

  private def mounted[A](host: => JavaFxInteractionHost[A])(
      body: (JavaFxInteractionHost[A], Stage) => Unit
  ) =
    val (h, stage) = fx {
      val h = host
      val stage = new Stage()
      stage.setScene(new _root_.javafx.scene.Scene(h.node, 480, 320))
      stage.show()
      h.node.requestFocus()
      (h, stage)
    }
    try fx(body(h, stage))
    finally fx { h.dispose(); stage.close() }

  // ---------------------------------------------------------------------------------------------
  // Capability matrix and refusals

  test("the capability matrix refuses each unsupported capability with an actionable reason") {
    val unsupported = JavaFxCapabilities.matrix.collect {
      case (c, CapabilitySupport.Unsupported(reason, instead)) => (c, reason, instead)
    }
    assertEquals(
      unsupported.map(_._1).toSet,
      Set(
        HostCapability.EmphasisTransitions,
        HostCapability.RuntimeTargetStyles,
        HostCapability.Toolbar,
        HostCapability.Fullscreen,
        HostCapability.PngExport,
        HostCapability.StandaloneHtml
      )
    )
    unsupported.foreach { (capability, reason, instead) =>
      assert(reason.nonEmpty && instead.nonEmpty)
      JavaFxCapabilities.require(capability) match
        case Left(InteractionError.UnsupportedCapability(component)) =>
          assert(component.contains(instead), component)
          assert(component.contains("JavaFX host"), component)
        case other => fail(s"$capability: $other")
    }
    assertEquals(JavaFxCapabilities.require(HostCapability.Pan), Right(()))
    for c <- Vector(
        HostCapability.Inspector,
        HostCapability.NamedSelections,
        HostCapability.UndoRedo,
        HostCapability.Snapshots,
        HostCapability.FilterToSelection
      )
    do assertEquals(JavaFxCapabilities.require(c), Right(()), c.toString)
    assertEquals(JavaFxCapabilities.matrix.size, HostCapability.values.length)
  }

  test("mounting refuses links without a handler, unknown legend links and resolverless deferral") {
    fx {
      val linking = InteractionBehavior
        .default[String]
        .withLink(t => t.entity.map(k => ok(TargetLink(s"#${k.value}"))))
      JavaFxInteractionHost.mount(view(), linking) match
        case Left(InteractionError.UnsupportedCapability(component)) =>
          assert(component.contains("onLink"), component)
        case other => fail(s"expected a refusal, got $other")
      var followed = Vector.empty[String]
      val host =
        ok(JavaFxInteractionHost.mount(view(), linking, onLink = Some(l => followed :+= l.url)))
      val (x, y) = local(host, "r3")
      mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
      mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
      mouse(host, MouseEvent.MOUSE_CLICKED, x, y)
      assertEquals(followed, Vector("#r3"))
      // Application selection is projected: no link is followed.
      ok(host.setSelection(Selection(Set(ok(space.entity("r4"))))))
      assertEquals(followed, Vector("#r3"))
      ok(host.dispose())

      val legend = InteractionBehavior
        .default[String]
        .withLegendLink(LegendLink("nope-legend", ok(KeySpace("g", KeyCodec.text))))
      assert(
        JavaFxInteractionHost.mount(view(), legend).left.exists(_.message.contains("nope-legend"))
      )

      val members = InteractionBehavior
        .default[String]
        .withAggregateSelection(_ => AggregateSelection.Members)
      val deferred = view(histogramPlan(MembershipRetention.Deferred))
      assert(
        JavaFxInteractionHost.mount(deferred, members).left.exists(_.message.contains("resolver"))
      )
      val counted = view(histogramPlan(MembershipRetention.CountOnly))
      assert(JavaFxInteractionHost.mount(counted, members).isLeft, "count-only members are refused")
    }
  }

  test("navigation is refused, not ignored, on views that cannot navigate") {
    val wide = RenderContext.unsafe(900, 320)
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
    val composed = ok(
      JavaFxInteractionView.compileComposition(
        ok(
          InteractionComposition
            .row(Vector(scatterPlan("left", at = wide), scatterPlan("right", at = wide)), wide)
        ),
        ok(PlanRevision("c1"))
      )
    )
    fx {
      for host <- Vector(
          ok(JavaFxInteractionHost.mount(named, InteractionBehavior.default[GraphicsName]))
        )
      do
        assert(!host.isNavigable)
        assert(
          host
            .navigate(PanelWindow(Some((0.2, 0.4)), None))
            .left
            .exists(_.isInstanceOf[InteractionError.UnsupportedCapability])
        )
        assert(
          host
            .setGestureMode(GestureMode.Pan)
            .left
            .exists(_.isInstanceOf[InteractionError.UnsupportedCapability])
        )
        assert(host.zoomBy(0.5).isLeft)
        ok(host.setGestureMode(GestureMode.Rectangle))
        ok(host.dispose())
      val host = ok(JavaFxInteractionHost.mount(composed, InteractionBehavior.default[String]))
      assert(!host.isNavigable)
      assert(host.resetWindow().left.exists(_.isInstanceOf[InteractionError.UnsupportedCapability]))
      // Both children are hosted: their marks carry the same key space.
      assertEquals(
        ok(host.currentView).navigation.targets.flatMap(_.target.entity).map(_.value).toSet,
        rows.map(_.id).toSet
      )
      assertEquals(ok(host.currentView).navigation.targets.size, 2 * rows.size)
      ok(host.dispose())
    }
  }

  test("every public entry point refuses the wrong thread, and a disposed host refuses use") {
    val v = view()
    assertEquals(
      JavaFxInteractionHost.mount(v, InteractionBehavior.default[String]).left.toOption,
      Some(JavaFxHostError.WrongThread)
    )
    val (host, saved) = fx {
      val host = ok(JavaFxInteractionHost.mount(v, InteractionBehavior.default[String]))
      (host, ok(host.snapshot))
    }
    val name = SelectionName.unsafe("n")
    val point = DevicePoint(10, 10)
    val panel = GraphicsName.unsafe(PlotRegion.Panel.value)
    // Every public method that returns Either; the plain getters are documented unchecked reads.
    def entryPoints: Vector[(String, () => Either[IntaglioError, Any])] = Vector(
      "state" -> (() => host.state),
      "currentView" -> (() => host.currentView),
      "setOverlayStyle" -> (() => host.setOverlayStyle(JavaFxOverlayStyle.default)),
      "subscribe" -> (() => host.subscribe(_ => ())),
      "subscribeParts" -> (() => host.subscribeParts(_ => ())),
      "subscribeHover" -> (() => host.subscribeHover(_ => ())),
      "subscribeState" -> (() => host.subscribeState(_ => ())),
      "setLinkedEmphasis" -> (() => host.setLinkedEmphasis(LinkedEmphasis.none[String])),
      "setSelection" -> (() => host.setSelection(Selection[String]())),
      "setHover" -> (() => host.setHover(None)),
      "setGestureMode" -> (() => host.setGestureMode(GestureMode.Pan)),
      "navigate" -> (() => host.navigate(PanelWindow.full)),
      "zoomBy" -> (() => host.zoomBy(0.5)),
      "resetWindow" -> (() => host.resetWindow()),
      "saveSelection" -> (() => host.saveSelection(name)),
      "recallSelection" -> (() => host.recallSelection(name, SelectionOperation.Replace)),
      "combineSelections" -> (() => host.combineSelections(name, name, SetCombination.Union, name)),
      "deleteSelection" -> (() => host.deleteSelection(name)),
      "snapshot" -> (() => host.snapshot),
      "restore" -> (() => host.restore(saved)),
      "undo" -> (() => host.undo()),
      "redo" -> (() => host.redo()),
      "inspector" -> (() => host.inspector()),
      "inspector(-1)" -> (() => host.inspector(-1)),
      "tooltip" -> (() => host.tooltip),
      "tooltipBounds" -> (() => host.tooltipBounds),
      "announcement" -> (() => host.announcement),
      "companionRows" -> (() => host.companionRows),
      "update" -> (() => host.update(v)),
      "toDevice" -> (() => host.toDevice(1, 1)),
      "toLocal" -> (() => host.toLocal(point)),
      "pickNative" -> (() => host.pickNative(panel, point))
    )
    for (method, call) <- entryPoints do
      assertEquals(call().left.toOption, Some(JavaFxHostError.WrongThread), method)
    assertEquals(host.dispose().left.toOption, Some(JavaFxHostError.WrongThread))
    assertEquals(
      JavaFxLink.connect(space, Vector(host)).left.toOption,
      Some(JavaFxHostError.WrongThread)
    )
    fx {
      ok(host.dispose())
      for (method, call) <- entryPoints do
        assertEquals(call().left.toOption, Some(JavaFxHostError.Disposed), method)
      assertEquals(host.node.getChildren.size, 0)
      assertEquals(host.dispose(), Right(()))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tooltips, inspection and inverse emphasis

  test("pointer hover shows a delayed text-only tooltip; keyboard focus shows it at once, inside") {
    val behavior = ok(
      InteractionBehavior
        .default[String]
        .withTooltip(t =>
          t.entity.map(k =>
            ok(
              TargetContent
                .fields(Some(s"Obs ${k.value}"), Vector(ok(TargetField("note", "<b>x</b>"))))
            )
          )
        )
        .withTooltipDelay(150)
    )
    mounted(ok(JavaFxInteractionHost.mount(view(), behavior))) { (host, _) =>
      val (x, y) = local(host, "r2")
      mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      assertEquals(ok(host.tooltip), None, "pointer tooltips wait for the delay")
    }
    Thread.sleep(400)
    // The delayed tooltip of the first host was cancelled by disposal; mount again to observe one.
    val host = fx(ok(JavaFxInteractionHost.mount(view(), behavior)))
    try
      fx {
        val (x, y) = local(host, "r2")
        mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      }
      Thread.sleep(400)
      fx {
        ok(host.tooltip) match
          case Some(TargetContent.Fields(Some("Obs r2"), rows)) =>
            assertEquals(rows.map(_.value), Vector("<b>x</b>"))
          case other => fail(s"tooltip $other")
        val texts = host.node.getChildren
          .get(2)
          .asInstanceOf[_root_.javafx.scene.layout.VBox]
          .getChildren
          .toArray
          .toVector
          .flatMap {
            case flow: _root_.javafx.scene.text.TextFlow =>
              flow.getChildren.toArray.toVector.collect { case t: _root_.javafx.scene.text.Text =>
                t.getText
              }
            case _ => Vector.empty
          }
        assert(texts.exists(_.contains("<b>x</b>")), "content is a literal Text, never markup")
        mouse(host, MouseEvent.MOUSE_EXITED, 0, 0)
        assertEquals(ok(host.tooltip), None)
        // Keyboard focus at the right edge: immediate, and inside the node.
        key(host, KeyCode.END)
        val (left, top, w, h) = ok(host.tooltipBounds).get
        assert(
          left >= 0 && top >= 0 && left + w <= host.node.getWidth + 0.5 && top + h <= host.node.getHeight + 0.5
        )
        assert(ok(host.announcement).startsWith("Obs "))
      }
    finally fx(ok(host.dispose()))
  }

  test("nearest hover reaches a sparse mark that direct hover misses; tooltip colours apply") {
    val near =
      ok(InteractionBehavior.describingEntities[String](id => id).withHover(HoverRule.Nearest(30)))
    val direct = InteractionBehavior.describingEntities[String](id => id)
    val colours =
      JavaFxHostOptions(tooltipBackground = Rgba.unsafe(10, 20, 30), tooltipText = Rgba.White)
    fx {
      def hovered(behavior: InteractionBehavior[String]): Option[VisualTargetId] =
        val host = ok(JavaFxInteractionHost.mount(view(), behavior, options = colours))
        val (x, y) = local(host, "r0")
        mouse(host, MouseEvent.MOUSE_MOVED, x + 12, y)
        val result = ok(host.state).hover
        ok(host.dispose())
        result
      assertEquals(hovered(direct), None)
      assert(hovered(near).nonEmpty)
      val host = ok(JavaFxInteractionHost.mount(view(), direct, options = colours))
      key(host, KeyCode.HOME)
      val box = host.node.getChildren.get(2).asInstanceOf[_root_.javafx.scene.layout.VBox]
      assertEquals(box.getBackground.getFills.get(0).getFill, Color.rgb(10, 20, 30))
      ok(host.dispose())
    }
  }

  test("fixed placement puts the tooltip at its configured coordinates") {
    val behavior = InteractionBehavior
      .describingEntities[String](id => s"obs $id")
      .withPlacement(TooltipPlacement.Fixed(20, 30))
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), behavior))
      key(host, KeyCode.HOME)
      val (left, top, _, _) = ok(host.tooltipBounds).get
      assertEquals((left, top), (20.0, 30.0))
      assertEquals(ok(host.companionRows).head._1, "mark")
      assert(
        ok(host.companionRows).exists((kind, text) => kind == "part" && text.contains("Scatter"))
      )
      ok(host.dispose())
    }
  }

  test("inverse emphasis dims the base and redraws the emphasized mark in its own paint") {

    /** Overlay ink strictly inside the hovered mark's bounds; the hover ring runs outside them. */
    def inside(inverse: Boolean): (Int, Double) =
      fx {
        val host = ok(
          JavaFxInteractionHost.mount(
            view(),
            InteractionBehavior.default[String].withInverseEmphasis(inverse)
          )
        )
        val base = host.node.getChildren.get(0).asInstanceOf[Canvas]
        assertEquals(base.getOpacity, 1.0)
        val g =
          ok(host.currentView).navigation.targets.find(_.target.entity.exists(_.value == "r5")).get
        val (x, y) = ok(host.toLocal(g.anchor)).get
        mouse(host, MouseEvent.MOUSE_MOVED, x, y)
        val dimmed = base.getOpacity
        val parameters = new SnapshotParameters()
        parameters.setFill(Color.TRANSPARENT)
        val overlay = host.node.getChildren.get(1).asInstanceOf[Canvas].snapshot(parameters, null)
        var ink = 0
        for
          py <- math.ceil(g.top).toInt until math.floor(g.bottom).toInt
          px <- math.ceil(g.left).toInt until math.floor(g.right).toInt
        do if (overlay.getPixelReader.getArgb(px, py) >>> 24) != 0 then ink += 1
        mouse(host, MouseEvent.MOUSE_EXITED, 0, 0)
        assertEquals(base.getOpacity, 1.0)
        ok(host.dispose())
        (ink, dimmed)
      }
    val (emphasized, dimmed) = inside(true)
    val (plain, undimmed) = inside(false)
    assertEquals((dimmed, undimmed), (0.3, 1.0))
    assert(emphasized > 0, "the hovered mark is redrawn in its own paint over the dimmed base")
    assertEquals(plain, 0, "without inverse emphasis the overlay only outlines the mark")
  }

  // ---------------------------------------------------------------------------------------------
  // Navigation through the shared navigator and rezoom

  test("keyboard, wheel and pinch zoom follow DataWindowNavigator and keep selection and stats") {
    val plan = scatterPlan()
    val nav = ok(DataWindowNavigator.of(plan, context))
    mounted(ok(JavaFxInteractionHost.mount(view(plan), InteractionBehavior.default[String]))) {
      (host, _) =>
        ok(host.setSelection(Selection(Set(ok(space.entity("r6"))))))
        val frame = view(plan).panelFrame.get
        key(host, KeyCode.EQUALS, shift = true)
        val centre = DevicePoint(frame.x + frame.width / 2, frame.y + frame.height / 2)
        val expected = ok(nav.normalize(nav.zoom(frame, centre, 0.8)))
        assertEquals(host.currentWindow, expected)
        assertEquals(ok(host.state).viewports.keySet.map(_.value), Set(PlotRegion.Panel.value))
        assertEquals(selected(host), Set("r6"), "a window never edits the selection")
        // The same plan in a narrower window: no statistic is recomputed and every target keeps its
        // identity; marks outside the window are simply not drawn.
        assertEquals(
          ok(host.currentView).plans.map(_.groups.map(_.size)),
          view(plan).plans.map(_.groups.map(_.size))
        )
        assertEquals(ok(host.currentView).plans.map(_.revision), Vector(plan.revision))
        key(host, KeyCode.DIGIT0)
        assertEquals(host.currentWindow, PanelWindow.full)
        // Wheel zoom about the pointer, only while the plot is focused.
        val (px, py) = ok(host.toLocal(centre)).get
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
            100,
            0,
            100,
            ScrollEvent.HorizontalTextScrollUnits.NONE,
            0,
            ScrollEvent.VerticalTextScrollUnits.NONE,
            0,
            0,
            new PickResult(host.node, px, py)
          )
        )
        assert(!host.currentWindow.isFull, "a wheel rotation away zooms in")
        key(host, KeyCode.DIGIT0)
        // Pinch: zoom is absolute from the gesture's start.
        def zoom(kind: EventType[ZoomEvent], total: Double) =
          Event.fireEvent(
            host.node,
            new ZoomEvent(
              kind,
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
              1.0,
              total,
              new PickResult(host.node, px, py)
            )
          )
        zoom(ZoomEvent.ZOOM_STARTED, 1.0)
        zoom(ZoomEvent.ZOOM, 2.0)
        val pinched = host.currentWindow
        val expectedPinch = ok(nav.normalize(nav.zoom(frame, centre, 0.5)))
        assertEqualsDouble(pinched.x.get._1, expectedPinch.x.get._1, 1e-9)
        assertEqualsDouble(pinched.x.get._2, expectedPinch.x.get._2, 1e-9)
        zoom(ZoomEvent.ZOOM_FINISHED, 2.0)
        ok(host.resetWindow())
        assertEquals(host.currentWindow, PanelWindow.full)
        assert(ok(host.state).viewports.isEmpty, "a reset removes the recorded viewport")
    }
  }

  test("a pan drag keeps the grabbed datum under the pointer and clamps at the bounds") {
    val plan = scatterPlan()
    mounted(ok(JavaFxInteractionHost.mount(view(plan), InteractionBehavior.default[String]))) {
      (host, _) =>
        ok(host.zoomBy(0.5))
        ok(host.setGestureMode(GestureMode.Pan))
        val zoomed = host.currentWindow
        // A mark still in the zoomed window.
        val grabbed = ok(host.currentView).navigation.targets.head.target.entity.get.value
        val (x, y) = local(host, grabbed)
        mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
        mouse(host, MouseEvent.MOUSE_DRAGGED, x + 40, y)
        mouse(host, MouseEvent.MOUSE_RELEASED, x + 40, y)
        mouse(host, MouseEvent.MOUSE_CLICKED, x + 40, y)
        val panned = host.currentWindow
        assert(panned.x.get._1 < zoomed.x.get._1, s"$zoomed -> $panned")
        // The grabbed datum now sits under the release point.
        val (nx, ny) = local(host, grabbed)
        assertEqualsDouble(nx, x + 40, 1.0)
        assertEqualsDouble(ny, y, 1.0)
        assertEquals(selected(host), Set.empty, "a drag is not a click")
        // Dragging far right clamps the window at the compiled lower bound (an unscaled axis: its
        // expanded data extent) instead of leaving the data.
        mouse(host, MouseEvent.MOUSE_PRESSED, 100, 100)
        mouse(host, MouseEvent.MOUSE_DRAGGED, 2000, 100)
        mouse(host, MouseEvent.MOUSE_RELEASED, 2000, 100)
        assertEqualsDouble(
          host.currentWindow.x.get._1,
          view(plan).panelFrame.get.xScale.lower,
          1e-9
        )
        ok(host.setGestureMode(GestureMode.Inspect))
    }
  }

  test("a zoom rectangle shows exactly the window the navigator computes for its corners") {
    val plan = scatterPlan()
    val nav = ok(DataWindowNavigator.of(plan, context))
    mounted(ok(JavaFxInteractionHost.mount(view(plan), InteractionBehavior.default[String]))) {
      (host, _) =>
        ok(host.setGestureMode(GestureMode.ZoomRectangle))
        val (ax, ay) = local(host, "r2")
        val (bx, by) = local(host, "r8")
        mouse(host, MouseEvent.MOUSE_PRESSED, ax, ay)
        mouse(host, MouseEvent.MOUSE_DRAGGED, bx, by)
        mouse(host, MouseEvent.MOUSE_RELEASED, bx, by)
        val a = ok(host.toDevice(ax, ay)).get
        val b = ok(host.toDevice(bx, by)).get
        val expected = ok(nav.normalize(nav.rectangle(view(plan).panelFrame.get, a, b)))
        // The drawn window is the re-windowed plan's frame, equal up to float resolution.
        assertEqualsDouble(host.currentWindow.x.get._1, expected.x.get._1, 1e-6)
        assertEqualsDouble(host.currentWindow.x.get._2, expected.x.get._2, 1e-6)
        assertEqualsDouble(host.currentWindow.y.get._1, expected.y.get._1, 1e-6)
        assertEqualsDouble(host.currentWindow.y.get._2, expected.y.get._2, 1e-6)
        ok(host.setGestureMode(GestureMode.Inspect))
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Selection gestures, parts, linked views, members and lifecycle

  test("rectangle and lasso sweeps replace, add and subtract exactly the covered marks") {
    mounted(ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))) {
      (host, _) =>
        ok(host.setGestureMode(GestureMode.Rectangle))
        def sweep(ids: (String, String), shift: Boolean = false, alt: Boolean = false) =
          val (ax, ay) = local(host, ids._1)
          val (bx, by) = local(host, ids._2)
          val (x0, x1) = (math.min(ax, bx) - 3, math.max(ax, bx) + 3)
          val (y0, y1) = (math.min(ay, by) - 3, math.max(ay, by) + 3)
          mouse(host, MouseEvent.MOUSE_PRESSED, x0, y0, shift, alt)
          mouse(host, MouseEvent.MOUSE_DRAGGED, x1, y1, shift, alt)
          mouse(host, MouseEvent.MOUSE_RELEASED, x1, y1, shift, alt)
          mouse(host, MouseEvent.MOUSE_CLICKED, x1, y1, shift, alt)
        // r0 (1,10) and r1 (2,17): every mark whose centre lies in their box.
        def inside(a: String, b: String) =
          val (ax, ay) = local(host, a)
          val (bx, by) = local(host, b)
          rows
            .map(_.id)
            .filter { id =>
              val (x, y) = local(host, id)
              x >= math.min(ax, bx) - 3 && x <= math
                .max(ax, bx) + 3 && y >= math.min(ay, by) - 3 && y <= math.max(ay, by) + 3
            }
            .toSet
        sweep(("r0", "r1"))
        assertEquals(selected(host), inside("r0", "r1"))
        sweep(("r10", "r11"), shift = true)
        assertEquals(selected(host), inside("r0", "r1") ++ inside("r10", "r11"))
        sweep(("r0", "r1"), alt = true)
        assertEquals(selected(host), inside("r10", "r11") -- inside("r0", "r1"))
        ok(host.setGestureMode(GestureMode.Lasso))
        // r2 (3, 13), r4 (5, 16) and r7 (8, 15): a proper triangle, widened about its centroid.
        val corners = Vector("r2", "r4", "r7").map(local(host, _))
        val cx = corners.map(_._1).sum / 3
        val cy = corners.map(_._2).sum / 3
        val ring = corners.map((x, y) => (cx + (x - cx) * 1.5, cy + (y - cy) * 1.5))
        mouse(host, MouseEvent.MOUSE_PRESSED, ring(0)._1, ring(0)._2)
        ring.tail.foreach((x, y) => mouse(host, MouseEvent.MOUSE_DRAGGED, x, y))
        mouse(host, MouseEvent.MOUSE_DRAGGED, ring(0)._1 + 1, ring(0)._2 + 1)
        mouse(host, MouseEvent.MOUSE_RELEASED, ring(0)._1 + 1, ring(0)._2 + 1)
        assert(Set("r2", "r4", "r7").subsetOf(selected(host)), selected(host))
        // Escape first abandons a drag in progress, keeping the selection.
        val before = selected(host)
        mouse(host, MouseEvent.MOUSE_PRESSED, 10, 10)
        mouse(host, MouseEvent.MOUSE_DRAGGED, 60, 60)
        key(host, KeyCode.ESCAPE)
        assertEquals(selected(host), before)
        assertEquals(ok(host.state).gesture, None)
        ok(host.setGestureMode(GestureMode.Inspect))
    }
  }

  test("legend parts are typed, emphasize linked marks and select them as a reader action") {
    val groups = ok(KeySpace("group", KeyCodec.text))
    val program = ok(
      plot(rows)
        .aes(_.x, _.y)
        .scaleColorDiscrete(_.group, levels = Vector("a", "b"), name = "group")
        .size(5)
        .geomPoint()
        .build
    )
    val bound = ok(
      InteractionCompiler.compileBound(
        program.plot,
        Vector(
          LayerBinding(program.plot.layers.head, space)(row => row.asInstanceOf[Obs].id)
            .withLinks(groups)(row => row.asInstanceOf[Obs].group)
        ),
        ok(DataRevision("d1")),
        SemanticId.unsafe("legend"),
        ok(PlanRevision("d1")),
        options
      )
    )
    val v = view(bound)
    val behavior =
      InteractionBehavior.default[String].withLegendLink(LegendLink("group-legend", groups))
    fx {
      val host = ok(JavaFxInteractionHost.mount(v, behavior))
      var parts = Vector.empty[JavaFxPartEvent]
      ok(host.subscribeParts(parts :+= _))
      var pointed = Vector.empty[LinkedEmphasis[String]]
      ok(host.subscribeHover(pointed :+= _))
      val names = ok(NamedPicking.fromResolved(v.deviceScene, v.context))
      val ring =
        ok(names.outline(GraphicsName.unsafe("group-legend-entry-0-key"), 0)).get.rings.head
      val centre = DevicePoint(
        (ring.map(_.x).min + ring.map(_.x).max) / 2,
        (ring.map(_.y).min + ring.map(_.y).max) / 2
      )
      val (x, y) = ok(host.toLocal(centre)).get
      mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      assert(
        parts.exists {
          case JavaFxPartEvent.Hovered(Some(PlotPart.LegendEntry("group-legend", _, _, "a"))) =>
            true
          case _ => false
        },
        parts
      )
      assert(pointed.lastOption.exists(_.links.nonEmpty), "the entry points at its category")
      mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
      mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
      mouse(host, MouseEvent.MOUSE_CLICKED, x, y)
      assertEquals(selected(host), rows.filter(_.group == "a").map(_.id).toSet)
      assert(parts.exists(_.isInstanceOf[JavaFxPartEvent.Activated]))
      ok(host.dispose())
    }
  }

  test("linked hosts project a reader's change without echo and report missing keys") {
    val other = Vector.tabulate(6)(i => Obs(s"r${i * 2}", i.toDouble, i.toDouble, "a"))
    fx {
      val a =
        ok(JavaFxInteractionHost.mount(view(scatterPlan("a")), InteractionBehavior.default[String]))
      val b = ok(
        JavaFxInteractionHost.mount(
          view(scatterPlan("b", other.reverse)),
          InteractionBehavior.default[String]
        )
      )
      var missing = Vector.empty[Set[String]]
      var events = Vector.empty[InputCause]
      ok(b.subscribe(e => events :+= e.stamp.cause))
      val link =
        ok(JavaFxLink.connect(space, Vector(a, b), (_, keys) => missing :+= keys.map(_.value)))
      assert(JavaFxLink.connect(space, Vector(a)).isLeft, "a host joins one live link")
      val (x, y) = local(a, "r3")
      mouse(a, MouseEvent.MOUSE_MOVED, x, y)
      mouse(a, MouseEvent.MOUSE_PRESSED, x, y)
      mouse(a, MouseEvent.MOUSE_RELEASED, x, y)
      mouse(a, MouseEvent.MOUSE_CLICKED, x, y)
      assertEquals(selected(b), Set.empty)
      assertEquals(missing, Vector(Set("r3")))
      val (x2, y2) = local(a, "r4")
      mouse(a, MouseEvent.MOUSE_MOVED, x2, y2)
      mouse(a, MouseEvent.MOUSE_PRESSED, x2, y2, shift = true)
      mouse(a, MouseEvent.MOUSE_RELEASED, x2, y2, shift = true)
      mouse(a, MouseEvent.MOUSE_CLICKED, x2, y2, shift = true)
      assertEquals(selected(b), Set("r4"))
      assertEquals(events, Vector.empty, "projection emits no event in the receiving host")
      // b's reader adds r6: the group keeps r3, which b lacks.
      val (x3, y3) = local(b, "r6")
      mouse(b, MouseEvent.MOUSE_MOVED, x3, y3)
      mouse(b, MouseEvent.MOUSE_PRESSED, x3, y3, shift = true)
      mouse(b, MouseEvent.MOUSE_RELEASED, x3, y3, shift = true)
      mouse(b, MouseEvent.MOUSE_CLICKED, x3, y3, shift = true)
      assertEquals(selected(a), Set("r3", "r4", "r6"))
      assertEquals(link.selection.map(_.value), Set("r3", "r4", "r6"))
      // Application selection is projected and does not propagate.
      ok(a.setSelection(Selection(Set(ok(space.entity("r0"))))))
      assertEquals(selected(b), Set("r4", "r6"))
      link.dispose()
      ok(a.dispose())
      ok(b.dispose())
    }
  }

  test("a members-mode bin selects its exact members and reports partial coverage") {
    val behavior = InteractionBehavior
      .default[String]
      .withTooltip(t => t.membership.total.map(n => TargetContent.Text(s"$n obs")))
      .withAggregateSelection(_ => AggregateSelection.Members)
    val plan = histogramPlan()
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(plan), behavior))
      val bin =
        ok(host.currentView).navigation.targets.find(_.target.membership.total.exists(_ > 1)).get
      val members = ok(bin.target.membership.exactKeys(plan.sourceRevision)).map(_.value).toSet
      val (x, y) = ok(host.toLocal(bin.anchor)).get
      mouse(host, MouseEvent.MOUSE_MOVED, x, y)
      mouse(host, MouseEvent.MOUSE_PRESSED, x, y)
      mouse(host, MouseEvent.MOUSE_RELEASED, x, y)
      mouse(host, MouseEvent.MOUSE_CLICKED, x, y)
      assertEquals(selected(host), members)
      assert(host.selectableEntities.map(_.value).subsetOf(rows.map(_.id).toSet))
      ok(host.setSelection(Selection(Set(ok(space.entity(members.head))))))
      val row = ok(host.companionRows).find(_._2.startsWith(s"${members.size} obs")).get
      assert(row._2.endsWith(s"(1 of ${members.size} selected)"), row)
      ok(host.dispose())
    }
  }

  test("update reconciles a new revision by key and keeps state for the same revision") {
    fx {
      val host = ok(JavaFxInteractionHost.mount(view(), InteractionBehavior.default[String]))
      var events = Vector.empty[InteractionEvent[String]]
      ok(host.subscribe(e => events :+= e.event))
      ok(host.setSelection(Selection(Set("r1", "r2").map(id => ok(space.entity(id))))))
      ok(host.zoomBy(0.5))
      ok(host.update(view()))
      assertEquals(selected(host), Set("r1", "r2"))
      assertEquals(host.currentWindow, PanelWindow.full, "a new view is the new unwindowed base")
      // The new data lacks r0 and r1: r1 is dropped and reported, r2 stays.
      ok(host.update(view(scatterPlan(data = rows.drop(2), revision = "d2"))))
      assertEquals(selected(host), Set("r2"))
      assert(
        events.exists {
          case InteractionEvent.Reconciled(removed, _, _) => removed.map(_.value) == Set("r1")
          case _                                          => false
        },
        events
      )
      ok(host.dispose())
    }
  }

  test("1000 mount/dispose cycles with tooltips, links and navigation leave hosts collectable") {
    val v = view()
    val behavior =
      InteractionBehavior.describingEntities[String](id => id).withInverseEmphasis(true)
    val refs = fx {
      (0 until 1000).map { i =>
        val host = ok(JavaFxInteractionHost.mount(v, behavior))
        key(host, KeyCode.HOME)
        if i % 10 == 0 then ok(host.zoomBy(0.8))
        ok(host.dispose())
        assert(!host.node.isFocusTraversable && host.node.getChildren.isEmpty)
        new WeakReference(host)
      }
    }
    var attempts = 0
    while refs.exists(_.get() != null) && attempts < 40 do
      System.gc()
      Thread.sleep(25)
      fx(())
      attempts += 1
    assert(refs.count(_.get() != null) <= 1, s"${refs.count(_.get() != null)} hosts retained")
  }
