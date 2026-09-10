package intaglio.svg

import intaglio.*

/** Rounded rectangles and step lines in SVG: exact attribute emission, byte-identity of the forms
  * they replace, and the clamping every backend shares. This suite runs unchanged on the JVM and
  * Scala.js, so every exact string below is also a cross-platform determinism check.
  */
class SvgGrobFormSuite extends munit.FunSuite:
  private val options = SvgOptions.unsafe(width = 200, height = 100)

  private def rendered(scene: Scene): String =
    SvgRenderer.render(scene, options).fold(error => fail(error.message), _.value)

  private def elements(scene: Scene): Vector[String] =
    rendered(scene).linesIterator.map(_.trim).filter(_.startsWith("<")).toVector

  private def bar(radius: ExtentExpr): Grob =
    Grob.rectUnsafe(
      Point.npcUnsafe(0.5, 0.5),
      Size.npcUnsafe(0.5, 0.4),
      cornerRadius = radius,
      gp = GraphicParams.unsafe(fill = Some(Rgba.unsafe(40, 110, 160))),
      name = Some(GraphicsName.unsafe("anchor-bar"))
    )

  test("a zero corner radius emits the same <rect> the sharp rectangle always emitted") {
    val sharp =
      """<rect data-name="anchor-bar" stroke="#000000" fill="#286ea0" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" x="50" y="30" width="100" height="40" />"""
    assertEquals(elements(Scene(Vector(bar(ExtentExpr.zero))))(1), sharp)
    assert(!rendered(Scene(Vector(bar(ExtentExpr.zero)))).contains(" rx="))
  }

  test("a corner radius emits rx and ry after the rectangle's geometry") {
    assertEquals(
      elements(Scene(Vector(bar(ExtentExpr.pointsUnsafe(6.0)))))(1),
      """<rect data-name="anchor-bar" stroke="#000000" fill="#286ea0" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" x="50" y="30" width="100" height="40" rx="8" ry="8" />"""
    )
  }

  test("an oversized radius is clamped to half the shorter side") {
    assertEquals(
      elements(Scene(Vector(bar(ExtentExpr.npcUnsafe(10.0)))))(1),
      """<rect data-name="anchor-bar" stroke="#000000" fill="#286ea0" stroke-width="1" stroke-linecap="butt" stroke-linejoin="miter" x="50" y="30" width="100" height="40" rx="20" ry="20" />"""
    )
  }

  private val track =
    Vector(
      Point.npcUnsafe(0.1, 0.2),
      Point.npcUnsafe(0.5, 0.7),
      Point.npcUnsafe(0.8, 0.4)
    )

  private def line(points: Vector[Point], interpolation: LineInterpolation): Grob =
    Grob.linesUnsafe(
      points,
      interpolation = interpolation,
      gp = GraphicParams.unsafe(stroke = Some(Rgba.unsafe(20, 90, 60))),
      name = Some(GraphicsName.unsafe("group-track"))
    )

  test("a step-after line emits the same path as its explicit corner form") {
    val stepped = rendered(Scene(Vector(line(track, LineInterpolation.StepAfter))))
    val explicit = rendered(
      Scene(
        Vector(
          line(
            Vector(
              track(0),
              Point(track(1).x, track(0).y),
              track(1),
              Point(track(2).x, track(1).y),
              track(2)
            ),
            LineInterpolation.Linear
          )
        )
      )
    )
    assertEquals(stepped, explicit)
    assert(stepped.contains("""points="20,80 100,80 100,30 160,30 160,60""""), stepped)
  }

  test("a step-before line emits the same path as its explicit corner form") {
    val stepped = rendered(Scene(Vector(line(track, LineInterpolation.StepBefore))))
    val explicit = rendered(
      Scene(
        Vector(
          line(
            Vector(
              track(0),
              Point(track(0).x, track(1).y),
              track(1),
              Point(track(1).x, track(2).y),
              track(2)
            ),
            LineInterpolation.Linear
          )
        )
      )
    )
    assertEquals(stepped, explicit)
    assert(stepped.contains("""points="20,80 20,30 100,30 100,60 160,60""""), stepped)
  }

  test("linear stays the default, so an unmarked line emits its given points") {
    assertEquals(
      rendered(Scene(Vector(line(track, LineInterpolation.Linear)))),
      rendered(
        Scene(
          Vector(
            Grob.linesUnsafe(
              track,
              gp = GraphicParams.unsafe(stroke = Some(Rgba.unsafe(20, 90, 60))),
              name = Some(GraphicsName.unsafe("group-track"))
            )
          )
        )
      )
    )
    assert(
      rendered(Scene(Vector(line(track, LineInterpolation.Linear))))
        .contains("""points="20,80 100,30 160,60"""")
    )
  }

  test("a step track is one named element rather than one per horizontal run") {
    val stepped = elements(Scene(Vector(line(track, LineInterpolation.StepAfter))))
    assertEquals(stepped.count(_.contains("""data-name="group-track"""")), 1)
  }
