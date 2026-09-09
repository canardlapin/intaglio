package intaglio.interaction

import intaglio.*

class InteractionCompositionSuite extends munit.FunSuite:
  private def ok[A](value: Either[IntaglioError, A]): A =
    value.fold(error => fail(error.message), identity)
  private val space = ok(KeySpace("observations", KeyCodec.integer))
  private val data = Vector(11, 12, 13)
  private val context = RenderContext.unsafe(width = 1200, height = 700)
  private val revision = ok(DataRevision("data-v1"))
  private val planRevision = ok(PlanRevision("geometry-v1"))

  private def plan(
      name: String,
      data: Vector[Int] = this.data,
      ctx: RenderContext = context
  ): InteractionPlan[Int] =
    val plot = ok(
      Plot(data)
        .withSemanticId(SemanticId.unsafe(name))
        .addLayer(Layer.point[Int](_.toDouble, n => (n % 3).toDouble))
    )
    ok(
      InteractionCompiler.compile(
        plot,
        space,
        revision,
        SemanticId.unsafe(name),
        planRevision,
        options = PlotCompilerOptions.lean.copy(renderContext = Some(ctx))
      )(identity)
    )

  private def routing(elements: Vector[DeviceElement]): Vector[String] = elements.flatMap {
    case DeviceElement.Annotated(meta, children) =>
      meta.data.collect {
        case (key, value) if key == InteractionCompiler.targetAttribute => value
      } ++ routing(children)
    case DeviceElement.Group(_, _, _, children) => routing(children)
    case _                                      => Vector.empty
  }

  test(
    "row composition preserves independent visual identities and shared entity keys through device lowering"
  ) {
    val first = plan("first")
    val second = plan("second", data.reverse)
    val composed = ok(InteractionComposition.row(Vector(first, second), context))
    val ordinary = ok(PlotComposition.row(Vector(first.trained, second.trained), context))
    assertEquals(composed.scene, ordinary.scene)
    val device = ok(DeviceScene.fromScene(composed.scene, context))
    assertEquals(routing(device.elements).toSet, composed.groups.map(_.name.value).toSet)
    assertEquals(composed.groups.length, 2)
    assertNotEquals(ok(first.groups.head.at(0)).id, ok(second.groups.head.at(2)).id)
    assertEquals(ok(first.groups.head.at(0)).entity, ok(second.groups.head.at(2)).entity)
    assertEquals(composed.composition.cells.map(_.column), Vector(0, 1))
    composed.groups.foreach(group => assertEquals(composed.group(group.name.value), Some(group)))
  }

  test("column, grid, and inset transforms retain typed routing and explicit clipping") {
    val first = plan("first")
    val second = plan("second")
    val column = ok(InteractionComposition.column(Vector(first, second), context))
    assertEquals(column.composition.cells.map(_.row), Vector(0, 1))
    val grid = ok(InteractionComposition.grid(Vector(first, second), 2, context))
    val detail = plan("detail", Vector(11))
    val inset = PlotInset.npcUnsafe(0.5, 0.5, 0.4, 0.4, Clip.On)
    val combined = ok(grid.withInset(detail, inset))
    assertEquals(combined.composition.insetCount, 1)
    assertEquals(combined.scene, grid.composition.withInset(detail.trained, inset).scene)
    assertEquals(
      routing(ok(DeviceScene.fromScene(combined.scene, context)).elements).toSet,
      combined.groups.map(_.name.value).toSet
    )
    assertEquals(combined.plans.map(_.sourceRevision), Vector.fill(3)(revision))
  }

  test("same plan identifiers cannot silently overwrite routing, including inset insertion") {
    val first = plan("same")
    val duplicate = plan("same", data.reverse)
    assert(InteractionComposition.row(Vector(first, duplicate), context).isLeft)
    val composed = ok(InteractionComposition.row(Vector(first), context))
    assert(composed.withInset(duplicate, PlotInset.npcUnsafe(0.5, 0.5, 0.4, 0.4, Clip.On)).isLeft)
  }

  test("composition rejects missing or different compilation contexts") {
    val first = plan("first")
    val different = plan("second", ctx = RenderContext.unsafe(width = 600, height = 350))
    assert(InteractionComposition.row(Vector(first, different), context).isLeft)
    val raw = ok(
      InteractionCompiler.compile(
        ok(Plot(data).addLayer(Layer.point[Int](_.toDouble, _.toDouble))),
        space,
        revision,
        SemanticId.unsafe("raw"),
        planRevision
      )(identity)
    )
    assert(InteractionComposition.row(Vector(raw), context).isLeft)
  }

  test("invalid grid dimensions retain the ordinary typed layout failure") {
    assert(InteractionComposition.grid(Vector(plan("first")), 0, context).isLeft)
    assert(InteractionComposition.row(Vector.empty[InteractionPlan[Int]], context).isLeft)
  }
