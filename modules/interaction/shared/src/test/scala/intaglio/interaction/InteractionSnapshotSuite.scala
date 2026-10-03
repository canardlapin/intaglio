package intaglio.interaction

import intaglio.*

/** Saved interaction state: a snapshot round-trips through JSON (identically on the JVM and
  * Scala.js), restores keys, targets, saved selections, viewports and mode as one change, and is
  * refused with a typed error when the schema, plan or data revision, key space or codec differ.
  */
class InteractionSnapshotSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)
  private val data = Vector(Obs(1, 1), Obs(2, 12), Obs(3, 23))
  private val space = ok(KeySpace("obs", KeyCodec.integer))

  private def plan(
      id: String = "bars",
      dataRevision: String = "d1",
      planRevision: String = "p1",
      in: KeySpace[Int] = space,
      rows: Vector[Obs] = data
  ): InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(
        ok(
          Plot(rows)
            .addLayer(Layer.point[Obs](_.x, _.x))
            .flatMap(
              _.addLayer(
                Layer
                  .histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(Vector(0.0, 10, 20, 30)))
              )
            )
        ),
        in,
        ok(DataRevision(dataRevision)),
        SemanticId.unsafe(id),
        ok(PlanRevision(planRevision))
      )(_.id)
    )
  private val base = plan()
  private def domainOf(p: InteractionPlan[Int]) = ok(InteractionDomain(Vector(p), p.revision))
  private val domain = domainOf(base)
  private val bin =
    base.groups.flatMap(g => Vector.tabulate(g.size)(i => ok(g.at(i)))).find(_.entity.isEmpty).get

  private var sequence = 0L
  private def run(state: InteractionState[Int], action: InteractionAction[Int]) =
    sequence += 1
    InteractionState.reduce(
      state,
      InputStamp(state.domain.revision, SemanticId.unsafe("t"), sequence, InputCause.Programmatic),
      action
    )

  /** Two observations and a bin selected, a saved selection, a recorded viewport. */
  private val saved: InteractionState[Int] =
    val start = ok(InteractionState.initial(domain))
    val chosen = Selection(Set(ok(space.entity(1)), ok(space.entity(3))), Set(bin.id))
    val withSelection = ok(
      run(start, InteractionAction.Select(chosen, SelectionOperation.Replace))
    ).state
    val named = ok(
      run(withSelection, InteractionAction.SaveSelection(SelectionName.unsafe("pair")))
    ).state
    ok(
      run(
        named,
        InteractionAction.SetViewport(
          SemanticId.unsafe("panel"),
          Some(ok(PanelViewport(0.1, 1.0, -2.5, 3.0)))
        )
      )
    ).state

  test("a snapshot round-trips through JSON and restores everything durable, nothing else") {
    val json = InteractionSnapshot.capture(saved).toJson
    val restored = ok(InteractionSnapshot.resolve(ok(InteractionSnapshot.fromJson(json)), domain))
    val fresh = ok(InteractionState.initial(domain))
    val applied = ok(run(fresh, InteractionAction.RestoreSnapshot(restored)))
    assertEquals(applied.state.selection, saved.selection)
    assertEquals(applied.state.named, saved.named)
    assertEquals(applied.state.viewports, saved.viewports)
    assertEquals(applied.state.selectionMode, saved.selectionMode)
    val kinds = applied.events.map(_.event.getClass.getSimpleName).toSet
    assertEquals(kinds, Set("SelectionChanged", "NamedSelectionsChanged", "ViewportChanged"))
    assert(!kinds.exists(k => k == "Activated" || k == "MembershipRequested"))
    // Restoring the same snapshot again changes nothing and says nothing.
    assertEquals(
      ok(run(applied.state, InteractionAction.RestoreSnapshot(restored))).events,
      Vector.empty
    )
  }

  test("the JSON is the same text on the JVM and Scala.js") {
    // The expected text is written out once; both platforms must produce it exactly.
    val json = InteractionSnapshot.capture(saved).toJson
    val expected =
      """{"schema":1,"plans":[{"id":"bars","planRevision":"p1","dataRevision":"d1"}],""" +
        """"mode":"Multiple","selection":{"entities":[""" +
        """{"namespace":"obs","codec":"integer","version":1,"payload":"1"},""" +
        """{"namespace":"obs","codec":"integer","version":1,"payload":"3"}],""" +
        s""""targets":[{"plan":"bars","planRevision":"p1","scope":"${bin.id.scope.value}","ordinal":${bin.id.ordinal}}]},""" +
        """"unresolved":[],""" +
        """"named":[{"name":"pair","selection":{"entities":[""" +
        """{"namespace":"obs","codec":"integer","version":1,"payload":"1"},""" +
        """{"namespace":"obs","codec":"integer","version":1,"payload":"3"}],""" +
        s""""targets":[{"plan":"bars","planRevision":"p1","scope":"${bin.id.scope.value}","ordinal":${bin.id.ordinal}}]}}],""" +
        """"viewports":[{"panel":"panel","xMin":0.1000000000000000055511151231257827021181583404541015625,""" +
        """"xMax":1,"yMin":-2.5,"yMax":3}]}"""
    assertEquals(json, expected)
    // Exact decimals parse back to the same doubles.
    assertEquals(ok(InteractionSnapshot.fromJson(json)).viewports.head.xMin, 0.1)
  }

  private def refused(
      snapshot: Either[SnapshotError, InteractionSnapshot],
      against: InteractionDomain[Int]
  ) =
    snapshot.flatMap(InteractionSnapshot.resolve(_, against))

  test("a different schema, plan revision, data revision, plan or malformed text is refused") {
    val json = InteractionSnapshot.capture(saved).toJson
    assertEquals(
      refused(InteractionSnapshot.fromJson(json.replace("\"schema\":1", "\"schema\":2")), domain)
        .map(_ => ()),
      Left(SnapshotError.UnsupportedSchema(2, 1))
    )
    assertEquals(
      refused(InteractionSnapshot.fromJson(json), domainOf(plan(planRevision = "p2"))).map(_ => ()),
      Left(SnapshotError.StalePlanRevision("bars", "p1", "p2"))
    )
    assertEquals(
      refused(InteractionSnapshot.fromJson(json), domainOf(plan(dataRevision = "d2"))).map(_ => ()),
      Left(SnapshotError.StaleDataRevision("bars", "d1", "d2"))
    )
    assertEquals(
      refused(InteractionSnapshot.fromJson(json), domainOf(plan(id = "lines"))).map(_ => ()),
      Left(SnapshotError.UnknownPlan("bars"))
    )
    Vector(
      json.dropRight(1),
      json + "x",
      json.replace("\"mode\":\"Multiple\"", "\"mode\":\"Several\""),
      json.replace("{\"schema\":1,", "{\"schema\":1,\"schema\":1,"),
      json.replace("\"ordinal\":", "\"ordinal\":1.5,\"x\":")
    ).foreach { bad =>
      assert(
        InteractionSnapshot.fromJson(bad) match
          case Left(SnapshotError.Malformed(_)) => true
          case other                            => false
        ,
        bad
      )
    }
  }

  test("keys from another key space, codec or the wrong data are refused by type") {
    val json = InteractionSnapshot.capture(saved).toJson
    val otherSpace = ok(KeySpace("trial", KeyCodec.integer))
    assertEquals(
      refused(InteractionSnapshot.fromJson(json), domainOf(plan(in = otherSpace))).map(_ => ()),
      Left(SnapshotError.KeySpaceMismatch("obs"))
    )
    assertEquals(
      refused(InteractionSnapshot.fromJson(json.replace("\"version\":1", "\"version\":2")), domain)
        .map(_ => ()),
      Left(SnapshotError.CodecMismatch("obs", "integer/2", "integer/1"))
    )
    assertEquals(
      refused(
        InteractionSnapshot.fromJson(json.replace("\"payload\":\"3\"", "\"payload\":\"99\"")),
        domain
      )
        .map(_ => ()),
      Left(SnapshotError.UnknownEntity("99"))
    )
    val address = TargetAddress("bars", "p1", bin.id.scope.value, 999)
    assertEquals(
      refused(
        InteractionSnapshot.fromJson(
          json.replace(s"\"ordinal\":${bin.id.ordinal}", "\"ordinal\":999")
        ),
        domain
      ).map(_ => ()),
      Left(SnapshotError.UnknownTarget(address))
    )
  }

  test("a restore supersedes pending member requests and refuses a snapshot resolved elsewhere") {
    val deferred = ok(
      InteractionCompiler.compile(
        ok(
          Plot(data).addLayer(
            Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(Vector(0.0, 10, 20, 30)))
          )
        ),
        space,
        ok(DataRevision("d1")),
        SemanticId.unsafe("deferred"),
        ok(PlanRevision("p1")),
        retention = MembershipRetention.Deferred
      )(_.id)
    )
    val dDomain = domainOf(deferred)
    val dBin = ok(deferred.groups.head.at(0))
    val pending = ok(
      run(
        ok(InteractionState.initial(dDomain)),
        InteractionAction.RequestMembers(dBin.id, 1, SelectionOperation.Replace)
      )
    ).state
    val empty = ok(
      InteractionSnapshot.resolve(
        InteractionSnapshot.capture(ok(InteractionState.initial(dDomain))),
        dDomain
      )
    )
    assert(ok(run(pending, InteractionAction.RestoreSnapshot(empty))).state.pendingMembers.isEmpty)
    // Resolved against another domain revision: refused as stale input.
    val elsewhere = ok(InteractionSnapshot.resolve(InteractionSnapshot.capture(saved), domain))
    val replacedDomain = ok(InteractionDomain(Vector(base), ok(PlanRevision("other"))))
    assert(
      run(
        ok(InteractionState.initial(replacedDomain)),
        InteractionAction.RestoreSnapshot(elsewhere)
      ).isLeft
    )
  }

  test("the reader is strict: unknown fields, repeats, non-JSON numbers and lone surrogates") {
    val json = InteractionSnapshot.capture(saved).toJson
    Vector(
      json.replace("{\"schema\":1,", "{\"schema\":1,\"extra\":0,"),
      json.replace("\"xMax\":1", "\"xMax\":01"),
      json.replace("\"xMax\":1", "\"xMax\":1."),
      json.replace("\"xMax\":1", "\"xMax\":\u0661"),
      json.replace("\"panel\":\"panel\"", "\"panel\":\"\\ud800\"")
    ).foreach { bad =>
      assert(
        InteractionSnapshot.fromJson(bad) match
          case Left(SnapshotError.Malformed(_)) => true
          case _                                => false
        ,
        bad
      )
    }
    // Repeated saved-selection names or viewport panels are refused when resolved.
    val snapshot = InteractionSnapshot.capture(saved)
    val twice = snapshot.copy(named = snapshot.named ++ snapshot.named)
    assert(InteractionSnapshot.resolve(twice, domain).isLeft)
    val panels = snapshot.copy(viewports = snapshot.viewports ++ snapshot.viewports)
    assert(InteractionSnapshot.resolve(panels, domain).isLeft)
  }

  test("every target's plan must be listed, and a namespace two key spaces share is refused") {
    val snapshot = InteractionSnapshot.capture(saved)
    assert(
      InteractionSnapshot.resolve(snapshot.copy(plans = Vector.empty), domain) match
        case Left(SnapshotError.Malformed(_)) => true
        case _                                => false
    )
    // Two plans keyed by distinct spaces that share the namespace "obs".
    val impostor = ok(KeySpace("obs", KeyCodec.integer))
    val twin = plan(id = "twin", in = impostor)
    val both = ok(InteractionDomain(Vector(base, twin), ok(PlanRevision("both"))))
    val withTwin = snapshot.copy(plans = snapshot.plans :+ SnapshotPlan("twin", "p1", "d1"))
    assertEquals(
      InteractionSnapshot.resolve(withTwin, both).map(_ => ()),
      Left(SnapshotError.AmbiguousKeySpace("obs"))
    )
  }

  test(
    "selections kept across a data replacement (unresolved keys) survive save, undo and snapshots"
  ) {
    val start = ok(InteractionState.initial(domain))
    val chosen = Selection(Set(ok(space.entity(1)), ok(space.entity(3))))
    val selected =
      ok(run(start, InteractionAction.Select(chosen, SelectionOperation.Replace))).state
    // New data without observation 3; the selection keeps it as unresolved.
    val fewer = plan(planRevision = "p2", dataRevision = "d2", rows = data.filter(_.id != 3))
    sequence += 1
    val replaced = ok(
      InteractionState.replaceDomain(
        selected,
        InputStamp(
          selected.domain.revision,
          SemanticId.unsafe("t"),
          sequence,
          InputCause.Programmatic
        ),
        domainOf(fewer),
        MissingEntityPolicy.Preserve
      )
    ).state
    assertEquals(replaced.unresolved, Set(ok(space.entity(3))))
    // A snapshot of that state restores the unresolved key as unresolved.
    val back = ok(
      InteractionSnapshot.resolve(
        ok(InteractionSnapshot.fromJson(InteractionSnapshot.capture(replaced).toJson)),
        domainOf(fewer)
      )
    )
    val restored = ok(
      run(ok(InteractionState.initial(domainOf(fewer))), InteractionAction.RestoreSnapshot(back))
    ).state
    assertEquals(restored.selection, replaced.selection)
    assertEquals(restored.unresolved, replaced.unresolved)
    // Saving keeps only what the data has, so recalling it later validates.
    val savedPair =
      ok(run(replaced, InteractionAction.SaveSelection(SelectionName.unsafe("kept")))).state
    assertEquals(savedPair.named(SelectionName.unsafe("kept")).entities, Set(ok(space.entity(1))))
    val cleared =
      ok(run(savedPair, InteractionAction.Select(Selection[Int](), SelectionOperation.Clear))).state
    assert(
      run(
        cleared,
        InteractionAction.RecallSelection(SelectionName.unsafe("kept"), SelectionOperation.Replace)
      ).isRight
    )
    // Undo past a click restores the selection that held the unresolved key.
    val controller = InteractionController(replaced)
    val history = ok(HistoryController(controller))
    sequence += 1
    ok(
      history.dispatch(
        InputStamp(replaced.domain.revision, SemanticId.unsafe("t"), sequence, InputCause.Pointer),
        InteractionAction.Select(Selection(Set(ok(space.entity(2)))), SelectionOperation.Replace)
      )
    )
    sequence += 1
    assert(
      history
        .undo(
          InputStamp(
            replaced.domain.revision,
            SemanticId.unsafe("t"),
            sequence,
            InputCause.Keyboard
          )
        )
        .exists(_.isRight)
    )
    assertEquals(ok(controller.state).selection, replaced.selection)
    assertEquals(ok(controller.state).unresolved, replaced.unresolved)
  }
