package intaglio.interaction

import intaglio.*
import PickGeometry.*

class TargetOutlineSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 200)
  private val fill = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
  private def stroke(width: Double) =
    GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = width)
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)
  private def mark(primitive: DevicePrimitive): DeviceElement = DeviceElement.Mark(primitive)
  private def plan(elements: DeviceElement*): NamedPickingPlan =
    ok(NamedPicking.fromResolved(DeviceScene(200, 200, elements.toVector), context))
  private def outline(plan: NamedPickingPlan, name: String, offset: Double): TargetOutline =
    ok(plan.outline(n(name), offset)).getOrElse(fail(s"no outline for $name"))
  private def p(point: DevicePoint): P = P(point.x, point.y)

  /** Every ring vertex lies `distance` from `boundary`, and no chord dips more than the flattening
    * tolerance inside it.
    */
  private def assertFollows(ring: Vector[DevicePoint], boundary: Region, distance: Double): Unit =
    def gap(point: P) = boundary.edges.map(_.nearest(point).distance(point)).min
    assert(ring.size >= 4, ring)
    ring.foreach(v => assertEqualsDouble(gap(p(v)), distance, 1e-6, v))
    ring.indices.foreach { i =>
      val middle = (p(ring(i)) + p(ring((i + 1) % ring.size))) * 0.5
      assert(gap(middle) >= distance - TargetOutline.toleranceDevicePx - 1e-9, middle)
      assert(gap(middle) <= distance + 1e-6, middle)
    }

  test("a circle's outline is a circle at its radius plus half the stroke plus the offset") {
    val filled = plan(mark(DevicePrimitive.Disc(50, 50, 10, fill, Some(n("filled")))))
    assertFollows(outline(filled, "filled", 3).rings.head, Region.disc(P(50, 50), 10), 3)
    val ring = plan(mark(DevicePrimitive.Disc(50, 50, 10, stroke(4), Some(n("ring")))))
    val rings = outline(ring, "ring", 3).rings
    assertEquals(rings.size, 1)
    assertFollows(rings.head, Region.disc(P(50, 50), 10), 5)
  }

  test("diamond and triangle points are offset edge by edge with round corners") {
    for shape <- Vector(PointShape.Diamond, PointShape.Triangle, PointShape.Square) do
      val batch = DevicePrimitive.PointBatch(
        Vector(DevicePoint(100, 100)),
        BatchColumn.Constant(12.0),
        BatchColumn.Constant(shape),
        BatchColumn.Constant(stroke(2)),
        Some(n("point"))
      )
      val target = plan(mark(batch))
      val drawn = Picking.pointPrimitives(P(100, 100), 12, shape, stroke(2)).head
      val boundary = drawn match
        case DevicePrimitive.Polyline(points, _, _, _)      => Region.polygon(Vector(points.map(p)))
        case DevicePrimitive.RectShape(x, y, w, h, _, _, _) =>
          Region.rectangle(Box(x, y, x + w, y + h))
        case other => fail(s"unexpected $other")
      for offset <- Vector(0.5, 2.0, 6.0) do
        assertFollows(outline(target, "point", offset).rings.head, boundary, 1 + offset)
  }

  test("a concave closed path keeps every vertex at the offset, mitred inside the notch") {
    val l = Vector(
      DevicePoint(20, 20),
      DevicePoint(80, 20),
      DevicePoint(80, 40),
      DevicePoint(40, 40),
      DevicePoint(40, 90),
      DevicePoint(20, 90)
    )
    for points <- Vector(l, l.reverse) do
      val target = plan(mark(DevicePrimitive.Polyline(points, closed = true, fill, Some(n("l")))))
      assertFollows(outline(target, "l", 4).rings.head, Region.polygon(Vector(points.map(p))), 4)
  }

  test("rounded rectangles keep their corner radius plus the offset") {
    val target = plan(mark(DevicePrimitive.RectShape(20, 30, 60, 40, 8, stroke(2), Some(n("r")))))
    assertFollows(
      outline(target, "r", 2).rings.head,
      Region.roundedRectangle(Box(20, 30, 80, 70), 8),
      3
    )
  }

  test("rotated groups move the outline with the mark") {
    val rotated = DeviceElement.Group(
      Some(n("turned")),
      None,
      Some(DeviceRotation(90, 100, 100)),
      Vector(mark(DevicePrimitive.Disc(150, 100, 5, fill, None)))
    )
    val ring = outline(plan(rotated), "turned", 1).rings.head
    // A quarter turn clockwise about (100, 100) carries (150, 100) to (100, 150).
    assertFollows(ring, Region.disc(P(100, 150), 5), 1)
  }

  test("a compound polygon is traced by its outer rings; holes are not outlined") {
    val donut = mark(
      DevicePrimitive.CompoundPolygon(
        Vector(
          Vector(
            DevicePoint(100, 100),
            DevicePoint(180, 100),
            DevicePoint(180, 180),
            DevicePoint(100, 180)
          ),
          Vector(
            DevicePoint(130, 130),
            DevicePoint(130, 150),
            DevicePoint(150, 150),
            DevicePoint(150, 130)
          )
        ),
        fill,
        Some(n("donut"))
      )
    )
    val rings = outline(plan(donut), "donut", 2).rings
    assertEquals(rings.size, 1)
    assertFollows(rings.head, Region.rectangle(Box(100, 100, 180, 180)), 2)
  }

  test("open paths and text use their painted bounds, offset") {
    val line = mark(
      DevicePrimitive.Polyline(
        Vector(DevicePoint(10, 10), DevicePoint(60, 30)),
        closed = false,
        stroke(2),
        Some(n("line"))
      )
    )
    val target = plan(line)
    val box = target.targets.head.bounds.get
    assertEquals(
      outline(target, "line", 3).rings,
      Vector(
        Vector(
          DevicePoint(box.left - 3, box.top - 3),
          DevicePoint(box.right + 3, box.top - 3),
          DevicePoint(box.right + 3, box.bottom + 3),
          DevicePoint(box.left - 3, box.bottom + 3)
        )
      )
    )
  }

  test("each painted primitive of a name gets its own ring") {
    val target = plan(
      mark(DevicePrimitive.Disc(40, 40, 5, fill, Some(n("pair")))),
      mark(DevicePrimitive.Disc(140, 40, 5, fill, Some(n("pair"))))
    )
    val rings = outline(target, "pair", 1).rings
    assertEquals(rings.size, 2)
    assertFollows(rings(0), Region.disc(P(40, 40), 5), 1)
    assertFollows(rings(1), Region.disc(P(140, 40), 5), 1)
  }

  test("plot targets are outlined by id; bad offsets and foreign ids are typed failures") {
    val keys = ok(KeySpace("rows", KeyCodec.integer))
    val plot = ok(Plot(Vector(0, 1)).addLayer(Layer.point[Int](_.toDouble, _.toDouble)))
    val compiled = ok(
      InteractionCompiler.compile(
        plot,
        keys,
        ok(DataRevision("d")),
        SemanticId.unsafe("outline"),
        ok(PlanRevision("r")),
        PlotCompilerOptions.lean.copy(renderContext = Some(context))
      )(identity)
    )
    val picking = ok(Picking.compile(compiled, context))
    val geometry = picking.prepareNavigation().targets.head
    val ring = ok(picking.outline(geometry.target.id, 2)).rings.head
    val centre = P((geometry.left + geometry.right) / 2, (geometry.top + geometry.bottom) / 2)
    val radius = (geometry.right - geometry.left) / 2
    assertFollows(ring, Region.disc(centre, radius), 2)
    assert(picking.outline(geometry.target.id, -1).isLeft)
    assert(picking.outline(geometry.target.id, Double.NaN).isLeft)
    val other = ok(Picking.compile(compiled, context)).prepareNavigation().targets.head.target.id
    assert(picking.outline(other, 1).isRight, "the same plan address is the same target")
    val named = plan(mark(DevicePrimitive.Disc(40, 40, 5, fill, Some(n("a")))))
    assertEquals(ok(named.outline(n("absent"), 1)), None)
    assert(named.outline(n("a"), Double.PositiveInfinity).isLeft)
  }
