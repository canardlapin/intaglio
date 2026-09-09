package intaglio

/** Numerical court for the claims made about [[DivergingPalette.BlueRust]].
  *
  * Every figure is measured through the shipped [[ColorVision]] and [[ColorSeparation]], so the
  * evidence exercises the code a consumer runs rather than a private copy of it. The observer model
  * is Brettel, Viénot and Mollon (1997) and the metric is CIE76; `ColorVisionSuite` is the court
  * for both.
  */
class DivergingPaletteEvidenceSuite extends munit.FunSuite:

  private val palette = DivergingPalette.BlueRust

  private val bluered =
    DivergingPalette.unsafe(palette.negative, palette.neutral, Rgba.unsafe(178, 24, 43))

  /** Matched magnitudes at which the sign of a value is expected to be legible. Below `0.25` the
    * two arms are both near the neutral by design: a value near zero is supposed to look like a
    * value near zero.
    */
  private val magnitudes: Vector[Double] =
    (25 to 100).map(_ / 100.0).toVector

  private def armSeparationFloor(ramp: DivergingPalette, vision: ColorVision): Double =
    magnitudes.map(t => ColorSeparation.between(vision, ramp.color(-t), ramp.color(t))).min

  private def assertPinned(measured: Double, documented: Double, what: String): Unit =
    assert(
      math.abs(measured - documented) <= 0.05,
      s"$what: documented $documented, measured $measured"
    )

  private def armName(sign: Double): String =
    if sign < 0.0 then "negative" else "positive"

  test("the two arms stay far apart at matched magnitude under every observer modelled"):
    for vision <- ColorVision.values do
      val floor = armSeparationFloor(palette, vision)
      assert(
        floor >= ColorSeparation.SeriesFloor,
        s"${vision.label}: arms are only dE76 $floor apart at some |t| >= 0.25"
      )

  test("the published arm separation figures are the measured ones"):
    assertPinned(armSeparationFloor(palette, ColorVision.Normal), 23.3, "normal vision floor")
    assertPinned(armSeparationFloor(palette, ColorVision.Protanopia), 19.1, "protanopia floor")
    assertPinned(armSeparationFloor(palette, ColorVision.Deuteranopia), 22.4, "deuteranopia floor")
    assertPinned(armSeparationFloor(palette, ColorVision.Tritanopia), 18.8, "tritanopia floor")

  test("rust rather than a saturated red is what buys the protanopia margin"):
    val rust = armSeparationFloor(palette, ColorVision.Protanopia)
    val red = armSeparationFloor(bluered, ColorVision.Protanopia)

    assert(rust > red, s"rust $rust is no longer clearer than saturated red $red")
    assertPinned(red, 16.1, "saturated-red protanopia floor")

    // Honest about the trade: red is the better of the two under tritanopia. Rust wins on the
    // worst case across observers, which is the case a default has to be chosen on.
    assert(
      armSeparationFloor(bluered, ColorVision.Tritanopia) >
        armSeparationFloor(palette, ColorVision.Tritanopia)
    )
    assert(
      ColorVision.values.map(armSeparationFloor(palette, _)).min >
        ColorVision.values.map(armSeparationFloor(bluered, _)).min
    )

  test("lightness never reverses outward along either arm"):
    val steps = (0 to 200).map(_ / 200.0)

    for sign <- Vector(-1.0, 1.0) do
      val mixed = steps.map(t =>
        Oklab
          .mix(
            Oklab.fromRgba(palette.neutral),
            Oklab.fromRgba(if sign < 0.0 then palette.negative else palette.positive),
            t
          )
          .lightness
      )
      val rendered = steps.map(t => ColorSeparation.lightness(palette.color(sign * t)))

      assert(
        mixed.zip(mixed.tail).forall((near, far) => far < near),
        s"mixed oklab lightness is not strictly decreasing on the ${armName(sign)} arm"
      )
      assert(
        rendered.zip(rendered.tail).forall((near, far) => far <= near),
        s"rendered L* reverses outward on the ${armName(sign)} arm; " +
          "eight-bit quantization may tie, it may not go back up"
      )

    assertPinned(ColorSeparation.lightness(palette.neutral), 94.9, "neutral L*")
    assertPinned(ColorSeparation.lightness(palette.negative), 42.5, "negative endpoint L*")
    assertPinned(ColorSeparation.lightness(palette.positive), 47.5, "positive endpoint L*")

  test("consecutive samples of a sixteen-step ramp stay perceptibly distinct"):
    val samples = (-16 to 16).map(step => palette.color(step / 16.0))

    val smallest =
      ColorVision.values.toVector
        .map(vision =>
          samples
            .zip(samples.tail)
            .map((low, high) => ColorSeparation.between(vision, low, high))
            .min
        )
        .min

    assert(smallest >= 2.0, s"adjacent ramp steps collapse to dE76 $smallest")
    assertPinned(smallest, 3.3, "smallest adjacent step over the four observers")

  test("srgb channel interpolation is what produces a grey dead zone, not oklab"):
    val blue = Rgba.unsafe(0, 0, 255)
    val yellow = Rgba.unsafe(255, 255, 0)

    assert(ColorSeparation.chroma(Palette.gradient(blue, yellow)(0.5)) < 1.0)
    assert(ColorSeparation.chroma(Palette.oklabGradient(blue, yellow)(0.5)) > 18.0)
    assertPinned(
      ColorSeparation.chroma(Palette.oklabGradient(blue, yellow)(0.5)),
      24.2,
      "oklab midpoint chroma"
    )

  test("the two interpolation spaces agree closely on this palette's own endpoints"):
    val steps = (0 to 1000).map(_ / 1000.0)
    val worst =
      Vector(palette.negative, palette.positive).flatMap { endpoint =>
        val perceptual = Palette.oklabGradient(palette.neutral, endpoint)
        val channels = Palette.gradient(palette.neutral, endpoint)
        steps.map(t => ColorSeparation.between(ColorVision.Normal, perceptual(t), channels(t)))
      }.max

    assertPinned(worst, 2.34, "widest sRGB-versus-oklab gap on the shipped arms")
