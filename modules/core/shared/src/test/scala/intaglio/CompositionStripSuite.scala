package intaglio

/** Composition cells keep each plot's axis text inside the cell's clip: strips hold point-sized
  * text, so they must keep their physical size however narrow the cell. Judged on the drawn device
  * scene: text runs, their anchors, and the portable text measure the layout used.
  */
class CompositionStripSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Reading(session: Double, score: Double)
  private val wide = Vector(Reading(1, 2.5), Reading(2, 3.25), Reading(3, 4.75), Reading(4, 5.5))
  private val narrow = Vector(Reading(1, 2.0), Reading(2, 7.0), Reading(3, 4.0), Reading(4, 9.0))

  private def trained(rows: Vector[Reading], yTitle: String, context: RenderContext) =
    ok(
      plot(rows)
        .aes(_.session, _.score)
        .geomPoint()
        .axisTitles("Session", yTitle)
        .resolve(context)
    )

  /** Every text run in each composition cell, with that cell's clip. */
  private def cells(scene: DeviceScene): Vector[(DeviceClip, Vector[DevicePrimitive.TextRun])] =
    def texts(element: DeviceElement): Vector[DevicePrimitive.TextRun] =
      element match
        case DeviceElement.Group(_, _, _, children)            => children.flatMap(texts)
        case DeviceElement.Annotated(_, children)              => children.flatMap(texts)
        case DeviceElement.Mark(text: DevicePrimitive.TextRun) => Vector(text)
        case DeviceElement.Mark(_)                             => Vector.empty
    scene.elements.collect {
      case DeviceElement.Group(Some(name), Some(clip), _, children)
          if name.value.startsWith("composition-cell-") =>
        (clip, children.flatMap(texts))
    }

  /** Horizontal ink extent of a text run under the portable estimate the layout measures with. */
  private def horizontalExtent(text: DevicePrimitive.TextRun, pxPerPt: Double): (Double, Double) =
    val fontPt = text.fontSizePx / pxPerPt
    if math.abs(text.rotationDegrees) % 180.0 == 90.0 then
      // A vertical title's horizontal extent is its line height, centred on the anchor.
      val half = TextMetrics.estimate.heightPt(fontPt) * pxPerPt / 2.0
      (text.x - half, text.x + half)
    else
      val width = TextMetrics.estimate.widthPt(text.label, fontPt) * pxPerPt
      text.horizontal match
        case HJust.Left   => (text.x, text.x + width)
        case HJust.Center => (text.x - width / 2.0, text.x + width / 2.0)
        case HJust.Right  => (text.x - width, text.x)

  private def assertTextInsideCells(composed: ComposedPlot, context: RenderContext): Unit =
    val pxPerPt = context.pixelsPerInch / 72.0
    val drawn = cells(ok(composed.renderPlan.deviceScene))
    assertEquals(drawn.length, composed.cells.length)
    drawn.foreach { (clip, texts) =>
      val names = texts.flatMap(_.name.map(_.value))
      assert(names.contains("y-axis-title"), clue((clip, names)))
      texts.foreach { text =>
        val (left, right) = horizontalExtent(text, pxPerPt)
        val where = clue((context.width, context.height, clip, text.label, text.name, left, right))
        assert(left >= clip.x - 1e-6, where)
        assert(right <= clip.x + clip.width + 1e-6, where)
      }
    }

  test("a row keeps every cell's tick labels and axis titles inside the cell") {
    for context <- Vector(
        RenderContext.unsafe(width = 640, height = 300),
        RenderContext.unsafe(width = 480, height = 240),
        RenderContext.unsafe(width = 1280, height = 600, pixelsPerInch = 192.0)
      )
    do
      val composed = ok(
        PlotComposition.row(
          Vector(trained(narrow, "Score", context), trained(wide, "Mean", context)),
          context
        )
      )
      assertTextInsideCells(composed, context)
  }

  test("a three-column grid keeps its axis text inside cells a third of the canvas wide") {
    val context = RenderContext.unsafe(width = 720, height = 480)
    val plots = Vector(
      trained(wide, "Mean", context),
      trained(narrow, "Score", context),
      trained(wide, "Mean", context),
      trained(narrow, "Score", context)
    )
    assertTextInsideCells(ok(PlotComposition.grid(plots, 3, context)), context)
  }

  test("aligned panels still share one left edge per column and one width per row") {
    val context = RenderContext.unsafe(width = 640, height = 300)
    val composed = ok(
      PlotComposition.row(
        Vector(trained(narrow, "Score", context), trained(wide, "Mean", context)),
        context
      )
    )
    def npc(expr: LengthExpr): Double = expr match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected an npc constant, got $other")
    val widths = composed.cells.map(cell => npc(cell.panel.size.width.expr))
    assertEqualsDouble(widths(0), widths(1), 1e-12)
    val bottoms = composed.cells.map(cell => npc(cell.panel.origin.y))
    assertEqualsDouble(bottoms(0), bottoms(1), 1e-12)
  }

  test("a collected legend's column is not reserved again inside every cell") {
    final case class Grouped(session: Double, score: Double, group: String)
    val grouped = Vector(
      Grouped(1, 2.5, "control"),
      Grouped(2, 3.5, "treated"),
      Grouped(3, 4.5, "control"),
      Grouped(4, 6.5, "treated")
    )
    val context = RenderContext.unsafe(width = 560, height = 360)
    val withLegend = ok(
      plot(grouped)
        .aes(_.session, _.score)
        .scaleColorDiscrete(_.group, name = "group")
        .geomPoint()
        .axisTitles("Session", "Score")
        .resolve(context)
    )
    val options = ok(
      CompositionOptions(guides = CompositionGuidePolicy.CollectCompatible, columnGapPt = Some(14))
    )
    val composed = ok(
      PlotComposition.row(Vector(withLegend, trained(wide, "Mean", context)), context, options)
    )
    assertTextInsideCells(composed, context)
    // Kept as a strip, the child's own legend column would take most of a cell: the panel left
    // between the strips measured a few pixels, narrower than the column itself.
    val legendLeft = withLegend.guides
      .collectFirst { case ResolvedGuide(_: GuideSpec.Legend, grob) => grob.viewport }
      .flatten
      .getOrElse(fail("expected the plot's own legend"))
    def npc(expr: LengthExpr): Double = expr match
      case LengthExpr.Const(length) => length.value
      case other                    => fail(s"expected an npc constant, got $other")
    val legendWidthPx = npc(legendLeft.size.width.expr) * context.width
    composed.cells.foreach { cell =>
      val panelPx = npc(cell.panel.size.width.expr) * context.width
      assert(panelPx > legendWidthPx, clue((panelPx, legendWidthPx)))
    }
  }

  test("a cell too narrow for the strips its plots reserve is a typed overflow") {
    val context = RenderContext.unsafe(width = 640, height = 300)
    val plots = Vector.fill(8)(trained(wide, "Mean", context))
    assertEquals(
      PlotComposition.row(plots, context).left.toOption,
      Some(GraphicsError.LayoutOverflow("aligned composition panel width"))
    )
  }
