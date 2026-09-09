package intaglio

/** Court for the Brettel, Viénot & Mollon (1997) dichromat simulation.
  *
  * The published reference implementation is libDaltonLens, whose own test harness accepts a
  * one-byte-per-channel difference. The values pinned here are its golden outputs for the sRGB
  * primaries and secondaries, adjusted for the fact that it truncates on encode where this
  * implementation rounds; the tolerance below absorbs that.
  */
class ColorVisionSuite extends munit.FunSuite:

  private def hex(color: Rgba): String =
    f"#${color.red}%02X${color.green}%02X${color.blue}%02X"

  private def rgb(value: String): Rgba =
    Rgba.unsafe(
      Integer.parseInt(value.substring(1, 3), 16),
      Integer.parseInt(value.substring(3, 5), 16),
      Integer.parseInt(value.substring(5, 7), 16)
    )

  test("the published simulated primaries and secondaries reproduce exactly"):
    val expected = Vector(
      ("#FF0000", "#6C5C0C", "#A48B00", "#FF004E"),
      ("#00FF00", "#FFED00", "#F1D12E", "#79E9FF"),
      ("#0000FF", "#0038FF", "#0057FE", "#006288"),
      ("#FFFF00", "#FFFA00", "#FFF316", "#FFEEF1"),
      ("#00FFFF", "#EEF2FF", "#D1DFFF", "#47F8FF"),
      ("#FF00FF", "#006BFF", "#67A1FC", "#EF667A")
    )

    val measured = expected.map { case (source, _, _, _) =>
      val color = rgb(source)
      (
        source,
        hex(ColorVision.Protanopia.simulate(color)),
        hex(ColorVision.Deuteranopia.simulate(color)),
        hex(ColorVision.Tritanopia.simulate(color))
      )
    }

    assertEquals(measured, expected)

  test("every grey is a fixed point under every observer"):
    val greys = (0 to 255 by 5).map(level => Rgba.unsafe(level, level, level))

    for
      vision <- ColorVision.values
      grey <- greys
    do assertEquals(vision.simulate(grey), grey, clues(vision.label, grey))

  test("each half-plane preserves the neutral axis and each separation plane contains it"):
    for vision <- ColorVision.dichromacies do
      for plane <- ColorVision.halfPlanes(vision) do
        for row <- 0 until 3 do
          val sum = plane(row * 3) + plane(row * 3 + 1) + plane(row * 3 + 2)
          assertEqualsDouble(sum, 1.0, 2e-5, clues(vision.label, row))
      val normal = ColorVision.separationNormal(vision)
      assertEqualsDouble(normal(0) + normal(1) + normal(2), 0.0, 1e-9, clues(vision.label))

  test("normal vision is the identity and alpha survives simulation"):
    val translucent = Rgba.unsafe(180, 85, 45, 0.4)

    assertEquals(ColorVision.Normal.simulate(translucent), translucent)
    for vision <- ColorVision.dichromacies do
      assertEqualsDouble(vision.simulate(translucent).alpha, 0.4, 0.0, clues(vision.label))

  test("tritanopia moves blue and yellow, which is what the folk matrix fails to do"):
    // The widely copied "tritanopia matrix" keeps the red-green projection plane, so it leaves blue
    // and yellow — the pair a tritanope actually confuses — untouched and destroys red instead.
    // Anything that reproduces that behaviour is simulating the wrong deficiency.
    val blue = rgb("#0000FF")
    val yellow = rgb("#FFFF00")
    val red = rgb("#FF0000")

    def moved(color: Rgba): Double =
      ColorSeparation.between(ColorVision.Normal, color, ColorVision.Tritanopia.simulate(color))

    assertNotEquals(ColorVision.Tritanopia.simulate(blue), blue)
    assertNotEquals(ColorVision.Tritanopia.simulate(yellow), yellow)
    assert(moved(blue) > 90.0, clues(moved(blue)))
    assert(moved(yellow) > 90.0, clues(moved(yellow)))
    assert(moved(red) > 20.0, clues(moved(red)))

  test("the two dichromacies collapse different pairs, which is why both are modelled"):
    val orange = rgb("#FF7F0E")
    val green = rgb("#2CA02C")
    val red = rgb("#D62728")

    // Deuteranopia is the one that takes red against green; protanopia leaves that pair legible
    // and takes orange against green instead. Checking only one of the two would miss the other.
    assert(ColorSeparation.between(ColorVision.Normal, red, green) > 100.0)
    assert(ColorSeparation.between(ColorVision.Deuteranopia, red, green) < 10.0)
    assert(ColorSeparation.between(ColorVision.Protanopia, red, green) > 30.0)

    assert(ColorSeparation.between(ColorVision.Normal, orange, green) > 100.0)
    assert(ColorSeparation.between(ColorVision.Protanopia, orange, green) < 10.0)
    assert(ColorSeparation.between(ColorVision.Deuteranopia, orange, green) > 30.0)

    // Tritanopia keeps both, which is the whole reason a blue-yellow axis survives it.
    assert(ColorSeparation.between(ColorVision.Tritanopia, red, green) > 40.0)
    assert(ColorSeparation.between(ColorVision.Tritanopia, orange, green) > 40.0)

  test("separation is symmetric, zero on identity, and finds the closest pair"):
    val colors = Vector(
      Rgba.unsafe(0, 0, 0),
      Rgba.unsafe(255, 255, 255),
      Rgba.unsafe(250, 250, 250)
    )

    assertEqualsDouble(
      ColorSeparation.between(ColorVision.Normal, colors(0), colors(1)),
      ColorSeparation.between(ColorVision.Normal, colors(1), colors(0)),
      0.0
    )
    assertEqualsDouble(ColorSeparation.between(ColorVision.Normal, colors(0), colors(0)), 0.0, 0.0)

    val closest = ColorSeparation.closest(colors, ColorVision.Normal)
    assertEquals(closest.map(value => (value.first, value.second)), Some((1, 2)))
    assert(closest.exists(_.deltaE76 < 3.0))
    assertEquals(ColorSeparation.closest(Vector(Rgba.Black), ColorVision.Normal), None)
    assertEquals(ColorSeparation.closest(Vector.empty, ColorVision.Normal), None)

  test("lightness reports CIE L* on the familiar scale"):
    assertEqualsDouble(ColorSeparation.lightness(Rgba.Black), 0.0, 1e-9)
    assertEqualsDouble(ColorSeparation.lightness(Rgba.White), 100.0, 1e-5)
    assertEqualsDouble(ColorSeparation.lightness(Rgba.unsafe(128, 128, 128)), 53.6, 0.05)
