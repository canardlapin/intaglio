package intaglio

/** Aspect-preserving viewports judged on the resolved device scene: the drawn image, the resolved
  * viewport frame, and its inverse mapping must describe the same rectangle.
  */
class ViewportAspectSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  private val widescreen = 16.0 / 9.0
  private val name = GraphicsName.unsafe("frame")
  private val raster = RasterImage.solid(RasterDimensions.unsafe(16, 9), Rgba32.unsafe(1, 2, 3))

  private def scene(aspect: ViewportAspect, clip: Clip = Clip.On): Scene =
    val viewport = Viewport.unsafe(xScale = Interval.unsafe(0.0, 16.0), clip = clip)
    Scene(
      Vector(
        ok(
          Grob.image(
            raster,
            Point.npcUnsafe(0.5, 0.5),
            Size.npcUnsafe(1.0, 1.0),
            viewport = Some(viewport.withAspect(aspect)),
            name = Some(name)
          )
        )
      )
    )

  private def drawn(aspect: ViewportAspect, width: Int, height: Int): (DeviceScene, Rect) =
    val device = ok(DeviceScene.fromScene(scene(aspect), RenderContext.unsafe(width, height)))
    val image = device.elements.collectFirst {
      case DeviceElement.Group(_, _, _, Vector(DeviceElement.Mark(i: DevicePrimitive.Image))) =>
        Rect(i.x, i.y, i.width, i.height)
    }
    (device, image.getOrElse(fail("missing image")))

  final case class Rect(x: Double, y: Double, width: Double, height: Double)

  private def assertRect(obtained: Rect, expected: Rect)(using munit.Location): Unit =
    assertEqualsDouble(obtained.x, expected.x, 1e-9)
    assertEqualsDouble(obtained.y, expected.y, 1e-9)
    assertEqualsDouble(obtained.width, expected.width, 1e-9)
    assertEqualsDouble(obtained.height, expected.height, 1e-9)

  test("a 16:9 frame in a 4:3 extent is letterboxed at the requested alignment") {
    // 400x300 extent: the fitted 16:9 frame is 400x225 with 75 px of vertical slack.
    val expectations = Vector(
      VJust.Top -> 0.0,
      VJust.Center -> 37.5,
      VJust.Bottom -> 75.0
    )
    expectations.foreach { (vertical, top) =>
      val (_, image) = drawn(ViewportAspect.unsafe(widescreen, vertical = vertical), 400, 300)
      assertRect(image, Rect(0.0, top, 400.0, 225.0))
    }
  }

  test("a tall extent places the frame along the horizontal slack") {
    // 300x400: fitted width 300, height 168.75; a 4:3 frame in 16:9 has horizontal slack instead.
    val (_, left) = drawn(ViewportAspect.unsafe(4.0 / 3.0, horizontal = HJust.Left), 640, 360)
    assertRect(left, Rect(0.0, 0.0, 480.0, 360.0))
    val (_, right) = drawn(ViewportAspect.unsafe(4.0 / 3.0, horizontal = HJust.Right), 640, 360)
    assertRect(right, Rect(160.0, 0.0, 480.0, 360.0))
  }

  test("resizing keeps the aspect ratio exactly") {
    Vector((400, 300), (1280, 720), (333, 777), (1000, 101)).foreach { (w, h) =>
      Vector(AspectMode.Fit, AspectMode.Fill).foreach { mode =>
        val (_, image) = drawn(ViewportAspect.unsafe(widescreen, mode), w, h)
        assertEqualsDouble(image.width / image.height, widescreen, 1e-12)
        mode match
          case AspectMode.Fit =>
            assert(image.width <= w + 1e-9 && image.height <= h + 1e-9)
          case AspectMode.Fill =>
            assert(image.width >= w - 1e-9 && image.height >= h - 1e-9)
      }
    }
  }

  test("fill covers the extent and clips to the extent, not the larger content frame") {
    val (device, image) = drawn(ViewportAspect.unsafe(widescreen, AspectMode.Fill), 400, 300)
    assertRect(image, Rect(-(533.3333333333334 - 400.0) / 2.0, 0.0, 533.3333333333334, 300.0))
    val clip = device.elements.collectFirst { case DeviceElement.Group(_, Some(c), _, _) => c }
    assertEquals(clip, Some(DeviceClip(0.0, 0.0, 400.0, 300.0)))
  }

  test("fit clips to the fitted frame, so the letterbox bars stay empty") {
    val (device, image) = drawn(ViewportAspect.unsafe(widescreen), 400, 300)
    val clip = device.elements.collectFirst { case DeviceElement.Group(_, Some(c), _, _) => c }
    assertEquals(clip, Some(DeviceClip(image.x, image.y, image.width, image.height)))
  }

  test("the resolved frame and its inverse mapping match the drawn image") {
    Vector(AspectMode.Fit, AspectMode.Fill).foreach { mode =>
      val (device, image) = drawn(ViewportAspect.unsafe(widescreen, mode), 400, 300)
      val frame = device.frame(name).fold(e => fail(e.message), identity)
      assertRect(
        Rect(frame.frame.x, frame.frame.y, frame.frame.width, frame.frame.height),
        image
      )
      // The image's top-left device corner is native (0, 1) in a y-up frame over x in [0, 16].
      val corner = frame.deviceToNative(DevicePoint(image.x, image.y))
      assertEquals(corner.map(p => (math.rint(p.x * 1e9) / 1e9, p.y)), Right((0.0, 1.0)))
      val centre = frame.nativeToDevice(DevicePoint(8.0, 0.5)).fold(e => fail(e.message), identity)
      assertEqualsDouble(centre.x, image.x + image.width / 2.0, 1e-9)
      assertEqualsDouble(centre.y, image.y + image.height / 2.0, 1e-9)
    }
  }

  test("an aspect from equal native units uses the scale ranges") {
    val aspect = ok(ViewportAspect.ofScales(Interval.unsafe(0.0, 1920.0), Interval.unsafe(0, 1080)))
    assertEqualsDouble(aspect.ratio, widescreen, 1e-15)
  }

  test("a non-positive or non-finite ratio is a typed error") {
    Vector(0.0, -1.0, Double.NaN, Double.PositiveInfinity).foreach { ratio =>
      assert(ViewportAspect(ratio).isLeft, s"ratio $ratio accepted")
    }
    assert(ViewportAspect.ofScales(Interval.unsafe(0.0, 1.0), Interval.unsafe(2.0, 2.0)).isLeft)
  }

  test("a viewport without an aspect resolves as before") {
    val viewport = Viewport.unsafe(size = Size.npcUnsafe(0.5, 0.5))
    val device = DeviceContext.unsafe(400, 300)
    val resolver = LengthResolver(device, DeviceFrame.root(device))
    val plain = ok(resolver.childFrame(viewport))
    val stretched = ok(
      resolver.childFrame(viewport.withAspect(ViewportAspect.unsafe(1.0)).withoutAspect)
    )
    assertEquals(stretched, plain)
    assertEquals((plain.width, plain.height), (200.0, 150.0))
  }
