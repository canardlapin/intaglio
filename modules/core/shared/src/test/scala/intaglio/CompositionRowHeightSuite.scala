package intaglio

/** Linked strips stacked over a matrix: rows of unequal, explicitly stated heights over one shared
  * discrete x frame. Judged on resolved cell geometry and the drawn device scene.
  */
class CompositionRowHeightSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Cell(contrast: String, value: Double)

  private val levels = Vector("A", "B", "C", "D")
  private val cells = levels.zipWithIndex.map((level, i) => Cell(level, i.toDouble + 0.5))

  private val context = RenderContext.unsafe(width = 480, height = 600)
  private val pxPerPt = context.pixelsPerInch / 72.0

  private def bandX(levelsShown: Vector[String] = levels) =
    ok(BandScaleSpec("contrast", levelsShown, BandPadding.unsafe(0.1)))

  private def panel(
      xAxis: Boolean,
      levelsShown: Vector[String] = levels
  ): TrainedPlot =
    val guides =
      if xAxis then GuidePolicy.Derived()
      else GuidePolicy.Explicit(Vector(GuideSpec.Axis(AxisSide.Left)))
    ok(
      plot(cells)
        .encode(Aesthetic.X, _.contrast, bandX(levelsShown))
        .encode(Aesthetic.Y, _.value, ok(ContinuousScaleSpec("value", Palette.numeric)))
        .geomPoint()
        .guides(guides)
        .resolve(context)
    )

  /** The strip, the matrix, and the contrast strip with its x axis: 45 pt, the rest, and 90 pt. */
  private val heights =
    Vector(RowHeight.pointsUnsafe(45.0), RowHeight.weightUnsafe(1.0), RowHeight.pointsUnsafe(90.0))

  private val stacked = Vector(panel(xAxis = false), panel(xAxis = false), panel(xAxis = true))

  private def resolved(frame: PanelFrame): (Double, Double, Double, Double) =
    val device = context.deviceContext
    val resolver = LengthResolver(device, DeviceFrame.root(device))
    (
      ok(resolver.x(frame.origin.x)),
      ok(resolver.y(frame.origin.y)),
      ok(resolver.width(frame.size.width)),
      ok(resolver.height(frame.size.height))
    )

  private def composeStacked(options: CompositionOptions = CompositionOptions.default) =
    PlotComposition.column(
      stacked,
      context,
      options.withRowHeights(heights).withSharedXFrame(true)
    )

  test("three stacked panels of unequal height share their column boundaries exactly") {
    val composed = ok(composeStacked())
    val panels = composed.cells.map(cell => resolved(cell.panel))
    // Column edges are identical floating-point values, not merely close.
    assertEquals(panels.map(_._1).distinct.length, 1, panels)
    assertEquals(panels.map(_._3).distinct.length, 1, panels)
    // The panels really are of unequal height, top row first.
    val panelHeights = panels.map(_._4)
    assert(panelHeights(1) > panelHeights(2) && panelHeights(2) > panelHeights(0), panelHeights)
  }

  test("fixed rows take their stated height and the weighted row takes the rest") {
    val composed = ok(composeStacked())
    val scene = ok(composed.renderPlan.deviceScene)
    val cellClips = scene.elements.collect {
      case DeviceElement.Group(Some(name), Some(clip), _, _)
          if name.value.startsWith("composition-cell-") =>
        clip
    }
    assertEquals(cellClips.length, 3)
    assertEqualsDouble(cellClips(0).height, 45.0 * pxPerPt, 1e-9)
    assertEqualsDouble(cellClips(2).height, 90.0 * pxPerPt, 1e-9)
    val gap = LayoutPolicy().panelGapPt * pxPerPt
    assertEqualsDouble(cellClips.map(_.height).sum + 2.0 * gap, context.height.toDouble, 1e-9)
    // Rows stack downward: the strip sits on top.
    assert(cellClips(0).y < cellClips(1).y && cellClips(1).y < cellClips(2).y)
  }

  test("the same band lands on the same device x in every row") {
    val scene = ok(ok(composeStacked()).renderPlan.deviceScene)
    def discs(element: DeviceElement): Vector[Double] =
      element match
        case DeviceElement.Mark(d: DevicePrimitive.Disc)       => Vector(d.centerX)
        case DeviceElement.Mark(b: DevicePrimitive.PointBatch) =>
          b.points.map(_.x)
        case DeviceElement.Mark(_)                  => Vector.empty
        case DeviceElement.Group(_, _, _, children) => children.flatMap(discs)
        case DeviceElement.Annotated(_, children)   => children.flatMap(discs)
    val perRow = scene.elements.collect {
      case group @ DeviceElement.Group(Some(name), _, _, _)
          if name.value.startsWith("composition-cell-") =>
        discs(group).sorted
    }
    assertEquals(perRow.length, 3)
    assertEquals(perRow.map(_.length).distinct, Vector(levels.length))
    assertEquals(perRow.distinct.length, 1, perRow)
  }

  test("an explicitly sized row reserves only its own plots' top and bottom strips") {
    val composed = ok(composeStacked())
    val panels = composed.cells.map(cell => resolved(cell.panel))
    val cellsPx = Vector(45.0 * pxPerPt, 0.0, 90.0 * pxPerPt)
    // The axis-free strip row's panel is not shrunk by the bottom row's x axis.
    val stripSlack = cellsPx(0) - panels(0)._4
    val axisSlack = cellsPx(2) - panels(2)._4
    assert(stripSlack < axisSlack, (stripSlack, axisSlack))
  }

  test("plots on different x frames are refused when a shared x frame is required") {
    val mismatched = Vector(panel(xAxis = false), panel(xAxis = true, levelsShown = levels.reverse))
    val result = PlotComposition.column(
      mismatched,
      context,
      CompositionOptions.default.withSharedXFrame(true)
    )
    assert(result.left.toOption.exists {
      case GraphicsError.InvalidCompositionPanel(1, _) => true
      case _                                           => false
    })
    // Without the requirement the same plots still compose.
    assert(PlotComposition.column(mismatched, context).isRight)
  }

  test("row height specifications are checked") {
    assert(RowHeight.points(0.0).isLeft)
    assert(RowHeight.weight(Double.NaN).isLeft)
    assert(
      PlotComposition
        .column(stacked, context, CompositionOptions.default.withRowHeights(heights.take(2)))
        .left
        .toOption
        .exists(_.isInstanceOf[GraphicsError.InvalidCompositionRowHeights])
    )
    val tooTall = Vector.fill(3)(RowHeight.pointsUnsafe(400.0))
    assertEquals(
      PlotComposition
        .column(stacked, context, CompositionOptions.default.withRowHeights(tooTall))
        .left
        .toOption,
      Some(GraphicsError.LayoutOverflow("composition fixed row heights"))
    )
  }

  test("equal weights reproduce the default equal split") {
    val weights = Vector.fill(3)(RowHeight.weightUnsafe(2.0))
    val plots = Vector(panel(xAxis = true), panel(xAxis = true), panel(xAxis = true))
    val byDefault = ok(PlotComposition.column(plots, context))
    val weighted = ok(
      PlotComposition.column(plots, context, CompositionOptions.default.withRowHeights(weights))
    )
    assertEquals(weighted.cells, byDefault.cells)
  }
