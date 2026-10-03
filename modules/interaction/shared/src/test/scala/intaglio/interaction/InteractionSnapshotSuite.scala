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
