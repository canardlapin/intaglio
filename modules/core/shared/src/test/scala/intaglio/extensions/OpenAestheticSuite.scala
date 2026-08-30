package external.aesthetic

import intaglio.*

class OpenAestheticSuite extends munit.FunSuite:
  private final case class Row(x: Double, y: Double, confidence: Double)
  private final case class Wrapped(row: Row)

  private val confidence = Aesthetic.unsafe[Double]("confidence")

  test("an external package defines and maps a typed aesthetic by key identity") {
    val sameLabel = Aesthetic.unsafe[String]("confidence")
    val row = Row(1.0, 2.0, 0.75)
    val mapping = AesSpec
      .empty[Row]
      .updated(Aesthetic.Y, AesValue.total(_.y))
      .updated(confidence, AesValue.total(_.confidence))
      .updated(Aesthetic.X, AesValue.total(_.x))

    assertEquals(mapping.bound, Vector(Aesthetic.X, Aesthetic.Y, confidence))
    assertEquals(mapping.get(confidence).flatMap(_.map(row)), Some(0.75))
    assertEquals(mapping.get(sameLabel).flatMap(_.map(row)), None)
    assert(!(confidence.asInstanceOf[AnyRef] eq sameLabel.asInstanceOf[AnyRef]))

    val copied = mapping.copy(color = Some(AesValue.constant(Rgba.Black)))
    assertEquals(copied.get(confidence).flatMap(_.map(row)), Some(0.75))
    val wrapped = copied.contramap[Wrapped](_.row)
    assertEquals(wrapped.get(confidence).flatMap(_.map(Wrapped(row))), Some(0.75))
  }

  test("external typed keys participate in scaled heterogeneous inspection") {
    val spec = ContinuousScaleSpec
      .numeric("confidence")
      .fold(error => fail(error.message), identity)
    val mapping = AesSpec
      .empty[Row]
      .bindScale(ScaleBinding(confidence, _.confidence, spec))
      .fold(error => fail(error.message), identity)
    val registry = ScaleRegistry.fromMapping(mapping)
    val declaration = registry.declarations(7).head

    assert(declaration.key eq confidence)
    assertEquals(declaration.aesthetic, "confidence")
    assertEquals(declaration.kind, ScaleKind.Continuous)
    assertEquals(
      registry.forAesthetic(confidence).map(_.descriptor.domain),
      Some(ScaleDomain.Unspecified)
    )
  }

  test("generic encoded x and y satisfy the point geom prerequisite") {
    val rows = Vector(Row(1.0, 10.0, 0.25), Row(3.0, 20.0, 0.75))
    val x = ContinuousScaleSpec.numeric("external-x").fold(error => fail(error.message), identity)
    val y = ContinuousScaleSpec.numeric("external-y").fold(error => fail(error.message), identity)
    val trained = plot(rows)
      .encode(Aesthetic.X, _.x, x)
      .encode(Aesthetic.Y, _.y, y)
      .geomPoint()
      .resolve
      .fold(error => fail(error.message), identity)

    assertEquals(
      trained.layers.head.rows.map(row => row.x -> row.y),
      Vector(0.0 -> 0.0, 1.0 -> 1.0)
    )
    assertEquals(
      trained.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor.domain),
      Some(ScaleDomain.Continuous(Interval.unsafe(1.0, 3.0), Interval.unsafe(1.0, 3.0)))
    )
  }

  test("external aesthetic names are validated at construction") {
    assertEquals(Aesthetic[Double]("  ").left.toOption, Some(GraphicsError.BlankName("aesthetic")))
  }
