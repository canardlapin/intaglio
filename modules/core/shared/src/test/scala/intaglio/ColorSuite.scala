package intaglio

class ColorSuite extends munit.FunSuite:

  private def hex(color: Rgba): String =
    f"#${color.red}%02X${color.green}%02X${color.blue}%02X"

  test("oklab components must be finite"):
    Oklab(Double.NaN, 0.0, 0.0).left.toOption match
      case Some(GraphicsError.NonFiniteColorComponent(component, value)) =>
        assertEquals(component, "lightness")
        assert(value.isNaN)
      case other => fail(s"expected a non-finite lightness, found $other")
    assert(Oklab(0.5, Double.PositiveInfinity, 0.0).isLeft)
    assert(Oklab(0.5, 0.0, Double.NegativeInfinity).isLeft)
    assert(Oklab(0.5, -0.1, 0.2).isRight)

  test("oklab round trips every sampled srgb color to the same bytes"):
    val stride = 5
    val samples =
      for
        red <- 0 to 255 by stride
        green <- 0 to 255 by stride
        blue <- 0 to 255 by stride
      yield Rgba.unsafe(red, green, blue)

    val changed = samples.filter(color => Oklab.toRgba(Oklab.fromRgba(color)) != color)
    assertEquals(changed, Vector.empty[Rgba])

    val greys = (0 to 255).map(level => Rgba.unsafe(level, level, level))
    assertEquals(greys.filter(g => Oklab.toRgba(Oklab.fromRgba(g)) != g), Vector.empty[Rgba])

  test("oklab lightness orders the grey axis and is achromatic on it"):
    val lightness = (0 to 255 by 15).map(level => Oklab.fromRgba(Rgba.unsafe(level, level, level)))

    assert(lightness.zip(lightness.tail).forall((low, high) => low.lightness < high.lightness))
    assert(lightness.forall(value => math.abs(value.a) < 1e-7 && math.abs(value.b) < 1e-7))
    assertEqualsDouble(Oklab.fromRgba(Rgba.Black).lightness, 0.0, 0.0)
    assertEqualsDouble(Oklab.fromRgba(Rgba.White).lightness, 1.0, 1e-8)

  test("oklab encoding clamps out-of-gamut coordinates instead of failing"):
    val beyond = Oklab.unsafe(0.6, 0.6, -0.6)

    assertEquals(Oklab.toRgba(beyond), Rgba.unsafe(255, 0, 255))
    assertEquals(Oklab.toRgba(Oklab.unsafe(2.0, 0.0, 0.0)), Rgba.White)
    assertEquals(Oklab.toRgba(Oklab.unsafe(-2.0, 0.0, 0.0)), Rgba.Black)

  test("oklab mix clamps its fraction and reaches both endpoints exactly"):
    val from = Oklab.fromRgba(Rgba.unsafe(33, 102, 172))
    val to = Oklab.fromRgba(Rgba.unsafe(180, 85, 45))

    assertEquals(Oklab.mix(from, to, 0.0), from)
    assertEquals(Oklab.mix(from, to, 1.0), to)
    assertEquals(Oklab.mix(from, to, -3.0), from)
    assertEquals(Oklab.mix(from, to, 4.0), to)
    assertEquals(Oklab.mix(from, to, Double.NaN), from)

  test("srgb channel interpolation crosses grey where oklab keeps chroma"):
    val blue = Rgba.unsafe(0, 0, 255)
    val yellow = Rgba.unsafe(255, 255, 0)

    assertEquals(hex(Palette.gradient(blue, yellow)(0.5)), "#808080")
    assertEquals(hex(Palette.oklabGradient(blue, yellow)(0.5)), "#6CABC7")

  test("oklab gradients reach their endpoints and carry alpha linearly"):
    val from = Rgba.unsafe(20, 30, 40, 0.2)
    val to = Rgba.unsafe(200, 120, 60, 1.0)
    val ramp = Palette.oklabGradient(from, to)

    assertEquals(ramp(0.0), from)
    assertEquals(ramp(1.0), to)
    assertEquals(ramp(-1.0), from)
    assertEquals(ramp(2.0), to)
    assertEqualsDouble(ramp(0.5).alpha, 0.6, 1e-12)

  test("diverging palettes refuse colors whose sign could not be read back"):
    val blue = Rgba.unsafe(33, 102, 172)
    val ivory = Rgba.unsafe(245, 240, 230)
    val rust = Rgba.unsafe(180, 85, 45)

    assertEquals(
      DivergingPalette(blue, ivory, blue).left.toOption,
      Some(GraphicsError.DegenerateDivergingPalette("both endpoints"))
    )
    assertEquals(
      DivergingPalette(ivory, ivory, rust).left.toOption,
      Some(GraphicsError.DegenerateDivergingPalette("the negative arm"))
    )
    assertEquals(
      DivergingPalette(blue, rust, rust).left.toOption,
      Some(GraphicsError.DegenerateDivergingPalette("the positive arm"))
    )
    assert(DivergingPalette(blue, ivory, rust).isRight)

  test("the diverging domain is signed: negative below zero, neutral at zero"):
    val palette = DivergingPalette.BlueRust

    assertEquals(palette.color(-1.0), palette.negative)
    assertEquals(palette.color(0.0), palette.neutral)
    assertEquals(palette.color(1.0), palette.positive)
    assertEquals(palette.color(-4.0), palette.negative)
    assertEquals(palette.color(4.0), palette.positive)
    assertEquals(palette.color(Double.NaN), palette.neutral)
    assertEquals(palette.color(-0.0), palette.neutral)

  test("the blue-rust ramp is pinned byte for byte"):
    val palette = DivergingPalette.BlueRust
    val samples = Vector(-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0)

    assertEquals(
      samples.map(t => hex(palette.color(t))),
      Vector(
        "#2166AC",
        "#5A89BD",
        "#8DABCC",
        "#C0CDDA",
        "#F5F0E6",
        "#E8C9B7",
        "#D8A38A",
        "#C77C5D",
        "#B4552D"
      )
    )

  test("the raster form is the scene form packed, at every sampled fraction"):
    val palette = DivergingPalette.BlueRust
    val fractions = (-40 to 40).map(_ / 40.0)

    assert(fractions.forall(t => palette.pixel(t) == Rgba32.fromRgba(palette.color(t))))
    assertEquals(palette.pixel(0.0).toRgba, palette.neutral)

  test("the unit-interval face puts the neutral exactly at the midpoint"):
    val palette = DivergingPalette.BlueRust
    val unit = palette.unitPalette

    assertEquals(unit(0.0), palette.negative)
    assertEquals(unit(0.5), palette.neutral)
    assertEquals(unit(1.0), palette.positive)
    assertEquals(unit(-1.0), palette.negative)
    assertEquals(unit(2.0), palette.positive)
    assertEquals(unit(0.25), palette.color(-0.5))

  test("infinities saturate and NaN takes the neutral on both faces of the domain"):
    val palette = DivergingPalette.BlueRust
    val unit = palette.unitPalette

    assertEquals(palette.color(Double.PositiveInfinity), palette.positive)
    assertEquals(palette.color(Double.NegativeInfinity), palette.negative)
    assertEquals(palette.color(Double.NaN), palette.neutral)
    assertEquals(unit(Double.PositiveInfinity), palette.positive)
    assertEquals(unit(Double.NegativeInfinity), palette.negative)
    assertEquals(unit(Double.NaN), palette.neutral)
    assertEquals(Palette.oklabGradient(Rgba.Black, Rgba.White)(Double.NaN), Rgba.Black)

  test("reversing swaps the arms and keeps the neutral"):
    val palette = DivergingPalette.BlueRust
    val reversed = palette.reversed

    assertEquals(reversed.negative, palette.positive)
    assertEquals(reversed.positive, palette.negative)
    assertEquals(reversed.neutral, palette.neutral)
    assertEquals(reversed.color(0.4), palette.color(-0.4))

  test("diverging arms interpolate alpha towards each endpoint"):
    val palette = DivergingPalette.unsafe(
      negative = Rgba.unsafe(33, 102, 172, 0.0),
      neutral = Rgba.unsafe(245, 240, 230, 1.0),
      positive = Rgba.unsafe(180, 85, 45, 0.5)
    )

    assertEqualsDouble(palette.color(0.0).alpha, 1.0, 0.0)
    assertEqualsDouble(palette.color(-1.0).alpha, 0.0, 0.0)
    assertEqualsDouble(palette.color(-0.5).alpha, 0.5, 1e-12)
    assertEqualsDouble(palette.color(1.0).alpha, 0.5, 0.0)

  test("a translucent palette agrees between its scene and raster forms"):
    val palette = DivergingPalette.unsafe(
      negative = Rgba.unsafe(33, 102, 172, 0.25),
      neutral = Rgba.unsafe(245, 240, 230, 0.75),
      positive = Rgba.unsafe(180, 85, 45, 1.0)
    )
    val fractions = (-20 to 20).map(_ / 20.0)

    assert(fractions.forall(t => palette.pixel(t) == Rgba32.fromRgba(palette.color(t))))
    assert(fractions.map(t => palette.pixel(t).alpha).distinct.length > 1)

  test("diverging colorizers require a positive finite limit"):
    assertEquals(
      DivergingColorizer.make(0.0).left.toOption,
      Some(DisplayError.InvalidDivergingLimit(0.0))
    )
    assert(DivergingColorizer.make(-1.0).isLeft)
    assert(DivergingColorizer.make(Double.PositiveInfinity).isLeft)
    assert(DivergingColorizer.make(Double.NaN).isLeft)
    assert(DivergingColorizer.make(3.0).isRight)

  test("a diverging colorizer places zero on the neutral and clamps beyond the limit"):
    val colorizer = DivergingColorizer.unsafe(4.0)
    val palette = DivergingPalette.BlueRust

    assertEquals(colorizer.color(0.0), Rgba32.fromRgba(palette.neutral))
    assertEquals(colorizer.color(4.0), Rgba32.fromRgba(palette.positive))
    assertEquals(colorizer.color(-4.0), Rgba32.fromRgba(palette.negative))
    assertEquals(colorizer.color(40.0), Rgba32.fromRgba(palette.positive))
    assertEquals(colorizer.color(2.0), palette.pixel(0.5))
    assertEquals(colorizer.window, DisplayWindow.unsafe(-4.0, 4.0))

  test("non-finite data takes the invalid pixel instead of reading as zero"):
    val invalid = Rgba32.unsafe(255, 0, 255)
    val colorizer = DivergingColorizer.unsafe(2.0, invalid = invalid)

    assertEquals(colorizer.color(Double.NaN), invalid)
    assertEquals(colorizer.color(Double.PositiveInfinity), invalid)
    assertNotEquals(colorizer.color(Double.NaN), colorizer.color(0.0))

  test("diverging thresholds hide a band around zero"):
    val threshold = DisplayThreshold.transparentBand(-0.5, 0.5).toOption.get
    val colorizer = DivergingColorizer.unsafe(2.0).withThreshold(threshold).get

    assertEquals(colorizer.color(0.0), Rgba32.unsafe(0, 0, 0, 0))
    assertEquals(colorizer.color(0.5), DivergingPalette.BlueRust.pixel(0.25))

  test("rewindowing a diverging colorizer keeps zero on the neutral"):
    val colorizer = DivergingColorizer.unsafe(1.0)
    val rewindowed = colorizer.withWindow(DisplayWindow.unsafe(-2.0, 6.0)).get

    assertEquals(rewindowed.color(0.0), Rgba32.fromRgba(DivergingPalette.BlueRust.neutral))
    assertEquals(rewindowed.color(6.0), Rgba32.fromRgba(DivergingPalette.BlueRust.positive))
    assertEquals(rewindowed.color(-6.0), Rgba32.fromRgba(DivergingPalette.BlueRust.negative))
    assertEqualsDouble(DivergingColorizer.covering(DisplayWindow.unsafe(-2.0, 6.0)), 6.0, 0.0)
    assertEqualsDouble(DivergingColorizer.covering(DisplayWindow.unsafe(-9.0, 6.0)), 9.0, 0.0)
    assertEqualsDouble(DivergingColorizer.covering(DisplayWindow.unsafe(2.0, 6.0)), 6.0, 0.0)
    assertEqualsDouble(DivergingColorizer.covering(DisplayWindow.unsafe(-6.0, -2.0)), 6.0, 0.0)
    assert(colorizer.supportsWindow)
    assert(colorizer.supportsThreshold)

  test("a diverging scale fixes a symmetric domain so the neutral cannot drift"):
    val palette = DivergingPalette.BlueRust
    val scale =
      ContinuousScale.diverging("contrast", 4.0).fold(error => fail(error.message), identity)

    assertEquals(scale.domain, Interval.unsafe(-4.0, 4.0))
    assertEquals(scale.training, ScaleTraining.Fixed)
    assertEquals(scale.mapValue(0.0), Some(palette.neutral))
    assertEquals(scale.mapValue(4.0), Some(palette.positive))
    assertEquals(scale.mapValue(-4.0), Some(palette.negative))
    assertEquals(scale.mapValue(2.0), Some(palette.color(0.5)))
    assertEquals(scale.mapValue(9.0), None)
    assertEquals(scale.mapValue(Double.NaN), None)

  test("plot-wide training cannot widen a diverging scale off its zero"):
    val scale =
      ContinuousScale.diverging("contrast", 1.0).fold(error => fail(error.message), identity)
    val widened = scale
      .trainPlotWide(Vector(0.5, 7.0, -0.25).map(ScaleObservation.Continuous.apply))
      .fold(error => fail(error.message), identity)

    assertEquals(widened.mapValue(0.0), Some(DivergingPalette.BlueRust.neutral))
    assertEquals(widened.descriptor.domain, scale.descriptor.domain)

  test("a diverging scale refuses a limit that is not a positive magnitude"):
    assertEquals(
      ContinuousScale.diverging("contrast", 0.0).left.toOption,
      Some(GraphicsError.InvalidInterval(-0.0, 0.0))
    )
    assert(ContinuousScale.diverging("contrast", -2.0).isLeft)
    assert(ContinuousScale.diverging("contrast", Double.NaN).isLeft)
    assert(ContinuousScale.diverging("contrast", Double.PositiveInfinity).isLeft)
