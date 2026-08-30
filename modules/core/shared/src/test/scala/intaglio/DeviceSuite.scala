package intaglio

class DeviceSuite extends munit.FunSuite:
  private val tol = 1e-9

  private val device = DeviceContext.unsafe(200.0, 100.0)

  private def rootResolver: LengthResolver =
    LengthResolver(device, DeviceFrame.root(device))

  test("device context rejects invalid sizes and resolutions") {
    assertEquals(
      DeviceContext(0.0, 100.0).left.toOption,
      Some(GraphicsError.InvalidDeviceSize(0.0, 100.0))
    )
    assertEquals(
      DeviceContext(100.0, -1.0).left.toOption,
      Some(GraphicsError.InvalidDeviceSize(100.0, -1.0))
    )
    assertEquals(
      DeviceContext(100.0, 100.0, pixelsPerInch = 0.0).left.toOption,
      Some(GraphicsError.InvalidDeviceResolution(0.0))
    )
  }

  test("npc locations resolve against the frame with y flipped upward") {
    val r = rootResolver
    assertEqualsDouble(r.x(LengthExpr.npcUnsafe(0.25)).toOption.get, 50.0, tol)
    assertEqualsDouble(r.y(LengthExpr.npcUnsafe(0.25)).toOption.get, 75.0, tol)
    assertEqualsDouble(r.y(LengthExpr.npcUnsafe(0.0)).toOption.get, 100.0, tol)
    assertEqualsDouble(r.y(LengthExpr.npcUnsafe(1.0)).toOption.get, 0.0, tol)
  }

  test("absolute units convert at 96 pixels per inch") {
    val r = rootResolver
    assertEqualsDouble(r.x(LengthExpr(Length.pointsUnsafe(36.0))).toOption.get, 48.0, tol)
    assertEqualsDouble(r.x(LengthExpr(Length.unsafe(1.0, LengthUnit.Inch))).toOption.get, 96.0, tol)
    assertEqualsDouble(r.x(LengthExpr(Length.unsafe(2.54, LengthUnit.Cm))).toOption.get, 96.0, tol)
    assertEqualsDouble(r.x(LengthExpr(Length.unsafe(25.4, LengthUnit.Mm))).toOption.get, 96.0, tol)
    assertEqualsDouble(r.y(LengthExpr(Length.pointsUnsafe(18.0))).toOption.get, 76.0, tol)
  }

  test("add, subtract, and multiply combine linearly for locations") {
    val r = rootResolver
    val expr = LengthExpr.npcUnsafe(0.5) + LengthExpr(Length.pointsUnsafe(9.0))
    assertEqualsDouble(r.x(expr).toOption.get, 112.0, tol)
    val down = LengthExpr.npcUnsafe(0.5) - LengthExpr.Mul(2.0, LengthExpr(Length.pointsUnsafe(6.0)))
    assertEqualsDouble(r.y(down).toOption.get, 66.0, tol)
  }

  test("mixed-unit multiplication resolves numerically") {
    val expr = (LengthExpr.npcUnsafe(0.5) + LengthExpr.nativeUnsafe(1.0)).times(2.0).toOption.get
    assertEqualsDouble(rootResolver.x(expr).toOption.get, 600.0, tol)
  }

  test("native locations and extents resolve differently") {
    val frame = DeviceFrame(
      x = 20.0,
      y = 40.0,
      width = 100.0,
      height = 40.0,
      xScale = Interval.unsafe(0.0, 10.0),
      yScale = Interval.unsafe(-1.0, 1.0),
      yDirection = YDirection.Up
    )
    val r = LengthResolver(device, frame)
    assertEqualsDouble(r.x(LengthExpr.nativeUnsafe(2.0)).toOption.get, 40.0, tol)
    assertEqualsDouble(r.width(LengthExpr.nativeUnsafe(2.0)).toOption.get, 20.0, tol)
    assertEqualsDouble(r.y(LengthExpr.nativeUnsafe(-1.0)).toOption.get, 80.0, tol)
    assertEqualsDouble(r.y(LengthExpr.nativeUnsafe(1.0)).toOption.get, 40.0, tol)
    assertEqualsDouble(r.height(LengthExpr.nativeUnsafe(0.5)).toOption.get, 10.0, tol)
  }

  test("location offsets resolve native terms as extents") {
    val frame = DeviceFrame(
      x = 0.0,
      y = 0.0,
      width = 100.0,
      height = 100.0,
      xScale = Interval.unsafe(10.0, 20.0),
      yScale = Interval.unsafe(10.0, 20.0),
      yDirection = YDirection.Up
    )
    val r = LengthResolver(DeviceContext.unsafe(100.0, 100.0), frame)
    val right = LengthExpr.nativeUnsafe(12.0) + ExtentExpr.nativeUnsafe(1.0)
    val down = LengthExpr.nativeUnsafe(12.0) - ExtentExpr.pointsUnsafe(7.5)

    assertEqualsDouble(r.x(right).toOption.get, 30.0, tol)
    assertEqualsDouble(r.y(down).toOption.get, 90.0, tol)
  }

  test("axis-neutral extents take the smaller of width and height resolutions") {
    val r = rootResolver
    assertEqualsDouble(r.extent(ExtentExpr.npcUnsafe(0.1)).toOption.get, 10.0, tol)
    assertEqualsDouble(r.extent(ExtentExpr.pointsUnsafe(6.0)).toOption.get, 8.0, tol)
  }

  test("degenerate native scales resolve extents to zero and locations to midpoints") {
    val frame = DeviceFrame(
      0.0,
      0.0,
      100.0,
      100.0,
      Interval.unsafe(3.0, 3.0),
      Interval.unsafe(0.0, 1.0),
      YDirection.Up
    )
    val r = LengthResolver(device, frame)
    assertEqualsDouble(r.width(LengthExpr.nativeUnsafe(1.0)).toOption.get, 0.0, tol)
    assertEqualsDouble(r.x(LengthExpr.nativeUnsafe(3.0)).toOption.get, 50.0, tol)
  }

  test("line units resolve from contextual line height while npc font sizes remain invalid") {
    val r = LengthResolver(device, DeviceFrame.root(device), lineHeightPt = 18.0)
    assertEqualsDouble(r.x(LengthExpr.linesUnsafe(1.0)).toOption.get, 24.0, tol)
    assertEqualsDouble(r.width(ExtentExpr.linesUnsafe(1.5)).toOption.get, 36.0, tol)
    assertEqualsDouble(r.fontSize(Length.linesUnsafe(0.5)).toOption.get, 12.0, tol)
    assert(r.fontSize(Length.unsafe(0.5, LengthUnit.Npc)).left.toOption.exists {
      case GraphicsError.UnresolvableLength(_) => true
      case _                                   => false
    })
    assertEqualsDouble(r.fontSize(Length.pointsUnsafe(12.0)).toOption.get, 16.0, tol)
  }

  test("device lowering distinguishes literal-pixel and physical-point stroke widths") {
    val points = Vector(Point.npcUnsafe(0.1, 0.25), Point.npcUnsafe(0.9, 0.25))
    val pixelLine = Grob
      .lines(
        points,
        gp = GraphicParams.unsafe(lineWidth = 2.0),
        name = Some(GraphicsName.unsafe("pixel-stroke"))
      )
      .fold(error => fail(error.message), identity)
    val pointLine = Grob
      .lines(
        points,
        gp = GraphicParams
          .unsafe(lineWidth = 2.0)
          .withStrokeWidth(StrokeWidth.pointsUnsafe(2.0)),
        name = Some(GraphicsName.unsafe("point-stroke"))
      )
      .fold(error => fail(error.message), identity)
    val context = RenderContext.unsafe(200, 100, pixelsPerInch = 144.0)
    val scene = DeviceScene
      .fromScene(Scene(Vector(pixelLine, pointLine)), context)
      .fold(error => fail(error.message), identity)
    val widths = scene.elements.collect {
      case DeviceElement.Mark(DevicePrimitive.Polyline(_, _, gp, name)) =>
        (name.map(_.value), gp.lineWidth, gp.lineWidthUnit)
    }

    assertEquals(
      widths,
      Vector(
        (Some("pixel-stroke"), 2.0, StrokeUnit.DevicePixel),
        (Some("point-stroke"), 4.0, StrokeUnit.DevicePixel)
      )
    )
  }

  test("DeviceScene receives the render context's line height for extents and fonts") {
    val circle = Grob.circleUnsafe(
      Point.npcUnsafe(0.25, 0.5),
      ExtentExpr.linesUnsafe(1.0),
      name = Some(GraphicsName.unsafe("line-radius"))
    )
    val text = Grob.textUnsafe(
      "line font",
      Point.npcUnsafe(0.75, 0.5),
      gp = GraphicParams.unsafe(fontSize = Length.linesUnsafe(0.5)),
      name = Some(GraphicsName.unsafe("line-font"))
    )
    val context = RenderContext.unsafe(
      200,
      100,
      pixelsPerInch = 144.0,
      lineHeightPt = 18.0
    )
    val scene = DeviceScene
      .fromScene(Scene(Vector(circle, text)), context)
      .fold(error => fail(error.message), identity)

    val radius = scene.elements.collectFirst {
      case DeviceElement.Mark(DevicePrimitive.Disc(_, _, value, _, name))
          if name.exists(_.value == "line-radius") =>
        value
    }
    val fontSize = scene.elements.collectFirst {
      case DeviceElement.Mark(DevicePrimitive.TextRun(_, _, _, _, _, _, value, _, _, name))
          if name.exists(_.value == "line-font") =>
        value
    }
    assertEquals(radius, Some(36.0))
    assertEquals(fontSize, Some(18.0))
  }

  test("child frames resolve with lower-left origins in y-up parents") {
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.2),
      size = Size.npcUnsafe(0.5, 0.4),
      xScale = Interval.unsafe(-1.0, 1.0),
      yScale = Interval.unsafe(0.0, 10.0)
    )
    val frame = rootResolver.childFrame(viewport).toOption.get
    assertEqualsDouble(frame.x, 20.0, tol)
    assertEqualsDouble(frame.y, 40.0, tol)
    assertEqualsDouble(frame.width, 100.0, tol)
    assertEqualsDouble(frame.height, 40.0, tol)
    assertEquals(frame.yDirection, YDirection.Up)
  }

  test("y-down viewports place scene y downward") {
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.0, 0.0),
      size = Size.npcUnsafe(1.0, 1.0),
      yScale = Interval.unsafe(0.0, 10.0),
      yDirection = YDirection.Down
    )
    val frame = rootResolver.childFrame(viewport).toOption.get
    val r = LengthResolver(device, frame)
    assertEqualsDouble(r.y(LengthExpr.nativeUnsafe(0.0)).toOption.get, 0.0, tol)
    assertEqualsDouble(r.y(LengthExpr.nativeUnsafe(10.0)).toOption.get, 100.0, tol)
  }

  test("scenes lower to numeric device primitives with clip and orientation") {
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.2),
      size = Size.npcUnsafe(0.5, 0.4),
      xScale = Interval.unsafe(-1.0, 1.0),
      yScale = Interval.unsafe(0.0, 10.0),
      clip = Clip.On
    )
    val grob = Grob
      .lines(
        Vector(Point.nativeUnsafe(-1.0, 0.0), Point.nativeUnsafe(1.0, 10.0)),
        viewport = Some(viewport),
        name = Some(GraphicsName.unsafe("native-line"))
      )
      .toOption
      .get
    val scene = DeviceScene.fromScene(Scene(Vector(grob)), device).toOption.get

    scene.elements match
      case Vector(
            DeviceElement.Group(
              name,
              Some(clip),
              None,
              Vector(DeviceElement.Mark(polyline: DevicePrimitive.Polyline))
            )
          ) =>
        assertEquals(name.map(_.value), Some("native-line"))
        assertEqualsDouble(clip.x, 20.0, tol)
        assertEqualsDouble(clip.y, 40.0, tol)
        assertEqualsDouble(clip.width, 100.0, tol)
        assertEqualsDouble(clip.height, 40.0, tol)
        assertEquals(polyline.closed, false)
        assertEqualsDouble(polyline.points(0).x, 20.0, tol)
        assertEqualsDouble(polyline.points(0).y, 80.0, tol)
        assertEqualsDouble(polyline.points(1).x, 120.0, tol)
        assertEqualsDouble(polyline.points(1).y, 40.0, tol)
        assert(
          polyline.points(0).y > polyline.points(1).y,
          "larger data y must render higher (smaller device y)"
        )
      case other =>
        fail(s"unexpected device elements: $other")
  }

  test("point shapes lower to centered device marks") {
    val grob = Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.pointsUnsafe(6.0),
        shape = PointShape.Square,
        name = Some(GraphicsName.unsafe("square"))
      )
      .toOption
      .get
    val square =
      DeviceScene.fromScene(Scene(Vector(grob)), DeviceContext.unsafe(100.0, 100.0)).toOption.get

    square.elements match
      case Vector(DeviceElement.Mark(rect: DevicePrimitive.RectShape)) =>
        assertEqualsDouble(rect.x, 42.0, tol)
        assertEqualsDouble(rect.y, 42.0, tol)
        assertEqualsDouble(rect.width, 16.0, tol)
        assertEqualsDouble(rect.height, 16.0, tol)
      case other =>
        fail(s"unexpected device elements: $other")
  }

  test("viewport rotation pivots on the resolved origin corner") {
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.2),
      size = Size.npcUnsafe(0.5, 0.4),
      clip = Clip.Off,
      angleDegrees = 15.0
    )
    val grob = Grob
      .lines(
        Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)),
        viewport = Some(viewport)
      )
      .toOption
      .get
    val scene = DeviceScene.fromScene(Scene(Vector(grob)), device).toOption.get

    scene.elements match
      case Vector(DeviceElement.Group(_, None, Some(rotation), _)) =>
        assertEqualsDouble(rotation.degrees, -15.0, tol)
        assertEqualsDouble(rotation.pivotX, 20.0, tol)
        assertEqualsDouble(rotation.pivotY, 80.0, tol)
      case other =>
        fail(s"unexpected device elements: $other")
  }

  test("scene angles are counterclockwise in y-up frames, clockwise in y-down frames") {
    def rotationFor(direction: YDirection): Double =
      val parent = Viewport.unsafe(clip = Clip.Off, yDirection = direction)
      val child = Viewport.unsafe(
        origin = Point.npcUnsafe(0.1, 0.1),
        size = Size.npcUnsafe(0.5, 0.5),
        clip = Clip.Off,
        angleDegrees = 30.0
      )
      val inner = Grob
        .lines(Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)), viewport = Some(child))
        .toOption
        .get
      val outer = Grob.group(Vector(inner), viewport = Some(parent))
      DeviceScene.fromScene(Scene(Vector(outer)), device).toOption.get.elements match
        case Vector(
              DeviceElement.Group(_, _, _, Vector(DeviceElement.Group(_, _, Some(rotation), _)))
            ) =>
          rotation.degrees
        case other =>
          fail(s"unexpected device elements: $other")

    assertEqualsDouble(rotationFor(YDirection.Up), -30.0, tol)
    assertEqualsDouble(rotationFor(YDirection.Down), 30.0, tol)
  }

  test("degenerate expressions are rejected instead of formatted") {
    val frame = DeviceFrame(
      0.0,
      0.0,
      100.0,
      100.0,
      Interval.unsafe(0.0, 1.0),
      Interval.unsafe(0.0, 1.0),
      YDirection.Up
    )
    val r = LengthResolver(device, frame)
    assert(r.x(LengthExpr.nativeUnsafe(1.0e18)).left.toOption.exists {
      case GraphicsError.UnresolvableLength(_) => true
      case _                                   => false
    })
  }

  test("device lowering rejects oversized style and text attributes") {
    val wideLine = Grob
      .lines(
        Vector(Point.npcUnsafe(0.0, 0.0), Point.npcUnsafe(1.0, 1.0)),
        gp = GraphicParams.unsafe(lineWidth = 1.0e308)
      )
      .toOption
      .get
    val hugeText = Grob
      .text(
        "label",
        Point.npcUnsafe(0.5, 0.5),
        rotationDegrees = 1.0e308,
        gp = GraphicParams.unsafe(fontSize = Length.pointsUnsafe(1.0e308))
      )
      .toOption
      .get

    assertEquals(
      DeviceScene.fromScene(Scene(Vector(wideLine)), device).left.toOption,
      Some(GraphicsError.InvalidDeviceValue("line width", 1.0e308))
    )
    assert(DeviceScene.fromScene(Scene(Vector(hugeText)), device).left.toOption.exists {
      case GraphicsError.InvalidDeviceValue(field, _) =>
        field == "font size" || field == "rotation"
      case _ => false
    })
  }

  test("images lower with rectangle-consistent anchors and top-left pixel order") {
    val raster = RasterImage
      .fromPacked(
        RasterDimensions.unsafe(2, 1),
        Vector(Rgba32.unsafe(255, 0, 0), Rgba32.unsafe(0, 0, 255))
      )
      .toOption
      .get
    val grob = Grob
      .image(
        raster,
        Point.npcUnsafe(0.5, 0.5),
        Size.npcUnsafe(0.4, 0.2),
        anchor = Anchor.BottomLeft,
        interpolation = RasterInterpolation.Nearest,
        alpha = 0.75,
        name = Some(GraphicsName.unsafe("device-image"))
      )
      .toOption
      .get
    val scene = DeviceScene.fromScene(Scene(Vector(grob)), device).toOption.get

    scene.elements match
      case Vector(DeviceElement.Mark(image: DevicePrimitive.Image)) =>
        assertEqualsDouble(image.x, 100.0, tol)
        assertEqualsDouble(image.y, 30.0, tol)
        assertEqualsDouble(image.width, 80.0, tol)
        assertEqualsDouble(image.height, 20.0, tol)
        assertEquals(image.image.pixelUnsafe(0, 0), Rgba32.unsafe(255, 0, 0))
        assertEquals(image.image.pixelUnsafe(1, 0), Rgba32.unsafe(0, 0, 255))
        assertEquals(image.interpolation, RasterInterpolation.Nearest)
        assertEqualsDouble(image.alpha, 0.75, tol)
        assertEquals(image.name.map(_.value), Some("device-image"))
      case other =>
        fail(s"unexpected device elements: $other")
  }
