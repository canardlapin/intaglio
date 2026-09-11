package intaglio

class ScalarMappingSuite extends munit.FunSuite:
  private val blue = Rgba32.unsafe(0, 0, 200)
  private val white = Rgba32.unsafe(200, 200, 200)
  private val red = Rgba32.unsafe(200, 0, 0)
  private val low = ScalarRamp.linear(blue, white)
  private val high = ScalarRamp.linear(white, red)
  private val window = DisplayWindow.unsafe(-4.0, 8.0)
  private def diverging = ScalarMapping(ScalarScale.diverging(window, 0.0, low, high).toOption.get)
  private def split = ScalarMapping(
    ScalarScale.split(window, 0.0, -1.0, 2.0, low, high).toOption.get
  )

  test("asymmetric continuous and split scales have independent tabulated tail values"):
    val continuousExpected = Vector(
      -4.0 -> blue,
      -2.0 -> Rgba32.unsafe(100, 100, 200),
      -1.0 -> Rgba32.unsafe(150, 150, 200),
      0.0 -> white,
      2.0 -> Rgba32.unsafe(200, 150, 150),
      4.0 -> Rgba32.unsafe(200, 100, 100),
      8.0 -> red
    )
    continuousExpected.foreach((value, expected) => assertEquals(diverging.color(value), expected))
    val splitExpected = Vector(
      -4.0 -> blue,
      -2.5 -> Rgba32.unsafe(100, 100, 200),
      -1.0 -> white,
      2.0 -> white,
      5.0 -> Rgba32.unsafe(200, 100, 100),
      8.0 -> red
    )
    splitExpected.foreach((value, expected) => assertEquals(split.color(value), expected))
    assertEquals(split.classify(0.0), ScalarSampleState.HiddenScaleGap)
    assertEquals(split.evaluate(0.0).coordinate, None)
    assertEqualsDouble(split.evaluate(-2.5).coordinate.get.fraction, 0.5, 1e-15)
    assertEquals(split.evaluate(5.0).coordinate.get.segment, 1)

  test("piecewise ramp knots and interpolation are explicit display bytes"):
    val ramp = ScalarRamp
      .make(Vector(0.0 -> Rgba32.unsafe(0, 0, 0), 0.25 -> blue, 0.75 -> red, 1.0 -> white))
      .toOption
      .get
    val expected = Vector(
      0.125 -> Rgba32.unsafe(0, 0, 100),
      0.25 -> blue,
      0.5 -> Rgba32.unsafe(100, 0, 100),
      0.75 -> red,
      0.875 -> Rgba32.unsafe(200, 100, 100)
    )
    expected.foreach((position, color) => assertEquals(ramp.colorAt(position), color))
    assertEquals(ramp.colorAt(-3.0), Rgba32.unsafe(0, 0, 0))
    assertEquals(ramp.colorAt(3.0), white)

  test("every interval endpoint convention distinguishes edges and immediate neighbors"):
    for inclusion <- ScalarEndpointInclusion.values do
      val interval = ScalarInterval.make(-1.0, 2.0, inclusion).toOption.get
      val includesLower =
        inclusion == ScalarEndpointInclusion.Lower || inclusion == ScalarEndpointInclusion.Both
      val includesUpper =
        inclusion == ScalarEndpointInclusion.Upper || inclusion == ScalarEndpointInclusion.Both
      val points = Vector(
        math.nextAfter(-1.0, Double.NegativeInfinity) -> false,
        -1.0 -> includesLower,
        math.nextAfter(-1.0, Double.PositiveInfinity) -> true,
        math.nextAfter(2.0, Double.NegativeInfinity) -> true,
        2.0 -> includesUpper,
        math.nextAfter(2.0, Double.PositiveInfinity) -> false
      )
      points.foreach: (value, inside) =>
        assertEquals(ScalarVisibility.Inside(interval).includes(value), inside)
        assertEquals(ScalarVisibility.Outside(interval).includes(value), !inside)

  test("split-gap boundaries are visible and their inward neighbors are hidden"):
    val mapping = split
    assertEquals(mapping.classify(-1.0), ScalarSampleState.Visible)
    assertEquals(mapping.classify(2.0), ScalarSampleState.Visible)
    assertEquals(mapping.classify(math.nextAfter(-1.0, 0.0)), ScalarSampleState.HiddenScaleGap)
    assertEquals(mapping.classify(math.nextAfter(2.0, 0.0)), ScalarSampleState.HiddenScaleGap)
    assertEquals(mapping.classify(math.nextAfter(-1.0, -2.0)), ScalarSampleState.Visible)
    assertEquals(mapping.classify(math.nextAfter(2.0, 3.0)), ScalarSampleState.Visible)

  test("invalid, hidden, and saturated states survive deliberately identical colors"):
    val mapping = diverging.copy(hidden = blue, invalid = blue)
    assertEquals(mapping.evaluate(-10.0).state, ScalarSampleState.ClampedLow)
    assertEquals(mapping.evaluate(10.0).state, ScalarSampleState.ClampedHigh)
    assertEquals(
      mapping.copy(outOfRange = ScalarOutOfRange.Hide).evaluate(-10.0).state,
      ScalarSampleState.HiddenOutOfRange
    )
    for value <- Vector(Double.NaN, Double.NegativeInfinity, Double.PositiveInfinity) do
      assertEquals(mapping.classify(value), ScalarSampleState.Invalid)
      assertEquals(mapping.color(value), blue)
      assertEquals(mapping.evaluate(value).coordinate, None)

  test("visibility tests the original observation before clamping"):
    val visible = ScalarInterval.make(-2.0, 5.0, ScalarEndpointInclusion.Both).toOption.get
    val mapping = diverging.copy(visibility = ScalarVisibility.Inside(visible))
    assertEquals(mapping.classify(-20.0), ScalarSampleState.HiddenVisibility)
    assertEquals(mapping.classify(20.0), ScalarSampleState.HiddenVisibility)
    assertEquals(mapping.classify(-2.0), ScalarSampleState.Visible)
    assertEquals(mapping.classify(5.0), ScalarSampleState.Visible)

  test("legacy adapter preserves open threshold edges, windows, invalid colors and sRGB rounding"):
    val legacy = ScalarColorizer(
      DisplayWindow.unsafe(-2.0, 3.0),
      ColorRamp(blue, red),
      invalid = white,
      threshold = DisplayThreshold.TransparentBand(ThresholdBand.unsafe(-0.5, 1.0))
    )
    val mapping = ScalarMapping.fromLegacy(legacy)
    val values = Vector(
      Double.NaN,
      Double.PositiveInfinity,
      Double.NegativeInfinity,
      -10.0,
      -2.0,
      math.nextAfter(-0.5, -1.0),
      -0.5,
      math.nextAfter(-0.5, 0.0),
      0.0,
      math.nextAfter(1.0, 0.0),
      1.0,
      math.nextAfter(1.0, 2.0),
      3.0,
      10.0
    )
    values.foreach(value => assertEquals(mapping.color(value), legacy.color(value)))
    assertEquals(mapping.classify(-0.5), ScalarSampleState.Visible)
    assertEquals(mapping.classify(1.0), ScalarSampleState.Visible)
    assertEquals(mapping.classify(0.0), ScalarSampleState.HiddenVisibility)
    assertEquals(ScalarMapping.inspect(legacy).get.canonicalKey, mapping.canonicalKey)
    assertEquals(ScalarMapping.inspect(Colorizer.constant[Double](red)), None)

  test("checked overrides retain absolute center, update identity and reject impossible windows"):
    val mapping = split
    assert(mapping.resolve(window = Some(DisplayWindow.unsafe(-0.5, 8.0))).isLeft)
    val threshold = DisplayThreshold.TransparentBand(ThresholdBand.unsafe(-2.0, 4.0))
    val effective =
      mapping.resolve(Some(DisplayWindow.unsafe(-8.0, 10.0)), Some(threshold)).toOption.get
    assertEquals(effective.scale.center, Some(0.0))
    assertEquals(effective.classify(-1.5), ScalarSampleState.HiddenVisibility)
    assertEquals(effective.classify(5.0), ScalarSampleState.Visible)
    assertNotEquals(effective.canonicalKey, mapping.canonicalKey)
    assertEquals(mapping.resolve().toOption.get.canonicalKey, mapping.canonicalKey)
    val adapter = effective.colorizer
    assert(!adapter.supportsWindow && !adapter.supportsThreshold)
    assertEquals(ScalarMapping.inspect(adapter).get.canonicalKey, effective.canonicalKey)

  test("malformed ramps, centers, intervals and split widths fail at construction"):
    for stops <- Vector(
        Vector.empty,
        Vector(0.0 -> blue),
        Vector(0.1 -> blue, 1.0 -> red),
        Vector(0.0 -> blue, 0.0 -> red, 1.0 -> white),
        Vector(0.0 -> blue, Double.NaN -> red, 1.0 -> white)
      )
    do assert(ScalarRamp.make(stops).isLeft)
    assert(ScalarScale.diverging(window, -4.0, low, high).isLeft)
    assert(ScalarScale.diverging(window, Double.NaN, low, high).isLeft)
    assert(ScalarScale.diverging(window, 0.0, high, low).isLeft)
    for (a, b, center) <- Vector(
        (-4.0, 2.0, 0.0),
        (-1.0, 8.0, 0.0),
        (1.0, -1.0, 0.0),
        (-1.0, 2.0, 3.0)
      )
    do assert(ScalarScale.split(window, center, a, b, low, high).isLeft)
    assert(ScalarInterval.make(0.0, 0.0, ScalarEndpointInclusion.Both).isLeft)
    assert(ScalarInterval.make(0.0, Double.PositiveInfinity, ScalarEndpointInclusion.Both).isLeft)

  test("finite extreme limits normalize without overflow and agree with the legacy adapter"):
    val extreme = DisplayWindow.unsafe(-Double.MaxValue, Double.MaxValue)
    assertEqualsDouble(extreme.normalize(0.0), 0.5, 0.0)
    assertEqualsDouble(extreme.normalize(-Double.MaxValue / 2), 0.25, 1e-15)
    assertEqualsDouble(extreme.normalize(Double.MaxValue / 2), 0.75, 1e-15)
    val legacy = ScalarColorizer(extreme)
    assertEquals(legacy.color(0.0), Rgba32.unsafe(128, 128, 128))
    assertEquals(ScalarMapping.fromLegacy(legacy).color(0.0), legacy.color(0.0))

  test("canonical key is platform stable and distinguishes all effective display policies"):
    val mapping = ScalarMapping.fromLegacy(ScalarColorizer(DisplayWindow.unsafe(-2.0, 2.0)))
    assertEquals(
      mapping.canonicalKey,
      "scalar-mapping-v1|Sequential|none|c000000000000000:4000000000000000:0=ff,3ff0000000000000=ffffffff|all|Clamp|0|0"
    )
    assertNotEquals(
      mapping.copy(outOfRange = ScalarOutOfRange.Hide).canonicalKey,
      mapping.canonicalKey
    )
    assertNotEquals(mapping.copy(hidden = white).canonicalKey, mapping.canonicalKey)
    assertNotEquals(mapping.copy(invalid = white).canonicalKey, mapping.canonicalKey)
    val positive = ScalarScale.diverging(window, 0.0, low, high).toOption.get
    val negative = ScalarScale.diverging(window, -0.0, low, high).toOption.get
    assertEquals(ScalarMapping(positive).canonicalKey, ScalarMapping(negative).canonicalKey)

  test("affine changes of scalar units preserve colors and visibility"):
    val mapping = split.copy(visibility =
      ScalarVisibility.Outside(
        ScalarInterval.make(-2.0, 3.0, ScalarEndpointInclusion.Neither).toOption.get
      )
    )
    val transformed = ScalarMapping(
      ScalarScale.split(DisplayWindow.unsafe(2.0, 26.0), 10.0, 8.0, 14.0, low, high).toOption.get,
      ScalarVisibility.Outside(
        ScalarInterval.make(6.0, 16.0, ScalarEndpointInclusion.Neither).toOption.get
      )
    )
    for value <- Vector(-10.0, -4.0, -2.5, -2.0, -1.0, 0.0, 2.0, 3.0, 5.0, 8.0, 10.0) do
      assertEquals(transformed.color(value * 2.0 + 10.0), mapping.color(value))
      assertEquals(transformed.classify(value * 2.0 + 10.0), mapping.classify(value))

  private val thresholdBand = ThresholdBand.unsafe(-1.0, 2.0)

  /** Every threshold mode, including constructions the enum admits but the checked constructors
    * reject: a non-finite cutoff and an outer band that does not contain its inner band.
    */
  private val thresholds = Vector(
    DisplayThreshold.Disabled,
    DisplayThreshold.TransparentBand(thresholdBand),
    DisplayThreshold.Below(-1.0),
    DisplayThreshold.Above(2.0),
    DisplayThreshold.TwoSided(thresholdBand, None),
    DisplayThreshold.TwoSided(thresholdBand, Some(ThresholdBand.unsafe(-3.0, 5.0))),
    DisplayThreshold.Below(Double.NaN),
    DisplayThreshold.Above(Double.PositiveInfinity),
    DisplayThreshold.TwoSided(thresholdBand, Some(ThresholdBand.unsafe(0.0, 1.0)))
  )

  /** Each cutoff and band edge, its immediate neighbours, and the extremes of the finite line. */
  private val probes =
    Vector(-3.0, -1.0, 0.0, 1.0, 2.0, 5.0).flatMap(edge =>
      Vector(
        math.nextAfter(edge, Double.NegativeInfinity),
        edge,
        math.nextAfter(edge, Double.PositiveInfinity)
      )
    ) ++ Vector(-Double.MaxValue, -10.0, -0.0, 10.0, Double.MaxValue)

  test("every display threshold becomes a visibility showing exactly what it does not hide"):
    for
      threshold <- thresholds
      value <- probes
    do
      assertEquals(
        ScalarVisibility.fromThreshold(threshold).includes(value),
        !threshold.hides(value),
        s"$threshold at $value"
      )

  test("legacy colorizers and checked overrides accept every threshold mode"):
    val values = probes ++ Vector(Double.NaN, Double.NegativeInfinity, Double.PositiveInfinity)
    for threshold <- thresholds do
      val legacy = ScalarColorizer(
        DisplayWindow.unsafe(-2.0, 3.0),
        ColorRamp(blue, red),
        invalid = white,
        threshold = threshold
      )
      val mapping = ScalarMapping.fromLegacy(legacy)
      for value <- values do
        assertEquals(mapping.color(value), legacy.color(value), s"$threshold at $value")
      assertEquals(ScalarMapping.inspect(legacy).map(_.canonicalKey), Some(mapping.canonicalKey))
      val effective = split.resolve(threshold = Some(threshold)).toOption.get
      for value <- probes do
        assertEquals(
          effective.classify(value) == ScalarSampleState.HiddenVisibility,
          threshold.hides(value),
          s"$threshold at $value"
        )

  test("visibility identity names every kind and cutoff exactly and feeds the boundary list"):
    val inner = ScalarInterval.make(-2.0, 2.0, ScalarEndpointInclusion.Neither).toOption.get
    val outer = ScalarInterval.make(-8.0, 8.0, ScalarEndpointInclusion.Both).toOption.get
    def mapped(visibility: ScalarVisibility) = diverging.copy(visibility = visibility)
    def selection(visibility: ScalarVisibility) = mapped(visibility).canonicalKey.split('|')(4)
    val kinds = Vector(
      ScalarVisibility.All,
      ScalarVisibility.Inside(inner),
      ScalarVisibility.Outside(inner),
      ScalarVisibility.AtLeast(2.0),
      ScalarVisibility.AtMost(-1.0),
      ScalarVisibility.Band(inner, outer)
    )
    assertEquals(kinds.map(selection).distinct.length, kinds.length)
    assertEquals(selection(ScalarVisibility.AtLeast(2.0)), "at-least:4000000000000000")
    assertEquals(selection(ScalarVisibility.AtMost(-1.0)), "at-most:bff0000000000000")
    assertEquals(
      selection(ScalarVisibility.Band(inner, outer)),
      "band:c000000000000000,4000000000000000,Neither/c020000000000000,4020000000000000,Both"
    )
    def viaThreshold(threshold: DisplayThreshold) =
      mapped(ScalarVisibility.fromThreshold(threshold)).canonicalKey
    val band = ThresholdBand.unsafe(-2.0, 2.0)
    assertEquals(
      viaThreshold(DisplayThreshold.TwoSided(band, None)),
      viaThreshold(DisplayThreshold.TransparentBand(band))
    )
    assertEquals(
      viaThreshold(DisplayThreshold.TwoSided(band, Some(ThresholdBand.unsafe(-8.0, 8.0)))),
      mapped(ScalarVisibility.Band(inner, outer)).canonicalKey
    )
    assertEquals(
      mapped(ScalarVisibility.Band(inner, outer)).boundaries,
      Vector(-8.0, -4.0, -2.0, 0.0, 2.0, 8.0)
    )
    assertEquals(mapped(ScalarVisibility.AtLeast(2.0)).boundaries, Vector(-4.0, 0.0, 2.0, 8.0))
    assertEquals(mapped(ScalarVisibility.AtMost(-1.0)).boundaries, Vector(-4.0, -1.0, 0.0, 8.0))
    assertEquals(mapped(ScalarVisibility.AtLeast(Double.NaN)).boundaries, Vector(-4.0, 0.0, 8.0))
