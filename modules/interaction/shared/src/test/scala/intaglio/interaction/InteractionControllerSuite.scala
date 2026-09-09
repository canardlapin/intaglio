package intaglio.interaction

import intaglio.*

class InteractionControllerSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val entities = ok(KeySpace("entities", KeyCodec.integer))
  private val categories = ok(KeySpace("categories", KeyCodec.text))
  private val version = ok(PlanRevision("one"))
  private val source = PlotLayer.inherited(Layer.point[Int](_.toDouble, _.toDouble))
  private val plot = ok(Plot(Vector(1, 2, 3)).addPackagedLayer(source))
  private val binding =
    LayerBinding(source, entities)(identity).withLinks(categories)(_ => "category")
  private val plan = ok(
    InteractionCompiler.compileBound(
      plot,
      Vector(binding),
      ok(DataRevision("one")),
      SemanticId.unsafe("example"),
      version
    )
  )
  private val domain = ok(InteractionDomain(Vector(plan), version))
  private val target = ok(plan.groups.head.at(0))
  private def controller() = new InteractionController(ok(InteractionState.initial(domain)))
  private def stamp(index: Long, cause: InputCause = InputCause.Keyboard) =
    InputStamp(version, SemanticId.unsafe("host"), index, cause)
  private val activate = InteractionAction.Activate[Int](target.id)

  test("subscriptions preserve entity and category key types through compilation and delivery") {
    val owner = controller()
    var received = Vector.empty[(EntityKey[Int], Vector[LinkKey[String]])]
    val subscription = ok(owner.subscribe { event =>
      event.event match
        case InteractionEvent.Activated(info) =>
          val entity: EntityKey[Int] = info.entity.get
          val links: Vector[LinkKey[String]] = info.links.in(categories)
          received :+= ((entity, links))
        case _ => ()
    })
    val result = ok(owner.dispatch(stamp(0), activate))
    assertEquals(result.failures, Vector.empty)
    assertEquals(
      received,
      Vector((ok(entities.entity(1)), Vector(ok(categories.link("category")))))
    )
    subscription.cancel()
    subscription.cancel()
    ok(owner.dispatch(stamp(1), activate))
    assertEquals(received.size, 1)
    assert(compileErrors("""
      import intaglio.interaction.*
      def subscribe(controller: InteractionController[Int]): Unit =
        controller.subscribe((event: EventRecord[String]) => ())
    """).nonEmpty)
  }

  test("state commits before delivery and callback failures do not suppress other listeners") {
    val owner = controller()
    var observed = Set.empty[Int]
    ok(owner.subscribe(_ => throw new IllegalStateException("host error")))
    ok(owner.subscribe(_ => observed = ok(owner.state).selection.entities.map(_.value)))
    val selected = Selection(Set(ok(entities.entity(2))))
    val result =
      ok(owner.dispatch(stamp(0), InteractionAction.Select(selected, SelectionOperation.Replace)))
    assertEquals(observed, Set(2))
    assertEquals(result.failures, Vector(DeliveryFailure(0, 0)))
    assertEquals(ok(owner.state).selection, selected)
  }

  test(
    "cancellation during delivery skips removed listeners and additions wait for the next event"
  ) {
    val owner = controller()
    var calls = Vector.empty[String]
    var second: Option[InteractionSubscription] = None
    var added = false
    ok(owner.subscribe { _ =>
      calls :+= "first"
      second.foreach(_.cancel())
      if !added then
        ok(owner.subscribe(_ => calls :+= "new"))
        added = true
    })
    second = Some(ok(owner.subscribe(_ => calls :+= "second")))
    ok(owner.dispatch(stamp(0), activate))
    assertEquals(calls, Vector("first"))
    ok(owner.dispatch(stamp(1), activate))
    assertEquals(calls, Vector("first", "first", "new"))
  }

  test("reentrant changes are rejected without consuming the next input sequence") {
    val owner = controller()
    var failure: Option[ControllerError] = None
    val subscription =
      ok(owner.subscribe(_ => failure = owner.dispatch(stamp(1), activate).left.toOption))
    ok(owner.dispatch(stamp(0), activate))
    assertEquals(failure, Some(ControllerError.DuringDelivery))
    subscription.cancel()
    assert(owner.dispatch(stamp(1), activate).isRight)
  }

  test("disposal during delivery stops callbacks and releases owned state and subscriptions") {
    val owner = controller()
    var secondCalled = false
    ok(owner.subscribe(_ => owner.dispose()))
    val second = ok(owner.subscribe(_ => secondCalled = true))
    ok(owner.dispatch(stamp(0), activate))
    assertEquals(secondCalled, false)
    assertEquals(owner.state.left.toOption, Some(ControllerError.Disposed))
    assertEquals(owner.dispatch(stamp(1), activate).left.toOption, Some(ControllerError.Disposed))
    assertEquals(owner.subscribe(_ => ()).left.toOption, Some(ControllerError.Disposed))
    owner.dispose()
    second.cancel()
  }

  test("projected input commits without notifying subscribers and rejected input does not commit") {
    val owner = controller()
    var count = 0
    ok(owner.subscribe(_ => count += 1))
    val selected = Selection(Set(ok(entities.entity(1))))
    ok(
      owner.dispatch(
        stamp(0, InputCause.Projected),
        InteractionAction.Select(selected, SelectionOperation.Replace)
      )
    )
    assertEquals(count, 0)
    assertEquals(ok(owner.state).selection, selected)
    assert(
      owner.dispatch(stamp(0), InteractionAction.Select(selected, SelectionOperation.Toggle)).isLeft
    )
    assertEquals(ok(owner.state).selection, selected)
    assertEquals(count, 0)
  }
