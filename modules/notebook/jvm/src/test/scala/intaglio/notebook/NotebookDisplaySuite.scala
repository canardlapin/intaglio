package intaglio.notebook

import intaglio.*

class NotebookDisplaySuite extends munit.FunSuite:
  private val scene =
    Scene(
      Vector(
        Grob.circleUnsafe(
          Point.npcUnsafe(0.5, 0.5),
          ExtentExpr.pointsUnsafe(6.0),
          gp = GraphicParams.unsafe(fill = Some(Rgba.unsafe(20, 80, 160)))
        )
      )
    )

  test("display returns the standard SVG and text Jupyter MIME representations") {
    val options = NotebookOptions.unsafe(
      width = 320,
      height = 180,
      pixelsPerInch = 144.0,
      deviceScale = 2.0,
      title = Some("Notebook plot")
    )
    val bundle = NotebookRenderer.display(scene, options)
    val svg = bundle.svg.getOrElse(fail("missing SVG MIME representation"))

    assert(svg.startsWith("<svg"))
    assert(svg.contains("width=\"320\""))
    assert(svg.contains("height=\"180\""))
    assert(svg.contains("<title>Notebook plot</title>"))
    assertEquals(bundle.plainText, Some("Intaglio SVG plot (160.0 x 90.0 logical px)"))
    assertEquals(
      bundle.metadata(NotebookMimeBundle.SvgMime),
      Map(
        "width" -> "160.0",
        "height" -> "90.0",
        "pixelsPerInch" -> "144.0",
        "deviceScale" -> "2.0"
      )
    )
  }

  test("displayPlan preserves the bound target rather than rebuilding notebook defaults") {
    val context = RenderContext.unsafe(
      width = 111,
      height = 73,
      pixelsPerInch = 120.0,
      deviceScale = 1.0
    )
    val bundle = NotebookRenderer.displayPlan(RenderPlan(scene, context))

    assert(bundle.svg.exists(_.contains("width=\"111\" height=\"73\"")))
    assertEquals(bundle.metadata(NotebookMimeBundle.SvgMime)("pixelsPerInch"), "120.0")
  }

  test("displayPlot compiles layout against the configured notebook target") {
    final case class Datum(x: Double, y: Double)
    val plot = Plot(Vector(Datum(0.0, 0.0), Datum(1.0, 1.0)))
      .addLayer(Layer.point[Datum](_.x, _.y))
      .fold(error => fail(error.message), identity)
    val options = NotebookOptions.unsafe(width = 480, height = 240, pixelsPerInch = 120.0)
    val bundle = NotebookRenderer.displayPlot(plot, options)

    assert(bundle.svg.exists(_.contains("width=\"480\" height=\"240\"")))
    assertEquals(bundle.metadata(NotebookMimeBundle.SvgMime)("pixelsPerInch"), "120.0")
  }

  test("checked rendering and displayable error bundles remain distinct") {
    val invalid = Scene(
      Vector(Grob.textUnsafe("bad\u0001text", Point.npcUnsafe(0.5, 0.5)))
    )
    val options = NotebookOptions.unsafe(errorDisplay = NotebookErrorDisplay.AccessibleHtml)
    val checked = NotebookRenderer.render(invalid, options)
    val display = NotebookRenderer.display(invalid, options)

    assert(checked.isLeft)
    assert(display.svg.isEmpty)
    assert(display.plainText.exists(_.contains("render failed")))
    assert(display.html.exists(_.startsWith("<pre role=\"alert\">")))
    assert(display.html.exists(!_.contains("\u0001")))
  }

  test("notebook target validation is typed") {
    assert(NotebookOptions(width = 0).left.toOption.exists {
      case NotebookRenderError.Svg(_) => true
      case _                          => false
    })
    assert(NotebookOptions(pixelsPerInch = Double.NaN).isLeft)
    assert(NotebookOptions(deviceScale = 0.0).isLeft)
  }
