package intaglio

class LayoutSuite extends munit.FunSuite:

  private val frame =
    PanelFrame.npcUnsafe(0.1, 0.2, 0.8, 0.6)

  private val layout =
    PanelLayout(
      frame,
      xScale = Interval.unsafe(0.0, 10.0),
      yScale = Interval.unsafe(-1.0, 1.0),
      margins = PanelMargins.npcUnsafe(0.08, 0.04, 0.12, 0.1),
      clip = Clip.On
    )

  test("panel layout exposes a viewport and maps data coordinates into panel space") {
    val viewport = layout.viewport
    val midpoint = layout.dataToPanel(5.0, 0.0).toOption.get

    assertEquals(viewport.origin, frame.origin)
    assertEquals(viewport.size, frame.size)
    assertEquals(viewport.xScale, Interval.unsafe(0.0, 10.0))
    assertEquals(viewport.yScale, Interval.unsafe(-1.0, 1.0))
    assertEquals(viewport.clip, Clip.On)
    assertEquals(
      midpoint,
      Point(
        frame.origin.x + LengthExpr.Mul(0.5, frame.size.width.expr),
        frame.origin.y + LengthExpr.Mul(0.5, frame.size.height.expr)
      )
    )
    assert(layout.contains(5.0, 0.0))
    assert(!layout.contains(11.0, 0.0))
  }

  test("panel coordinate transforms reject non-finite inputs") {
    assert(layout.dataToPanel(Double.NaN, 0.0).left.toOption.exists {
      case GraphicsError.InvalidLayoutCoordinate("x", value) => value.isNaN
      case _                                                 => false
    })
    assertEquals(
      layout.dataToPanel(0.0, Double.PositiveInfinity).left.toOption,
      Some(GraphicsError.InvalidLayoutCoordinate("y", Double.PositiveInfinity))
    )
  }

  test("axis guides lower through existing axis grobs with unclipped guide viewport") {
    val guide =
      GuideSpec
        .lower(
          GuideSpec.Axis(
            AxisSide.Bottom,
            breaks = Breaks.countUnsafe(3),
            tickLength = Some(ExtentExpr.nativeUnsafe(0.2)),
            labelOffset = Some(ExtentExpr.nativeUnsafe(0.4)),
            name = Some(GraphicsName.unsafe("x-guide"))
          ),
          layout
        )
        .toOption
        .get
    val group = guide.grob.asInstanceOf[Grob.Group]
    val baseline = group.children.head.asInstanceOf[Grob.Segments]
    val ticks = group.children(1).asInstanceOf[Grob.Segments]

    assertEquals(group.name.map(_.value), Some("x-guide"))
    assertEquals(group.viewport.map(_.clip), Some(Clip.Off))
    assertEquals(group.children.length, 5)
    assertEquals(
      baseline.segments,
      Vector((Point.nativeUnsafe(0.0, -1.0), Point.nativeUnsafe(10.0, -1.0)))
    )
    assertEquals(
      ticks.segments(1),
      (
        Point.nativeUnsafe(5.0, -1.0),
        Point(
          LengthExpr.nativeUnsafe(5.0),
          LengthExpr.nativeUnsafe(-1.0) - ExtentExpr.nativeUnsafe(0.2)
        )
      )
    )
  }

  test("axis guides lower titles at solver-matched offsets") {
    val guide = GuideSpec
      .lower(
        GuideSpec.Axis(
          AxisSide.Left,
          ticks = Some(Vector(AxisTick.unsafe(-1.0, "low"), AxisTick.unsafe(1.0, "high"))),
          title = Some("Signal"),
          name = Some(GraphicsName.unsafe("signal-axis"))
        ),
        layout
      )
      .fold(e => fail(e.message), identity)
    val group = guide.grob.asInstanceOf[Grob.Group]
    val title = group.children.last.asInstanceOf[Grob.Text]

    assertEquals(title.name.map(_.value), Some("signal-axis-title"))
    assertEquals(title.label, "Signal")
    assertEquals(title.anchor, Anchor(HJust.Center, VJust.Center))
    assertEqualsDouble(title.rotationDegrees, 90.0, 1e-12)
    assertEquals(title.gp.fontSize, Length.pointsUnsafe(11.0))
  }

  test("legend guides lower to stable marker and label grobs") {
    val guide =
      GuideSpec
        .lower(
          GuideSpec.Legend(
            title = Some("condition"),
            entries = Vector(
              LegendEntry.colorUnsafe("A", Rgba.Black),
              LegendEntry.colorUnsafe("B", Rgba.White)
            ),
            origin = Point.npcUnsafe(0.82, 0.2),
            rowGap = ExtentExpr.npcUnsafe(0.05),
            labelOffset = LengthExpr.npcUnsafe(0.03),
            name = Some(GraphicsName.unsafe("condition-legend"))
          ),
          layout
        )
        .toOption
        .get
    val group = guide.grob.asInstanceOf[Grob.Group]
    val title = group.children.head.asInstanceOf[Grob.Text]
    val secondKey = group.children(3).asInstanceOf[Grob.Points]
    val secondLabel = group.children(4).asInstanceOf[Grob.Text]

    assertEquals(group.name.map(_.value), Some("condition-legend"))
    assertEquals(group.children.length, 5)
    assertEquals(title.name.map(_.value), Some("condition-legend-title"))
    assertEquals(title.label, "condition")
    assertEquals(secondKey.name.map(_.value), Some("condition-legend-entry-1-key"))
    assertEquals(secondKey.gp.fill, Some(Rgba.White))
    assertEquals(secondLabel.name.map(_.value), Some("condition-legend-entry-1-label"))
    assertEquals(secondLabel.label, "B")
  }

  test("empty legends fail before backend rendering") {
    val guide =
      GuideSpec.Legend(
        title = None,
        entries = Vector.empty
      )

    assertEquals(
      GuideSpec.lower(guide, layout).left.toOption,
      Some(GraphicsError.EmptyGeometry("legend"))
    )
  }

  test("colorbar guides lower sampled colors, transform ticks, labels, and title") {
    val name = GraphicsName.unsafe("activation-colorbar")
    val guide = GuideSpec
      .lower(
        GuideSpec.Colorbar(
          title = Some("activation"),
          colors = Vector(Rgba.Black, Rgba.unsafe(128, 128, 128), Rgba.White),
          ticks = Vector(
            AxisTick.unsafe(0.0, "1"),
            AxisTick.unsafe(0.5, "10"),
            AxisTick.unsafe(1.0, "100")
          ),
          origin = Point.npcUnsafe(0.82, 0.15),
          name = Some(name)
        ),
        layout
      )
      .fold(e => fail(e.message), identity)
    val group = guide.grob.asInstanceOf[Grob.Group]
    val swatches = group.children.collect { case rect: Grob.Rect => rect }
    val ticks = group.children
      .collectFirst { case segments: Grob.Segments => segments }
      .getOrElse(fail("missing colorbar ticks"))
    val labels = group.children.collect {
      case text: Grob.Text if text.name.exists(_.value.contains("-label-")) => text
    }
    val title = group.children
      .collectFirst {
        case text: Grob.Text if text.name.exists(_.value == "activation-colorbar-title") => text
      }
      .getOrElse(fail("missing colorbar title"))

    assertEquals(group.name, Some(name))
    assertEquals(
      swatches.map(_.gp.fill),
      Vector(Some(Rgba.Black), Some(Rgba.unsafe(128, 128, 128)), Some(Rgba.White))
    )
    assertEquals(
      swatches.map(_.name.map(_.value)),
      Vector(
        Some("activation-colorbar-swatch-0"),
        Some("activation-colorbar-swatch-1"),
        Some("activation-colorbar-swatch-2")
      )
    )
    assertEquals(ticks.segments.length, 3)
    assertEquals(labels.map(_.label), Vector("1", "10", "100"))
    assertEquals(title.label, "activation")
  }

  test("empty colorbars fail before backend rendering") {
    val guide = GuideSpec.Colorbar(None, Vector.empty, Vector.empty)

    assertEquals(
      GuideSpec.lower(guide, layout).left.toOption,
      Some(GraphicsError.EmptyGeometry("colorbar"))
    )
  }

  test("default axis offsets follow point policy across device sizes") {
    val spec = GuideSpec.Axis(
      AxisSide.Bottom,
      ticks = Some(Vector(AxisTick.unsafe(5.0, "5"))),
      name = Some(GraphicsName.unsafe("policy-axis"))
    )
    val policy = LayoutPolicy(tickLengthPt = 6.0, tickLabelGapPt = 3.0)

    def offsets(device: DeviceContext): (Double, Double) =
      val guide =
        GuideSpec.lower(spec, layout, policy = policy).fold(e => fail(e.message), identity)
      val scene = DeviceScene
        .fromScene(Scene(Vector(guide.grob)), device)
        .fold(e => fail(e.message), identity)
      val marks = scene.elements match
        case Vector(DeviceElement.Group(_, _, _, children)) =>
          children.collect { case DeviceElement.Mark(mark) => mark }
        case other =>
          fail(s"unexpected axis device scene: $other")
      val tick = marks
        .collectFirst {
          case line: DevicePrimitive.Polyline if line.name.exists(_.value == "policy-axis-ticks") =>
            line
        }
        .getOrElse(fail("missing policy-axis tick"))
      val label = marks
        .collectFirst {
          case text: DevicePrimitive.TextRun if text.name.exists(_.value == "policy-axis-label") =>
            text
        }
        .getOrElse(fail("missing policy-axis label"))
      val baselineY = tick.points.head.y
      (math.abs(tick.points.last.y - baselineY), math.abs(label.y - baselineY))

    val small = offsets(DeviceContext.unsafe(640.0, 480.0))
    val large = offsets(DeviceContext.unsafe(1280.0, 960.0))
    val pxPerPt = 96.0 / 72.0
    assertEqualsDouble(small._1, 6.0 * pxPerPt, 1e-9)
    assertEqualsDouble(small._2, 9.0 * pxPerPt, 1e-9)
    assertEqualsDouble(large._1, small._1, 1e-9)
    assertEqualsDouble(large._2, small._2, 1e-9)
  }

  test("default legend spacing clears point-sized keys and the title") {
    val name = GraphicsName.unsafe("absolute-legend")
    val legend = GuideSpec.Legend(
      title = Some("condition"),
      entries = Vector(
        LegendEntry.colorUnsafe("A", Rgba.Black),
        LegendEntry.colorUnsafe("B", Rgba.White)
      ),
      origin = Point.npcUnsafe(0.1, 0.9),
      name = Some(name)
    )
    val viewport = Viewport.unsafe(
      origin = Point.npcUnsafe(0.8, 0.2),
      size = Size.npcUnsafe(0.12, 0.6),
      clip = Clip.Off
    )
    val guide = GuideSpec
      .lower(legend, layout, legendViewport = Some(viewport))
      .fold(e => fail(e.message), identity)
    val scene = DeviceScene
      .fromScene(Scene(Vector(guide.grob)), DeviceContext.unsafe(640.0, 480.0))
      .fold(e => fail(e.message), identity)
    val marks = scene.elements match
      case Vector(DeviceElement.Group(_, _, _, children)) =>
        children.collect { case DeviceElement.Mark(mark) => mark }
      case other =>
        fail(s"unexpected legend device scene: $other")
    val title = marks
      .collectFirst {
        case text: DevicePrimitive.TextRun
            if text.name.exists(_.value == "absolute-legend-title") =>
          text
      }
      .getOrElse(fail("missing legend title"))
    val firstKey = marks
      .collectFirst {
        case disc: DevicePrimitive.Disc
            if disc.name.exists(_.value == "absolute-legend-entry-0-key") =>
          disc
      }
      .getOrElse(fail("missing first legend key"))
    val secondKey = marks
      .collectFirst {
        case disc: DevicePrimitive.Disc
            if disc.name.exists(_.value == "absolute-legend-entry-1-key") =>
          disc
      }
      .getOrElse(fail("missing second legend key"))
    val firstLabel = marks
      .collectFirst {
        case text: DevicePrimitive.TextRun
            if text.name.exists(_.value == "absolute-legend-entry-0-label") =>
          text
      }
      .getOrElse(fail("missing first legend label"))
    val pxPerPt = 96.0 / 72.0

    assertEqualsDouble(firstLabel.x - firstKey.centerX, 10.0 * pxPerPt, 1e-9)
    assertEqualsDouble(firstLabel.x - firstKey.centerX - firstKey.radius, 5.0 * pxPerPt, 1e-9)
    assertEqualsDouble(firstKey.centerY - title.y, 20.0 * pxPerPt, 1e-9)
    assert(
      (firstKey.centerY - firstKey.radius) > (title.y + title.fontSizePx),
      "title must clear the first key"
    )
    assertEqualsDouble(secondKey.centerY - firstKey.centerY, 20.0 * pxPerPt, 1e-9)
  }
