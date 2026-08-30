package intaglio

class AxisSuite extends munit.FunSuite:

  test("bottom axis lowers to baseline, tick segments, and labels") {
    val range = Interval.unsafe(0.0, 10.0)
    val ticks = Axis.ticks(range, Breaks.countUnsafe(3)).toOption.get
    val axis =
      Axis
        .bottom(
          range,
          ticks,
          y = 0.0,
          tickLength = ExtentExpr.nativeUnsafe(0.4),
          labelOffset = ExtentExpr.nativeUnsafe(0.8),
          name = Some(GraphicsName.unsafe("x-axis"))
        )
        .flatMap(_.toGrob())
        .toOption
        .get

    val group = axis.asInstanceOf[Grob.Group]
    assertEquals(group.children.length, 5)
    assertEquals(group.name.map(_.value), Some("x-axis"))

    val baseline = group.children(0).asInstanceOf[Grob.Segments]
    val ticksGrob = group.children(1).asInstanceOf[Grob.Segments]
    val midpointLabel = group.children(3).asInstanceOf[Grob.Text]

    assertEquals(baseline.name.map(_.value), Some("x-axis-baseline"))
    assertEquals(
      baseline.segments,
      Vector((Point.nativeUnsafe(0.0, 0.0), Point.nativeUnsafe(10.0, 0.0)))
    )
    assertEquals(ticksGrob.name.map(_.value), Some("x-axis-ticks"))
    assertEquals(
      ticksGrob.segments(1),
      (
        Point.nativeUnsafe(5.0, 0.0),
        Point(
          LengthExpr.nativeUnsafe(5.0),
          LengthExpr.nativeUnsafe(0.0) - ExtentExpr.nativeUnsafe(0.4)
        )
      )
    )
    assertEquals(midpointLabel.name.map(_.value), Some("x-axis-label"))
    assertEquals(midpointLabel.label, "5")
    assertEquals(
      midpointLabel.at,
      Point(
        LengthExpr.nativeUnsafe(5.0),
        LengthExpr.nativeUnsafe(0.0) - ExtentExpr.nativeUnsafe(0.8)
      )
    )
    assertEquals(midpointLabel.anchor, Anchor(HJust.Center, VJust.Top))
  }

  test("left axis places ticks and labels on the negative x side") {
    val range = Interval.unsafe(-1.0, 1.0)
    val ticks = Vector(AxisTick.unsafe(-1.0, "low"), AxisTick.unsafe(1.0, "high"))
    val axis =
      Axis
        .left(
          range,
          ticks,
          x = 0.0,
          tickLength = ExtentExpr.nativeUnsafe(0.25),
          labelOffset = ExtentExpr.nativeUnsafe(0.5)
        )
        .flatMap(_.toGrob())
        .toOption
        .get
        .asInstanceOf[Grob.Group]

    val tickGrob = axis.children(1).asInstanceOf[Grob.Segments]
    val firstLabel = axis.children(2).asInstanceOf[Grob.Text]

    assertEquals(
      tickGrob.segments.head,
      (
        Point.nativeUnsafe(0.0, -1.0),
        Point(
          LengthExpr.nativeUnsafe(0.0) - ExtentExpr.nativeUnsafe(0.25),
          LengthExpr.nativeUnsafe(-1.0)
        )
      )
    )
    assertEquals(
      firstLabel.at,
      Point(
        LengthExpr.nativeUnsafe(0.0) - ExtentExpr.nativeUnsafe(0.5),
        LengthExpr.nativeUnsafe(-1.0)
      )
    )
    assertEquals(firstLabel.anchor, Anchor(HJust.Right, VJust.Center))
  }

  test("axis validation rejects invalid coordinates and out-of-range ticks") {
    val range = Interval.unsafe(0.0, 1.0)

    assert(AxisTick(Double.NaN, "bad").left.toOption.exists {
      case GraphicsError.InvalidAxisCoordinate("tick", value) => value.isNaN
      case _                                                  => false
    })
    assertEquals(
      Axis.bottom(range, Vector(AxisTick.unsafe(2.0, "outside"))).left.toOption,
      Some(GraphicsError.AxisTickOutsideRange(2.0, 0.0, 1.0))
    )
    assert(ExtentExpr.native(-1.0).isLeft, "negative axis extents must be unrepresentable")
  }

  test("tick generation rejects labeler count mismatches") {
    val range = Interval.unsafe(0.0, 1.0)
    val badLabeler = new Labeler:
      override def apply(values: Vector[Double]): Vector[String] =
        Vector("one")

    assertEquals(
      Axis.ticks(range, Breaks.countUnsafe(3), badLabeler).left.toOption,
      Some(GraphicsError.AxisLabelCountMismatch(3, 1))
    )
  }
