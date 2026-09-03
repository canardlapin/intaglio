package intaglio.svg

import intaglio.*

/** Per-grob metadata in SVG: emission shape, escaping, omission of absent parts, typed boundary
  * errors, geometry transparency, and byte-identity of unannotated scenes with the renderer that
  * predates annotation. This suite runs unchanged on the JVM and Scala.js, so every exact string
  * below is also a cross-platform determinism check.
  */
class SvgAnnotationSuite extends munit.FunSuite:
  private val options = SvgOptions.unsafe(width = 100, height = 80)

  private def render(scene: Scene): Either[SvgRenderError, String] =
    SvgRenderer.render(scene, options).map(_.value)

  private def rendered(scene: Scene): String =
    render(scene).fold(error => fail(error.message), identity)

  private def unit(name: String): Grob =
    Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.pointsUnsafe(4.0),
        name = Some(GraphicsName.unsafe(name))
      )
      .fold(error => fail(error.message), identity)

  private val circleLine =
    """<circle data-name="unit-7" stroke="#000000" fill="none" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" cx="50" cy="40" r="5.3333" />"""

  test(
    "annotated grobs emit a wrapping group with class, data attributes, title, and description"
  ) {
    val meta = GrobMeta(
      title = Some("""Unit 7 & "friends" <b>"""),
      description = Some("Mass ]]> 0.5"),
      cssClass = Some(CssClass.unsafe("mark  decode-filled")),
      data = Vector(
        DataKey.unsafe("kind") -> """anchor "A" & <b>""",
        DataKey.unsafe("origin-index") -> "3"
      )
    )
    val expected =
      s"""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="80" viewBox="0 0 100 80">
         |  <g class="mark decode-filled" data-kind="anchor &quot;A&quot; &amp; &lt;b&gt;" data-origin-index="3">
         |    <title>Unit 7 &amp; "friends" &lt;b&gt;</title>
         |    <desc>Mass ]]&gt; 0.5</desc>
         |    $circleLine
         |  </g>
         |</svg>
         |""".stripMargin

    assertEquals(rendered(Scene(Vector(Grob.annotated(unit("unit-7"), meta)))), expected)
  }

  test("absent metadata parts emit nothing and an empty annotation is a bare group") {
    val titleOnly = rendered(Scene(Vector(Grob.annotated(unit("unit-7"), GrobMeta.title("t")))))
    assert(titleOnly.contains("\n  <g>\n    <title>t</title>\n    <circle"), titleOnly)
    assert(!titleOnly.contains("<desc>"))
    assert(!titleOnly.contains(" class="))

    val classOnly = rendered(
      Scene(
        Vector(
          Grob.annotated(unit("unit-7"), GrobMeta(cssClass = Some(CssClass.unsafe("hot"))))
        )
      )
    )
    assert(classOnly.contains("\n  <g class=\"hot\">\n    <circle"), classOnly)
    assert(!classOnly.contains("<title>"))

    val empty = rendered(Scene(Vector(Grob.annotated(unit("unit-7"), GrobMeta.empty))))
    assertEquals(
      empty,
      s"""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="80" viewBox="0 0 100 80">
         |  <g>
         |    $circleLine
         |  </g>
         |</svg>
         |""".stripMargin
    )
  }

  test("annotation leaves the child's element byte-identical and only wraps it") {
    val plain = rendered(Scene(Vector(unit("unit-7"))))
    val annotated = rendered(
      Scene(Vector(Grob.annotated(unit("unit-7"), GrobMeta.title("hover"))))
    )
    val plainLines = plain.linesIterator.map(_.trim).toVector
    val annotatedLines = annotated.linesIterator.map(_.trim).toVector

    assert(plainLines.contains(circleLine))
    assertEquals(annotatedLines.filter(_.startsWith("<circle")), Vector(circleLine))
    assertEquals(annotatedLines.count(_ == "<g>"), 1)
    assertEquals(annotatedLines.count(_ == "</g>"), 1)
  }

  test("nested annotations and annotated viewport children nest their groups") {
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.1),
      size = Size.npcUnsafe(0.5, 0.5)
    )
    val framed = Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("framed"))
      )
      .fold(error => fail(error.message), identity)
    val scene = Scene(
      Vector(
        Grob.annotated(
          Grob.annotated(framed, GrobMeta.title("inner")),
          GrobMeta(cssClass = Some(CssClass.unsafe("outer")))
        )
      )
    )
    val svg = rendered(scene)
    val lines = svg.linesIterator.toVector

    assertEquals(lines(1), """  <g class="outer">""")
    assertEquals(lines(2), "    <g>")
    assertEquals(lines(3), "      <title>inner</title>")
    assert(
      lines(4).startsWith("""      <g data-name="framed" clip-path="url(#clip-0)">"""),
      lines(4)
    )
    assert(lines(5).trim.startsWith("<circle"))
  }

  test("a repeated data key is a typed error rather than a duplicate attribute") {
    val kind = DataKey.unsafe("kind")
    val meta = GrobMeta(data = Vector(kind -> "a", DataKey.unsafe("other") -> "b", kind -> "c"))

    assertEquals(
      render(Scene(Vector(Grob.annotated(unit("unit-7"), meta)))),
      Left(SvgRenderError.DuplicateDataKey("kind"))
    )
  }

  test("XML-illegal characters in annotation text are typed errors naming the field") {
    val illegal = "\u0001"
    def scene(meta: GrobMeta): Scene =
      Scene(Vector(Grob.annotated(unit("unit-7"), meta)))

    assertEquals(
      render(scene(GrobMeta.title("bad" + illegal))),
      Left(SvgRenderError.InvalidXmlCharacter("annotation title", 0x1))
    )
    assertEquals(
      render(scene(GrobMeta(description = Some(illegal)))),
      Left(SvgRenderError.InvalidXmlCharacter("annotation description", 0x1))
    )
    assertEquals(
      render(scene(GrobMeta(data = Vector(DataKey.unsafe("kind") -> illegal)))),
      Left(SvgRenderError.InvalidXmlCharacter("annotation data-kind", 0x1))
    )
  }

  test("supplementary Unicode in annotation text survives escaping") {
    val meta = GrobMeta.title("📍 pin & <mark>")
    val svg = rendered(Scene(Vector(Grob.annotated(unit("unit-7"), meta))))
    assert(svg.contains("<title>📍 pin &amp; &lt;mark&gt;</title>"), svg)
  }

  test("annotated rendering is deterministic") {
    val meta = GrobMeta(
      title = Some("t"),
      cssClass = Some(CssClass.unsafe("a b")),
      data = Vector(DataKey.unsafe("k") -> "v")
    )
    val scene = Scene(Vector(Grob.annotated(unit("unit-7"), meta)))
    assertEquals(rendered(scene), rendered(scene))
  }

  /** FNV-1a 64-bit over UTF-16 code units: portable across the JVM and Scala.js without a digest
    * library, and enough to pin a document byte-for-byte for regression purposes.
    */
  private def fnv1a64(value: String): String =
    var hash = 0xcbf29ce484222325L
    var index = 0
    while index < value.length do
      hash ^= value.charAt(index).toLong
      hash *= 0x100000001b3L
      index += 1
    val hex = java.lang.Long.toHexString(hash)
    "0" * (16 - hex.length) + hex

  /** Digests of every conformance case rendered at 240 x 160 by the renderer as of commit 65bc425,
    * immediately before per-grob annotation existed. A case with two digests already rendered
    * differently on the JVM and on Scala.js before annotation; both pre-annotation outputs are
    * accepted so the law is about this change, not that pre-existing divergence.
    */
  private val preAnnotationDigests: Map[String, Set[String]] =
    Map(
      "point" -> Set("14c2f4dbc3628be6"),
      "line" -> Set("9cbae27e1c22c8f6"),
      "shapes" -> Set("db6f4ae4e21a81e3"),
      "rect-circle" -> Set("3cc0d2aaaf5d84f7"),
      "pattern-fills" -> Set("f037482344498699"),
      "text" -> Set("03dbc7c75664ea2b"),
      "image" -> Set("858623f1baab7b4c"),
      "clipped-viewport" -> Set("859d90999c7d3d87"),
      "rotated-viewport" -> Set("1685eff0448a6a12"),
      "clipped-rotated-viewport" -> Set("1bf9d42aeb0945f8"),
      "ydown-viewport" -> Set("e5789c704d136296"),
      "axes" -> Set("96a8a307ef2ba60d"),
      "legend" -> Set("cce7007e836207fa"),
      "colorbar" -> Set("eab14bce421ed0e8"),
      "scaled-plot" -> Set("183be6bbdd763191", "4315161ef3fde199"),
      "mixed-layer-plot" -> Set("6fce53769e4901a2"),
      "titled-plot" -> Set("8e11bc1d3d8ccd67"),
      "comparison-scatter" -> Set("65e2b216385e10c2"),
      "comparison-line" -> Set("885ba064c730b940"),
      "comparison-histogram" -> Set("211b7a88b4e7cc54"),
      "comparison-density" -> Set("fd72ea03b7c01fd2"),
      "comparison-summary" -> Set("7f5bef54e50f201d"),
      "comparison-ribbon" -> Set("ae9957a1f5894cf2"),
      "comparison-tiles" -> Set("26e7333147258988"),
      "comparison-heatmap" -> Set("9a415be599b3685d", "a7dfbeda15b31b0d"),
      "comparison-bin2d" -> Set("68b0bed728ed9ea4", "a95e5b5911209ccc"),
      "comparison-kde2d" -> Set("c7e1736175d0cad9", "44815109bef561b9"),
      "comparison-contour" -> Set("16b773129e554ec8"),
      "comparison-filled-contour" -> Set("cf3dc9e20cb6d80e"),
      "faceted-plot" -> Set("ef7bd76b8ab36584"),
      "count-plot" -> Set("98f0d26d360295fd"),
      "band-position-plot" -> Set("5c5d3bd9e1ab68ff"),
      "position-dodge" -> Set("4eb565137d4b5075"),
      "position-stack" -> Set("7c2bd216c0688221"),
      "position-jitter" -> Set("eaa26caf9d7947ef"),
      "scientific-stats" -> Set("3ce5dbe3a142934a"),
      "flipped-plot" -> Set("421be661811ad165"),
      "bounded-geoms" -> Set("7e5ffe0b9fa7cc52"),
      "segment-geoms" -> Set("f767a89d906847f9"),
      "band-geoms" -> Set("682ace0285cbe234")
    )

  /** Cases added after the pin was taken; each must be listed here deliberately. */
  private val postPinCases: Set[String] =
    Set("annotated", "step-lines", "rounded-rect")

  test("scenes without annotation render byte-identically to the pre-annotation renderer") {
    val cases = RendererConformance.cases.fold(error => fail(error.message), identity)
    val pinOptions = SvgOptions.unsafe(width = 240, height = 160)

    assertEquals(cases.map(_.name.value).toSet, preAnnotationDigests.keySet ++ postPinCases)
    cases.filter(c => preAnnotationDigests.contains(c.name.value)).foreach { conformance =>
      val svg = SvgRenderer
        .render(conformance.scene, pinOptions)
        .fold(error => fail(s"${conformance.name.value}: ${error.message}"), _.value)
      val digest = fnv1a64(svg)
      assert(
        preAnnotationDigests(conformance.name.value).contains(digest),
        s"${conformance.name.value} rendered with digest $digest, not one of ${preAnnotationDigests(conformance.name.value)}"
      )
    }
  }
