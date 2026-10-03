package intaglio.interaction

import intaglio.*

/** Selection algebra and named selections: the set laws hold for every pair and triple of
  * selections over a small universe of observation keys and visual targets, operands of different
  * key spaces are refused, and saved selections follow the data across a domain replacement.
  */
class SelectionAlgebraSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: Int, x: Double)
  private val data = Vector(Obs(1, 1), Obs(2, 12), Obs(3, 23))
  private val space = ok(KeySpace("obs", KeyCodec.integer))
  private val other = ok(KeySpace("obs", KeyCodec.integer))

  private def compile(plot: Plot[Obs], id: String): InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(
        plot,
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe(id),
        ok(PlanRevision("p"))
      )(_.id)
    )
  private val points = compile(ok(Plot(data).addLayer(Layer.point[Obs](_.x, _.x))), "points")
  private val bars = compile(
    ok(
      Plot(data).addLayer(
        Layer.histogram[Obs](_.x, bins = HistogramBins.breaksUnsafe(Vector(0.0, 10.0, 20.0, 30.0)))
      )
    ),
    "bars"
  )
  private val domain = ok(InteractionDomain(Vector(points, bars), ok(PlanRevision("both"))))
  private val keys = data.map(o => ok(space.entity(o.id)))
  private val targets =
    bars.groups.flatMap(g => Vector.tabulate(g.size)(i => ok(g.at(i)).id)).take(2)

  private def subsets[T](xs: Vector[T]): Vector[Set[T]] =
    (0 until (1 << xs.size)).toVector.map(mask =>
      xs.indices.filter(i => (mask & (1 << i)) != 0).map(xs).toSet
    )

  /** Every selection over three keys and two targets: 32 of them. */
  private val all: Vector[Selection[Int]] =
    for
      e <- subsets(keys)
      t <- subsets(targets)
    yield Selection(e, t)
  private val universe = Selection(keys.toSet, targets.toSet)
  private val empty = Selection[Int]()

  private def u(a: Selection[Int], b: Selection[Int]) = ok(SelectionAlgebra.union(a, b, domain))
  private def i(a: Selection[Int], b: Selection[Int]) = ok(SelectionAlgebra.intersect(a, b, domain))
  private def d(a: Selection[Int], b: Selection[Int]) = ok(SelectionAlgebra.diff(a, b, domain))

  test("union and intersection are commutative, idempotent, and have their identities") {
    for a <- all; b <- all do
      assertEquals(u(a, b), u(b, a))
      assertEquals(i(a, b), i(b, a))
    for a <- all do
      assertEquals(u(a, a), a)
      assertEquals(i(a, a), a)
      assertEquals(u(a, empty), a)
      assertEquals(i(a, universe), a)
      assertEquals(i(a, empty), empty)
  }

  test("associativity, both distributive laws and absorption hold for every triple") {
    for a <- all; b <- all; c <- all do
      assertEquals(u(u(a, b), c), u(a, u(b, c)))
      assertEquals(i(i(a, b), c), i(a, i(b, c)))
      assertEquals(i(a, u(b, c)), u(i(a, b), i(a, c)))
      assertEquals(u(a, i(b, c)), i(u(a, b), u(a, c)))
    for a <- all; b <- all do
      assertEquals(u(a, i(a, b)), a)
      assertEquals(i(a, u(a, b)), a)
  }

  test("difference is intersection with the complement, and De Morgan holds in the universe") {
    def complement(a: Selection[Int]) = d(universe, a)
    for a <- all; b <- all do
      assertEquals(d(a, b), i(a, complement(b)))
      assertEquals(complement(u(a, b)), i(complement(a), complement(b)))
      assertEquals(complement(i(a, b)), u(complement(a), complement(b)))
      assertEquals(i(d(a, b), b), empty)
  }

  test("operands of another key space or unknown targets are refused") {
    val foreign = Selection(Set(ok(other.entity(1))))
    assert(
      SelectionAlgebra.union(Selection(keys.toSet), foreign, domain) match
        case Left(StateError.IncompatibleSelections(_)) => true
        case _                                          => false
    )
    val elsewhere = compile(ok(Plot(data).addLayer(Layer.point[Obs](_.x, _.x))), "elsewhere")
    val stranger = elsewhere.groups.head.series.at(0).toOption.get
    assertEquals(
      SelectionAlgebra.intersect(Selection(targets = Set(stranger)), empty, domain),
      Left(StateError.UnknownTarget(stranger))
    )
  }

  private var sequence = 0L
  private def run(state: InteractionState[Int], action: InteractionAction[Int]) =
    sequence += 1
    InteractionState.reduce(
      state,
      InputStamp(state.domain.revision, SemanticId.unsafe("t"), sequence, InputCause.Programmatic),
      action
    )
  private def name(s: String) = SelectionName.unsafe(s)

  test("named selections are saved, recalled under any operation, combined and deleted") {
    val start = ok(InteractionState.initial(domain))
    val a = Selection(keys.take(2).toSet, Set(targets.head))
    val b = Selection(keys.drop(1).toSet, targets.toSet)
    val withA = ok(run(start, InteractionAction.Select(a, SelectionOperation.Replace))).state
    val savedA = ok(run(withA, InteractionAction.SaveSelection(name("first"))))
    assertEquals(
      savedA.events.map(_.event),
      Vector(InteractionEvent.NamedSelectionsChanged(Map(name("first") -> a)))
    )
    val withB = ok(run(savedA.state, InteractionAction.Select(b, SelectionOperation.Replace))).state
    val both = ok(run(withB, InteractionAction.SaveSelection(name("second")))).state
    val combined = ok(
      run(
        both,
        InteractionAction.CombineSelections(
          name("first"),
          name("second"),
          SetCombination.Intersection,
          name("overlap")
        )
      )
    ).state
    assertEquals(combined.named(name("overlap")), i(a, b))
    assertEquals(combined.selection, b, "combining leaves the current selection alone")
    val recalled = ok(
      run(combined, InteractionAction.RecallSelection(name("first"), SelectionOperation.Intersect))
    ).state
    assertEquals(recalled.selection, i(b, a))
    val added = ok(
      run(combined, InteractionAction.RecallSelection(name("first"), SelectionOperation.Add))
    ).state
    assertEquals(added.selection, u(b, a))
    val gone = ok(run(combined, InteractionAction.DeleteSelection(name("overlap")))).state
    assertEquals(gone.named.keySet, Set(name("first"), name("second")))
    assertEquals(
      run(gone, InteractionAction.RecallSelection(name("overlap"), SelectionOperation.Replace))
        .map(_.state.selection),
      Left(StateError.UnknownSelection(name("overlap")))
    )
  }

  test("a domain replacement keeps only what saved selections still name, and says so") {
    val start = ok(InteractionState.initial(domain))
    val a = Selection(keys.toSet, Set(targets.head))
    val saved = ok(
      run(
        ok(run(start, InteractionAction.Select(a, SelectionOperation.Replace))).state,
        InteractionAction.SaveSelection(name("all"))
      )
    ).state
    // New data without observation 3, and only the point plot.
    val fewer = compile(ok(Plot(data.take(2)).addLayer(Layer.point[Obs](_.x, _.x))), "points")
    sequence += 1
    val replaced = ok(
      InteractionState.replaceDomain(
        saved,
        InputStamp(
          saved.domain.revision,
          SemanticId.unsafe("t"),
          sequence,
          InputCause.Programmatic
        ),
        ok(InteractionDomain(Vector(fewer), ok(PlanRevision("fewer")))),
        MissingEntityPolicy.Drop
      )
    )
    assertEquals(replaced.state.named(name("all")), Selection(keys.take(2).toSet))
    assert(replaced.events.exists(_.event.isInstanceOf[InteractionEvent.NamedSelectionsChanged[?]]))
  }

  test("selection names are checked") {
    Vector("", " lead", "trail ", "a/b", "x" * 65).foreach(n => assert(SelectionName(n).isLeft, n))
    Vector("a", "Lasso 1", "bins_2-3.v2").foreach(n => assert(SelectionName(n).isRight, n))
  }
