package intaglio

class RenderContextSuite extends munit.FunSuite:
  private val tolerance = 1e-9

  private final case class Observation(x: Double, y: Double)

  private def titledPlot: Plot[Observation] =
    Plot(Vector(Observation(0.0, 1.0), Observation(1.0, 2.0)))
      .addLayer(Layer.point[Observation](_.x, _.y))
      .fold(error => fail(error.message), identity)
      .withTitle("Target-aware title")

  private def primitives(elements: Vector[DeviceElement]): Vector[DevicePrimitive] =
    elements.flatMap {
      case DeviceElement.Mark(primitive)        => Vector(primitive)
      case DeviceElement.Group(_, _, _, nested) => primitives(nested)
      case DeviceElement.Annotated(_, nested)   => primitives(nested)
    }

  private def titleText(scene: DeviceScene): (Double, Double, Double, Option[String]) =
    primitives(scene.elements)
      .collectFirst {
        case DevicePrimitive.TextRun(
              _,
              x,
              y,
              _,
              _,
              _,
              fontSizePx,
              fontFamily,
              _,
              name
            ) if name.contains(PlotRegion.Title) =>
          (x, y, fontSizePx, fontFamily)
      }
      .getOrElse(fail("expected a resolved plot title"))

  private def namedClips(
      elements: Vector[DeviceElement]
  ): Vector[(Option[GraphicsName], DeviceClip)] =
    elements.flatMap {
      case DeviceElement.Group(name, clip, _, nested) =>
        clip.map(name -> _).toVector ++ namedClips(nested)
      case DeviceElement.Annotated(_, nested) =>
        namedClips(nested)
      case DeviceElement.Mark(_) =>
        Vector.empty
    }

  private def panelClip(elements: Vector[DeviceElement]): DeviceClip =
    namedClips(elements)
      .collectFirst { case (name, clip) if name.contains(PlotRegion.Panel) => clip }
      .getOrElse(fail("expected a clipped plot panel"))

  test("checked contexts bind device size, metrics, and resolved font families") {
    assert(RenderContext(width = 0, height = 480).isLeft)
    assert(RenderContext(width = 640, height = -1).isLeft)
    assert(RenderContext(width = 640, height = 480, pixelsPerInch = 0.0).isLeft)
    assert(RenderContext(width = 640, height = 480, lineHeightPt = 0.0).isLeft)
    assert(RenderContext(width = 640, height = 480, deviceScale = 0.0).isLeft)

    val metrics = new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        text.length * fontSizePt

      override def heightPt(fontSizePt: Double): Double =
        fontSizePt

    val registry = FontRegistry {
      case Some(family) => Some(s"resolved-$family")
      case None         => Some("resolved-default")
    }
    val context = RenderContext
      .unsafe(800, 600, pixelsPerInch = 144.0, textMetrics = metrics, fontRegistry = registry)
    val policy = context.layoutPolicy(
      LayoutPolicy(
        axisFontFamily = Some("axis"),
        axisTitleFontFamily = Some("axis-title"),
        plotTitleFontFamily = Some("plot-title"),
        plotSubtitleFontFamily = Some("plot-subtitle"),
        legendFontFamily = Some("legend"),
        legendTitleFontFamily = Some("legend-title")
      )
    )

    assert(policy.metrics eq metrics)
    assertEquals(policy.referenceDevice, context.deviceContext)
    assertEquals(policy.axisFontFamily, Some("resolved-axis"))
    assertEquals(policy.axisTitleFontFamily, Some("resolved-axis-title"))
    assertEquals(policy.plotTitleFontFamily, Some("resolved-plot-title"))
    assertEquals(policy.plotSubtitleFontFamily, Some("resolved-plot-subtitle"))
    assertEquals(policy.legendFontFamily, Some("resolved-legend"))
    assertEquals(policy.legendTitleFontFamily, Some("resolved-legend-title"))
  }

  test("HiDPI construction preserves logical size and records actual target density") {
    val context = RenderContext
      .hidpi(320, 240, devicePixelRatio = 2.0, logicalPixelsPerInch = 96.0)
      .fold(error => fail(error.message), identity)

    assertEquals(context.width, 640)
    assertEquals(context.height, 480)
    assertEquals(context.pixelsPerInch, 192.0)
    assertEquals(context.deviceScale, 2.0)
    assertEquals(context.logicalWidth, 320.0)
    assertEquals(context.logicalHeight, 240.0)
    assertEquals(context.logicalPixelsPerInch, 96.0)
    assert(RenderContext.hidpi(320, 240, devicePixelRatio = 0.0).isLeft)
  }

  test("font registry and target text metrics enter before layout and device lowering") {
    var measuredFamilies = Vector.empty[Option[String]]
    val metrics = new TextMetrics:
      override def widthPt(text: String, fontSizePt: Double): Double =
        text.length * fontSizePt

      override def heightPt(fontSizePt: Double): Double =
        fontSizePt * 2.0

      override def widthPt(text: String, style: TextStyle): Double =
        measuredFamilies = measuredFamilies :+ style.fontFamily
        widthPt(text, style.fontSizePt)

      override def heightPt(style: TextStyle): Double =
        measuredFamilies = measuredFamilies :+ style.fontFamily
        heightPt(style.fontSizePt)

    val requestedTitle = GraphicParams.unsafe(
      stroke = None,
      fill = Some(Rgba.Black),
      fontFamily = Some("Requested Sans"),
      fontSize = Length.pointsUnsafe(16.0)
    )
    val theme = Theme.default.copy(
      plotText = Theme.default.plotText.copy(title = requestedTitle)
    )
    val context = RenderContext.unsafe(
      640,
      480,
      textMetrics = metrics,
      fontRegistry = FontRegistry(_ => Some("Resolved Sans"))
    )
    val plan = PlotCompiler
      .compile(titledPlot, context, PlotCompilerOptions(theme = theme))
      .fold(error => fail(error.message), identity)
    val device = plan.deviceScene.fold(error => fail(error.message), identity)

    assert(measuredFamilies.contains(Some("Resolved Sans")))
    assert(!measuredFamilies.contains(Some("Requested Sans")))
    assertEquals(titleText(device)._4, Some("Resolved Sans"))
    assertEquals(device.width, 640.0)
    assertEquals(device.height, 480.0)
  }

  test("recompiling an equal physical target preserves typography and spacing") {
    val lowDensity = RenderContext.unsafe(640, 480, pixelsPerInch = 96.0)
    val highDensity = RenderContext.unsafe(1280, 960, pixelsPerInch = 192.0)
    val lowPlan = PlotCompiler
      .compile(titledPlot, lowDensity)
      .fold(error => fail(error.message), identity)
    val highPlan = PlotCompiler
      .compile(titledPlot, highDensity)
      .fold(error => fail(error.message), identity)
    val low = lowPlan.deviceScene.fold(error => fail(error.message), identity)
    val high = highPlan.deviceScene.fold(error => fail(error.message), identity)

    assertEquals(lowPlan.scene, highPlan.scene)
    val (lowX, lowY, lowFont, _) = titleText(low)
    val (highX, highY, highFont, _) = titleText(high)
    assertEqualsDouble(
      lowX / lowDensity.pixelsPerInch,
      highX / highDensity.pixelsPerInch,
      tolerance
    )
    assertEqualsDouble(
      lowY / lowDensity.pixelsPerInch,
      highY / highDensity.pixelsPerInch,
      tolerance
    )
    assertEqualsDouble(
      lowFont / lowDensity.pixelsPerInch,
      highFont / highDensity.pixelsPerInch,
      tolerance
    )

    val lowPanel = panelClip(low.elements)
    val highPanel = panelClip(high.elements)
    assertEqualsDouble(
      lowPanel.x / lowDensity.pixelsPerInch,
      highPanel.x / highDensity.pixelsPerInch,
      tolerance
    )
    assertEqualsDouble(
      lowPanel.y / lowDensity.pixelsPerInch,
      highPanel.y / highDensity.pixelsPerInch,
      tolerance
    )
    assertEqualsDouble(
      lowPanel.width / lowDensity.pixelsPerInch,
      highPanel.width / highDensity.pixelsPerInch,
      tolerance
    )
    assertEqualsDouble(
      lowPanel.height / lowDensity.pixelsPerInch,
      highPanel.height / highDensity.pixelsPerInch,
      tolerance
    )
  }
