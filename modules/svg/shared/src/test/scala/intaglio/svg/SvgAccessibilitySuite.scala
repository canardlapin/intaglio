package intaglio.svg

import intaglio.*

class SvgAccessibilitySuite extends munit.FunSuite:
  private final case class Observation(x: Double, y: Double)

  test("compiled plot semantics become SVG role, ARIA references, title, and description") {
    val data = Vector(Observation(0.0, 1.0), Observation(1.0, 2.0))
    val plot = Plot(data)
      .withSemanticId(SemanticId.unsafe("activation-plot"))
      .withTitle("Activation & condition")
      .withDescription("A generic activation plot.")
      .withAltText("Treatment < control at baseline, then treatment rises.")
      .addLayer(Layer.point[Observation](_.x, _.y))
      .fold(error => fail(error.message), identity)
    val trained = PlotCompiler
      .resolve(plot, PlotCompilerOptions.lean)
      .fold(error => fail(error.message), identity)
    val svg = SvgRenderer
      .render(trained.scene, SvgOptions.unsafe(width = 320, height = 240))
      .fold(error => fail(error.message), _.value)

    assert(
      svg.contains(
        """id="activation-plot" role="img" aria-labelledby="activation-plot-title" aria-describedby="activation-plot-description"""
      )
    )
    assert(
      svg.contains(
        """<title id="activation-plot-title">Activation &amp; condition</title>"""
      )
    )
    assert(
      svg.contains(
        """<desc id="activation-plot-description">Treatment &lt; control at baseline, then treatment rises.</desc>"""
      )
    )
    assert(!svg.contains("A generic activation plot."))
  }

  test("raw scenes can opt into accessible SVG metadata through options") {
    val options = SvgOptions.unsafe(
      width = 80,
      height = 60,
      title = Some("Raw scene"),
      description = Some("One empty renderer-neutral scene.")
    )
    val svg = SvgRenderer.render(Scene.empty, options).fold(error => fail(error.message), _.value)

    assert(svg.contains("""id="intaglio-svg" role="img"""))
    assert(svg.contains("""aria-labelledby="intaglio-svg-title"""))
    assert(svg.contains("""aria-describedby="intaglio-svg-description"""))
    assert(svg.contains("""<title id="intaglio-svg-title">Raw scene</title>"""))
    assert(
      svg.contains(
        """<desc id="intaglio-svg-description">One empty renderer-neutral scene.</desc>"""
      )
    )
  }

  test("invalid accessible descriptions fail the checked XML boundary") {
    val result = SvgRenderer.render(
      Scene.empty,
      SvgOptions.unsafe(description = Some("bad\u0000description"))
    )

    assertEquals(
      result.left.toOption,
      Some(SvgRenderError.InvalidXmlCharacter("document description", 0))
    )
  }
