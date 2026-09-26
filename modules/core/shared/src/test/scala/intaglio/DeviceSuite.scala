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

  test("diamond points lower to an equal-area closed polyline on the axes") {
    val grob = Grob
      .points(
        Vector(Point.npcUnsafe(0.5, 0.5)),
        size = ExtentExpr.pointsUnsafe(6.0),
        shape = PointShape.Diamond,
        name = Some(GraphicsName.unsafe("diamond"))
      )
      .toOption
      .get
    val scene =
      DeviceScene.fromScene(Scene(Vector(grob)), DeviceContext.unsafe(100.0, 100.0)).toOption.get
    // 6 pt at 96 ppi is an 8 px radius; the half-diagonal is 8 * sqrt(pi / 2).
    val half = 8.0 * math.sqrt(math.Pi / 2.0)

    scene.elements match
      case Vector(DeviceElement.Mark(DevicePrimitive.Polyline(points, true, _, name))) =>
        assertEquals(name.map(_.value), Some("diamond"))
        assertEquals(points.length, 4)
        assertEqualsDouble(points(0).x, 50.0, tol)
        assertEqualsDouble(points(0).y, 50.0 - half, tol)
        assertEqualsDouble(points(1).x, 50.0 + half, tol)
        assertEqualsDouble(points(1).y, 50.0, tol)
        assertEqualsDouble(points(2).x, 50.0, tol)
        assertEqualsDouble(points(2).y, 50.0 + half, tol)
        assertEqualsDouble(points(3).x, 50.0 - half, tol)
        assertEqualsDouble(points(3).y, 50.0, tol)
        assertEqualsDouble(2.0 * half * half, math.Pi * 64.0, tol)
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

  test("named viewport frames round trip transformed native coordinates") {
    val name = GraphicsName.unsafe("log-panel")
    val mapping = ViewportCoordinateMapping(
      ViewportAxisMapping.Continuous(
        Interval.unsafe(1.0, 100.0),
        Interval.unsafe(0.0, 2.0),
        Transform.log10
      ),
      ViewportAxisMapping.Native
    )
    val viewport = Viewport
      .unsafe(
        origin = Point.npcUnsafe(0.1, 0.2),
        size = Size.npcUnsafe(0.5, 0.6),
        xScale = Interval.unsafe(-0.1, 1.1),
        yScale = Interval.unsafe(0.0, 10.0)
      )
      .withCoordinateMapping(mapping)
    val group = Grob.group(Vector.empty, viewport = Some(viewport), name = Some(name))
    val resolved = DeviceScene
      .fromScene(Scene(Vector(group)), device)
      .orThrow
      .frame(name)
      .fold(error => fail(error.message), identity)

    val point = DevicePoint(10.0, 2.5)
    val mapped = resolved.nativeToDevice(point).fold(error => fail(error.message), identity)
    assertEqualsDouble(mapped.x, resolved.frame.x + resolved.frame.width * (0.5 + 0.1) / 1.2, tol)
    assertEqualsDouble(mapped.y, resolved.frame.y + resolved.frame.height * 0.75, tol)
    val roundTrip = resolved.deviceToNative(mapped).fold(error => fail(error.message), identity)
    assertEqualsDouble(roundTrip.x, point.x, tol)
    assertEqualsDouble(roundTrip.y, point.y, tol)
    assertEquals(
      resolved.deviceToNative(DevicePoint(resolved.frame.x - 1.0, mapped.y)).left.toOption,
      Some(ViewportFrameError.OutsideFrame(name, DevicePoint(resolved.frame.x - 1.0, mapped.y)))
    )
  }

  test("named viewport frames refuse unavailable and rotated coordinate mappings") {
    val unavailableName = GraphicsName.unsafe("discrete-panel")
    val unavailable = Grob.group(
      Vector.empty,
      viewport = Some(
        Viewport
          .unsafe()
          .withCoordinateMapping(
            ViewportCoordinateMapping(ViewportAxisMapping.Unavailable, ViewportAxisMapping.Native)
          )
      ),
      name = Some(unavailableName)
    )
    val unavailableFrame = DeviceScene
      .fromScene(Scene(Vector(unavailable)), device)
      .orThrow
      .frame(unavailableName)
      .fold(error => fail(error.message), identity)
    assertEquals(
      unavailableFrame.nativeToDevice(DevicePoint(0.5, 0.5)).left.toOption,
      Some(ViewportFrameError.UnavailableAxis(unavailableName, "x"))
    )
    unavailableFrame.nativeToDevice(DevicePoint(Double.NaN, 0.5)) match
      case Left(ViewportFrameError.NonFiniteCoordinate(name, axis, value)) =>
        assertEquals(name, unavailableName)
        assertEquals(axis, "x")
        assert(value.isNaN)
      case other => fail(s"expected a typed non-finite coordinate failure, found $other")

    unavailableFrame
      .copy(coordinateMapping = ViewportCoordinateMapping.native)
      .nativeToDevice(DevicePoint(1e308, 0.5)) match
      case Left(ViewportFrameError.NonFiniteCoordinate(_, "x", value)) => assert(value.isInfinite)
      case other => fail(s"expected a typed coordinate overflow failure, found $other")

    val degenerateName = GraphicsName.unsafe("degenerate-panel")
    val degenerate = Grob.group(
      Vector.empty,
      viewport = Some(Viewport.unsafe(xScale = Interval.unsafe(1.0, 1.0))),
      name = Some(degenerateName)
    )
    val degenerateFrame = DeviceScene
      .fromScene(Scene(Vector(degenerate)), device)
      .orThrow
      .frame(degenerateName)
      .fold(error => fail(error.message), identity)
    assertEquals(
      degenerateFrame.deviceToNative(DevicePoint(100.0, 50.0)).left.toOption,
      Some(ViewportFrameError.UnavailableAxis(degenerateName, "x"))
    )

    val rotatedName = GraphicsName.unsafe("rotated-panel")
    val rotated = Grob.group(
      Vector.empty,
      viewport = Some(Viewport.unsafe(angleDegrees = 15.0)),
      name = Some(rotatedName)
    )
    val rotatedFrame = DeviceScene
      .fromScene(Scene(Vector(rotated)), device)
      .orThrow
      .frame(rotatedName)
      .fold(error => fail(error.message), identity)
    assertEquals(
      rotatedFrame.deviceToNative(DevicePoint(100.0, 50.0)).left.toOption,
      Some(ViewportFrameError.Rotated(rotatedName))
    )
  }

  test("one named frame maps conformance primitive anchors and image bounds to lowered output") {
    def named(value: String): GraphicsName = GraphicsName.unsafe(value)
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.1, 0.1),
      size = Size.npcUnsafe(0.8, 0.8),
      xScale = Interval.unsafe(0.0, 10.0),
      yScale = Interval.unsafe(0.0, 10.0)
    )
    val group = Grob.group(
      Vector(
        Grob.points(Vector(Point.nativeUnsafe(1.0, 2.0)), name = Some(named("point"))).orThrow,
        Grob
          .lines(
            Vector(Point.nativeUnsafe(3.0, 4.0), Point.nativeUnsafe(4.0, 5.0)),
            name = Some(named("line"))
          )
          .orThrow,
        Grob
          .circle(
            Point.nativeUnsafe(6.0, 7.0),
            ExtentExpr.nativeUnsafe(0.2),
            name = Some(named("circle"))
          )
          .orThrow,
        Grob.text("label", Point.nativeUnsafe(8.0, 9.0), name = Some(named("text"))).orThrow,
        Grob
          .rect(
            Point.nativeUnsafe(2.0, 7.0),
            Size.fromExtents(ExtentExpr.nativeUnsafe(1.0), ExtentExpr.nativeUnsafe(2.0)),
            name = Some(named("rect-tile"))
          )
          .orThrow,
        Grob
          .image(
            RasterImage
              .fromPacked(
                RasterDimensions.unsafe(1, 1),
                Vector(Rgba32.unsafe(40, 50, 60))
              )
              .orThrow,
            Point.nativeUnsafe(5.0, 2.0),
            Size.fromExtents(ExtentExpr.nativeUnsafe(2.0), ExtentExpr.nativeUnsafe(1.0)),
            name = Some(named("image"))
          )
          .orThrow
      ),
      viewport = Some(viewport),
      name = Some(named("primitive-panel"))
    )
    val scene = DeviceScene.fromScene(Scene(Vector(group)), device).orThrow
    val frame = scene.frame(named("primitive-panel")).fold(error => fail(error.message), identity)
    def mapped(x: Double, y: Double): DevicePoint =
      frame.nativeToDevice(DevicePoint(x, y)).fold(error => fail(error.message), identity)
    val children = scene.elements.head.asInstanceOf[DeviceElement.Group].children
    children.foreach {
      case DeviceElement.Mark(DevicePrimitive.Disc(x, y, _, _, Some(name)))
          if name == named("point") =>
        assertEquals(mapped(1.0, 2.0), DevicePoint(x, y))
      case DeviceElement.Mark(DevicePrimitive.Polyline(points, _, _, Some(name)))
          if name == named("line") =>
        assertEquals(points.head, mapped(3.0, 4.0))
        assertEquals(points(1), mapped(4.0, 5.0))
      case DeviceElement.Mark(DevicePrimitive.Disc(x, y, _, _, Some(name)))
          if name == named("circle") =>
        assertEquals(mapped(6.0, 7.0), DevicePoint(x, y))
      case DeviceElement.Mark(DevicePrimitive.TextRun(_, x, y, _, _, _, _, _, _, Some(name)))
          if name == named("text") =>
        assertEquals(mapped(8.0, 9.0), DevicePoint(x, y))
      case DeviceElement.Mark(DevicePrimitive.RectShape(x, y, width, height, _, _, Some(name)))
          if name == named("rect-tile") =>
        assertEquals(mapped(2.0, 7.0), DevicePoint(x + width / 2.0, y + height / 2.0))
      case DeviceElement.Mark(DevicePrimitive.Image(_, x, y, width, height, _, _, Some(name)))
          if name == named("image") =>
        assertEquals(mapped(5.0, 2.0), DevicePoint(x + width / 2.0, y + height / 2.0))
      case other => fail(s"unexpected lowered primitive: $other")
    }
  }
