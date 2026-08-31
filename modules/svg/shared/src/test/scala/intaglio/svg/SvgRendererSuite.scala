package intaglio.svg

import intaglio.*

class SvgRendererSuite extends munit.FunSuite:

  private def occurrences(value: String, needle: String): Int =
    var count = 0
    var from = 0
    var next = value.indexOf(needle, from)
    while next >= 0 do
      count += 1
      from = next + needle.length
      next = value.indexOf(needle, from)
    count

  private def render(scene: Scene, options: SvgOptions = SvgOptions.default): String =
    SvgRenderer.render(scene, options).toOption.get.value

  test("point batches serialize identically to heterogeneous per-mark grobs") {
    val points = Vector(
      Point.npcUnsafe(0.2, 0.25),
      Point.npcUnsafe(0.4, 0.5),
      Point.npcUnsafe(0.6, 0.75),
      Point.npcUnsafe(0.8, 0.5)
    )
    val sizes = Vector(3.0, 4.0, 5.0, 6.0).map(ExtentExpr.pointsUnsafe)
    val shapes = PointShape.values.toVector
    val params = Vector(
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(120, 20, 30)),
        fill = Some(Rgba.unsafe(240, 180, 80)),
        lineWidth = 1.25
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(20, 110, 50)),
        fill = Some(Rgba.unsafe(100, 220, 160)),
        lineWidth = 1.5
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(40, 70, 160)),
        fill = Some(Rgba.unsafe(130, 160, 240)),
        lineWidth = 1.75
      ),
      GraphicParams.unsafe(
        stroke = Some(Rgba.unsafe(90, 40, 130)),
        fill = None,
        lineWidth = 2.0,
        lineType = LineType.Dashed
      )
    )
    val batch = Grob.pointBatchUnsafe(
      points,
      BatchColumn.Values(sizes),
      BatchColumn.Values(shapes),
      BatchColumn.Values(params)
    )
    val legacy = points.indices
      .map(index =>
        Grob
          .points(
            Vector(points(index)),
            sizes(index),
            shapes(index),
            params(index)
          )
          .fold(error => fail(error.message), identity)
      )
      .toVector
    val options = SvgOptions.unsafe(width = 160, height = 100)

    assertEquals(render(Scene(Vector(batch)), options), render(Scene(legacy), options))
  }

  test("renders numeric-only SVG for basic grobs with y-up npc coordinates") {
    val point =
      Grob
        .points(
          Vector(Point.npcUnsafe(0.5, 0.25)),
          gp = GraphicParams.unsafe(fill = Some(Rgba.unsafe(40, 80, 120, 0.5)))
        )
        .toOption
        .get
    val segment =
      Grob
        .segments(
          Vector((Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0))),
          gp = GraphicParams.unsafe(
            stroke = Some(Rgba.unsafe(200, 10, 5)),
            lineWidth = 2.0,
            lineType = LineType.Dashed
          )
        )
        .toOption
        .get
    val label =
      Grob
        .text(
          "A&B <test>",
          Point.npcUnsafe(0.5, 0.75),
          anchor = Anchor(HJust.Left, VJust.Top),
          gp = GraphicParams.unsafe(fontFamily = Some("Inter"))
        )
        .toOption
        .get

    val svg = render(
      Scene(Vector(point, segment, label)),
      SvgOptions.unsafe(width = 120, height = 80, title = Some("Smoke & SVG"))
    )

    assert(
      svg.contains(
        """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="80" viewBox="0 0 120 80">"""
      )
    )
    assert(svg.contains("<title>Smoke &amp; SVG</title>"))
    assert(
      svg.contains(
        """<circle stroke="#000000" fill="#285078" fill-opacity="0.5" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" cx="60" cy="60" r="5.3333" />"""
      )
    )
    assert(
      svg.contains(
        """<polyline stroke="#c80a05" fill="none" stroke-width="2" stroke-linecap="butt" stroke-linejoin="miter" stroke-dasharray="6 4" points="0,80 120,0" />"""
      )
    )
    assert(
      svg.contains(
        """<text fill="#000000" stroke="none" font-family="Inter" font-size="16" x="60" y="20" text-anchor="start" dominant-baseline="text-before-edge">A&amp;B &lt;test&gt;</text>"""
      )
    )
    assert(svg.endsWith("</svg>\n"))
  }

  test("npc y = 0 renders at the bottom of the document") {
    val bottom = Grob.points(Vector(Point.npcUnsafe(0.5, 0.0))).toOption.get
    val top = Grob.points(Vector(Point.npcUnsafe(0.5, 1.0))).toOption.get
    val svg = render(Scene(Vector(bottom, top)), SvgOptions.unsafe(width = 100, height = 100))

    assert(svg.contains("""cy="100""""), "npc y=0 must be at device bottom")
    assert(svg.contains("""cy="0""""), "npc y=1 must be at device top")
  }

  test("renders shared scene conformance cases deterministically without unresolved lengths") {
    val cases = RendererConformance.cases.toOption.get
    val options = SvgOptions.unsafe(width = 240, height = 160)
    val first = cases.map(sceneCase => render(sceneCase.scene, options))
    val second = cases.map(sceneCase => render(sceneCase.scene, options))

    assertEquals(first, second)
    assert(
      first.forall(
        _.startsWith(
          "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"240\" height=\"160\" viewBox=\"0 0 240 160\""
        )
      )
    )
    assert(first.forall(_.endsWith("</svg>\n")))
    assert(first.forall(!_.contains("calc(")), "SVG output must not contain CSS calc expressions")
    assert(first.forall(!_.contains("%")), "SVG output must not contain percentage lengths")
    assert(first.forall(!_.contains("pt\"")), "SVG output must not contain unit-suffixed lengths")
  }

  test("renders scaled plot conformance scene through clipped panel and guide groups") {
    val scene = RendererConformance.scaledPlotCase.toOption.get.scene
    val svg = render(scene, SvgOptions.unsafe(width = 300, height = 180))

    assert(svg.contains("""<g data-name="plot-panel" clip-path="url(#clip-0)">"""))
    assert(svg.contains("""<g data-name="scaled-x-axis">"""))
    assert(svg.contains("""<g data-name="condition-legend">"""))
    assert(svg.contains("""<clipPath id="clip-0">"""))
    assertEquals(occurrences(svg, """<circle stroke="#285078" fill="none" stroke-width="1""""), 2)
    assertEquals(occurrences(svg, """<circle stroke="#d27828" fill="none" stroke-width="1""""), 1)
    assert(svg.contains("""<text data-name="condition-legend-title""""))
    assert(!svg.contains("calc("))
    assert(!svg.contains("%"))
  }

  test("renders a bottom axis at the visual bottom of its panel") {
    val range = Interval.unsafe(0.0, 10.0)
    val ticks = Axis.ticks(range, Breaks.countUnsafe(3)).toOption.get
    val viewport =
      Viewport.unsafe(
        origin = Point.npcUnsafe(0.1, 0.1),
        size = Size.npcUnsafe(0.8, 0.15),
        xScale = range,
        yScale = Interval.unsafe(-1.0, 1.0),
        clip = Clip.Off
      )
    val axis =
      Axis
        .bottom(
          range,
          ticks,
          tickLength = ExtentExpr.nativeUnsafe(0.4),
          labelOffset = ExtentExpr.nativeUnsafe(0.8),
          axisGp = GraphicParams.unsafe(lineWidth = 0.5),
          tickGp = GraphicParams.unsafe(lineWidth = 0.5),
          labelGp = GraphicParams.unsafe(fontSize = Length.pointsUnsafe(8.0)),
          name = Some(GraphicsName.unsafe("x-axis"))
        )
        .flatMap(_.toGrob(Some(viewport)))
        .toOption
        .get

    val expected =
      """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
        |  <g data-name="x-axis">
        |    <polyline data-name="x-axis-baseline" stroke="#000000" fill="none" stroke-width="0.5" stroke-linecap="butt" stroke-linejoin="miter" points="20,99 180,99" />
        |    <polyline data-name="x-axis-ticks" stroke="#000000" fill="none" stroke-width="0.5" stroke-linecap="butt" stroke-linejoin="miter" points="20,99 20,102.6" />
        |    <polyline data-name="x-axis-ticks" stroke="#000000" fill="none" stroke-width="0.5" stroke-linecap="butt" stroke-linejoin="miter" points="100,99 100,102.6" />
        |    <polyline data-name="x-axis-ticks" stroke="#000000" fill="none" stroke-width="0.5" stroke-linecap="butt" stroke-linejoin="miter" points="180,99 180,102.6" />
        |    <text data-name="x-axis-label" fill="#000000" stroke="none" font-size="10.6667" x="20" y="106.2" text-anchor="middle" dominant-baseline="text-before-edge">0</text>
        |    <text data-name="x-axis-label" fill="#000000" stroke="none" font-size="10.6667" x="100" y="106.2" text-anchor="middle" dominant-baseline="text-before-edge">5</text>
        |    <text data-name="x-axis-label" fill="#000000" stroke="none" font-size="10.6667" x="180" y="106.2" text-anchor="middle" dominant-baseline="text-before-edge">10</text>
        |  </g>
        |</svg>
        |""".stripMargin

    assertEquals(
      render(Scene(Vector(axis)), SvgOptions.unsafe(width = 200, height = 120)),
      expected
    )
  }

  test("renders multi-point line grobs as a single polyline") {
    val viewport =
      Viewport.unsafe(
        xScale = Interval.unsafe(0.0, 4.0),
        yScale = Interval.unsafe(0.0, 2.0),
        clip = Clip.Off
      )
    val polyline =
      Grob
        .lines(
          Vector(
            Point.nativeUnsafe(0.0, 0.0),
            Point.nativeUnsafe(1.0, 2.0),
            Point.nativeUnsafe(3.0, 1.0)
          ),
          gp = GraphicParams
            .unsafe(stroke = Some(Rgba.unsafe(20, 30, 40)), lineType = LineType.Dotted),
          viewport = Some(viewport),
          name = Some(GraphicsName.unsafe("trajectory"))
        )
        .toOption
        .get

    val svg = render(Scene(Vector(polyline)), SvgOptions.unsafe(width = 100, height = 100))

    assert(
      svg.contains(
        """<polyline data-name="trajectory" stroke="#141e28" fill="none" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" stroke-dasharray="1 3" points="0,100 25,0 75,50" />"""
      )
    )
  }

  test("renders point shapes, rectangles, and groups numerically") {
    val square =
      Grob
        .points(
          Vector(Point.npcUnsafe(0.5, 0.5)),
          size = ExtentExpr.pointsUnsafe(6.0),
          shape = PointShape.Square,
          gp = GraphicParams.unsafe(fill = Some(Rgba.White)),
          name = Some(GraphicsName.unsafe("shape-square"))
        )
        .toOption
        .get
    val triangle =
      Grob
        .points(
          Vector(Point.npcUnsafe(0.25, 0.25)),
          size = ExtentExpr.pointsUnsafe(6.0),
          shape = PointShape.Triangle,
          gp = GraphicParams.unsafe(fill = Some(Rgba.White)),
          name = Some(GraphicsName.unsafe("shape-triangle"))
        )
        .toOption
        .get
    val cross =
      Grob
        .points(
          Vector(Point.npcUnsafe(0.75, 0.75)),
          size = ExtentExpr.pointsUnsafe(6.0),
          shape = PointShape.Cross,
          gp = GraphicParams.unsafe(fill = Some(Rgba.White)),
          name = Some(GraphicsName.unsafe("shape-cross"))
        )
        .toOption
        .get
    val rect =
      Grob
        .rect(
          Point.npcUnsafe(0.5, 0.5),
          Size.npcUnsafe(0.2, 0.4),
          anchor = Anchor.Center,
          gp = GraphicParams.unsafe(fill = Some(Rgba.unsafe(10, 20, 30)), alpha = 0.75),
          name = Some(GraphicsName.unsafe("centered-rect"))
        )
        .toOption
        .get
    val group =
      Grob.group(
        Vector(square, triangle, cross, rect),
        name = Some(GraphicsName.unsafe("shape-group"))
      )

    val svg = render(Scene(Vector(group)), SvgOptions.unsafe(width = 100, height = 100))

    assert(svg.contains("""<g data-name="shape-group">"""))
    assert(
      svg.contains(
        """<rect data-name="shape-square" stroke="#000000" fill="#ffffff" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" x="42" y="42" width="16" height="16" />"""
      )
    )
    assert(
      svg.contains(
        """<polygon data-name="shape-triangle" stroke="#000000" fill="#ffffff" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" points="25,67 33,83 17,83" />"""
      )
    )
    assert(
      svg.contains(
        """<polyline data-name="shape-cross" stroke="#000000" fill="none" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" points="67,25 83,25" />"""
      )
    )
    assert(
      svg.contains(
        """<polyline data-name="shape-cross" stroke="#000000" fill="none" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" points="75,17 75,33" />"""
      )
    )
    assert(
      svg.contains(
        """<rect data-name="centered-rect" stroke="#000000" fill="#0a141e" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" opacity="0.75" x="40" y="30" width="20" height="40" />"""
      )
    )
  }

  test("serializes every validated pattern recipe in absolute user-space units") {
    val angled =
      PatternRecipe.angledHatch(30.0, 8.0, 1.5).fold(error => fail(error.message), identity)
    val crossed =
      PatternRecipe.crossHatch(45.0, 10.0, 2.0).fold(error => fail(error.message), identity)
    val rules = PatternRecipe
      .parallelRules(RuleOrientation.Horizontal, 6.0, 0.75)
      .fold(error => fail(error.message), identity)
    val stipple = PatternRecipe.stipple(12.0, 2.5).fold(error => fail(error.message), identity)
    val paints = Vector(angled, crossed, rules, stipple).map { recipe =>
      GraphicParams
        .unsafe(stroke = None)
        .withPatternFill(PatternPaint(recipe, Rgba.unsafe(20, 40, 60)))
    }
    val grobs = Vector(
      Grob.rectUnsafe(Point.npcUnsafe(0.2, 0.2), Size.npcUnsafe(0.2, 0.2), gp = paints(0)),
      Grob.polygonUnsafe(
        Vector(Point.npcUnsafe(0.4, 0.1), Point.npcUnsafe(0.6, 0.1), Point.npcUnsafe(0.5, 0.3)),
        gp = paints(1)
      ),
      Grob.circleUnsafe(Point.npcUnsafe(0.7, 0.2), ExtentExpr.npcUnsafe(0.1), gp = paints(2)),
      Grob.compoundPolygonUnsafe(
        Vector(
          Vector(Point.npcUnsafe(0.1, 0.6), Point.npcUnsafe(0.3, 0.6), Point.npcUnsafe(0.2, 0.8))
        ),
        gp = paints(3)
      )
    )

    val svg = render(Scene(grobs), SvgOptions.unsafe(width = 100, height = 100))

    assert(
      svg.contains(
        """<pattern id="pattern-0" x="0" y="0" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(30)">"""
      )
    )
    assert(
      svg.contains("""<line x1="0" y1="0" x2="0" y2="8" stroke="#14283c" stroke-width="1.5" />""")
    )
    assert(
      svg.contains(
        """<pattern id="pattern-1" x="0" y="0" width="10" height="10" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">"""
      )
    )
    assert(
      svg.contains("""<line x1="0" y1="0" x2="10" y2="0" stroke="#14283c" stroke-width="2" />""")
    )
    assert(
      svg.contains(
        """<pattern id="pattern-2" x="0" y="0" width="6" height="6" patternUnits="userSpaceOnUse">"""
      )
    )
    assert(
      svg.contains("""<line x1="0" y1="0" x2="6" y2="0" stroke="#14283c" stroke-width="0.75" />""")
    )
    assert(
      svg.contains(
        """<pattern id="pattern-3" x="0" y="0" width="12" height="12" patternUnits="userSpaceOnUse">"""
      )
    )
    assert(svg.contains("""<circle cx="6" cy="6" r="2.5" fill="#14283c" />"""))
    assert(!svg.contains("%"))
  }

  test("deduplicates complete pattern paints in stable first-use order") {
    val firstRecipe =
      PatternRecipe.angledHatch(20.0, 7.0, 1.0).fold(error => fail(error.message), identity)
    val equalRecipe =
      PatternRecipe.angledHatch(20.0, 7.0, 1.0).fold(error => fail(error.message), identity)
    val secondRecipe = PatternRecipe.stipple(9.0, 2.0).fold(error => fail(error.message), identity)
    val first = PatternPaint(firstRecipe, Rgba.unsafe(10, 20, 30), Some(Rgba.unsafe(230, 240, 250)))
    val equalFirst =
      PatternPaint(equalRecipe, Rgba.unsafe(10, 20, 30), Some(Rgba.unsafe(230, 240, 250)))
    val second = PatternPaint(secondRecipe, Rgba.unsafe(80, 90, 100))
    val grobs = Vector(
      Grob.rectUnsafe(
        Point.npcUnsafe(0.2, 0.5),
        Size.npcUnsafe(0.2, 0.4),
        gp = GraphicParams.unsafe(stroke = None).withPatternFill(first),
        name = Some(GraphicsName.unsafe("first-pattern"))
      ),
      Grob.rectUnsafe(
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(0.2, 0.4),
        gp = GraphicParams.unsafe(stroke = None).withPatternFill(second),
        name = Some(GraphicsName.unsafe("second-pattern"))
      ),
      Grob.rectUnsafe(
        Point.npcUnsafe(0.8, 0.5),
        Size.npcUnsafe(0.2, 0.4),
        gp = GraphicParams.unsafe(stroke = None).withPatternFill(equalFirst),
        name = Some(GraphicsName.unsafe("equal-pattern"))
      )
    )

    val firstRender = render(Scene(grobs), SvgOptions.unsafe(width = 120, height = 80))
    val secondRender = render(Scene(grobs), SvgOptions.unsafe(width = 120, height = 80))

    assertEquals(firstRender, secondRender)
    assertEquals(occurrences(firstRender, "<pattern id="), 2)
    assertEquals(occurrences(firstRender, """fill="url(#pattern-0)""""), 2)
    assertEquals(occurrences(firstRender, """fill="url(#pattern-1)""""), 1)
    assert(
      firstRender.indexOf("""<pattern id="pattern-0"""") < firstRender.indexOf(
        """<pattern id="pattern-1""""
      )
    )
    assertEquals(occurrences(firstRender, "data-name="), 3)
    assertEquals(occurrences(firstRender, "<line "), 1)
  }

  test("pattern serialization matches one shared JVM and Scala.js byte oracle") {
    val recipe = PatternRecipe.stipple(8.0, 2.0).fold(error => fail(error.message), identity)
    val mark = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.5),
      gp = GraphicParams
        .unsafe(stroke = None)
        .withPatternFill(
          PatternPaint(recipe, Rgba.unsafe(10, 20, 30, 0.5), Some(Rgba.unsafe(240, 241, 242, 0.25)))
        ),
      name = Some(GraphicsName.unsafe("pattern-oracle"))
    )
    val expected =
      """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="80" viewBox="0 0 100 80">
        |  <rect data-name="pattern-oracle" stroke="none" fill="url(#pattern-0)" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" x="25" y="20" width="50" height="40" />
        |  <defs>
        |    <pattern id="pattern-0" x="0" y="0" width="8" height="8" patternUnits="userSpaceOnUse">
        |      <rect x="0" y="0" width="8" height="8" fill="#f0f1f2" fill-opacity="0.25" />
        |      <circle cx="4" cy="4" r="2" fill="#0a141e" fill-opacity="0.5" />
        |    </pattern>
        |  </defs>
        |</svg>
        |""".stripMargin

    assertEquals(render(Scene(Vector(mark)), SvgOptions.unsafe(width = 100, height = 80)), expected)
  }

  test("keeps pattern ink, background, and element alpha composition explicit") {
    val recipe =
      PatternRecipe.crossHatch(-35.0, 8.0, 1.25).fold(error => fail(error.message), identity)
    val paint = PatternPaint(
      recipe,
      Rgba.unsafe(12, 34, 56, 0.4),
      Some(Rgba.unsafe(210, 220, 230, 0.25))
    )
    val patterned = Grob.polygonUnsafe(
      Vector(Point.npcUnsafe(0.1, 0.1), Point.npcUnsafe(0.9, 0.1), Point.npcUnsafe(0.5, 0.9)),
      gp = GraphicParams
        .unsafe(stroke = Some(Rgba.unsafe(90, 80, 70, 0.6)), alpha = 0.5)
        .withPatternFill(paint),
      name = Some(GraphicsName.unsafe("pattern-polygon"))
    )
    val solid = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.1, 0.1),
      gp = GraphicParams.unsafe(stroke = None, fill = Some(Rgba.unsafe(1, 2, 3, 0.7))),
      name = Some(GraphicsName.unsafe("solid-rect"))
    )

    val svg = render(Scene(Vector(patterned, solid)), SvgOptions.unsafe(width = 100, height = 100))

    assert(
      svg.contains(
        """data-name="pattern-polygon" stroke="#5a5046" stroke-opacity="0.6" fill="url(#pattern-0)"""
      )
    )
    assert(svg.contains("""opacity="0.5"""))
    assert(
      svg.contains(
        """<rect x="0" y="0" width="8" height="8" fill="#d2dce6" fill-opacity="0.25" />"""
      )
    )
    assertEquals(occurrences(svg, """stroke="#0c2238" stroke-opacity="0.4""""), 2)
    assert(
      svg.contains("""data-name="solid-rect" stroke="none" fill="#010203" fill-opacity="0.7""")
    )
    assert(!svg.contains("data-name=\"pattern-0\""))
  }

  test("does not reinterpret pattern fills on open lines or text") {
    val recipe =
      PatternRecipe.angledHatch(45.0, 8.0, 1.0).fold(error => fail(error.message), identity)
    val params = GraphicParams
      .unsafe(stroke = Some(Rgba.unsafe(120, 30, 40)))
      .withPatternFill(PatternPaint(recipe, Rgba.Black))
    val lineGrob = Grob
      .lines(
        Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)),
        gp = params,
        name = Some(GraphicsName.unsafe("open-line"))
      )
      .fold(error => fail(error.message), identity)
    val textGrob = Grob
      .text(
        "plain text",
        Point.npcUnsafe(0.5, 0.5),
        gp = params,
        name = Some(GraphicsName.unsafe("plain-text"))
      )
      .fold(error => fail(error.message), identity)

    val svg = render(Scene(Vector(lineGrob, textGrob)), SvgOptions.unsafe(width = 100, height = 80))

    assert(svg.contains("""data-name="open-line" stroke="#781e28" fill="none"""))
    assert(svg.contains("""data-name="plain-text" fill="#781e28" stroke="none"""))
    assert(!svg.contains("<pattern"))
    assert(!svg.contains("url(#pattern-"))
  }

  test("anchors rectangles from their scene-space corners") {
    val bottomLeft =
      Grob
        .rect(
          Point.npcUnsafe(0.5, 0.5),
          Size.npcUnsafe(0.2, 0.4),
          anchor = Anchor.BottomLeft,
          name = Some(GraphicsName.unsafe("anchored-rect"))
        )
        .toOption
        .get
    val svg = render(Scene(Vector(bottomLeft)), SvgOptions.unsafe(width = 100, height = 100))

    assert(svg.contains("""x="50" y="10" width="20" height="40""""))
  }

  test("renders clipped viewports via clipPath definitions") {
    val viewport =
      Viewport.unsafe(
        origin = Point.npcUnsafe(0.1, 0.2),
        size = Size.npcUnsafe(0.5, 0.4),
        xScale = Interval.unsafe(-1.0, 1.0),
        yScale = Interval.unsafe(0.0, 10.0),
        clip = Clip.On
      )
    val grob =
      Grob
        .lines(
          Vector(Point.nativeUnsafe(-1.0, 0.0), Point.nativeUnsafe(1.0, 10.0)),
          viewport = Some(viewport),
          name = Some(GraphicsName.unsafe("native-line"))
        )
        .toOption
        .get

    val svg = render(Scene(Vector(grob)), SvgOptions.unsafe(width = 200, height = 100))

    assert(svg.contains("""<g data-name="native-line" clip-path="url(#clip-0)">"""))
    assert(
      svg.contains(
        """<polyline data-name="native-line" stroke="#000000" fill="none" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" points="20,80 120,40" />"""
      )
    )
    assert(svg.contains("""<clipPath id="clip-0">"""))
    assert(svg.contains("""<rect x="20" y="40" width="100" height="40" />"""))
  }

  test("rotated viewports pivot on the resolved origin corner") {
    val viewport =
      Viewport.unsafe(
        origin = Point.npcUnsafe(0.1, 0.2),
        size = Size.npcUnsafe(0.5, 0.4),
        clip = Clip.Off,
        angleDegrees = 15.0
      )
    val grob =
      Grob
        .lines(
          Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)),
          viewport = Some(viewport),
          name = Some(GraphicsName.unsafe("rotated"))
        )
        .toOption
        .get

    val svg = render(Scene(Vector(grob)), SvgOptions.unsafe(width = 200, height = 100))

    assert(svg.contains("""<g data-name="rotated" transform="rotate(-15 20 80)">"""))
  }

  test("renders rotated text about its own anchor point") {
    val text =
      Grob
        .text(
          "A&B <label>",
          Point.npcUnsafe(0.5, 0.5),
          rotationDegrees = 45.0,
          gp = GraphicParams
            .unsafe(stroke = None, fill = Some(Rgba.Black), fontSize = Length.pointsUnsafe(9.0)),
          name = Some(GraphicsName.unsafe("caption"))
        )
        .toOption
        .get

    val svg = render(Scene(Vector(text)), SvgOptions.unsafe(width = 80, height = 60))

    assert(
      svg.contains(
        """<text data-name="caption" fill="#000000" stroke="none" font-size="12" x="40" y="30" text-anchor="middle" dominant-baseline="middle" transform="rotate(-45 40 30)">A&amp;B &lt;label&gt;</text>"""
      )
    )
  }

  test("default render context resolves public line units") {
    val grob =
      Grob
        .circle(
          Point.npcUnsafe(0.5, 0.5),
          ExtentExpr.unsafe(Length.unsafe(1.0, LengthUnit.Line))
        )
        .toOption
        .get

    val svg = SvgRenderer.render(Scene(Vector(grob))).fold(error => fail(error.message), _.value)
    assert(svg.contains(" r=\"16\""))
  }

  test("returns typed errors for relative font sizes") {
    val grob =
      Grob
        .text(
          "label",
          Point.npcUnsafe(0.5, 0.5),
          gp = GraphicParams.unsafe(fontSize = Length.unsafe(0.5, LengthUnit.Npc))
        )
        .toOption
        .get

    assert(SvgRenderer.render(Scene(Vector(grob))).left.toOption.exists {
      case SvgRenderError.Graphics(GraphicsError.UnresolvableLength(_)) => true
      case _                                                            => false
    })
  }

  test("rejects invalid document sizes through smart options constructor") {
    assertEquals(
      SvgOptions(width = 0, height = 20).left.toOption,
      Some(SvgRenderError.InvalidDocumentSize(0, 20))
    )
  }

  test("rejects XML-illegal characters instead of returning malformed documents") {
    val label = Grob
      .text("bad\u0001label", Point.npcUnsafe(0.5, 0.5))
      .toOption
      .get

    assertEquals(
      SvgRenderer.render(Scene(Vector(label))).left.toOption,
      Some(SvgRenderError.InvalidXmlCharacter("text label", 1))
    )
    assertEquals(
      SvgRenderer
        .render(Scene.empty, SvgOptions.unsafe(title = Some("bad\u0000title")))
        .left
        .toOption,
      Some(SvgRenderError.InvalidXmlCharacter("document title", 0))
    )
  }

  test("accepts supplementary Unicode characters in XML text") {
    val label = Grob
      .text("activation \ud83e\udde0", Point.npcUnsafe(0.5, 0.5))
      .toOption
      .get

    assert(render(Scene(Vector(label))).contains("activation \ud83e\udde0"))
  }

  test("rejects oversized numeric attributes instead of silently saturating them") {
    val line = Grob
      .lines(
        Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)),
        gp = GraphicParams.unsafe(lineWidth = 1.0e308)
      )
      .toOption
      .get

    assertEquals(
      SvgRenderer.render(Scene(Vector(line))).left.toOption,
      Some(SvgRenderError.Graphics(GraphicsError.InvalidDeviceValue("line width", 1.0e308)))
    )
  }

  test("embeds raster images as deterministic PNG data with explicit interpolation") {
    val scene = RendererConformance.imageCase.fold(error => fail(error.message), identity).scene
    val first = render(scene, SvgOptions.unsafe(width = 100, height = 80))
    val second = render(scene, SvgOptions.unsafe(width = 100, height = 80))

    assertEquals(first, second)
    assert(
      first.contains(
        """<image data-name="conformance-image" data-pixel-width="2" data-pixel-height="2"""
      )
    )
    assert(first.contains("""x="25" y="20" width="50" height="40"""))
    assert(
      first.contains("""preserveAspectRatio="none" image-rendering="pixelated" opacity="0.8""")
    )
    assert(first.contains("""href="data:image/png;base64,iVBORw0KGgo"""))
  }

  test("smooth images retain scene z-order") {
    val source = RendererConformance.imageCase
      .fold(error => fail(error.message), identity)
      .scene
      .grobs
      .head
      .asInstanceOf[Grob.Image]
    val image = Grob.imageUnsafe(
      source.image,
      source.at,
      source.size,
      interpolation = RasterInterpolation.Smooth,
      name = Some(GraphicsName.unsafe("smooth-image"))
    )
    val overlay = Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.1, 0.1),
      name = Some(GraphicsName.unsafe("image-overlay"))
    )
    val svg = render(Scene(Vector(image, overlay)), SvgOptions.unsafe(width = 100, height = 80))

    assert(svg.contains("""data-name="smooth-image"""))
    assert(svg.contains("""image-rendering="auto"""))
    assert(svg.indexOf("""data-name="smooth-image""") < svg.indexOf("""data-name="image-overlay"""))
  }
