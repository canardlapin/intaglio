package external.laws

import intaglio.*
import intaglio.laws.*

class SceneLayoutLawsSuite extends munit.FunSuite:
  private val tolerance = 1.0e-9

  private final case class Observation(x: Double, y: Double)

  private final case class PhysicalTargetSnapshot(
      titleXInches: Double,
      titleYInches: Double,
      titleSizeInches: Double,
      panelXInches: Double,
      panelYInches: Double,
      panelWidthInches: Double,
      panelHeightInches: Double
  )

  private def assertValid(suite: LawSuite): Unit =
    assertEquals(suite.failures, Vector.empty, clues(suite.name))

  private def samplePlot: Plot[Observation] =
    Plot(Vector(Observation(0.0, 1.0), Observation(1.0, 2.0), Observation(2.0, 1.5)))
      .addLayer(Layer.point[Observation](_.x, _.y))
      .orThrow
      .withTitle("Target-aware title")

  private def primitives(elements: Vector[DeviceElement]): Vector[DevicePrimitive] =
    elements.flatMap {
      case DeviceElement.Mark(primitive)        => Vector(primitive)
      case DeviceElement.Group(_, _, _, nested) => primitives(nested)
      case DeviceElement.Annotated(_, nested)   => primitives(nested)
    }

  private def panelClip(elements: Vector[DeviceElement]): Option[DeviceClip] =
    elements.iterator
      .map {
        case DeviceElement.Group(name, clip, _, nested) =>
          clip.filter(_ => name.contains(PlotRegion.Panel)).orElse(panelClip(nested))
        case DeviceElement.Annotated(_, nested) => panelClip(nested)
        case DeviceElement.Mark(_)              => None
      }
      .collectFirst { case Some(value) => value }

  private def physicalSnapshot(plan: RenderPlan): Either[GraphicsError, PhysicalTargetSnapshot] =
    plan.deviceScene.flatMap { scene =>
      val title = primitives(scene.elements).collectFirst {
        case DevicePrimitive.TextRun(_, x, y, _, _, _, size, _, _, name)
            if name.contains(PlotRegion.Title) =>
          (x, y, size)
      }
      for
        text <- title.toRight(GraphicsError.MissingLayout("target-law title"))
        panel <- panelClip(scene.elements).toRight(GraphicsError.MissingLayout("target-law panel"))
      yield
        val ppi = plan.context.pixelsPerInch
        PhysicalTargetSnapshot(
          text._1 / ppi,
          text._2 / ppi,
          text._3 / ppi,
          panel.x / ppi,
          panel.y / ppi,
          panel.width / ppi,
          panel.height / ppi
        )
    }

  private def equivalent(
      first: PhysicalTargetSnapshot,
      second: PhysicalTargetSnapshot
  ): Boolean =
    val left = productValues(first)
    val right = productValues(second)
    left.zip(right).forall { case (a, b) => math.abs(a - b) <= tolerance }

  private def productValues(value: PhysicalTargetSnapshot): Vector[Double] =
    Vector(
      value.titleXInches,
      value.titleYInches,
      value.titleSizeInches,
      value.panelXInches,
      value.panelYInches,
      value.panelWidthInches,
      value.panelHeightInches
    )

  test("scene algebra, traversal, and device lowering satisfy one portable suite") {
    val first = Scene(
      Vector(
        Grob
          .points(
            Vector(Point.npcUnsafe(0.2, 0.3)),
            name = Some(GraphicsName.unsafe("first-point"))
          )
          .orThrow
      )
    )
    val nested = Grob
      .lines(
        Vector(Point.npcUnsafe(0.1, 0.1), Point.npcUnsafe(0.9, 0.9)),
        name = Some(GraphicsName.unsafe("nested-line"))
      )
      .orThrow
    val second = Scene(
      Vector(Grob.group(Vector(nested), name = Some(GraphicsName.unsafe("second-group"))))
    )
    val third = Scene(
      Vector(
        Grob.rectUnsafe(
          Point.npcUnsafe(0.5, 0.5),
          Size.npcUnsafe(0.25, 0.2),
          name = Some(GraphicsName.unsafe("third-rect"))
        )
      )
    )

    assertValid(SceneDeviceLaws(first, second, third, DeviceContext.unsafe(320.0, 200.0)))
  }

  test("annotated grobs keep the scene-device laws and contribute no name of their own") {
    val meta = GrobMeta(
      title = Some("Unit 7 & \"friends\""),
      cssClass = Some(CssClass.unsafe("mark decode-filled")),
      data = Vector(DataKey.unsafe("kind") -> "anchor")
    )
    val plain = Grob
      .points(
        Vector(Point.npcUnsafe(0.3, 0.6)),
        name = Some(GraphicsName.unsafe("annotated-point"))
      )
      .orThrow
    val first = Scene(Vector(Grob.annotated(plain, meta)))
    val second = Scene(
      Vector(
        Grob.group(
          Vector(Grob.annotated(Grob.annotated(plain, GrobMeta.empty), meta)),
          name = Some(GraphicsName.unsafe("outer-group"))
        )
      )
    )
    val third = Scene(Vector(plain))

    assertValid(SceneDeviceLaws(first, second, third, DeviceContext.unsafe(320.0, 200.0)))

    def names(grob: Grob): Vector[Option[String]] =
      grob.name.map(_.value) +: grob.children.flatMap(names)
    assertEquals(first.grobs.flatMap(names), Vector(None, Some("annotated-point")))
    assertEquals(
      second.grobs.flatMap(names),
      Vector(Some("outer-group"), None, None, Some("annotated-point"))
    )
  }

  test("the native coordinate transpose is an involution for rows, grobs, and ranges") {
    val trained = PlotCompiler.resolve(samplePlot).orThrow
    val input = CoordInput(
      trained.layers,
      Some(Interval.unsafe(-1.0, 3.0) -> Interval.unsafe(0.0, 4.0)),
      trained.scaleRegistry
    )

    assertValid(CoordinateInvolutionLaws(input))
  }

  test("layout frames stay finite and the complete guide stack fits its allocation") {
    val request = PlotLayoutRequest(
      axes = Map(
        AxisSide.Bottom -> AxisRequest(Vector("0", "5", "10"), Some("time")),
        AxisSide.Left -> AxisRequest(Vector("-1", "0", "1"), Some("signal"))
      ),
      legend = Some(
        LegendRequest(
          Vector(
            GuideLayoutRequest.Legend(Some("condition"), Vector("control", "task")),
            GuideLayoutRequest.Colorbar(Some("activation"), Vector("0", "50", "100"))
          )
        )
      ),
      labels = PlotLabels(title = Some("Activation"), subtitle = Some("Subject mean")),
      grid = Some(PanelGridRequest(rows = 2, columns = 2, count = 4))
    )

    assertValid(PlotLayoutLaws(LayoutPolicy(), request))
  }

  test("layout laws report typed overflow instead of treating it as a valid fixture") {
    val request = PlotLayoutRequest(
      legend = Some(LegendRequest(None, Vector("x" * 200)))
    )
    val failures = PlotLayoutLaws(LayoutPolicy(), request).failures

    assert(failures.exists(_.law == "successful deterministic solve"))
    assert(failures.exists(_.detail.contains("plot layout leaves no room")))
  }

  test("equal physical targets preserve scene layout and normalized target observations") {
    val lowDensity = RenderContext.unsafe(640, 480, pixelsPerInch = 96.0)
    val highDensity = RenderContext.unsafe(1280, 960, pixelsPerInch = 192.0)
    val suite = TargetRecompilationLaws.withEquality(
      samplePlot,
      lowDensity,
      highDensity
    )(physicalSnapshot)(equivalent)

    assertValid(suite)
  }

  test("target recompilation applicability rejects different physical sizes") {
    val first = RenderContext.unsafe(640, 480, pixelsPerInch = 96.0)
    val second = RenderContext.unsafe(640, 480, pixelsPerInch = 192.0)
    val failures = TargetRecompilationLaws
      .withEquality(samplePlot, first, second)(physicalSnapshot)(equivalent)
      .failures

    assert(failures.exists(_.law == "equal physical target applicability"))
  }
