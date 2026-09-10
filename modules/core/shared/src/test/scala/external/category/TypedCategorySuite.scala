package external.category

import intaglio.*

enum Arm(val code: Int):
  case Control extends Arm(10)
  case Treatment extends Arm(20)

given CategoryIdentity[Arm] =
  CategoryIdentity.by(_.code, _ => "Study arm")

final case class Observation(arm: Arm, value: Double)

class TypedCategorySuite extends munit.FunSuite:
  private val colors = Vector(Rgba.unsafe(30, 90, 160), Rgba.unsafe(210, 100, 40))
  private val palette = DiscretePalette.valuesUnsafe(colors)
  private val values =
    Vector(
      Observation(Arm.Treatment, 2.0),
      Observation(Arm.Control, 1.0),
      Observation(Arm.Treatment, 3.0)
    )

  test("typed domains retain values, explicit stable identity, and explicit labels") {
    val declared: DiscreteDomain[Arm] =
      DiscreteDomain.ordered(Vector(Arm.Treatment)).orThrow
    val trained = declared.train(Vector(Arm.Control, Arm.Treatment)).orThrow
    val unordered =
      DiscreteDomain.unordered(Vector(Arm.Treatment, Arm.Control)).orThrow

    assertEquals(declared.levels, Vector(Arm.Treatment))
    assertEquals(trained.levels, Vector(Arm.Treatment, Arm.Control))
    assertEquals(trained.indexOf(Arm.Treatment), Some(0))
    assertEquals(trained.indexOf(Arm.Control), Some(1))
    assertEquals(trained.labels, Vector("Study arm", "Study arm"))
    assertEquals(unordered.levels, Vector(Arm.Control, Arm.Treatment))
  }

  test("typed discrete scales map enum values without String coercion") {
    val domain: DiscreteDomain[Arm] =
      DiscreteDomain.ordered(Vector(Arm.Control, Arm.Treatment)).orThrow
    val scale: DiscreteScale[Arm, Rgba] =
      DiscreteScale("arm-color", domain, palette).orThrow
    val trained = Plot(values)
      .withScale(ScaleBinding(Aesthetic.Color, _.arm, scale))
      .flatMap(_.addLayer(Layer.point[Observation](_ => 1.0, _.value)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)
    val rows = trained.layers.head.rows

    assertEquals(scale.mapLevels(Vector(Arm.Control, Arm.Treatment)), colors.map(Some(_)))
    assertEquals(rows.map(_.gp.stroke), Vector(Some(colors(1)), Some(colors(0)), Some(colors(1))))
    assertNotEquals(rows.head.groupKey, rows(1).groupKey)
    assertEquals(
      scale.descriptor.domain,
      ScaleDomain.Discrete(Vector("Study arm", "Study arm"), ordered = true)
    )
  }

  test("typed band scales preserve category values and label their guides explicitly") {
    val domain: DiscreteDomain[Arm] =
      DiscreteDomain.ordered(Vector(Arm.Treatment, Arm.Control)).orThrow
    val scale: BandScale[Arm] =
      BandScale("study-arm", domain, BandPadding.unsafe(0.2)).orThrow
    val layer =
      Layer
        .fromMapping[Observation](
          Geom.Point,
          AesSpec.empty[Observation].updated(Aesthetic.Y, AesValue.total(_.value))
        )
        .orThrow
    val trained = Plot(values)
      .withScale(ScaleBinding(Aesthetic.X, _.arm, scale))
      .flatMap(_.addLayer(layer))
      .flatMap(
        PlotCompiler.resolve(
          _,
          PlotCompilerOptions(policy = Some(LayoutPolicy()), guides = GuidePolicy.Derived())
        )
      )
      .fold(error => fail(error.message), identity)
    val bottom = trained.guides
      .collectFirst {
        case ResolvedGuide(axis: GuideSpec.Axis, _) if axis.side == AxisSide.Bottom => axis
      }
      .getOrElse(fail("missing typed-category axis"))

    assertEquals(scale.band(Arm.Treatment), Some(Band.unsafe(0.0, 0.8)))
    assertEquals(scale.band(Arm.Control), Some(Band.unsafe(1.0, 0.8)))
    assertEquals(trained.layers.head.rows.map(_.x), Vector(0.0, 1.0, 0.0))
    assertEquals(bottom.ticks.map(_.map(_.label)), Some(Vector("Study arm", "Study arm")))
  }

  test("typed scale specs train categories while retaining their input type") {
    val spec: DiscreteScaleSpec[Arm, Rgba] =
      DiscreteScaleSpec("arm-spec", Vector(Arm.Control), palette).orThrow
    val trained = Plot(values)
      .withScale(ScaleBinding(Aesthetic.Fill, _.arm, spec))
      .flatMap(_.addLayer(Layer.point[Observation](_ => 1.0, _.value)))
      .flatMap(PlotCompiler.resolve(_))
      .fold(error => fail(error.message), identity)

    assertEquals(spec.declaredLevels, Vector(Arm.Control))
    assertEquals(
      trained.scaleRegistry.forAesthetic(Aesthetic.Fill).map(_.descriptor.domain),
      Some(ScaleDomain.Discrete(Vector("Study arm", "Study arm"), ordered = true))
    )
    assertEquals(
      trained.layers.head.rows.map(_.gp.fill),
      Vector(Some(colors(1)), Some(colors(0)), Some(colors(1)))
    )
  }
