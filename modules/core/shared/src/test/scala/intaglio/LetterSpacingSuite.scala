package intaglio

class LetterSpacingSuite extends munit.FunSuite:
  test(
    "requested tracking rejects malformed Unicode and multiline text through the checked compiler"
  ) {
    def directDevice(label: String, spacing: Double): Either[GraphicsError, DeviceScene] =
      val params =
        GraphicParams.unsafe().withLetterSpacing(LetterSpacing.devicePixelsUnsafe(spacing))
      DeviceScene.validate(
        DeviceScene(
          100,
          100,
          Vector(
            DeviceElement.Mark(
              DevicePrimitive.TextRun(
                label,
                10,
                10,
                HJust.Left,
                VJust.Top,
                0,
                12,
                None,
                params,
                None
              )
            )
          )
        )
      )
    for label <- Vector("a\uD800", "one\ntwo") do
      val gp = GraphicParams.unsafe().withLetterSpacing(LetterSpacing.pointsUnsafe(1))
      val plan = RenderPlan(
        Scene(Vector(Grob.textUnsafe(label, Point.npcUnsafe(0.5, 0.5), gp = gp))),
        RenderContext.unsafe()
      )
      assert(plan.deviceScene.left.exists(_.isInstanceOf[GraphicsError.InvalidTextTracking]))
      assert(directDevice(label, 1).left.exists(_.isInstanceOf[GraphicsError.InvalidTextTracking]))
      assert(directDevice(label, 0).isRight, "zero tracking preserves ordinary text validation")
    assertEquals(
      TextMetrics.estimate.widthPt("one\ntwo", TextStyle(None, 12)),
      TextMetrics.estimate.widthPt("one\ntwo", 12)
    )
  }
  test("letter spacing accepts finite signed values and rejects non-finite values") {
    assertEquals(LetterSpacing.points(-0.5).map(_.value), Right(-0.5))
    assert(LetterSpacing.devicePixels(Double.NaN).isLeft)
    assert(LetterSpacing.points(Double.PositiveInfinity).isLeft)
    intercept[IllegalArgumentException] {
      TextStyle(None, 10.0, letterSpacingPt = Double.NegativeInfinity)
    }
  }

  test("device resolution converts letter spacing independently of the stroke") {
    val params = GraphicParams
      .unsafe(lineWidth = 3.0, lineWidthUnit = StrokeUnit.Point)
      .withLetterSpacing(LetterSpacing.pointsUnsafe(-1.5))
    val resolver = new LengthResolver(
      DeviceContext.unsafe(100.0, 100.0, pixelsPerInch = 144.0),
      DeviceFrame.root(DeviceContext.unsafe(100.0, 100.0, pixelsPerInch = 144.0))
    )

    val resolved = resolver.graphicParams(params).fold(error => fail(error.message), identity)
    assertEqualsDouble(resolved.lineWidth, 6.0, 1e-12)
    assertEquals(resolved.letterSpacing, Some(LetterSpacing.devicePixelsUnsafe(-3.0)))
  }

  test("text-style compatibility copy and aesthetic overrides preserve letter spacing") {
    val spacing = LetterSpacing.devicePixelsUnsafe(2.0)
    val params = GraphicParams.unsafe().withLetterSpacing(spacing)
    assertEquals(TextStyle(None, 10.0, None, 1.5).copy(None, 11.0, None).letterSpacingPt, 1.5)
    assertEquals(
      params.withAestheticOverrides(fill = Some(Rgba.White)).map(_.letterSpacing),
      Right(Some(spacing))
    )
  }

  test("zero spacing preserves default text styles") {
    val style = TextStyle(None, 12.0)
    assertEquals(style.letterSpacingPt, 0.0)
    assertEquals(LayoutPolicy().axisTextStyle.letterSpacingPt, 0.0)
  }

  test("generic metrics fail explicitly when tracking was requested") {
    val metrics = new TextMetrics:
      def widthPt(text: String, fontSizePt: Double): Double = text.length * fontSizePt
      def heightPt(fontSizePt: Double): Double = fontSizePt

    intercept[UnsupportedOperationException] {
      metrics.widthPt("ab", TextStyle(None, 10.0, letterSpacingPt = 1.0))
    }
  }

  test("the conservative estimator includes tracking between Unicode tracking units") {
    val style = TextStyle(None, 10.0, letterSpacingPt = 1.25)
    assertEqualsDouble(
      TextMetrics.estimate.widthPt("ab", style),
      TextMetrics.estimate.widthPt("ab", 10.0) + 1.25,
      1e-12
    )
  }

  test("theme layout policy resolves physical and device spacing in points") {
    val device = DeviceContext.unsafe(640.0, 480.0, pixelsPerInch = 144.0)
    val axisText = GraphicParams
      .unsafe(stroke = None, fontSize = Length.pointsUnsafe(10.0))
      .withLetterSpacing(LetterSpacing.devicePixelsUnsafe(3.0))
    val theme = Theme.default.copy(axis = Theme.default.axis.copy(text = axisText))

    assertEqualsDouble(
      theme.layoutPolicy(LayoutPolicy(referenceDevice = device)).axisLetterSpacingPt,
      1.5,
      1e-12
    )
  }
