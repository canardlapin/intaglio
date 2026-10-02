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

  test("a shared x frame also requires the same x transform") {
    final case class Dose(dose: Double, response: Double)
    val doses = Vector(Dose(1.0, 0.0), Dose(10.0, 1.0), Dose(100.0, 2.0))
    def dosePlot(transform: Transform) =
      ok(
        plot(doses)
          .aes(_.dose, _.response)
          .scaleXContinuous(transform = transform)
          .geomPoint()
          .resolve(context)
      )
    val linear = dosePlot(Transform.identity)
    val logged = dosePlot(Transform.log10)
    val options = CompositionOptions.default.withSharedXFrame(true)
    assert(PlotComposition.column(Vector(linear, linear), context, options).isRight)
    assert(PlotComposition.column(Vector(linear, logged), context, options).left.toOption.exists {
      case GraphicsError.InvalidCompositionPanel(1, _) => true
      case _                                           => false
    })
  }

  private final case class NumericCell(x: Double, y: Double)
  private val numericCells =
    Vector(NumericCell(0.0, 0.0), NumericCell(0.25, 0.25), NumericCell(1.0, 1.0))

  private def numericPanel(
      xTransform: Transform = Transform.identity,
      yTransform: Transform = Transform.identity,
      xPalette: Palette[Double] = Palette.numeric,
      coord: Coord = Coord.Cartesian()
  ): TrainedPlot =
    ok(
      plot(numericCells)
        .encode(Aesthetic.X, _.x, ok(ContinuousScaleSpec("x", xPalette, xTransform)))
        .encode(Aesthetic.Y, _.y, ok(ContinuousScaleSpec.numeric("y", yTransform)))
        .coord(coord)
        .geomPoint()
        .resolve(context)
    )

  private def sharedXResult(plots: TrainedPlot*) =
    PlotComposition.column(
      plots.toVector,
      context,
      CompositionOptions.default.withSharedXFrame(true)
    )

  private def assertMappingRefused(result: Either[GraphicsError, ComposedPlot]): Unit =
    assert(result.left.toOption.exists {
      case GraphicsError.InvalidCompositionPanel(1, _) => true
      case _                                           => false
    })

  test("equal endpoint domains and transform names do not prove an equal interior mapping") {
    val linear = ok(Transform("same-name", identity, identity))
    val sqrt = ok(Transform("same-name", math.sqrt, value => value * value))
    val first = numericPanel(xTransform = linear)
    val second = numericPanel(xTransform = sqrt)
    assertEquals(first.layout.map(_.xScale), second.layout.map(_.xScale))
    assertEquals(
      first.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor),
      second.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor)
    )
    assertNotEquals(first.layers.head.rows.map(_.x), second.layers.head.rows.map(_.x))
    assert(sharedXResult(first, numericPanel(xTransform = linear)).isRight)
    assertMappingRefused(sharedXResult(first, second))
  }

  test("shared x frame checks position palettes as well as transforms") {
    val ordinary = numericPanel()
    val reversed = numericPanel(xPalette = value => 1.0 - value)
    assertEquals(ordinary.layout.map(_.xScale), reversed.layout.map(_.xScale))
    assertNotEquals(ordinary.layers.head.rows.map(_.x), reversed.layers.head.rows.map(_.x))
    assertMappingRefused(sharedXResult(ordinary, reversed))
  }

  test("custom position scales need a shared mapping instance") {
    def custom(mapping: Double => Double): Scale[Double, Double] =
      new Scale[Double, Double]:
        val name = GraphicsName.unsafe("custom-x")
        def mapValue(value: Double): Option[Double] = Some(mapping(value))
    def customPanel(scale: Scale[Double, Double]) =
      ok(
        plot(numericCells)
          .encode(Aesthetic.X, _.x, scale)
          .encode(Aesthetic.Y, _.y, ok(ContinuousScaleSpec.numeric("y")))
          .geomPoint()
          .resolve(context)
      )
    val linear = custom(identity)
    val first = customPanel(linear)
    assert(sharedXResult(first, customPanel(linear)).isRight)
    assertMappingRefused(sharedXResult(first, customPanel(custom(value => 1.0 - value))))
  }

  test("a flipped shared x frame checks logical y and permits different vertical mappings") {
    val ordinary = numericPanel(coord = Coord.Flipped())
    val differentVertical = numericPanel(xTransform = Transform.sqrt, coord = Coord.Flipped())
    val differentHorizontal = numericPanel(yTransform = Transform.sqrt, coord = Coord.Flipped())
    assert(sharedXResult(ordinary, differentVertical).isRight)
    assertMappingRefused(sharedXResult(ordinary, differentHorizontal))
  }

  test("shared categorical frames compare stable category identities rather than display labels") {
    final case class Category(key: Int, label: String)
    given CategoryIdentity[Category] = CategoryIdentity.by(_.key, _.label)
    val a = Category(1, "A")
    val b = Category(2, "B")
    val data = Vector(a -> 0.0, b -> 1.0)
    def categoryPanel(declared: Vector[Category]) =
      ok(
        plot(data)
          .encode(Aesthetic.X, _._1, ok(BandScaleSpec("category", declared)))
          .encode(Aesthetic.Y, _._2, ok(ContinuousScaleSpec.numeric("y")))
          .geomPoint()
          .resolve(context)
      )
    val first = categoryPanel(Vector(a, b))
    val reversed = categoryPanel(Vector(Category(2, "A"), Category(1, "B")))
    assertEquals(
      first.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor),
      reversed.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor)
    )
    assertNotEquals(first.layers.head.rows.map(_.x), reversed.layers.head.rows.map(_.x))
    assertMappingRefused(sharedXResult(first, reversed))
  }

  test("large finite row weights preserve equal and unequal proportions") {
    val plots = Vector.fill(3)(panel(xAxis = true))
    def compose(weights: Vector[Double]) =
      ok(
        PlotComposition.column(
          plots,
          context,
          CompositionOptions.default.withRowHeights(weights.map(RowHeight.weightUnsafe))
        )
      )
    Vector(Vector(1.0, 1.0, 1.0), Vector(1.0, 2.0, 3.0)).foreach { weights =>
      val ordinary = compose(weights).cells.map(cell => resolved(cell.panel))
      val multiplier = 1.5e308 / weights.max
      val large = compose(weights.map(_ * multiplier)).cells.map(cell => resolved(cell.panel))
      ordinary.zip(large).foreach { (expected, actual) =>
        assertEqualsDouble(actual._1, expected._1, 1e-9)
        assertEqualsDouble(actual._2, expected._2, 1e-9)
        assertEqualsDouble(actual._3, expected._3, 1e-9)
        assertEqualsDouble(actual._4, expected._4, 1e-9)
      }
    }
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
