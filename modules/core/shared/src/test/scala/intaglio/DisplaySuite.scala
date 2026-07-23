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
