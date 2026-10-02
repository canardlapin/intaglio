package intaglio.interaction

import intaglio.*

class NamedInteractionSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 200)
  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)
  private def disc(x: Double, y: Double, radius: Double, name: Option[String]) =
    DeviceElement.Mark(DevicePrimitive.Disc(x, y, radius, fill, name.map(n)))
  private val keys = ok(NamedInteraction.keySpace("names"))
  private val revision = ok(PlanRevision("view-1"))

  /** Named discs, a two-part name, an unnamed part and a named group, interleaved. */
  private val scene = DeviceScene(
    200,
    200,
    Vector(
      disc(40, 40, 10, Some("a")),
      disc(100, 40, 10, None),
      disc(160, 40, 10, Some("b")),
      DeviceElement.Group(
        Some(n("group")),
        None,
        None,
        Vector(disc(40, 100, 8, None), disc(60, 100, 8, None))
      ),
      disc(100, 100, 12, Some("a")),
      disc(160, 160, 10, Some("c")),
      disc(165, 160, 10, Some("d"))
    )
  )
  private def bound(): NamedInteraction =
    ok(
      NamedInteraction(
        ok(NamedPicking.fromResolved(scene, context)),
        keys,
        SemanticId.unsafe("hand-built"),
        revision
      )
    )

  test("typed hits are the named plan's hits, with the name as the entity value") {
    val interaction = bound()
    for
      x <- 0 to 200 by 4
      y <- 0 to 200 by 4
      tolerance <- Vector(0.0, 6.0)
    do
      val point = DevicePoint(x.toDouble, y.toDouble)
      val named = ok(interaction.names.hits(point, tolerance))
      val typed = ok(interaction.picking.hits(point, tolerance))
      assertEquals(
        typed.map(hit => (hit.target.entity.map(_.value), hit.distanceDevicePx, hit.drawOrder)),
        named.map(hit => (Some(hit.name), hit.distanceDevicePx, hit.drawOrder)),
        point
      )
    val area = ok(PickArea.rectangle(0, 0, 120, 120))
    for rule <- AreaRule.values do
      assertEquals(
        interaction.picking.select(area, rule).flatMap(_.entity).map(_.value),
        interaction.names.select(area, rule)
      )
  }

  test("targets are addressed in draw order and found by name") {
    val interaction = bound()
    val navigation = interaction.prepareNavigation()
    assertEquals(
      navigation.targets.flatMap(_.target.entity).map(_.value),
      interaction.names.names
    )
    assertEquals(navigation.targets.map(_.target.id.ordinal), navigation.targets.indices.toVector)
    assertEquals(interaction.target(n("group")).flatMap(_.entity).map(_.value), Some(n("group")))
    assertEquals(interaction.target(n("absent")), None)
  }

  test("directional and sequential navigation move between names") {
    val interaction = bound()
    val navigation = interaction.prepareNavigation()
    def id(name: String) = interaction.target(n(name)).get.id
    def name(result: Either[PickingError, Option[TargetGeometry[GraphicsName]]]) =
      ok(result).flatMap(_.target.entity).map(_.value.value)
    // "a" has parts at (40, 40) and (100, 100); its anchor is on the nearer edge, near (91, 91).
    assertEquals(name(navigation.nearest(id("c"), NavigationDirection.Up)), Some("a"))
    assertEquals(name(navigation.nearest(id("c"), NavigationDirection.Right)), Some("d"))
    assertEquals(name(navigation.nearest(id("d"), NavigationDirection.Right)), None)
    val order = interaction.names.names.map(_.value)
    order.indices.foreach { i =>
      assertEquals(name(navigation.next(id(order(i)))), order.lift(i + 1))
      assertEquals(name(navigation.previous(id(order(i)))), order.lift(i - 1))
    }
    val foreign = bound().picking.prepareNavigation().targets.head.target.id
    assertEquals(navigation.next(foreign).isRight, true, "same plan id and revision address alike")
    val other = ok(
      NamedInteraction(
        ok(NamedPicking.fromResolved(scene, context)),
        keys,
        SemanticId.unsafe("other"),
        revision
      )
    ).prepareNavigation().targets.head.target.id
    assertEquals(navigation.next(other), Left(PickingError.UnknownTarget(other)))
  }

  test("the shared state machine selects, focuses and activates names") {
    val interaction = bound()
    val initial = ok(InteractionState.initial(interaction.domain))
    val origin = SemanticId.unsafe("test")
    val target = interaction.target(n("b")).get
    val focused = ok(
      InteractionState.reduce(
        initial,
        InputStamp(revision, origin, 0, InputCause.Keyboard),
        InteractionAction.Focus(Some(target.id))
      )
    )
    assertEquals(
      focused.events.map(_.event),
      Vector(InteractionEvent.FocusChanged(Some(target)))
    )
    val selected = ok(
      InteractionState.reduce(
        focused.state,
        InputStamp(revision, origin, 1, InputCause.Pointer),
        InteractionAction.Select(Selection(target.entity.toSet), SelectionOperation.Replace)
      )
    )
    assertEquals(selected.state.selection.entities.map(_.value), Set(n("b")))
    val foreign = ok(ok(NamedInteraction.keySpace("names")).entity(n("b")))
    assert(
      InteractionState
        .reduce(
          selected.state,
          InputStamp(revision, origin, 2, InputCause.Pointer),
          InteractionAction.Select(Selection(Set(foreign)), SelectionOperation.Replace)
        )
        .isLeft,
      "a different key-space instance does not join, even with the same namespace"
    )
  }

  test("the name codec is canonical and rejects blank names") {
    val key = ok(keys.entity(n("mark-7")))
    assertEquals(ok(keys.readEntity(key.token)), key)
    assert(keys.readEntity(key.token.copy(payload = " mark-7")).isLeft)
    assert(keys.readEntity(key.token.copy(payload = " ")).isLeft)
    assert(NamedInteraction.keySpace(" ").isLeft)
  }
