package intaglio.interaction

import intaglio.*

/** The shared host input contract: the same pointer and key meanings a JavaFX or browser host maps
  * its toolkit events to, checked by applying the produced actions through the reducer.
  */
class HostInputSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  private val space = ok(KeySpace("n", KeyCodec.integer))
  private val context = RenderContext.unsafe(300, 200)
  private val plan = ok(
    InteractionCompiler.compile(
      ok(Plot(Vector(1, 2, 3)).addLayer(Layer.point[Int](_.toDouble, i => (i % 2).toDouble))),
      space,
      ok(DataRevision("d")),
      SemanticId.unsafe("plot"),
      ok(PlanRevision("p")),
      PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    )(identity)
  )
  private val picking = ok(Picking.compile(plan, context))
  private val navigation = picking.prepareNavigation()
  // CSS and device pixels coincide: a 300 x 200 scene in a 300 x 200 box at the origin.
  private val viewport = ok(PickViewport.fit(300, 200, 0, 0, 300, 200))
  private val domain = ok(InteractionDomain(Vector(plan), ok(PlanRevision("p"))))
  private val anchors = navigation.targets.map(g => (g.anchor.x, g.anchor.y))

  private def input(behavior: InteractionBehavior[Int] = InteractionBehavior.default[Int]) =
    HostInput(picking, navigation, viewport, behavior)

  private def initial(mode: SelectionMode = SelectionMode.Multiple) =
    ok(InteractionState.initial(domain, mode))

  private var sequence = 0L
  private def apply(state: InteractionState[Int], actions: Vector[HostAction[Int]]) =
    actions.foldLeft(state) { (current, a) =>
      sequence += 1
      ok(
        InteractionState.reduce(
          current,
          InputStamp(domain.revision, SemanticId.unsafe("host"), sequence, a.cause),
          a.action
        )
      ).state
    }

  test("pointer movement hovers the mark under it once, and leaving clears it") {
    val host = input()
    val (x, y) = anchors.head
    val first = ok(host.pointer(initial(), PointerInput.Move(x, y)))
    assertEquals(
      first.map(_.action),
      Vector(InteractionAction.Hover(Some(navigation.targets.head.target.id)))
    )
    val hovered = apply(initial(), first)
    assertEquals(ok(host.pointer(hovered, PointerInput.Move(x + 0.5, y))), Vector.empty)
    assertEquals(
      ok(host.pointer(hovered, PointerInput.Leave)).map(_.action),
      Vector(InteractionAction.Hover(None))
    )
    // Empty space far from every mark reveals nothing under the direct rule.
    assertEquals(
      ok(host.pointer(hovered, PointerInput.Move(x + 40, y + 40))).map(_.action),
      Vector(InteractionAction.Hover(None))
    )
  }

  test("the nearest rule reaches a mark within its CSS distance") {
    val (x, y) = anchors.head
    val nearest = input(ok(InteractionBehavior.default[Int].withHover(HoverRule.Nearest(30))))
    assertEquals(ok(nearest.targetAt(x + 20, y)).map(_.id), Some(navigation.targets.head.target.id))
    assertEquals(ok(input().targetAt(x + 20, y)), None)
  }

  test("a click focuses, selects and activates; an additive click toggles") {
    val host = input()
    val (x, y) = anchors.head
    val clicked =
      apply(initial(), ok(host.pointer(initial(), PointerInput.Click(x, y, additive = false))))
    assertEquals(clicked.focus, Some(navigation.targets.head.target.id))
    assertEquals(clicked.selection.entities.size, 1)
    val (x2, y2) = anchors(1)
    val added =
      apply(clicked, ok(host.pointer(clicked, PointerInput.Click(x2, y2, additive = true))))
    assertEquals(added.selection.entities.size, 2)
    val toggledOff =
      apply(added, ok(host.pointer(added, PointerInput.Click(x2, y2, additive = true))))
    assertEquals(toggledOff.selection.entities.size, 1)
  }

  test("a cancelled pointer ends its gesture, and a new press is accepted") {
    val host = input()
    val pressed = apply(initial(), ok(host.pointer(initial(), PointerInput.Press)))
    assert(pressed.gesture.nonEmpty)
    val cancelled = apply(pressed, ok(host.pointer(pressed, PointerInput.Cancel)))
    assert(cancelled.gesture.isEmpty)
    assertEquals(ok(host.pointer(cancelled, PointerInput.Cancel)), Vector.empty)
    assert(apply(cancelled, ok(host.pointer(cancelled, PointerInput.Press))).gesture.nonEmpty)
  }

  test("disabled selection still activates") {
    val host = input()
    val (x, y) = anchors.head
    val actions = ok(host.pointer(initial(SelectionMode.Disabled), PointerInput.Click(x, y, false)))
    assert(!actions.exists(_.action.isInstanceOf[InteractionAction.Select[?]]))
    assert(actions.exists(_.action.isInstanceOf[InteractionAction.Activate[?]]))
  }

  test("keys rove focus, choose the focused mark, and escape clears the selection") {
    val host = input()
    val order = navigation.targets.map(_.target.id)
    val started =
      apply(initial(), ok(host.key(initial(), KeyInput.Arrow(NavigationDirection.Right))))
    assertEquals(started.focus, order.headOption)
    val last = apply(started, ok(host.key(started, KeyInput.Last)))
    assertEquals(last.focus, order.lastOption)
    val previous = apply(last, ok(host.key(last, KeyInput.Previous)))
    assertEquals(previous.focus, order.lift(order.size - 2))
    val chosen = apply(previous, ok(host.key(previous, KeyInput.Choose(additive = false))))
    assertEquals(chosen.selection.entities.size, 1)
    val cleared = apply(chosen, ok(host.key(chosen, KeyInput.Escape)))
    assert(cleared.selection.entities.isEmpty)
    // Choosing with nothing focused does nothing.
    assertEquals(ok(host.key(initial(), KeyInput.Choose(false))), Vector.empty)
  }

  test("tooltips sit below-right, flip at the edges, and never leave the widget") {
    val pointer = TooltipPlacement.Pointer(10)
    assertEquals(
      TooltipLayout.place(pointer, Some((50, 50)), (0, 0), 80, 30, 300, 200),
      TooltipBox(60, 60)
    )
    // Near the right and bottom edges the box flips to the pointer's other side.
    assertEquals(
      TooltipLayout.place(pointer, Some((280, 190)), (0, 0), 80, 30, 300, 200),
      TooltipBox(190, 150)
    )
    // A box wider than the widget is clamped to its left edge.
    assertEquals(TooltipLayout.place(pointer, Some((150, 20)), (0, 0), 400, 30, 300, 200).left, 0.0)
    // Keyboard focus has no pointer: the anchor is used.
    assertEquals(TooltipLayout.place(pointer, None, (40, 40), 80, 30, 300, 200), TooltipBox(50, 50))
    assertEquals(
      TooltipLayout
        .place(TooltipPlacement.Anchored(4), Some((200, 10)), (40, 40), 80, 30, 300, 200),
      TooltipBox(44, 44)
    )
    assertEquals(
      TooltipLayout.place(TooltipPlacement.Fixed(290, -5), Some((1, 1)), (0, 0), 80, 30, 300, 200),
      TooltipBox(220, 0)
    )
  }
