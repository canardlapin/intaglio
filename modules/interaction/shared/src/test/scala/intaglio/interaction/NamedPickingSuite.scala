package intaglio.interaction

import intaglio.*

class NamedPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 200)
  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)
  private def disc(x: Double, y: Double, radius: Double = 5, name: Option[String] = None) =
    DeviceElement.Mark(DevicePrimitive.Disc(x, y, radius, fill, name.map(n)))
  private def group(name: Option[String], children: DeviceElement*): DeviceElement =
    DeviceElement.Group(name.map(n), None, None, children.toVector)
  private def compile(elements: DeviceElement*): NamedPickingPlan =
    ok(
      NamedPicking.fromDeviceScene(
        DeviceScene(200, 200, elements.toVector),
        context,
        PickPolicy.default
      )
    )
  private def at(plan: NamedPickingPlan, x: Double, y: Double, tolerance: Double = 0) =
    ok(plan.hits(DevicePoint(x, y), tolerance)).map(_.name)

  test("a part belongs to the innermost enclosing name, as closest([data-name]) finds it"):
    val plan = compile(
      group(
        Some("outer"),
        disc(50, 50, name = Some("inner")),
        disc(100, 50),
        group(None, disc(150, 50))
      )
    )
    assertEquals(at(plan, 50, 50), Vector(n("inner")))
    assertEquals(at(plan, 100, 50), Vector(n("outer")))
    assertEquals(at(plan, 150, 50), Vector(n("outer")), "an unnamed group does not reset the name")
    assertEquals(plan.targetCount, 2)

  test("parts sharing a name are one logical target; unnamed parts are not targets"):
    val plan = compile(
      disc(20, 20),
      disc(40, 50, name = Some("pair")),
      disc(60, 50, name = Some("pair"))
    )
    assertEquals(plan.targetCount, 1)
    assertEquals(plan.names, Vector(n("pair")))
    assertEquals(at(plan, 50, 50, 20), Vector(n("pair")))
    assertEquals(at(plan, 20, 20), Vector.empty)

  test("coincident targets order by distance, then prefer the later-drawn one"):
    val plan = compile(
      disc(50, 50, radius = 10, name = Some("under")),
      disc(10, 10),
      disc(50, 50, radius = 2, name = Some("over"))
    )
    assertEquals(at(plan, 50, 50), Vector(n("over"), n("under")))
    val drawn = ok(plan.hits(DevicePoint(50, 50))).map(_.drawOrder)
    assert(drawn.head > drawn(1), s"draw order counts unnamed parts too: $drawn")
    assertEquals(ok(plan.nearest(DevicePoint(56, 50), 10)).map(_.name), Some(n("under")))
    assertEquals(ok(plan.nearest(DevicePoint(75, 50), 10)), None)

  test("a named point batch is one target over all its points"):
    val batch = DeviceElement.Mark(
      DevicePrimitive.PointBatch(
        Vector(DevicePoint(20, 100), DevicePoint(80, 100)),
        BatchColumn.Constant(5.0),
        BatchColumn.Constant(PointShape.Circle),
        BatchColumn.Constant(fill),
        Some(n("batch"))
      )
    )
    val plan = compile(batch)
    assertEquals(plan.targetCount, 1)
    assertEquals(at(plan, 20, 100), Vector(n("batch")))
    assertEquals(at(plan, 80, 100), Vector(n("batch")))
    assertEquals(at(plan, 50, 100), Vector.empty)

  test("group clips and rotations apply to named parts exactly as they are drawn"):
    val clipped = DeviceElement.Group(
      Some(n("clipped")),
      Some(DeviceClip(50, 45, 5, 10)),
      Some(DeviceRotation(90, 50, 50)),
      Vector(disc(50, 50, radius = 10))
    )
    val plan = compile(clipped)
    assertEquals(at(plan, 50, 53), Vector(n("clipped")))
    assertEquals(at(plan, 53, 48), Vector.empty)

  test("area selection returns names in draw order under each rule"):
    val plan = compile(
      disc(40, 50, name = Some("left")),
      disc(60, 50, name = Some("left")),
      disc(150, 150, name = Some("far"))
    )
    val box = ok(PickArea.rectangle(34, 44, 46, 56))
    assertEquals(plan.select(box, AreaRule.Intersecting), Vector(n("left")))
    assertEquals(plan.select(box, AreaRule.FullyContained), Vector.empty)
    val all = ok(PickArea.rectangle(0, 0, 200, 200))
    assertEquals(plan.select(all, AreaRule.FullyContained), Vector(n("left"), n("far")))

  test("the grid path agrees with the exhaustive oracle"):
    val plan = compile(
      (0 until 40).map(i =>
        disc(5 + (i * 37) % 190, 5 + (i * 53) % 190, radius = 3 + i % 5, name = Some(s"m${i % 13}"))
      )*
    )
    for
      x <- 0 to 200 by 7
      y <- 0 to 200 by 11
      tolerance <- Vector(0.0, 4.0)
    do
      val point = DevicePoint(x.toDouble, y.toDouble)
      assertEquals(plan.hits(point, tolerance), plan.hitsExhaustive(point, tolerance), point)

  test("invalid queries and malformed batches are typed failures"):
    val plan = compile(disc(50, 50, name = Some("a")))
    assert(plan.hits(DevicePoint(Double.NaN, 0)).isLeft)
    assert(plan.hits(DevicePoint(0, 0), -1).isLeft)
    val malformed = DeviceElement.Mark(
      DevicePrimitive.PointBatch(
        Vector(DevicePoint(1, 1), DevicePoint(2, 2)),
        BatchColumn.Values(Vector(1.0)),
        BatchColumn.Constant(PointShape.Circle),
        BatchColumn.Constant(fill),
        Some(n("bad"))
      )
    )
    assertEquals(
      NamedPicking
        .fromDeviceScene(DeviceScene(200, 200, Vector(malformed)), context, PickPolicy.default)
        .left
        .map(_.message),
      Left(PickingError.InvalidInput("point batch columns").message)
    )
    assert(
      NamedPicking
        .fromDeviceScene(DeviceScene(0, 200, Vector.empty), context, PickPolicy.default)
        .isLeft
    )

  test("a hand-built Scene compiles through the public entry point"):
    // npc y runs upward; the device scene is y-down, so npc 0.7 on a 200px device is y = 60.
    def circle(x: Double, name: Option[String]) =
      Grob.circleUnsafe(
        Point.npcUnsafe(x / 200, 0.7),
        ExtentExpr.pointsUnsafe(6),
        fill,
        name = name.map(n)
      )
    val scene = Scene(
      Vector(
        Grob.group(Vector(circle(40, None), circle(120, Some("own"))), name = Some(n("mark-1")))
      )
    )
    val plan = ok(NamedPicking.compile(scene, context))
    assertEquals(plan.names.toSet, Set(n("mark-1"), n("own")))
    assertEquals(ok(plan.nearest(DevicePoint(40, 60), 2)).map(_.name), Some(n("mark-1")))
    assertEquals(ok(plan.nearest(DevicePoint(120, 60), 2)).map(_.name), Some(n("own")))
