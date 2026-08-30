package intaglio

class ScaleSuite extends munit.FunSuite:
  test("scale specs are row-free inferred declarations distinct from fixed scales") {
    val inferred = ContinuousScaleSpec
      .numeric("x")
      .fold(error => fail(error.message), identity)
    val themed = ContinuousScaleSpec
      .themeRgba("fill")
      .fold(error => fail(error.message), identity)
    val discrete = DiscreteScaleSpec
      .themeRgba("condition", Vector("control"))
      .fold(error => fail(error.message), identity)
    val fixed = ContinuousScale
      .fixed("x", Vector(0.0, 10.0), Palette.numeric)
      .fold(error => fail(error.message), identity)

    assertEquals(inferred.descriptor.domain, ScaleDomain.Unspecified)
    assertEquals(inferred.descriptor.training, ScaleTraining.PlotWide)
    assertEquals(themed.paletteSource, ScalePaletteSource.ThemeDefault)
    assertEquals(discrete.declaredLevels, Vector("control"))
    assertEquals(discrete.descriptor.domain, ScaleDomain.Unspecified)
    assertEquals(fixed.descriptor.training, ScaleTraining.Fixed)
    assertEquals(
      fixed.descriptor.domain,
      ScaleDomain.Continuous(Interval.unsafe(0.0, 10.0), Interval.unsafe(0.0, 10.0))
    )
  }

  test("continuous ranges train as an associative union over finite values") {
    val left =
      ContinuousRange.empty
        .train(Vector(2.0, Double.NaN, 8.0))
        .train(Vector(-1.0, Double.PositiveInfinity))
        .requireTrained
        .toOption
        .get

    val right =
      ContinuousRange.empty
        .train(Vector(2.0, Double.NaN, 8.0, -1.0, Double.PositiveInfinity))
        .requireTrained
        .toOption
        .get

    assertEquals(left, right)
    assertEquals(left.lower, -1.0)
    assertEquals(left.upper, 8.0)
  }

  test("continuous scale maps through transform, rescale, and out-of-bounds policy") {
    val scale =
      ContinuousScale
        .train("x", Vector(1.0, 10.0, 100.0), Palette.numeric, transform = Transform.log10)
        .toOption
        .get

    val mapped = scale.mapValues(Vector(1.0, 10.0, 100.0, 1000.0))

    assertEqualsDouble(mapped(0).get, 0.0, 1e-12)
    assertEqualsDouble(mapped(1).get, 0.5, 1e-12)
    assertEqualsDouble(mapped(2).get, 1.0, 1e-12)
    assertEquals(mapped(3), None)
    assertEquals(scale.breaks, Vector(1.0, 10.0, 100.0))
    assertEquals(scale.breaks.flatMap(scale.mapValue), Vector(0.0, 0.5, 1.0))
    assertEquals(scale.descriptor.kind, ScaleKind.Continuous)
    assertEquals(
      scale.descriptor.domain,
      ScaleDomain.Continuous(Interval.unsafe(1.0, 100.0), Interval.unsafe(0.0, 2.0))
    )
    assertEquals(
      scale.mapValueResult(0.0).left.toOption,
      Some(ScaleMapFailure.TransformDomain("log10", 0.0))
    )
  }

  test("continuous palette sampling is deterministic at equal-width bin centers") {
    val scale = ContinuousScale
      .train(
        "activation",
        Vector(1.0, 100.0),
        Palette.gradient(Rgba.Black, Rgba.White),
        transform = Transform.log10
      )
      .fold(e => fail(e.message), identity)

    assertEquals(
      scale.paletteSamples(4),
      Right(
        Vector(
          Rgba.unsafe(32, 32, 32),
          Rgba.unsafe(96, 96, 96),
          Rgba.unsafe(159, 159, 159),
          Rgba.unsafe(223, 223, 223)
        )
      )
    )
    assertEquals(scale.paletteSamples(0).left.toOption, Some(GraphicsError.InvalidBreakCount(0)))
  }

  test("squish keeps out-of-bounds values by clamping to palette endpoints") {
    val scale =
      ContinuousScale
        .train(
          "x",
          Vector(0.0, 10.0),
          Palette.numeric,
          oob = OobPolicy.Squish
        )
        .toOption
        .get

    assertEquals(scale.mapValues(Vector(-5.0, 15.0)), Vector(Some(0.0), Some(1.0)))
  }

  test("transforms advertise round-trip laws over their domains") {
    assert(Transform.identity.roundTrips(-4.0, 1e-12))
    assert(Transform.sqrt.roundTrips(9.0, 1e-12))
    assert(Transform.log10.roundTrips(100.0, 1e-12))
    assert(Transform.log10.transform(0.0).isLeft)
    assert(Transform.log10.transform(-1.0).isLeft)
  }

  test("transform domains model open and closed endpoints explicitly") {
    val open =
      TransformDomain
        .openClosed("positive", 0.0, 1.0)
        .toOption
        .get
    val closed =
      TransformDomain
        .closed("unit", 0.0, 1.0)
        .toOption
        .get

    assert(!open.contains(0.0))
    assert(open.contains(1.0))
    assert(closed.contains(0.0))
  }

  test("discrete domains preserve declared ordering and append new levels") {
    val trained =
      DiscreteDomain
        .ordered(Vector("low", "mid"))
        .flatMap(_.train(Vector("high", "low")))
        .toOption
        .get

    assertEquals(trained.levels, Vector("low", "mid", "high"))
  }

  test("finite discrete palettes reject over-capacity domains by default") {
    val domain = DiscreteDomain.ordered(Vector("A", "B", "C")).toOption.get
    val palette = DiscretePalette.valuesUnsafe(Vector(Rgba.Black, Rgba.White))

    assertEquals(
      DiscreteScale("condition", domain, palette).left.toOption,
      Some(GraphicsError.DiscretePaletteOverflow("condition", 3, 2))
    )

    val initiallyValid = DiscreteScale(
      "condition",
      DiscreteDomain.ordered(Vector("A", "B")).toOption.get,
      palette
    ).toOption.get
    assertEquals(
      initiallyValid
        .trainPlotWide(Vector(ScaleObservation.discrete("C", CategoryIdentity.strings)))
        .left
        .toOption,
      Some(GraphicsError.DiscretePaletteOverflow("condition", 3, 2))
    )
  }

  test("discrete scale cycles finite palettes only under the explicit overflow policy") {
    val domain = DiscreteDomain.ordered(Vector("A", "B", "C")).toOption.get
    val palette = DiscretePalette.valuesUnsafe(
      Vector(Rgba.Black, Rgba.White),
      PaletteOverflowPolicy.Cycle
    )
    val scale = DiscreteScale("condition", domain, palette).toOption.get

    assertEquals(
      scale.mapLevels(Vector("A", "B", "C", "D")),
      Vector(Some(Rgba.Black), Some(Rgba.White), Some(Rgba.Black), None)
    )
    assertEquals(scale.descriptor.kind, ScaleKind.Discrete)
    assertEquals(
      scale.descriptor.domain,
      ScaleDomain.Discrete(Vector("A", "B", "C"), ordered = true)
    )
    assertEquals(
      scale.mapValueResult("D").left.toOption,
      Some(ScaleMapFailure.OutOfDomain("condition", "D"))
    )
  }

  test("band scales expose checked categorical intervals as domain values") {
    val padding = BandPadding(0.2).fold(e => fail(e.message), identity)
    val domain = DiscreteDomain
      .ordered(Vector("control", "task", "other"))
      .fold(e => fail(e.message), identity)
    val scale = BandScale("condition", domain, padding).fold(e => fail(e.message), identity)

    assertEquals(
      scale.mapLevels(Vector("control", "task", "other", "missing")),
      Vector(Some(0.0), Some(1.0), Some(2.0), None)
    )
    assertEquals(scale.band("control"), Some(Band.unsafe(0.0, 0.8)))
    assertEquals(scale.band("task").map(_.lower), Some(0.6))
    assertEquals(scale.band("task").map(_.upper), Some(1.4))
    assertEquals(scale.descriptor.kind, ScaleKind.Band)
    assertEquals(scale.descriptor.domain, ScaleDomain.Band(domain.levels, ordered = true, padding))
    assertEquals(
      scale.mapValueResult("missing").left.toOption,
      Some(ScaleMapFailure.OutOfDomain("condition", "missing"))
    )
  }

  test("band construction rejects invalid padding, centers, and widths") {
    assertEquals(BandPadding(-0.1).left.toOption, Some(GraphicsError.InvalidBandPadding(-0.1)))
    assertEquals(BandPadding(1.0).left.toOption, Some(GraphicsError.InvalidBandPadding(1.0)))
    BandPadding(Double.NaN).left.toOption match
      case Some(GraphicsError.InvalidBandPadding(value)) => assert(value.isNaN)
      case result => fail(s"expected InvalidBandPadding(NaN), obtained $result")
    assertEquals(
      Band(Double.PositiveInfinity, 0.8).left.toOption,
      Some(GraphicsError.InvalidBand(Double.PositiveInfinity, 0.8))
    )
    assertEquals(Band(0.0, 0.0).left.toOption, Some(GraphicsError.InvalidBand(0.0, 0.0)))
  }

  test("break generators are deterministic functions of intervals") {
    val breaks = Breaks.width(2.0).toOption.get

    assertEquals(breaks(Interval.unsafe(1.0, 6.0)), Vector(2.0, 4.0, 6.0))
    assertEquals(Breaks.countUnsafe(3)(Interval.unsafe(0.0, 10.0)), Vector(0.0, 5.0, 10.0))
  }

  test("checked break generation rejects sub-ULP non-progress on every iterative path") {
    val range = Interval.unsafe(1.0e16, 1.0e16 + 4.0)

    val failures = Vector(
      Breaks.countUnsafe(5).generate(range),
      Breaks.prettyUnsafe().generate(range),
      Breaks.width(0.5).flatMap(_.generate(range))
    )

    failures.foreach { result =>
      assert(result.left.toOption.exists {
        case GraphicsError.BreakGenerationDidNotProgress(_, previous, next) =>
          previous == next && previous >= range.lower
        case _ => false
      })
    }
  }

  test("every break policy has a deterministic output cap and typed overflow") {
    assertEquals(
      Breaks.count(Breaks.MaximumOutputSize + 1).left.toOption,
      Some(
        GraphicsError.BreakOutputLimitExceeded(
          "count",
          Breaks.MaximumOutputSize + 1,
          Breaks.MaximumOutputSize
        )
      )
    )
    assertEquals(
      Breaks.pretty(Breaks.MaximumOutputSize + 1).left.toOption,
      Some(
        GraphicsError.BreakOutputLimitExceeded(
          "pretty",
          Breaks.MaximumOutputSize + 1,
          Breaks.MaximumOutputSize
        )
      )
    )

    val tooManyFixedWidth = Breaks
      .width(1.0)
      .flatMap(_.generate(Interval.unsafe(0.0, Breaks.MaximumOutputSize.toDouble)))
    assertEquals(
      tooManyFixedWidth.left.toOption,
      Some(
        GraphicsError.BreakOutputLimitExceeded(
          "width",
          Breaks.MaximumOutputSize + 1,
          Breaks.MaximumOutputSize
        )
      )
    )
  }

  test("custom break policies receive finite, ordered, and bounded output validation") {
    val duplicate = new Breaks:
      override def apply(range: Interval): Vector[Double] =
        Vector(range.lower, range.lower)

    assertEquals(
      duplicate.generate(Interval.unsafe(2.0, 3.0)).left.toOption,
      Some(GraphicsError.BreakGenerationDidNotProgress("custom", 2.0, 2.0))
    )
  }

  test("default pretty breaks use readable 1/2/5 steps for ordinary ranges") {
    val interval = Interval.unsafe(0.0, 31.0)
    val breaks = Breaks.default(interval)

    assertEquals(breaks, Vector(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0))
    assertEquals(Labeler.default(breaks), Vector("0", "5", "10", "15", "20", "25", "30"))
  }

  test("pretty breaks include zero and platform-stable labels for signed ranges") {
    val breaks = Breaks.prettyUnsafe()(Interval.unsafe(-0.25, 1.0))

    assertEquals(breaks.length, 7)
    assertEqualsDouble(breaks.head, -0.2, 1e-15)
    assertEqualsDouble(breaks.last, 1.0, 1e-15)
    assert(breaks.contains(0.0))
    assertEquals(Labeler.default(breaks), Vector("-0.2", "0", "0.2", "0.4", "0.6", "0.8", "1"))
  }

  test("pretty breaks remain readable for tiny and large ranges") {
    val tiny = Breaks.prettyUnsafe()(Interval.unsafe(-3.0e-6, 7.0e-6))
    val large = Breaks.prettyUnsafe()(Interval.unsafe(1.0e9, 5.0e9))

    assertEquals(Labeler.default(tiny), Vector("-2e-6", "0", "2e-6", "4e-6", "6e-6"))
    assertEquals(
      Labeler.default(large),
      Vector("1000000000", "2000000000", "3000000000", "4000000000", "5000000000")
    )
  }

  test("pretty breaks are scale-equivariant across powers of ten") {
    val base = Breaks.prettyUnsafe()(Interval.unsafe(-3.0, 7.0))
    val scaled = Breaks.prettyUnsafe()(Interval.unsafe(-3.0e6, 7.0e6))

    assertEquals(base.length, scaled.length)
    base.zip(scaled).foreach { case (left, right) =>
      assertEqualsDouble(right, left * 1.0e6, 1e-9)
    }
  }

  test("pretty breaks collapse degenerate ranges and validate target counts") {
    assertEquals(Breaks.prettyUnsafe()(Interval.unsafe(42.0, 42.0)), Vector(42.0))
    assertEquals(Breaks.pretty(0).left.toOption, Some(GraphicsError.InvalidBreakCount(0)))
  }
