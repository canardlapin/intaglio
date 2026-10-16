package intaglio

class BivariateEffectConfidenceSuite extends munit.FunSuite:
  private val blue = Rgba32.unsafe(0, 0, 200)
  private val red = Rgba32.unsafe(200, 0, 0)
  private val effect = ScalarMapping(
    ScalarScale.sequential(DisplayWindow.unsafe(-1.0, 1.0), ScalarRamp.linear(blue, red))
  )
  private val mapping = BivariateEffectConfidenceMapping(
    effect,
    BivariateConfidencePolicy.SurfViewJsConfidence(BivariateTextureSize.unsafe(3))
  )
  private val effectTitle = LegendTitle.make("Effect", Some("z")).toOption.get
  private val confidenceTitle = LegendTitle.make("Confidence").toOption.get
  private val effectTicks = Vector(
    AxisTick.unsafe(-1.0, "-1"),
    AxisTick.unsafe(0.0, "0"),
    AxisTick.unsafe(1.0, "1")
  )
  private val confidenceTicks = Vector(AxisTick.unsafe(0.0, "low"), AxisTick.unsafe(1.0, "high"))

  test("SurfViewJS confidence policy uses discrete HSV texture samples with opaque alpha"):
    assertEquals(mapping.color(-1.0, 0.0), Rgba32.unsafe(255, 255, 255))
    assertEquals(mapping.color(-1.0, 1.0), Rgba32.unsafe(255, 0, 0))
    // SurfViewJS fb60f370 ColorMap2D.getColor returns Float32 0.699999988079071,
    // which rounds to 178 after multiplication by 255 (not double-precision 179).
    assertEquals(mapping.color(0.0, 0.5), Rgba32.unsafe(128, 255, 178))
    assertEquals(mapping.color(1.0, 1.0), Rgba32.unsafe(204, 0, 255))
    assertEquals(mapping.color(-0.1, 0.75), Rgba32.unsafe(255, 128, 128))
    assertEquals(mapping.referenceCoordinate(0.0, 0.5), Some(BivariateTextureCoordinate(1, 1)))
    assertEquals(mapping.color(-4.0, 2.0), Rgba32.unsafe(255, 0, 0))
    assertEquals(mapping.color(Double.NaN, 1.0), Rgba32.unsafe(0, 0, 0, 0))
    assertEquals(mapping.color(0.0, Double.PositiveInfinity), Rgba32.unsafe(0, 0, 0, 0))

  test("reference texture size controls interior HSV texels"):
    val sizeFive = BivariateEffectConfidenceMapping(
      effect,
      BivariateConfidencePolicy.SurfViewJsConfidence(BivariateTextureSize.unsafe(5))
    )

    assertEquals(sizeFive.referenceCoordinate(0.5, 0.6), Some(BivariateTextureCoordinate(3, 2)))
    assertEquals(sizeFive.color(0.5, 0.6), Rgba32.unsafe(128, 178, 255))

  test("confidence alpha remains an explicit non-reference alternative"):
    val alphaMapping =
      BivariateEffectConfidenceMapping(effect, BivariateConfidencePolicy.ConfidenceAlpha)

    assertEquals(alphaMapping.color(0.0, 0.5), Rgba32.unsafe(100, 0, 100, 128))
    assertEquals(alphaMapping.color(0.0, -0.01), Rgba32.unsafe(0, 0, 0, 0))
    assertEquals(alphaMapping.referenceCoordinate(0.0, 0.5), None)

  test("key pixels and both axes agree with direct mapping at corners and center"):
    val key = BivariateEffectConfidenceKey
      .make(
        mapping,
        effectTitle,
        confidenceTitle,
        effectTicks,
        confidenceTicks,
        width = 3,
        height = 3
      )
      .toOption
      .get
    val coordinates = Vector(0 -> 0, 2 -> 0, 0 -> 2, 2 -> 2, 1 -> 1)

    assertEquals(key.effectTitle.text, "Effect (z)")
    assertEquals(key.confidenceTitle.text, "Confidence")
    assertEquals(key.effectTicks.map(_.label), Vector("-1", "0", "1"))
    assertEquals(key.confidenceTicks.map(_.label), Vector("low", "high"))
    coordinates.foreach: (column, row) =>
      assertEquals(
        key.pixelAt(column, row),
        mapping.color(key.effectAt(column), key.confidenceAt(row))
      )
      assertEquals(
        key.colorAt(key.effectAt(column), key.confidenceAt(row)),
        key.pixelAt(column, row)
      )
    assertEquals(key.pixelAt(0, 0), Rgba32.unsafe(255, 0, 0))
    assertEquals(key.pixelAt(2, 2), Rgba32.unsafe(255, 255, 255))

  test("key identity binds the mapping and labeled axes"):
    val key = BivariateEffectConfidenceKey
      .make(
        mapping,
        effectTitle,
        confidenceTitle,
        effectTicks,
        confidenceTicks,
        width = 3,
        height = 3
      )
      .toOption
      .get
    val relabeled = BivariateEffectConfidenceKey
      .make(
        mapping,
        effectTitle,
        confidenceTitle,
        effectTicks,
        Vector(AxisTick.unsafe(0.0, "none"), AxisTick.unsafe(1.0, "certain")),
        width = 3,
        height = 3
      )
      .toOption
      .get

    assert(key.canonicalKey.contains(mapping.canonicalKey))
    assertNotEquals(key.canonicalKey, relabeled.canonicalKey)
    assertNotEquals(
      mapping.canonicalKey,
      BivariateEffectConfidenceMapping(
        effect.copy(outOfRange = ScalarOutOfRange.Hide),
        mapping.policy
      ).canonicalKey
    )

  test("key construction validates grid and tick domains"):
    assert(
      BivariateEffectConfidenceKey
        .make(mapping, effectTitle, confidenceTitle, effectTicks, confidenceTicks, width = 1)
        .isLeft
    )
    assert(
      BivariateEffectConfidenceKey
        .make(
          mapping,
          effectTitle,
          confidenceTitle,
          Vector(AxisTick.unsafe(2.0, "outside")),
          confidenceTicks
        )
        .isLeft
    )
    assert(
      BivariateEffectConfidenceKey
        .make(
          mapping,
          effectTitle,
          confidenceTitle,
          effectTicks,
          Vector(AxisTick.unsafe(-0.1, "outside"))
        )
        .isLeft
    )
    assert(BivariateTextureSize.make(1).isLeft)
