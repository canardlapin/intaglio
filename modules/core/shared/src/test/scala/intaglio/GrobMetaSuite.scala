package intaglio

class GrobMetaSuite extends munit.FunSuite:
  private val device = DeviceContext.unsafe(100.0, 100.0)

  private def disc(name: String): Grob =
    Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.pointsUnsafe(3.0),
        name = Some(GraphicsName.unsafe(name))
      )
      .fold(error => fail(error.message), identity)

  test("CssClass accepts identifier tokens and normalises whitespace") {
    assertEquals(CssClass("mark decode-filled").map(_.value), Right("mark decode-filled"))
    assertEquals(CssClass("  a\tb  \n c ").map(_.tokens), Right(Vector("a", "b", "c")))
    assertEquals(CssClass("-webkit_x9").map(_.value), Right("-webkit_x9"))
    assertEquals(CssClass("_under"), CssClass("_under"))
  }

  test("CssClass refuses blank input and non-identifier tokens") {
    assertEquals(
      CssClass(""),
      Left(GraphicsError.InvalidCssClass("", CssClass.expectation))
    )
    assertEquals(
      CssClass("   "),
      Left(GraphicsError.InvalidCssClass("   ", CssClass.expectation))
    )
    assertEquals(
      CssClass("mark 1abc"),
      Left(GraphicsError.InvalidCssClass("1abc", CssClass.expectation))
    )
    assertEquals(
      CssClass("a b!"),
      Left(GraphicsError.InvalidCssClass("b!", CssClass.expectation))
    )
    assertEquals(
      CssClass("--x"),
      Left(GraphicsError.InvalidCssClass("--x", CssClass.expectation))
    )
    assertEquals(
      CssClass("naïve"),
      Left(GraphicsError.InvalidCssClass("naïve", CssClass.expectation))
    )
  }

  test("DataKey accepts lowercase letters, digits, and hyphens and names its attribute") {
    assertEquals(DataKey("kind").map(_.attributeName), Right("data-kind"))
    assertEquals(DataKey("k1-x2").map(_.value), Right("k1-x2"))
    assertEquals(DataKey("a-").map(_.value), Right("a-"))
  }

  test("DataKey refuses empty, uppercase, leading-digit, underscore, whitespace, and 'name'") {
    Vector("", "Kind", "1a", "a_b", "a b", "name", "-a", "kind\n").foreach { candidate =>
      assertEquals(
        DataKey(candidate),
        Left(GraphicsError.InvalidDataKey(candidate, DataKey.expectation)),
        clues(candidate)
      )
    }
  }

  test("GrobMeta builders append data in order and detect the first duplicate key") {
    val kind = DataKey.unsafe("kind")
    val origin = DataKey.unsafe("origin")
    val meta = GrobMeta.empty
      .withTitle("t")
      .withDescription("d")
      .withCssClass(CssClass.unsafe("x"))
      .withData(kind, "anchor")
      .withData(origin, "decode")

    assert(GrobMeta.empty.isEmpty)
    assert(!GrobMeta.title("only").isEmpty)
    assertEquals(meta.data, Vector(kind -> "anchor", origin -> "decode"))
    assertEquals(meta.duplicateDataKey, None)
    assertEquals(meta.withData(kind, "again").duplicateDataKey, Some(kind))
  }

  test("an annotated grob has no name or viewport and exactly one child") {
    val child = disc("child")
    val annotated = Grob.annotated(child, GrobMeta.title("hover"))

    assertEquals(annotated.name, None)
    assertEquals(annotated.viewport, None)
    assertEquals(annotated.children, Vector(child))
    annotated match
      case Grob.Annotated(inner, meta) =>
        assertEquals(inner, child)
        assertEquals(meta, GrobMeta.title("hover"))
      case other => fail(s"expected an annotated grob, found $other")
  }

  test("device lowering wraps the child's primitives in an annotated element") {
    val meta = GrobMeta(title = Some("hover"), data = Vector(DataKey.unsafe("kind") -> "anchor"))
    val plain = DeviceScene.fromScene(Scene(Vector(disc("mark"))), device).toOption.get
    val annotated = DeviceScene
      .fromScene(Scene(Vector(Grob.annotated(disc("mark"), meta))), device)
      .toOption
      .get

    annotated.elements match
      case Vector(DeviceElement.Annotated(actualMeta, children)) =>
        assertEquals(actualMeta, meta)
        assertEquals(children, plain.elements)
      case other => fail(s"unexpected device elements: $other")
  }

  test("an annotated child with a viewport still lowers to its clipped group") {
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.1),
      size = Size.npcUnsafe(0.5, 0.5)
    )
    val child = Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("framed"))
      )
      .fold(error => fail(error.message), identity)
    val plain = DeviceScene.fromScene(Scene(Vector(child)), device).toOption.get
    val annotated = DeviceScene
      .fromScene(Scene(Vector(Grob.annotated(child, GrobMeta.empty))), device)
      .toOption
      .get

    annotated.elements match
      case Vector(DeviceElement.Annotated(meta, Vector(group: DeviceElement.Group))) =>
        assertEquals(meta, GrobMeta.empty)
        assertEquals(group.name.map(_.value), Some("framed"))
        assert(group.clip.nonEmpty)
        assertEquals(Vector(group), plain.elements)
      case other => fail(s"unexpected device elements: $other")
  }

  test("nested annotations and annotated groups lower recursively") {
    val inner = Grob.annotated(disc("a"), GrobMeta.title("inner"))
    val group = Grob.group(Vector(inner, disc("b")), name = Some(GraphicsName.unsafe("g")))
    val outer = Grob.annotated(group, GrobMeta.title("outer"))
    val lowered = DeviceScene.fromScene(Scene(Vector(outer)), device).toOption.get

    lowered.elements match
      case Vector(
            DeviceElement.Annotated(
              outerMeta,
              Vector(
                DeviceElement.Group(
                  groupName,
                  None,
                  None,
                  Vector(
                    DeviceElement.Annotated(innerMeta, Vector(DeviceElement.Mark(_))),
                    DeviceElement.Mark(_)
                  )
                )
              )
            )
          ) =>
        assertEquals(outerMeta, GrobMeta.title("outer"))
        assertEquals(innerMeta, GrobMeta.title("inner"))
        assertEquals(groupName.map(_.value), Some("g"))
      case other => fail(s"unexpected device elements: $other")
  }

  test("the renderer conformance contract includes an annotated case every backend must draw") {
    val annotated = RendererConformance.annotatedCase.fold(e => fail(e.message), identity)
    assertEquals(annotated.group, ConformanceGroup.Primitive)
    assertEquals(annotated.markers.map(_.value), Vector("conformance-annotated"))
    assert(annotated.scene.grobs.forall(_.isInstanceOf[Grob.Annotated]))
    val names = RendererConformance.cases.fold(e => fail(e.message), identity).map(_.name.value)
    assert(names.contains("annotated"))
  }
