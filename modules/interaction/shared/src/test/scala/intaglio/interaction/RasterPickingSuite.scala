package intaglio.interaction

import intaglio.*

class RasterPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(e => fail(e.message), identity)
  private val context = RenderContext.unsafe(width = 300, height = 200)
  private val field = ScalarField2D.unsafe(
    RegularGridAxis.cellCenteredUnsafe(0, 3, 3),
    RegularGridAxis.cellCenteredUnsafe(0, 2, 2),
    Vector(1, 2, 3, 4, 5, 6).map(_.toDouble)
  )
  private def interaction(mask: ScalarCell => Boolean = _ => false) =
    val program = ok(plot(field).geomRaster(missing = mask).build)
    ok(
      InteractionCompiler.compile(
        program.plot,
        ok(KeySpace("cells", KeyCodec.integer)),
        ok(DataRevision("one")),
        SemanticId.unsafe("raster"),
        ok(PlanRevision("one")),
        program.compilerOptions
      )(c => c.yIndex * field.width + c.xIndex)
    )
  private def image(elements: Vector[DeviceElement]): DevicePrimitive.Image =
    def marks(elements: Vector[DeviceElement]): Vector[DevicePrimitive] = elements.flatMap {
      case DeviceElement.Mark(mark)               => Vector(mark)
      case DeviceElement.Group(_, _, _, children) => marks(children)
      case DeviceElement.Annotated(_, children)   => marks(children)
    }
    marks(elements).collectFirst { case i: DevicePrimitive.Image => i }.get

  test("one raster route picks original cell row, column, value and entity at every centre") {
    val plan = interaction()
    assertEquals(plan.groups.size, 1)
    assertEquals(plan.groups.head.size, 6)
    val device = ok(DeviceScene.fromScene(plan.scene, context))
    val img = image(device.elements)
    val picking = ok(Picking.compile(plan, context))
    assertEquals(picking.targetCount, 6)
    field.cells.foreach { cell =>
      val point = DevicePoint(
        img.x + (cell.xIndex + 0.5) * img.width / 3,
        img.y + (1 - cell.yIndex + 0.5) * img.height / 2
      )
      val hits = ok(picking.hits(point))
      assertEquals(hits.size, 1)
      assertEquals(
        hits.head.target.rasterCell,
        Some(RasterCell(cell.yIndex, cell.xIndex, cell.value))
      )
      assert(hits.head.target.entity.nonEmpty)
      assertEquals(hits, ok(picking.hitsExhaustive(point)))
    }
    assertEquals(ok(picking.hits(DevicePoint(img.x - 2, img.y - 2))).size, 0)
    val area =
      ok(PickArea.rectangle(img.x + 1, img.y + 1, img.x + img.width - 1, img.y + img.height - 1))
    AreaRule.values.foreach(rule =>
      assertEquals(picking.select(area, rule), picking.selectExhaustive(area, rule))
    )
  }

  test("transparent missing cells obey the shared includeTransparent policy") {
    val plan = interaction(_.value == 1)
    val img = image(ok(DeviceScene.fromScene(plan.scene, context)).elements)
    val point = DevicePoint(img.x + img.width / 6, img.y + img.height * 0.75)
    assertEquals(ok(ok(Picking.compile(plan, context)).hits(point)).size, 0)
    val inclusive = ok(Picking.compile(plan, context, ok(PickPolicy(includeTransparent = true))))
    assertEquals(ok(inclusive.hits(point)).head.target.rasterCell, Some(RasterCell(0, 0, 1)))
    val id = ok(inclusive.hits(point)).head.target.id
    assertEquals(ok(ok(Picking.compile(plan, context)).geometry(id)), None)
    assert(ok(inclusive.geometry(id)).nonEmpty)
  }

  test("rotated clipped raster queries agree with exhaustive cell geometry") {
    val plan = interaction()
    val group = plan.groups.head
    val img = image(ok(DeviceScene.fromScene(plan.scene, context)).elements)
    val route = DeviceElement.Annotated(
      GrobMeta(data = Vector(InteractionCompiler.targetAttribute -> group.name.value)),
      Vector(DeviceElement.Mark(img))
    )
    val scene = DeviceScene(
      300,
      200,
      Vector(
        DeviceElement.Group(
          None,
          Some(DeviceClip(20, 20, 240, 150)),
          Some(DeviceRotation(20, 150, 100)),
          Vector(route)
        )
      )
    )
    val picking = ok(Picking.fromDeviceScene(scene, Vector(group), context, PickPolicy.default))
    for x <- 0 to 300 by 19; y <- 0 to 200 by 17 do
      val p = DevicePoint(x, y)
      assertEquals(ok(picking.hits(p, 3)), ok(picking.hitsExhaustive(p, 3)))
  }
