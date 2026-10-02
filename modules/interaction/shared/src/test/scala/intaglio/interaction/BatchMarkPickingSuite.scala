package intaglio.interaction

import intaglio.*

class BatchMarkPickingSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val context = RenderContext.unsafe(width = 1000, height = 1000)
  private val ink = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.Black))
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)

  /** Marks on a 100 x 100 grid, 10 device pixels apart, with radius 2 px. */
  private def position(i: Int): Point =
    Point.npcUnsafe((i % 100 * 10 + 5) / 1000.0, 1 - (i / 100 * 10 + 5) / 1000.0)
  private def device(i: Int): DevicePoint = DevicePoint(i % 100 * 10 + 5, i / 100 * 10 + 5)
  private def ids(rows: Vector[Int]): Vector[GraphicsName] = rows.map(i => n(s"row-$i"))
  private def batch(rows: Vector[Int], name: String = "batch"): Grob =
    Grob.pointBatchUnsafe(
      rows.map(position),
      sizes = BatchColumn.Constant(ExtentExpr.pointsUnsafe(1.5)),
      graphicParams = BatchColumn.Constant(ink),
      name = Some(n(name))
    )
  private def marked(rows: Vector[Int]): Grob =
    Grob.annotated(batch(rows), GrobMeta.marks(BatchMarks.unsafe(ids(rows))))
  private def plan(grobs: Grob*): NamedPickingPlan =
    ok(NamedPicking.compile(Scene(grobs.toVector), context))
  private def at(plan: NamedPickingPlan, point: DevicePoint): Option[GraphicsName] =
    ok(plan.nearest(point, 1)).map(_.name)

  private val all = (0 until 10000).toVector
  private val sample = Vector(0, 1, 99, 100, 4321, 5000, 7777, 9998, 9999)

  test("picking a mark in a 10,000-point batch returns that mark's name") {
    val picked = plan(marked(all))
    assertEquals(picked.targetCount, 10000)
    sample.foreach(i => assertEquals(at(picked, device(i)), Some(n(s"row-$i")), i))
    sample.foreach { i =>
      assertEquals(picked.hits(device(i), 3), picked.hitsExhaustive(device(i), 3), i)
    }
    assertEquals(at(picked, DevicePoint(10, 10)), None, "between marks nothing is hit")
  }

  test("without marks a named batch is still one target") {
    val whole = plan(batch(all))
    assertEquals(whole.targetCount, 1)
    assertEquals(at(whole, device(4321)), Some(n("batch")))
  }

  test("a clipped batch keeps the names of the marks that remain visible") {
    val resolved = ok(DeviceScene.fromScene(Scene(Vector(marked(all))), context))
    val clipped = resolved.copy(
      elements = Vector(
        DeviceElement.Group(None, Some(DeviceClip(250, 250, 500, 500)), None, resolved.elements)
      )
    )
    val picked = ok(NamedPicking.fromResolved(clipped, context))
    // Row 5050 sits at (505, 505), inside the clip; row 0 at (5, 5) is clipped away.
    assertEquals(at(picked, device(5050)), Some(n("row-5050")))
    assertEquals(at(picked, device(0)), None)
  }

  test("filtered and split batches keep each mark's name") {
    val kept = all.filter(i => i % 7 != 0)
    val filtered = plan(marked(kept))
    assertEquals(filtered.targetCount, kept.size)
    sample
      .filter(_ % 7 != 0)
      .foreach(i => assertEquals(at(filtered, device(i)), Some(n(s"row-$i"))))
    assertEquals(at(filtered, device(7777)), None, "a filtered mark is gone, not renamed")

    // Three separately identified chunks, and one identity spanning three chunks.
    val chunks = all.grouped(3334).toVector
    val separate = plan(chunks.map(marked)*)
    val names = BatchMarks.unsafe(ids(all))
    val spanning = plan(Grob.annotated(Grob.group(chunks.map(batch(_))), GrobMeta.marks(names)))
    for picked <- Vector(separate, spanning) do
      assertEquals(picked.targetCount, 10000)
      sample.foreach(i => assertEquals(at(picked, device(i)), Some(n(s"row-$i")), i))
    val sliced = plan(
      chunks.zipWithIndex.map { case (rows, k) =>
        Grob.annotated(
          batch(rows),
          GrobMeta.marks(ok(names.slice(k * 3334, k * 3334 + rows.size)))
        )
      }*
    )
    sample.foreach(i => assertEquals(at(sliced, device(i)), Some(n(s"row-$i")), i))
  }

  test("mark titles are the marks' accessible text") {
    val rows = Vector(1, 2)
    val marks = ok(BatchMarks.unsafe(ids(rows)).withTitles(Vector("first trial", "second trial")))
    val picked = plan(Grob.annotated(batch(rows), GrobMeta.marks(marks)))
    assertEquals(picked.accessibleText(n("row-2")), Some("second trial"))
    assertEquals(picked.accessibleText(n("batch")), None)
  }

  test("an unchecked resolved scene with too few names is a typed failure") {
    val resolved = ok(DeviceScene.fromScene(Scene(Vector(batch(Vector(1, 2, 3)))), context))
    val short = DeviceScene(
      resolved.width,
      resolved.height,
      Vector(
        DeviceElement.Annotated(
          GrobMeta.marks(BatchMarks.unsafe(ids(Vector(1)))),
          resolved.elements
        )
      )
    )
    assertEquals(
      NamedPicking.fromResolved(short, context).left.map(_.message),
      Left(PickingError.InvalidInput("batch mark names").message)
    )
  }
