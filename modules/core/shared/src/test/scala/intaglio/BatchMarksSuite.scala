package intaglio

class BatchMarksSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)
  private val device = ok(DeviceContext(200, 100, 96))
  private def n(value: String): GraphicsName = GraphicsName.unsafe(value)
  private def names(prefix: String, count: Int) = (0 until count).toVector.map(i => n(s"$prefix$i"))

  /** Marks at x = (first + i) / 10, so split batches land exactly where the whole one does. */
  private def batch(count: Int, first: Int = 1, name: Option[String] = Some("batch")): Grob =
    Grob.pointBatchUnsafe(
      (0 until count).toVector.map(i => Point.npcUnsafe((first + i) / 10.0, 0.5)),
      name = name.map(n)
    )

  test("batch marks validate their columns and slice with their points") {
    assertEquals(BatchMarks(Vector.empty), Left(GraphicsError.EmptyGeometry("batch marks")))
    val marks = ok(BatchMarks(names("m", 4)))
    assertEquals(
      marks.withTitles(Vector("a")),
      Left(GraphicsError.BatchColumnLengthMismatch("mark titles", 4, 1))
    )
    val titled = ok(marks.withTitles(Vector("a", "b", "c", "d")))
    assertEquals(titled.title(2), Some("c"))
    assertEquals(marks.title(2), None)
    val middle = ok(titled.slice(1, 3))
    assertEquals(middle.names, Vector(n("m1"), n("m2")))
    assertEquals(middle.titles, Some(Vector("b", "c")))
    assert(titled.slice(3, 3).isLeft)
    assert(titled.slice(-1, 2).isLeft)
    assert(titled.slice(0, 5).isLeft)
    assertEquals(ok(BatchMarks(names("m", 4))), marks)
    assertNotEquals(titled, marks)
  }

  test("the identity wrapper lowers transparently around the batch it names") {
    val plain = ok(DeviceScene.fromScene(Scene(Vector(batch(3))), device))
    val marks = ok(BatchMarks(names("m", 3)))
    val marked =
      ok(
        DeviceScene.fromScene(
          Scene(Vector(Grob.annotated(batch(3), GrobMeta.marks(marks)))),
          device
        )
      )
    assertEquals(
      marked.elements,
      Vector(DeviceElement.Annotated(GrobMeta.marks(marks), plain.elements))
    )
  }

  test("lowering refuses names that do not match the marks beneath them") {
    val two = ok(BatchMarks(names("m", 2)))
    assertEquals(
      DeviceScene.fromScene(Scene(Vector(Grob.annotated(batch(3), GrobMeta.marks(two)))), device),
      Left(GraphicsError.BatchColumnLengthMismatch("mark names", 3, 2))
    )
    val disc = Grob.circleUnsafe(Point.npcUnsafe(0.5, 0.5), ExtentExpr.pointsUnsafe(2))
    assertEquals(
      DeviceScene.fromScene(Scene(Vector(Grob.annotated(disc, GrobMeta.marks(two)))), device),
      Left(GraphicsError.BatchColumnLengthMismatch("mark names", 0, 2)),
      "a disc is not a batch mark"
    )
  }

  test("names run across split batches in draw order; a nearer annotation names its own") {
    val split = Grob.group(Vector(batch(2), batch(3, first = 3)))
    val outer = ok(BatchMarks(names("outer", 5)))
    val scene =
      ok(DeviceScene.fromScene(Scene(Vector(Grob.annotated(split, GrobMeta.marks(outer)))), device))
    val whole = ok(DeviceScene.fromScene(Scene(Vector(batch(5))), device))
    assertEquals(BatchMarks.marksOf(scene).map(_.name), outer.names)
    assertEquals(
      BatchMarks.marksOf(scene).map(_.at),
      whole.elements.collect { case DeviceElement.Mark(b: DevicePrimitive.PointBatch) =>
        b.points
      }.flatten
    )
    val inner = ok(BatchMarks(names("inner", 3)))
    val nested = Grob.group(
      Vector(batch(2), Grob.annotated(batch(3, first = 4), GrobMeta.marks(inner)))
    )
    val partial = ok(BatchMarks(names("outer", 2)))
    val resolved =
      ok(
        DeviceScene.fromScene(
          Scene(Vector(Grob.annotated(nested, GrobMeta.marks(partial)))),
          device
        )
      )
    assertEquals(BatchMarks.marksOf(resolved).map(_.name), partial.names ++ inner.names)
  }

  test("marksOf reports titles and ignores unidentified batches") {
    val marks = ok(ok(BatchMarks(names("m", 2))).withTitles(Vector("first", "second")))
    val scene = ok(
      DeviceScene.fromScene(
        Scene(Vector(batch(4), Grob.annotated(batch(2, first = 6), GrobMeta.marks(marks)))),
        device
      )
    )
    assertEquals(
      BatchMarks.marksOf(scene).map(m => (m.name.value, m.title)),
      Vector("m0" -> Some("first"), "m1" -> Some("second"))
    )
  }

  test("pre-identity GrobMeta constructors, factories and copies remain") {
    val key = DataKey.unsafe("kind")
    val legacy = new GrobMeta(Some("t"), None, None, Vector(key -> "v"))
    assertEquals(legacy, GrobMeta(title = Some("t"), data = Vector(key -> "v")))
    assertEquals(GrobMeta(Some("t"), None, None, Vector(key -> "v")), legacy)
    val marks = ok(BatchMarks(names("m", 1)))
    val marked = legacy.withMarks(marks)
    assertEquals(marked.copy(Some("u"), None, None, Vector.empty).marks, Some(marks))
    assert(!GrobMeta.marks(marks).isEmpty)
    assert(GrobMeta.empty.isEmpty)
  }
