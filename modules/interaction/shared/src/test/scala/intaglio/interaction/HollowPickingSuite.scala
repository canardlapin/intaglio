package intaglio.interaction

import intaglio.*

class HollowPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 200, height = 200)
  private val outline = GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = None, lineWidth = 2)
  private val outlinedAndFilled =
    GraphicParams.unsafe(stroke = Some(Rgba.Black), fill = Some(Rgba.Black), lineWidth = 2)
  private val invisible = GraphicParams.unsafe(stroke = None, fill = None)
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)
  private val interior = PickPolicy.default.withHollow(HollowPicking.Interior)
  private val outlinePoints = PickPolicy.default.withHollowPoints(HollowPicking.Outline)

  private def named(policy: PickPolicy, elements: DeviceElement*): NamedPickingPlan =
    ok(NamedPicking.fromResolved(DeviceScene(200, 200, elements.toVector), context, policy))
  private def at(plan: NamedPickingPlan, x: Double, y: Double): Vector[GraphicsName] =
    ok(plan.hits(DevicePoint(x, y))).map(_.name)
  private def mark(primitive: DevicePrimitive): DeviceElement = DeviceElement.Mark(primitive)
  private def disc(x: Double, y: Double, r: Double, gp: GraphicParams, name: String) =
    mark(DevicePrimitive.Disc(x, y, r, gp, Some(n(name))))
  private def ring(x: Double, y: Double, r: Double, name: String) =
    mark(
      DevicePrimitive.Polyline(
        (0 until 32).toVector.map { i =>
          val a = i * math.Pi / 16
          DevicePoint(x + r * math.cos(a), y + r * math.sin(a))
        },
        closed = true,
        outline,
        Some(n(name))
      )
    )
  private def batch(shape: PointShape, gp: GraphicParams, name: String) =
    mark(
      DevicePrimitive.PointBatch(
        Vector(DevicePoint(40, 40), DevicePoint(120, 120)),
        BatchColumn.Constant(10.0),
        BatchColumn.Constant(shape),
        BatchColumn.Constant(gp),
        Some(n(name))
      )
    )

  test("the centre of a hollow circle point or ring path hits only with the inside included") {
    val elements = Vector(disc(50, 50, 12, outline, "circle"), ring(140, 60, 20, "ring"))
    val off = named(PickPolicy.default, elements*)
    val on = named(interior, elements*)
    assertEquals(at(off, 50, 50), Vector.empty)
    assertEquals(at(off, 140, 60), Vector.empty)
    assertEquals(at(on, 50, 50), Vector(n("circle")))
    assertEquals(at(on, 140, 60), Vector(n("ring")))
    assertEquals(at(off, 62, 50), Vector(n("circle")), "the outline is hit either way")
    assertEquals(at(on, 75, 50), Vector.empty, "outside the mark is still a miss")
  }

  test("closed point shapes have an inside; a cross and an invisible mark do not") {
    for shape <- Vector(
        PointShape.Circle,
        PointShape.Square,
        PointShape.Triangle,
        PointShape.Diamond
      )
    do
      val off = named(outlinePoints, batch(shape, outline, "b"))
      val on = named(interior, batch(shape, outline, "b"))
      val byDefault = named(PickPolicy.default, batch(shape, outline, "b"))
      assertEquals(at(off, 120, 122), Vector.empty, shape)
      assertEquals(at(on, 120, 122), Vector(n("b")), shape)
      assertEquals(
        at(byDefault, 120, 122),
        Vector(n("b")),
        s"$shape: a batch mark is a point glyph"
      )
    val cross = named(interior, batch(PointShape.Cross, outline, "x"))
    assertEquals(at(cross, 123, 123), Vector.empty, "a cross has no inside")
    val hidden = named(interior, disc(50, 50, 10, invisible, "hidden"))
    assertEquals(at(hidden, 50, 50), Vector.empty, "an unpainted mark stays untargetable")
  }

  test("rounded rectangles and compound polygons are hit inside under the policy") {
    val rounded = mark(DevicePrimitive.RectShape(20, 20, 60, 40, 8, outline, Some(n("rounded"))))
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
        outline,
        Some(n("donut"))
      )
    )
    val off = named(PickPolicy.default, rounded, donut)
    val on = named(interior, rounded, donut)
    assertEquals(at(off, 50, 40), Vector.empty)
    assertEquals(at(on, 50, 40), Vector(n("rounded")))
    assertEquals(at(on, 115, 115), Vector(n("donut")))
    assertEquals(at(on, 140, 140), Vector.empty, "the hole follows the renderer's nonzero fill")
  }

  test("overlapping hollow marks keep stack order: the later-drawn mark wins a shared inside") {
    val plan = named(
      interior,
      disc(80, 80, 30, outline, "under"),
      disc(100, 80, 30, outline, "over")
    )
    assertEquals(at(plan, 90, 80), Vector(n("over"), n("under")))
    assertEquals(ok(plan.hits(DevicePoint(90, 80))).map(_.drawOrder), Vector(1, 0))
    assertEquals(at(plan, 60, 80), Vector(n("under")))
    assertEquals(
      ok(plan.nearest(DevicePoint(90, 80), 0)).map(_.name),
      Some(n("over")),
      "a point inside both is a distance tie broken by draw order, as for filled marks"
    )
  }

  test("InteriorOf includes the inside only for the listed innermost names") {
    val elements = Vector(
      DeviceElement.Group(Some(n("chosen")), None, None, Vector(disc(50, 50, 12, outline, "own"))),
      DeviceElement.Group(
        Some(n("group")),
        None,
        None,
        Vector(mark(DevicePrimitive.Disc(150, 50, 12, outline, None)))
      )
    )
    val plan = named(
      PickPolicy.default.withHollow(HollowPicking.InteriorOf(Set(n("chosen"), n("group")))),
      elements*
    )
    assertEquals(at(plan, 50, 50), Vector.empty, "the primitive's own name is the innermost one")
    assertEquals(at(plan, 150, 50), Vector(n("group")))
  }

  test("the inside policy only adds hits, and makes hollow marks pick as if filled") {
    val shapes =
      Vector(PointShape.Circle, PointShape.Square, PointShape.Triangle, PointShape.Diamond)
    val hollow = (0 until 30).toVector.map { i =>
      mark(
        DevicePrimitive.PointBatch(
          Vector(DevicePoint(10 + (i * 37) % 180, 10 + (i * 53) % 180)),
          BatchColumn.Constant(4.0 + i % 7),
          BatchColumn.Constant(shapes(i % shapes.size)),
          BatchColumn.Constant(outline),
          Some(n(s"m$i"))
        )
      )
    }
    val filled = hollow.map {
      case DeviceElement.Mark(b: DevicePrimitive.PointBatch) =>
        DeviceElement.Mark(b.copy(graphicParams = BatchColumn.Constant(outlinedAndFilled)))
      case other => other
    }
    val off = named(outlinePoints, hollow*)
    val on = named(interior, hollow*)
    val byDefault = named(PickPolicy.default, hollow*)
    val asFilled = named(PickPolicy.default, filled*)
    for
      x <- 0 to 200 by 3
      y <- 0 to 200 by 3
    do
      val point = DevicePoint(x.toDouble, y.toDouble)
      val without = ok(off.hits(point, 1)).map(_.name).toSet
      val withInside = ok(on.hits(point, 1)).map(_.name).toSet
      assert(without.subsetOf(withInside), point)
      assertEquals(on.hits(point, 1), asFilled.hits(point, 1), point)
      assertEquals(byDefault.hits(point, 1), asFilled.hits(point, 1), point)
      assertEquals(on.hits(point, 1), on.hitsExhaustive(point, 1), point)
  }

  test("plot picking honours the policy for a hollow point layer") {
    val context = RenderContext.unsafe(width = 200, height = 200, pixelsPerInch = 288)
    val keys = ok(KeySpace("rows", KeyCodec.integer))
    val plot = ok(
      Plot(Vector(0, 1)).addLayer(
        Layer.point[Int](_.toDouble, _.toDouble, params = Some(outline))
      )
    )
    val plan = ok(
      InteractionCompiler.compile(
        plot,
        keys,
        ok(DataRevision("d")),
        SemanticId.unsafe("hollow"),
        ok(PlanRevision("r")),
        PlotCompilerOptions.lean.copy(renderContext = Some(context))
      )(identity)
    )
    val off = ok(Picking.compile(plan, context, outlinePoints))
    val on = ok(Picking.compile(plan, context, interior))
    val byDefault = ok(Picking.compile(plan, context))
    val centres = off.prepareNavigation().targets.map(_.anchor)
    assertEquals(centres.size, 2)
    // A navigation anchor of a hollow mark sits on its outline; the bounds centre is the inside.
    val geometry = off.prepareNavigation().targets.head
    val centre =
      DevicePoint((geometry.left + geometry.right) / 2, (geometry.top + geometry.bottom) / 2)
    assertEquals(ok(off.hits(centre)).size, 0)
    assertEquals(ok(on.hits(centre)).flatMap(_.target.entity).map(_.value), Vector(0))
    assertEquals(ok(byDefault.hits(centre)).flatMap(_.target.entity).map(_.value), Vector(0))
  }
