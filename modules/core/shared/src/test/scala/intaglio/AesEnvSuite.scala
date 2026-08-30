package intaglio

class AesEnvSuite extends munit.FunSuite:
  private final case class Row(x: Double, y: Double, condition: String)

  private def colorScale: DiscreteScale[Rgba] =
    DiscreteScale(
      "condition-color",
      DiscreteDomain.ordered(Vector("A", "B")).fold(e => fail(e.message), identity),
      DiscretePalette.valuesUnsafe(Vector(Rgba.unsafe(10, 20, 30), Rgba.unsafe(40, 50, 60)))
    ).fold(e => fail(e.message), identity)

  private def xScale: ContinuousScale[Double] =
    ContinuousScale
      .train("x-position", Vector(0.0, 1.0, 2.0), Palette.numeric)
      .fold(e => fail(e.message), identity)

  test("AesSpec is the canonical mapping and reports bound aesthetics in declaration order") {
    val spec = AesSpec
      .empty[Row]
      .withPosition(_.x, _.y)
      .withColor(Rgba.Black)
    assertEquals(spec.bound, Vector[Aesthetic[?]](Aesthetic.X, Aesthetic.Y, Aesthetic.Color))
    assert(spec.get(Aesthetic.X).nonEmpty)
    assert(spec.get(Aesthetic.Fill).isEmpty)
  }

  test("typed updates preserve the precise public record accessors without conversion") {
    val spec = AesSpec
      .empty[Row]
      .withPosition(_.x, _.y)
      .withLabel(_.condition)
      .updated(Aesthetic.Alpha, AesValue.constant(0.5))
    val row = Row(1.0, 2.0, "A")
    assertEquals(spec.alpha.flatMap(_.map(row)), Some(0.5))
    assertEquals(spec.label.flatMap(_.map(row)), Some("A"))
    assertEquals(spec.get(Aesthetic.Alpha), spec.alpha)
  }

  test("typed lookup returns values at the aesthetic's own type") {
    val mapping = AesSpec.empty[Row].withColor(Rgba.Black)
    val color: Option[AesValue[Row, Rgba]] = mapping.get(Aesthetic.Color)
    assertEquals(color.flatMap(_.map(Row(0.0, 0.0, "A"))), Some(Rgba.Black))
  }

  test("AesEnv remains a source-compatible alias without allocating a second representation") {
    val mapping = AesSpec.empty[Row].withColor(Rgba.Black)
    val env: AesEnv[Row] = mapping
    assert(env eq mapping)
    assert(AesEnv.empty[Row].bound.isEmpty)
  }

  test("bind registers a scaled binding once and rejects a duplicate") {
    val binding = ScaleBinding[Row, String, Rgba](Aesthetic.Color, _.condition, colorScale)
    val bound = AesEnv.empty[Row].bind(binding)
    assert(bound.isRight)
    val again = bound.flatMap(_.bind(binding))
    assertEquals(again, Left(GraphicsError.DuplicateScale("color")))
  }

  test("bind allows replacing an unscaled binding with a scaled one") {
    val env = AesSpec.empty[Row].withColor(Rgba.Black)
    val binding = ScaleBinding[Row, String, Rgba](Aesthetic.Color, _.condition, colorScale)
    val bound = env.bind(binding)
    assert(bound.exists(_.get(Aesthetic.Color).exists(_.isScaled)))
  }

  test("inherit prefers scaled local, then scaled parent, then local, then parent") {
    val row = Row(1.0, 2.0, "A")
    val scaledColor =
      ScaleBinding[Row, String, Rgba](Aesthetic.Color, _.condition, colorScale).toAesValue
    val localDirect = AesSpec.empty[Row].withColor(Rgba.White)
    val parentScaled = AesEnv.empty[Row].updated(Aesthetic.Color, scaledColor)

    val merged = localDirect.inherit(parentScaled)
    assert(merged.get(Aesthetic.Color).exists(_.isScaled), "scaled parent overrides direct local")

    val parentDirect = AesSpec.empty[Row].withColor(Rgba.Black)
    val localWins = localDirect.inherit(parentDirect)
    assertEquals(localWins.get(Aesthetic.Color).flatMap(_.map(row)), Some(Rgba.White))

    val parentOnly = AesEnv.empty[Row].inherit(parentDirect)
    assertEquals(parentOnly.get(Aesthetic.Color).flatMap(_.map(row)), Some(Rgba.Black))
  }

  test("AesSpec inheritance preserves typed field access") {
    val plotMapping = AesSpec.empty[Row].withPosition(_.x, _.y).withColor(Rgba.Black)
    val layerMapping = AesSpec.empty[Row].withColor(Rgba.White)
    val inherited = layerMapping.inherit(plotMapping)
    val row = Row(0.0, 0.0, "A")
    assertEquals(inherited.color.flatMap(_.map(row)), Some(Rgba.White))
    assert(inherited.x.nonEmpty && inherited.y.nonEmpty)
  }

  test("ScaleRegistry registers each scaled binding exactly once, in declaration order") {
    val mapping = AesEnv
      .empty[Row]
      .bind(ScaleBinding[Row, String, Rgba](Aesthetic.Color, _.condition, colorScale))
      .flatMap(_.bind(ScaleBinding[Row, Double, Double](Aesthetic.X, _.x, xScale)))
      .fold(e => fail(e.message), identity)
    val registry = ScaleRegistry.fromMapping(mapping)
    assertEquals(
      registry.entries.map(_.aesthetic),
      Vector[Aesthetic[?]](Aesthetic.X, Aesthetic.Color)
    )
    val declarations = registry.declarations(3)
    assertEquals(declarations.map(_.aesthetic), Vector("x", "color"))
    assertEquals(declarations.map(_.layerIndex), Vector(3, 3))
    assertEquals(declarations.map(_.kind), Vector(ScaleKind.Continuous, ScaleKind.Discrete))
    val trained = registry.trained
    assertEquals(trained.map(_.descriptor.kind), Vector(ScaleKind.Continuous, ScaleKind.Discrete))
  }

  test("ScaleRegistry inspects an untrained spec without pretending it is a trained scale") {
    val spec = ContinuousScaleSpec
      .numeric("inferred-x")
      .fold(error => fail(error.message), identity)
    val mapping = AesEnv
      .empty[Row]
      .bind(ScaleBinding[Row, Double, Double](Aesthetic.X, _.x, spec))
      .fold(error => fail(error.message), identity)
    val registry = ScaleRegistry.fromMapping(mapping)

    assertEquals(
      registry.declarations(4),
      Vector(ScaleDeclaration(4, "x", GraphicsName.unsafe("inferred-x"), ScaleKind.Continuous))
    )
    assertEquals(
      registry.forAesthetic(Aesthetic.X).map(_.descriptor.domain),
      Some(ScaleDomain.Unspecified)
    )
  }

  test("registry lookup by aesthetic finds the registered scale") {
    val mapping = AesEnv
      .empty[Row]
      .bind(ScaleBinding[Row, String, Rgba](Aesthetic.Color, _.condition, colorScale))
      .fold(e => fail(e.message), identity)
    val registry = ScaleRegistry.fromMapping(mapping)
    assert(registry.forAesthetic(Aesthetic.Color).nonEmpty)
    assert(registry.forAesthetic(Aesthetic.Fill).isEmpty)
  }
