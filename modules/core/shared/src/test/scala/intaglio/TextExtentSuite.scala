package intaglio

/** The rotated text extent is pure shared geometry: these run on the JVM and on Scala.js. The 45
  * degree goldens use `sqrt(0.5)`, which IEEE 754 rounds identically everywhere, rather than a trig
  * call whose last bit is platform-dependent.
  */
class TextExtentSuite extends munit.FunSuite:
  private val tol = 1e-9
  private val w = 40.0
  private val h = 10.0

  private def extent(
      anchor: Anchor,
      degrees: Double,
      direction: YDirection = YDirection.Up
  ): TextExtent =
    TextExtent.rotated(w, h, anchor, degrees, direction).orThrow

  private def assertExtent(actual: TextExtent, expected: TextExtent)(using munit.Location): Unit =
    assertEqualsDouble(actual.left, expected.left, tol, clue(actual))
    assertEqualsDouble(actual.top, expected.top, tol, clue(actual))
    assertEqualsDouble(actual.width, expected.width, tol, clue(actual))
    assertEqualsDouble(actual.height, expected.height, tol, clue(actual))

  private val anchors =
    for
      horizontal <- HJust.values.toVector
      vertical <- VJust.values.toVector
    yield Anchor(horizontal, vertical)

  test("unrotated, justification places the layout box the way backends anchor text") {
    val expectedLeft = Map(HJust.Left -> 0.0, HJust.Center -> -20.0, HJust.Right -> -40.0)
    val expectedTop = Map(VJust.Top -> 0.0, VJust.Center -> -5.0, VJust.Bottom -> -10.0)
    anchors.foreach { anchor =>
      val expected =
        TextExtent(expectedLeft(anchor.horizontal), expectedTop(anchor.vertical), w, h)
      assertExtent(extent(anchor, 0.0), expected)
      assertExtent(TextExtent.anchored(w, h, anchor).orThrow, expected)
    }
    assertEqualsDouble(extent(Anchor.BottomLeft, 0.0).right, 40.0, tol)
    assertEqualsDouble(extent(Anchor.BottomLeft, 0.0).bottom, 0.0, tol)
  }

  test("90 degrees in a y-up scene reads upward, the box above and left of a bottom-left anchor") {
    // Device rotation is -90 (counterclockwise on screen): the advance runs up the screen and the
    // text's top faces left.
    assertExtent(extent(Anchor.BottomLeft, 90.0), TextExtent(-10.0, -40.0, 10.0, 40.0))
    assertExtent(extent(Anchor(HJust.Right, VJust.Top), 90.0), TextExtent(0.0, 0.0, 10.0, 40.0))
    assertExtent(extent(Anchor.Center, 90.0), TextExtent(-5.0, -20.0, 10.0, 40.0))
    // Right-justified, centred vertically: the run ends at the anchor and extends downward.
    assertExtent(
      extent(Anchor(HJust.Right, VJust.Center), 90.0),
      TextExtent(-5.0, 0.0, 10.0, 40.0)
    )
  }

  test("-90 degrees reads downward, the box below and right of a bottom-left anchor") {
    assertExtent(extent(Anchor.BottomLeft, -90.0), TextExtent(0.0, 0.0, 10.0, 40.0))
    assertExtent(
      extent(Anchor(HJust.Right, VJust.Top), -90.0),
      TextExtent(-10.0, -40.0, 10.0, 40.0)
    )
    assertExtent(extent(Anchor.Center, -90.0), TextExtent(-5.0, -20.0, 10.0, 40.0))
  }

  test("180 degrees mirrors the box through the anchor") {
    anchors.foreach { anchor =>
      val upright = extent(anchor, 0.0)
      assertExtent(
        extent(anchor, 180.0),
        TextExtent(-upright.right, -upright.bottom, upright.width, upright.height)
      )
    }
  }

  test("45 degrees: the extent is (w + h) / sqrt 2 on both axes, placed by the rotated anchor") {
    val r = math.sqrt(0.5)
    val side = (w + h) * r
    assertExtent(extent(Anchor.Center, 45.0), TextExtent(-side / 2.0, -side / 2.0, side, side))
    // Bottom-left anchor, counterclockwise on screen (device angle -45): the local corners
    // (0,-h) (w,-h) (0,0) (w,0) map to (x, y) = (u r + v r, -u r + v r).
    val xs = Vector(-h * r, (w - h) * r, 0.0, w * r)
    val ys = Vector(-h * r, (-w - h) * r, 0.0, -w * r)
    assertExtent(
      extent(Anchor.BottomLeft, 45.0),
      TextExtent(xs.min, ys.min, xs.max - xs.min, ys.max - ys.min)
    )
  }

  test("a y-down frame turns the other way, and angles are periodic") {
    anchors.foreach { anchor =>
      assertExtent(extent(anchor, 90.0, YDirection.Down), extent(anchor, -90.0))
      assertExtent(extent(anchor, 30.0, YDirection.Down), extent(anchor, -30.0))
      assertExtent(extent(anchor, 450.0), extent(anchor, 90.0))
      assertExtent(extent(anchor, -270.0), extent(anchor, 90.0))
      assertExtent(extent(anchor, -720.0), extent(anchor, 0.0))
    }
    assertEqualsDouble(TextExtent.deviceDegrees(30.0, YDirection.Up), -30.0, tol)
    assertEqualsDouble(TextExtent.deviceDegrees(30.0, YDirection.Down), 30.0, tol)
  }

  test("at every angle the extent is w|cos| + h|sin| by w|sin| + h|cos| and contains the anchor") {
    for
      anchor <- anchors
      step <- 0 until 72
    do
      val degrees = step * 5.0
      val box = extent(anchor, degrees)
      val radians = degrees * math.Pi / 180.0
      val c = math.abs(math.cos(radians))
      val s = math.abs(math.sin(radians))
      assertEqualsDouble(box.width, w * c + h * s, 1e-9, clue((anchor, degrees)))
      assertEqualsDouble(box.height, w * s + h * c, 1e-9, clue((anchor, degrees)))
      assert(box.left <= 1e-9 && box.right >= -1e-9, clue((anchor, degrees, box)))
      assert(box.top <= 1e-9 && box.bottom >= -1e-9, clue((anchor, degrees, box)))
      if anchor == Anchor.Center then
        assertEqualsDouble(box.left, -box.width / 2.0, 1e-9)
        assertEqualsDouble(box.top, -box.height / 2.0, 1e-9)
  }

  test("multiples of 90 degrees are exact") {
    val box = extent(Anchor.BottomLeft, 90.0)
    assert(box.left == -10.0 && box.top == -40.0 && box.width == 10.0 && box.height == 40.0, box)
    assert(extent(Anchor.BottomLeft, 270.0).width == 10.0)
  }

  test("invalid input is a typed error") {
    assert(
      TextExtent.rotated(w, h, Anchor.Center, Double.NaN).left.exists {
        case GraphicsError.InvalidRotation(value) => value.isNaN
        case _                                    => false
      }
    )
    assert(TextExtent.rotated(w, h, Anchor.Center, Double.PositiveInfinity).isLeft)
    assert(
      TextExtent.rotated(-1.0, h, Anchor.Center, 0.0).left.exists {
        case GraphicsError.InvalidExtent(_) => true
        case _                              => false
      }
    )
    assert(TextExtent.rotated(w, Double.NaN, Anchor.Center, 0.0).isLeft)
    assertEquals(TextExtent.rotated(0.0, 0.0, Anchor.Center, 33.0).map(_.width), Right(0.0))
  }

  test("measure takes the provider's width and height, and reports a throwing provider") {
    val style = TextStyle(None, 12.0)
    val label = "Header"
    val measured = TextExtent.measure(TextMetrics.estimate, label, style, Anchor.BottomLeft, 90.0)
    val expected = TextExtent
      .rotated(
        TextMetrics.estimate.widthPt(label, style),
        TextMetrics.estimate.heightPt(style),
        Anchor.BottomLeft,
        90.0
      )
      .orThrow
    assertEquals(measured, Right(expected))
    val throwing = new TextMetrics:
      def widthPt(text: String, fontSizePt: Double): Double = throw new IllegalStateException("no")
      def heightPt(fontSizePt: Double): Double = 1.0
    assert(
      TextExtent.measure(throwing, label, style).left.exists {
        case GraphicsError.LayoutMeasurementFailed(_, _) => true
        case _                                           => false
      }
    )
  }

  test("the angle convention is the one scene lowering hands to backends") {
    def loweredDegrees(degrees: Double, direction: YDirection): Double =
      val text = Grob.text("label", Point.npcUnsafe(0.5, 0.5), rotationDegrees = degrees).orThrow
      val parent = Viewport.unsafe(clip = Clip.Off, yDirection = direction)
      val scene = Scene(Vector(Grob.group(Vector(text), viewport = Some(parent))))
      DeviceScene.fromScene(scene, DeviceContext.unsafe(100, 100)).orThrow.elements match
        case Vector(
              DeviceElement.Group(
                _,
                _,
                _,
                Vector(DeviceElement.Mark(run: DevicePrimitive.TextRun))
              )
            ) =>
          run.rotationDegrees
        case other => fail(s"unexpected device elements: $other")

    for
      direction <- Vector(YDirection.Up, YDirection.Down)
      degrees <- Vector(0.0, 90.0, -90.0, 45.0, 180.0, 30.0)
    do
      assertEqualsDouble(
        loweredDegrees(degrees, direction),
        TextExtent.deviceDegrees(degrees, direction),
        0.0
      )
  }
