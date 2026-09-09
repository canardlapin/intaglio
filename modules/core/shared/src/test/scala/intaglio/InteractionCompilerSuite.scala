package intaglio.interaction

import intaglio.*

class InteractionCompilerSuite extends munit.FunSuite:
  private final case class Row(id: Int, x: Double, y: Double, panel: String)
  private val rows = Vector(Row(11, 1, 2, "A"), Row(12, 1, 4, "A"), Row(13, 2, 7, "B"))
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val space = ok(KeySpace("observations", KeyCodec.integer))
  private val revision = ok(DataRevision("source-v1"))
  private val planId = SemanticId.unsafe("example")
  private val planRevision = ok(PlanRevision("geometry-v1"))
  private def points(data: Vector[Row]): Plot[Row] =
    ok(Plot(data).addLayer(Layer.point[Row](_.x, _.y)))
  private def compile(
      plot: Plot[Row],
      retention: MembershipRetention = MembershipRetention.CountOnly,
      options: PlotCompilerOptions = PlotCompilerOptions.lean
  ): InteractionPlan[Int] =
    ok(
      InteractionCompiler.compile(plot, space, revision, planId, planRevision, options, retention)(
        _.id
      )
    )
  private def infos(plan: InteractionPlan[Int]): Vector[TargetInfo[Int]] =
    plan.groups.flatMap(group => Vector.tabulate(group.size)(i => ok(group.at(i))))

  private def stripRouting(elements: Vector[DeviceElement]): Vector[DeviceElement] =
    elements.flatMap {
      case DeviceElement.Annotated(meta, children)
          if meta.data.exists(_._1 == InteractionCompiler.targetAttribute) =>
        stripRouting(children)
      case DeviceElement.Annotated(meta, children) =>
        Vector(DeviceElement.Annotated(meta, stripRouting(children)))
      case DeviceElement.Group(name, clip, rotation, children) =>
        Vector(DeviceElement.Group(name, clip, rotation, stripRouting(children)))
      case mark => Vector(mark)
    }

  private def assertSameDrawing(
      plot: Plot[Row],
      interactive: InteractionPlan[Int],
      options: PlotCompilerOptions
  ): Unit =
    val device = DeviceContext.unsafe(640, 480)
    val baseline = ok(DeviceScene.fromScene(ok(PlotCompiler.compile(plot, options)), device))
    val actual = ok(DeviceScene.fromScene(interactive.scene, device))
    assertEquals(actual.copy(elements = stripRouting(actual.elements)), baseline)

  test("ordinary point compilation preserves compact batches and releases source inspection") {
    val data = Vector.tabulate(10000)(i => Row(i, i.toDouble, (i % 37).toDouble, "A"))
    val plot = points(data)
    val result = compile(plot)
    assertEquals(result.groups.length, 1)
    assertEquals(result.groups.head.size, 10000)
    assertEquals(ok(result.groups.head.at(9999)).entity.map(_.value), Some(9999))
    assert(result.trained.layers.head.rows.isEmpty)
    assert(result.trained.layers.head.statFrame.rows.isEmpty)
    assertEquals(result.trained.layers.head.grobs.length, 1)
    result.trained.layers.head.grobs.head match
      case Grob.Annotated(_: Grob.PointBatch, meta) =>
        assertEquals(
          meta.data,
          Vector(InteractionCompiler.targetAttribute -> result.groups.head.name.value)
        )
      case other => fail(s"expected one annotated batch, received $other")
    assertSameDrawing(plot, result, PlotCompilerOptions.lean)
  }

  test("point entity keys survive reordering, filtering, and dropped non-finite rows") {
    val first = compile(points(rows))
    val second = compile(points(Vector(rows(2), rows(0), Row(99, Double.NaN, 3, "A"))))
    assertEquals(infos(first).flatMap(_.entity).map(_.value), Vector(11, 12, 13))
    assertEquals(
      infos(second).flatMap(_.entity),
      Vector(infos(first)(2).entity.get, infos(first)(0).entity.get)
    )
  }

  test("custom-stat membership uses explicit keys when every source row compares equal") {
    final class EqualRow(val id: Int, val x: Double, val y: Double):
      override def equals(other: Any): Boolean = other != null
      override def hashCode(): Int = 0
    val data = Vector(new EqualRow(19, 1, 3), new EqualRow(4, 2, 5), new EqualRow(81, 3, 7))
    val stat = external.stat.CenterStat[EqualRow](_.x, _.y)
    val layer = ok(
      Layer.fromMapping(Geom.Point, AesSpec.empty[EqualRow], inheritMapping = false, stat = stat)
    )
    for input <- Vector(data, Vector(data(2), data(0))) do
      val plot = ok(Plot(input).addLayer(layer))
      val result = ok(
        InteractionCompiler.compile(
          plot,
          space,
          revision,
          planId,
          planRevision,
          retention = MembershipRetention.ExactKeys
        )(_.id)
      )
      val targets = infos(result)
      assertEquals(
        targets.map(info => ok(info.membership.exactKeys(revision)).map(_.value)),
        input.map(row => Vector(row.id))
      )
      assertEquals(targets.map(_.membership.total), Vector.fill(input.size)(Some(1)))
      assertEquals(result.sourceEntities.map(_.value), input.map(_.id))
      assert(
        targets.forall(_.entity.isEmpty),
        "custom output targets do not implicitly become identity marks"
      )
  }

  test("a custom statistic cannot replace a source key after the source index is validated") {
    final class MutableRow(var id: Int)
    val stat = new Stat[MutableRow]:
      val label = "mutating-source-key"
      val contract = Stat.Identity.contract
      def compute[Input <: MutableRow](
          batch: StatBatch[Input],
          context: StatContext
      ): Either[StatError, StatResult[Input]] =
        batch.rows.head.id = 999
        Stat.Identity.compute(batch, context)
    val mapping = AesSpec[MutableRow](
      x = Some(AesValue.total(_.id.toDouble)),
      y = Some(AesValue.total(_.id.toDouble))
    )
    val layer = ok(Layer.fromMapping(Geom.Point, mapping, inheritMapping = false, stat = stat))
    val plot = ok(Plot(Vector(new MutableRow(1), new MutableRow(2))).addLayer(layer))
    val result = InteractionCompiler.compile(
      plot,
      space,
      revision,
      planId,
      planRevision,
      retention = MembershipRetention.ExactKeys
    )(_.id)
    assertEquals(result.left.toOption, Some(InteractionError.UnknownEntity(0, 0)))
  }

  test(
    "geometry revisions invalidate visual addresses while preserving source keys and membership"
  ) {
    val original = compile(points(rows), MembershipRetention.ExactKeys)
    val changed = ok(
      InteractionCompiler.compile(
        points(rows.reverse),
        space,
        revision,
        planId,
        ok(PlanRevision("geometry-v2")),
        retention = MembershipRetention.ExactKeys
      )(_.id)
    )
    assertEquals(changed.sourceRevision, original.sourceRevision)
    assertEquals(infos(changed).flatMap(_.entity), infos(original).flatMap(_.entity).reverse)
    assertNotEquals(infos(original).head.id, infos(changed).head.id)
    assertEquals(ok(infos(changed).head.membership.exactKeys(revision)).map(_.value), Vector(13))
  }

  test("two summary primitives share one logical target and explicit aggregate membership") {
    val plot = ok(Plot(rows).addLayer(Layer.summary[Row](_.x, _.y)))
    val result = compile(plot, MembershipRetention.ExactKeys)
    assertEquals(result.groups.length, 2)
    assertEquals(result.groups.map(_.size), Vector(1, 1))
    val metadata = result.trained.layers.head.grobs.collect { case Grob.Annotated(_, meta) => meta }
    assertEquals(metadata.length, 4)
    assertEquals(metadata(0), metadata(1))
    assertEquals(metadata(2), metadata(3))
    assertNotEquals(metadata(0), metadata(2))
    assertEquals(
      infos(result).map(info => ok(info.membership.exactKeys(revision)).map(_.value)),
      Vector(Vector(11, 12), Vector(13))
    )
    assert(infos(result).forall(_.entity.isEmpty))
    assertSameDrawing(plot, result, PlotCompilerOptions.lean)
  }

  test("count-only aggregates cannot report exact member selection") {
    val result = compile(ok(Plot(rows).addLayer(Layer.summary[Row](_.x, _.y))))
    assertEquals(infos(result).map(_.membership.total), Vector(Some(2), Some(1)))
    assert(infos(result).forall(_.membership.exactKeys(revision).isLeft))
    assert(infos(result).forall(_.membership.retainedKeys.isEmpty))
  }

  test("grouped line target membership unions observations in encounter order") {
    val plot = ok(Plot(rows).addLayer(Layer.line[Row](_.x, _.y)))
    val result = compile(plot, MembershipRetention.ExactKeys)
    assertEquals(result.groups.map(_.size), Vector(1))
    assertEquals(
      ok(infos(result).head.membership.exactKeys(revision)).map(_.value),
      Vector(11, 12, 13)
    )
    assertSameDrawing(plot, result, PlotCompilerOptions.lean)
  }

  test("facet copies keep distinct visual scopes and original observation identities") {
    val program = ok(
      intaglio
        .plot(rows)
        .aes(_.x, _.y)
        .facetWrap(_.panel, columns = 2, scales = FacetScales.FreeY)
        .geomPoint()
        .build
    )
    val result = compile(program.plot)
    assertEquals(result.groups.map(_.size), Vector(2, 1))
    assertEquals(infos(result).flatMap(_.entity).map(_.value), Vector(11, 12, 13))
    assertEquals(infos(result).map(_.id).distinct.length, 3)
    assert(result.trained.facetPanels.flatMap(_.layers).forall(_.rows.isEmpty))
    assertSameDrawing(program.plot, result, PlotCompilerOptions.lean)
  }

  test("coordinate transformations preserve routing and drawing") {
    val program = ok(intaglio.plot(rows).aes(_.x, _.y).coord(Coord.Flipped()).geomPoint().build)
    val result = compile(program.plot)
    assertEquals(infos(result).flatMap(_.entity).map(_.value), Vector(11, 12, 13))
    assertSameDrawing(program.plot, result, PlotCompilerOptions.lean)
  }

  test("rich inspection remains an explicit option without changing entity identities") {
    val plot = points(rows)
    val rich = compile(plot, options = PlotCompilerOptions.rich)
    val lean = compile(plot)
    assertEquals(rich.trained.layers.head.rows.length, 3)
    assertEquals(rich.groups.map(_.size), Vector(1, 1, 1))
    assertEquals(infos(rich).flatMap(_.entity), infos(lean).flatMap(_.entity))
    assertSameDrawing(plot, rich, PlotCompilerOptions.rich)
  }

  test("duplicate source keys fail before a misleading interaction plan is returned") {
    val plot = points(rows :+ rows.head.copy(y = 100))
    assertEquals(
      InteractionCompiler.compile(plot, space, revision, planId, planRevision)(_.id),
      Left(InteractionError.DuplicateEntity(0, 3))
    )
  }

  test("independent row types fail explicitly pending their typed binding capability") {
    val plot = ok(
      points(rows).addIndependentLayer(
        Vector(1, 2),
        Layer.point[Int](_.toDouble, _.toDouble),
        LayerFacetPolicy.Exclude
      )
    )
    assertEquals(
      InteractionCompiler.compile(plot, space, revision, planId, planRevision)(_.id),
      Left(InteractionError.UnsupportedCapability("independent-layer source bindings"))
    )
  }

  test("extension geometry requires a declared target-lowering contract") {
    val external = new Geom:
      val label = "external-point"
      val contract = Geom.Point.contract
      def lower[R](batch: GeomBatch[R]): Either[GraphicsError, Vector[Grob]] =
        Geom.Point.lower(batch)
    val layer = ok(Layer.fromMapping(external, AesSpec.empty[Row].withPosition(_.x, _.y)))
    val plot = ok(Plot(rows).addLayer(layer))
    assertEquals(
      InteractionCompiler.compile(plot, space, revision, planId, planRevision)(_.id),
      Left(InteractionError.UnsupportedCapability("geom 'external-point' target lowering"))
    )
  }
