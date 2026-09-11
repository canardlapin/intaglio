package intaglio

class ScalarLegendSuite extends munit.FunSuite:
  private val blue = Rgba32.unsafe(0, 0, 200)
  private val white = Rgba32.unsafe(200, 200, 200)
  private val red = Rgba32.unsafe(200, 0, 0)
  private val transparent = Rgba32.unsafe(0, 0, 0, 0)
  private val title = LegendTitle.make("Contrast", Some("percent signal")).toOption.get
  private val split = ScalarMapping(
    ScalarScale
      .split(
        DisplayWindow.unsafe(-4, 8),
        0,
        -1,
        2,
        ScalarRamp.linear(blue, white),
        ScalarRamp.linear(white, red)
      )
      .toOption
      .get
  )

  test("asymmetric split bar positions and colors have an independent piecewise oracle"):
    val legend = ScalarLegend.make(split, title).toOption.get
    assertEqualsDouble(legend.position(-1), 0.25, 0)
    assertEqualsDouble(legend.position(2), 0.5, 0)
    assertEqualsDouble(legend.position(0), 1.0 / 3, 1e-15)
    assertEquals(legend.colorAt(0), blue)
    assertEquals(legend.colorAt(1), red)
    assertEquals(legend.colorAt(0.375), transparent)
    for cell <- legend.cells(101) do
      val x = cell.sampleValue
      val expected = if x > -1 && x < 2 then transparent
      else if x <= -1 then
        val channel = math.round((x + 4) / 3 * 200).toInt
        Rgba32.unsafe(channel, channel, 200)
      else
        val channel = math.round((8 - x) / 6 * 200).toInt
        Rgba32.unsafe(200, channel, channel)
      assertEquals(cell.color, expected)
      assert(!(cell.lowerFraction < 0.25 && cell.upperFraction > 0.25))
      assert(!(cell.lowerFraction < 0.5 && cell.upperFraction > 0.5))

  test("effective thresholds and hidden versus invalid keys retain exact semantics"):
    val mapping = split.copy(
      visibility = ScalarVisibility.Inside(
        ScalarInterval.make(-2, 5, ScalarEndpointInclusion.Lower).toOption.get
      ),
      invalid = red
    )
    val legend = ScalarLegend.make(mapping, title).toOption.get
    assert(legend.notes.contains("Visible interval: [-2, 5)"))
    assert(legend.notes.contains("Omitted interval: (-1, 2)"))
    assert(legend.notes.contains("Unlit mapping colors"))
    assertEquals(legend.mapping.color(Double.NaN), red)
    assertEquals(legend.mapping.color(5), transparent)
    assertNotEquals(legend.canonicalKey, ScalarLegend.make(split, title).toOption.get.canonicalKey)
    assertNotEquals(
      legend.canonicalKey,
      ScalarLegend.make(mapping, title, showInvalid = false).toOption.get.canonicalKey
    )

  test("custom ticks and metadata reject ambiguous or unsupported calibration"):
    assert(LegendTitle.make(" ").isLeft)
    assert(LegendTitle.make("value", Some(" ")).isLeft)
    for ticks <- Vector(
        Vector(AxisTick.unsafe(9, "outside")),
        Vector(AxisTick.unsafe(0, " ")),
        Vector(AxisTick.unsafe(0, "a"), AxisTick.unsafe(0, "b"))
      )
    do assert(ScalarLegend.make(split, title, ticks).isLeft)
    intercept[IllegalArgumentException](ScalarLegend.make(split, title).toOption.get.position(9))

  test("narrow high-offset windows get distinct default tick labels"):
    val mapping = ScalarMapping(
      ScalarScale.sequential(
        DisplayWindow.unsafe(1e8 + 0.001, 1e8 + 0.002),
        ScalarRamp.linear(blue, red)
      )
    )
    val legend = ScalarLegend.make(mapping, title).toOption.get
    assertEquals(legend.ticks.map(_.label), Vector("100000000.001", "100000000.002"))

  test("drawn strips retain exact sampled colors and never interpolate across a split gap"):
    val legend = ScalarLegend.make(split, title).toOption.get
    val drawing = ScalarLegendDrawing.draw(legend, ScalarLegendStyle(resolution = 100)).toOption.get
    val strips = drawing.scene.grobs.collect { case image: Grob.Image => image }
    assertEquals(strips.length, 3)
    assertEquals(strips.map(_.image.width), Vector(25, 25, 50))
    assert(strips.forall(_.interpolation == RasterInterpolation.Nearest))
    for x <- 0 until 25 do
      assertEquals(strips(1).image.pixelUnsafe(x, 0), transparent)
      val c = math.round((x + 0.5) / 25 * 200).toInt
      assertEquals(strips.head.image.pixelUnsafe(x, 0), Rgba32.unsafe(c, c, 200))
    for x <- 0 until 50 do
      val c = math.round((1 - (x + 0.5) / 50) * 200).toInt
      assertEquals(strips.last.image.pixelUnsafe(x, 0), Rgba32.unsafe(200, c, c))

  test("title wrapping and crowded ticks stay inside measured bounds without overlap"):
    val longTitle = LegendTitle
      .make(
        "A long scientific quantity with a deliberately extended description",
        Some("millimeters per second")
      )
      .toOption
      .get
    val ticks = Vector(-4.0, -3.99, -3.98, 0.0, 7.99, 8.0).map(value =>
      AxisTick.unsafe(value, s"value ${value.toInt}")
    )
    val legend = ScalarLegend.make(split, longTitle, ticks).toOption.get
    val style = ScalarLegendStyle(widthPt = 180, fontPt = 12)
    val drawing = ScalarLegendDrawing.draw(legend, style).toOption.get
    assertEquals(drawing.legendKey, legend.canonicalKey)
    for tick <- drawing.ticks do
      assert(tick.leftPt >= style.marginPt)
      assert(tick.leftPt + tick.widthPt <= drawing.widthPt - style.marginPt + 1e-9)
      assert(tick.topPt > 0 && tick.topPt + tick.heightPt < drawing.heightPt)
    for (a, i) <- drawing.ticks.zipWithIndex; b <- drawing.ticks.drop(i + 1) do
      assert(
        a.leftPt + a.widthPt <= b.leftPt || b.leftPt + b.widthPt <= a.leftPt ||
          a.topPt + a.heightPt <= b.topPt || b.topPt + b.heightPt <= a.topPt
      )
    val larger = ScalarLegendDrawing.draw(legend, style.copy(widthPt = 350)).toOption.get
    assertEquals(larger.legendKey, drawing.legendKey)
    assert(larger.heightPt < drawing.heightPt)
