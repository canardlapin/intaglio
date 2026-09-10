package intaglio.interaction

import intaglio.*
import scala.compiletime.testing.typeCheckErrors

class InteractionBindingSuite extends munit.FunSuite:
  private final case class Observation(id: Int, x: Double, y: Double, category: String)
  private final case class Marker(markerId: Int, time: Double, height: Double)
  private val rows = Vector(Observation(11, 1, 2, "A"), Observation(12, 1, 4, "B"))
  private val markers = Vector(Marker(11, 1.5, 3), Marker(99, 2, 8))
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val entities = ok(KeySpace("subjects", KeyCodec.integer))
  private val categories = ok(KeySpace("conditions", KeyCodec.text))
  private val revision = ok(DataRevision("data-v1"))
  private val planRevision = ok(PlanRevision("geometry-v1"))
  private val planId = SemanticId.unsafe("typed-layers")
  private val observationLayer = PlotLayer.inherited(Layer.point[Observation](_.x, _.y))
  private val markerLayer = PlotLayer.independent[Observation, Marker](
    markers,
    Layer.point[Marker](_.time, _.height),
    LayerFacetPolicy.Repeat
  )

  private def compile(
      plot: Plot[Observation],
      bindings: Vector[LayerBinding[Observation, Int]],
      retention: MembershipRetention = MembershipRetention.ExactKeys,
      adapters: Vector[GeomTargetAdapter] = Vector.empty
  ): Either[IntaglioError, InteractionPlan[Int]] =
    InteractionCompiler.compileBound(
      plot,
      bindings,
      revision,
      planId,
      planRevision,
      retention = retention,
      adapters = adapters
    )

  private def infos(plan: InteractionPlan[Int]): Vector[TargetInfo[Int]] =
    plan.groups.flatMap(group => Vector.tabulate(group.size)(i => ok(group.at(i))))

  test("independent row accessors keep their own types while explicitly sharing entity identity") {
    val plot = ok(ok(Plot(rows).addPackagedLayer(observationLayer)).addPackagedLayer(markerLayer))
    val observations =
      LayerBinding(observationLayer, entities)(_.id).withLinks(categories)(_.category)
    val markerBinding = LayerBinding(markerLayer, entities)(_.markerId)
    val result = ok(compile(plot, Vector(observations, markerBinding)))
    assertEquals(infos(result).flatMap(_.entity).map(_.value), Vector(11, 12, 11, 99))
    assertEquals(infos(result)(0).entity, infos(result)(2).entity)
    assertEquals(infos(result)(0).links.in(categories).map(_.value), Vector("A"))
    assert(infos(result)(2).links.in(categories).isEmpty)
    assertEquals(result.groups.map(_.size), Vector(2, 2))
    assert(result.trained.layers.forall(_.rows.isEmpty))
  }

  test("independent namespaces with identical key values do not join") {
    val separate = ok(KeySpace("markers", KeyCodec.integer))
    val plot = ok(ok(Plot(rows).addPackagedLayer(observationLayer)).addPackagedLayer(markerLayer))
    val result = ok(
      compile(
        plot,
        Vector(
          LayerBinding(observationLayer, entities)(_.id),
          LayerBinding(markerLayer, separate)(_.markerId)
        )
      )
    )
    assertNotEquals(infos(result)(0).entity, infos(result)(2).entity)
    assertEquals(result.spaces, Vector(entities, separate))
    assertEquals(infos(result)(2).membership.space, separate)
  }

  test(
    "repeated independent facet layers have distinct visual targets and shared observation keys"
  ) {
    val program =
      ok(intaglio.plot(rows).aes(_.x, _.y).facetWrap(_.category, columns = 2).geomPoint().build)
    val inherited = PlotLayer.inheritedPackage(program.plot.layers.head).get
    val plot = ok(program.plot.addPackagedLayer(markerLayer))
    val result = ok(
      compile(
        plot,
        Vector(
          LayerBinding(inherited, entities)(_.id),
          LayerBinding(markerLayer, entities)(_.markerId)
        )
      )
    )
    val all = infos(result)
    assertEquals(all.flatMap(_.entity).map(_.value), Vector(11, 11, 99, 12, 11, 99))
    assertEquals(all.map(_.id).distinct.length, 6)
    assert(result.trained.facetPanels.flatMap(_.layers).forall(_.rows.isEmpty))
  }

  test("bindings are matched by package reference, not equal layer contents or vector position") {
    val layer = Layer.point[Observation](_.x, _.y)
    val actual = PlotLayer.inherited(layer)
    val equalButForeign = PlotLayer.inherited(layer)
    assert(actual == equalButForeign)
    assert(!(actual eq equalButForeign))
    val plot = ok(Plot(rows).addPackagedLayer(actual))
    var calls = 0
    val foreign = LayerBinding(equalButForeign, entities) { row => calls += 1; row.id }
    assertEquals(
      compile(plot, Vector(foreign)),
      Left(InteractionError.InvalidValue("layer binding", "source package is not in this plot"))
    )
    assertEquals(calls, 0)
    val valid = LayerBinding(actual, entities)(_.id)
    assert(compile(plot, Vector.empty).isLeft)
    assert(compile(plot, Vector(valid, valid)).isLeft)
  }

  test("binding order does not change layer routing") {
    val plot = ok(ok(Plot(rows).addPackagedLayer(observationLayer)).addPackagedLayer(markerLayer))
    val bindings = Vector(
      LayerBinding(observationLayer, entities)(_.id),
      LayerBinding(markerLayer, entities)(_.markerId)
    )
    val forward = ok(compile(plot, bindings))
    val reverse = ok(compile(plot, bindings.reverse))
    assertEquals(forward.scene, reverse.scene)
    assertEquals(infos(forward).flatMap(_.entity), infos(reverse).flatMap(_.entity))
  }

  test("group projections retain different key types and deduplicate repeated links") {
    val summary = PlotLayer.inherited(Layer.summary[Observation](_.x, _.y))
    val groupNumbers = ok(KeySpace("group-numbers", KeyCodec.integer))
    val binding = LayerBinding(summary, entities)(_.id)
      .withLinks(categories)(_.category)
      .withLinks(groupNumbers)(_ => 7)
    val result = ok(
      compile(
        ok(Plot(rows).addPackagedLayer(summary)),
        Vector(binding),
        MembershipRetention.CountOnly
      )
    )
    val target = infos(result).head
    val typedStrings: Vector[LinkKey[String]] = target.links.in(categories)
    val typedInts: Vector[LinkKey[Int]] = target.links.in(groupNumbers)
    assertEquals(typedStrings.map(_.value), Vector("A", "B"))
    assertEquals(typedInts.map(_.value), Vector(7))
    assertEquals(target.links.size, 3)
    assert(target.links.in(ok(KeySpace("conditions", KeyCodec.text))).isEmpty)
    assert(target.membership.exactKeys(revision).isLeft)
  }

  test("link accessor failures use the interaction error channel") {
    val binding = LayerBinding(observationLayer, entities)(_.id)
      .withLinks(categories)(_ => throw new IllegalStateException("link accessor"))
    assertEquals(
      compile(ok(Plot(rows).addPackagedLayer(observationLayer)), Vector(binding)),
      Left(InteractionError.CallbackFailed("link key accessor"))
    )
  }

  private def doubledGeom: Geom = new Geom:
    val label = "two-parts"
    val contract = Geom.Point.contract
    def lower[R](batch: GeomBatch[R]): Either[GraphicsError, Vector[Grob]] =
      Geom.Point.lower(batch).map(_.flatMap(grob => Vector(grob, grob)))

  private val pairs = new TargetLowering:
    def apply[R](layer: ResolvedLayer[R]): Either[InteractionError, Vector[TargetAssignment]] =
      Right(
        layer.rows.indices
          .map(i => TargetAssignment(Vector(2 * i, 2 * i + 1), Vector(Vector(i))))
          .toVector
      )

  test("custom geom declares multi-part target identity without replacing its ordinary rendering") {
    val geom = doubledGeom
    val source = PlotLayer.inherited(
      ok(Layer.fromMapping(geom, AesSpec.empty[Observation].withPosition(_.x, _.y)))
    )
    val plot = ok(Plot(rows).addPackagedLayer(source))
    val result = ok(
      compile(
        plot,
        Vector(LayerBinding(source, entities)(_.id)),
        adapters = Vector(GeomTargetAdapter(geom, pairs))
      )
    )
    assertEquals(result.groups.map(_.size), Vector(1, 1))
    assertEquals(infos(result).flatMap(_.entity).map(_.value), Vector(11, 12))
    val metadata = result.trained.layers.head.grobs.collect { case Grob.Annotated(_, meta) => meta }
    assertEquals(metadata(0), metadata(1))
    assertEquals(metadata(2), metadata(3))
    val original = ok(PlotCompiler.compile(plot))
    val stripped = result.trained
      .copy(layers = result.trained.layers.map { layer =>
        TrainedLayer(layer.value.copy(grobs = layer.grobs.map {
          case Grob.Annotated(child, _) => child
          case other                    => other
        }))
      })
      .scene
    assertEquals(stripped, original)
  }

  test("malformed extension target layouts fail instead of returning partial routing") {
    val geom = doubledGeom
    val source = PlotLayer.inherited(
      ok(Layer.fromMapping(geom, AesSpec.empty[Observation].withPosition(_.x, _.y)))
    )
    val plot = ok(Plot(rows).addPackagedLayer(source))
    val bindings = Vector(LayerBinding(source, entities)(_.id))
    val invalid = Vector(
      Vector(TargetAssignment(Vector(0, 1, 2, 4), Vector(Vector(0)))),
      Vector(TargetAssignment(Vector(0, 1, 2, 3, 3), Vector(Vector(0)))),
      Vector(TargetAssignment(Vector(0, 1), Vector(Vector(0)))),
      Vector(TargetAssignment(Vector(0, 1, 2, 3), Vector(Vector(2)))),
      Vector(TargetAssignment(Vector(0, 1, 2, 3), Vector(Vector(0, 0)))),
      Vector(TargetAssignment(Vector(0, 1, 2, 3), Vector(Vector(0), Vector(1)))),
      Vector(TargetAssignment(Vector(0, 1, 2, 3), Vector.empty))
    )
    invalid.foreach { assignments =>
      val adapter = new TargetLowering:
        def apply[R](layer: ResolvedLayer[R]) = Right(assignments)
      assert(compile(plot, bindings, adapters = Vector(GeomTargetAdapter(geom, adapter))).isLeft)
    }
    val throwing = new TargetLowering:
      def apply[R](layer: ResolvedLayer[R]) = throw new IllegalStateException("layout")
    assertEquals(
      compile(plot, bindings, adapters = Vector(GeomTargetAdapter(geom, throwing))),
      Left(InteractionError.CallbackFailed("target lowering"))
    )
    assert(
      compile(plot, bindings, adapters = Vector.fill(2)(GeomTargetAdapter(geom, pairs))).isLeft
    )
    assertEquals(
      compile(plot, bindings, adapters = Vector(GeomTargetAdapter(doubledGeom, pairs))),
      Left(InteractionError.UnsupportedCapability("geom 'two-parts' target lowering"))
    )
  }

  test("an independent binding cannot accept an accessor for the root row type") {
    assert(typeCheckErrors("""
      import intaglio.*
      import intaglio.interaction.*
      val source = PlotLayer.independent[String, Int](Vector(1), Layer.point[Int](_.toDouble, _.toDouble), LayerFacetPolicy.Exclude)
      val space = KeySpace("keys", KeyCodec.integer).toOption.get
      LayerBinding(source, space)((text: String) => text.length)
    """).nonEmpty)
  }
