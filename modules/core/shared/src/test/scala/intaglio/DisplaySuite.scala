package intaglio

class DisplaySuite extends munit.FunSuite:

  test("display windows and thresholds reject invalid bounds"):
    assertEquals(
      DisplayWindow.make(1.0, 1.0).left.toOption,
      Some(DisplayError.InvalidWindow(1.0, 1.0))
    )
    ThresholdBand.make(Double.NaN, 2.0).left.toOption match
      case Some(DisplayError.InvalidThresholdBand(lower, upper)) =>
        assert(lower.isNaN)
        assertEqualsDouble(upper, 2.0, 0.0)
      case other => fail(s"expected an invalid threshold band, found $other")

  test("transparent bands hide only finite values in their strict interior"):
    val threshold = DisplayThreshold.transparentBand(-0.5, 0.5).toOption.get

    assert(!threshold.hides(-0.5))
    assert(threshold.hides(0.0))
    assert(!threshold.hides(0.5))
    assert(!threshold.hides(Double.NaN))
    assert(!DisplayThreshold.Disabled.hides(0.0))

  test("display opacity is a checked opaque scalar"):
    assertEquals(
      DisplayOpacity.make(-0.1).left.toOption,
      Some(DisplayError.InvalidOpacity(-0.1))
    )
    assert(DisplayOpacity.make(Double.PositiveInfinity).isLeft)
    assertEqualsDouble(DisplayOpacity.unsafe(0.4).toDouble, 0.4, 0.0)
    assertEqualsDouble(DisplayOpacity.Transparent.toDouble, 0.0, 0.0)
    assertEqualsDouble(DisplayOpacity.Opaque.toDouble, 1.0, 0.0)

  test("normal blending is source-over and honors display opacity"):
    val blue = Rgba32.unsafe(0, 0, 255)
    val halfRed = Rgba32.unsafe(255, 0, 0, 128)

    assertEquals(
      DisplayBlendMode.Normal.composite(blue, halfRed),
      Rgba32.unsafe(128, 0, 127)
    )
    assertEquals(
      DisplayBlendMode.Normal.composite(blue, halfRed, DisplayOpacity.Transparent),
      blue
    )

  test("separable blend modes have exact opaque channel semantics"):
    val under = Rgba32.unsafe(128, 200, 40)
    val over = Rgba32.unsafe(64, 128, 255)

    assertEquals(DisplayBlendMode.Additive.composite(under, over), Rgba32.unsafe(192, 255, 255))
    assertEquals(DisplayBlendMode.Multiply.composite(under, over), Rgba32.unsafe(32, 100, 40))
    assertEquals(DisplayBlendMode.Screen.composite(under, over), Rgba32.unsafe(160, 228, 255))

  test("graphics-owned colorizers preserve image-view threshold behavior"):
    val colorizer = ScalarColorizer(
      DisplayWindow.unsafe(-1.0, 1.0),
      threshold = DisplayThreshold.transparentBand(-0.5, 0.5).toOption.get
    )

    assertEquals(colorizer.color(-0.5).alpha, 255)
    assertEquals(colorizer.color(0.0).alpha, 0)
    assertEquals(colorizer.color(0.5).alpha, 255)
    assertEquals(colorizer.color(Double.NaN).alpha, 0)

  private val probes: Vector[Double] =
    Vector(-1e6, -3.0, -1.5, -1.0, -0.5, -0.25, -1e-9, 0.0, 1e-9, 0.25, 0.5, 1.0, 1.5, 3.0, 1e6)

  test("below and above partition the finite line and overlap exactly at the cutoff"):
    for cutoff <- Vector(-2.0, -0.5, 0.0, 0.5, 2.0) do
      val below = DisplayThreshold.below(cutoff).toOption.get
      val above = DisplayThreshold.above(cutoff).toOption.get
      assert(!below.hides(cutoff) && !above.hides(cutoff), s"cutoff $cutoff must stay visible")
      for v <- probes if v != cutoff do
        assertEquals(below.hides(v), v < cutoff, s"below($cutoff).hides($v)")
        assertEquals(above.hides(v), v > cutoff, s"above($cutoff).hides($v)")
        assert(below.hides(v) != above.hides(v), s"exactly one side must hide $v")

  test("two-sided thresholds hide inside the inner band and outside the outer band"):
    val inner = ThresholdBand.unsafe(-0.5, 0.5)
    val outer = ThresholdBand.unsafe(-1.5, 1.5)
    val open = DisplayThreshold.twoSided(inner, None).toOption.get
    val bounded = DisplayThreshold.twoSided(inner, Some(outer)).toOption.get
    val band = DisplayThreshold.transparentBand(-0.5, 0.5).toOption.get

    for v <- probes do
      assertEquals(open.hides(v), band.hides(v), s"TwoSided(inner, None) must agree with TransparentBand at $v")
      assertEquals(bounded.hides(v), inner.contains(v) || v < -1.5 || v > 1.5, s"bounded.hides($v)")
    assert(!bounded.hides(-0.5) && !bounded.hides(0.5) && !bounded.hides(-1.5) && !bounded.hides(1.5))

  test("two-sided magnitude thresholds are symmetric"):
    val threshold = DisplayThreshold.twoSidedMagnitude(0.5, Some(1.5)).toOption.get
    for v <- probes do
      assertEquals(threshold.hides(v), threshold.hides(-v), s"symmetry at $v")
      assertEquals(threshold.hides(v), math.abs(v) < 0.5 || math.abs(v) > 1.5, s"magnitude at $v")
    assertEquals(
      DisplayThreshold.twoSidedMagnitude(0.5).toOption.get,
      DisplayThreshold.TwoSided(ThresholdBand.unsafe(-0.5, 0.5), None)
    )

  test("threshold constructors reject non-finite cutoffs and unnested bands"):
    for bad <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      DisplayThreshold.below(bad).left.toOption match
        case Some(DisplayError.InvalidThresholdCutoff(c)) => assert(c.isNaN || c == bad)
        case other                                        => fail(s"expected invalid cutoff, found $other")
      assert(DisplayThreshold.above(bad).isLeft)
    val inner = ThresholdBand.unsafe(-1.0, 1.0)
    assertEquals(
      DisplayThreshold.twoSided(inner, Some(ThresholdBand.unsafe(-0.5, 2.0))).left.toOption,
      Some(DisplayError.InvalidThresholdNesting(inner, ThresholdBand.unsafe(-0.5, 2.0)))
    )
    assert(DisplayThreshold.twoSided(inner, Some(ThresholdBand.unsafe(-2.0, 0.5))).isLeft)
    assert(DisplayThreshold.twoSided(inner, Some(inner)).isRight)
    assert(DisplayThreshold.twoSidedMagnitude(0.0).isLeft)
    assert(DisplayThreshold.twoSidedMagnitude(-1.0).isLeft)
    assert(DisplayThreshold.twoSidedMagnitude(1.0, Some(0.5)).isLeft)
    assert(DisplayThreshold.twoSidedMagnitude(1.0, Some(Double.NaN)).isLeft)
    assert(DisplayThreshold.twoSidedMagnitude(Double.PositiveInfinity).isLeft)

  test("non-finite values are never thresholded and render as invalid under every mode"):
    val modes = Vector(
      DisplayThreshold.Disabled,
      DisplayThreshold.transparentBand(-0.5, 0.5).toOption.get,
      DisplayThreshold.below(0.5).toOption.get,
      DisplayThreshold.above(-0.5).toOption.get,
      DisplayThreshold.twoSidedMagnitude(0.5, Some(1.5)).toOption.get
    )
    val invalid = Rgba32.unsafe(9, 9, 9, 0)
    for mode <- modes do assert(!mode.hides(Double.NaN), s"$mode must not claim to hide NaN")
    for mode <- modes; v <- Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity) do
      val colorizer = ScalarColorizer(DisplayWindow.unsafe(-1.0, 1.0), invalid = invalid, threshold = mode)
      assertEquals(colorizer.color(v), invalid, s"$mode must render $v as invalid")
      assertEquals(colorizer.color(v).alpha, 0)

  test("scalar colorizers render one-sided and bounded thresholds transparently"):
    val base = ScalarColorizer(DisplayWindow.unsafe(-1.0, 1.0))
    val positive = base.withThreshold(DisplayThreshold.below(0.25).toOption.get).get
    val negative = base.withThreshold(DisplayThreshold.above(-0.25).toOption.get).get
    val bounded = base.withThreshold(DisplayThreshold.twoSidedMagnitude(0.25, Some(0.75)).toOption.get).get

    assertEquals(positive.color(0.5).alpha, 255)
    assertEquals(positive.color(0.25).alpha, 255)
    assertEquals(positive.color(-0.5).alpha, 0)
    assertEquals(negative.color(-0.5).alpha, 255)
    assertEquals(negative.color(-0.25).alpha, 255)
    assertEquals(negative.color(0.5).alpha, 0)
    assertEquals(bounded.color(0.5).alpha, 255)
    assertEquals(bounded.color(-0.5).alpha, 255)
    assertEquals(bounded.color(0.1).alpha, 0)
    assertEquals(bounded.color(0.9).alpha, 0)
    assertEquals(bounded.color(-0.9).alpha, 0)
