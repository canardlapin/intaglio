package intaglio

/** [[PanelFraming.MarkInk]] judged against the device scene actually drawn: ink bounds come from
  * the resolved primitives and the panel clip, not from the framing's own extent model.
  */
class PanelFramingSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Score(brain: Double, design: Double)

  // The consumer's paired-score specimen: extrema at +/-0.5 on both axes, drawn at 5.5 pt.
  private val scores = Vector(
    Score(-1.0, -0.49999999999999983),
    Score(0.0, 0.0),
    Score(1.0, 0.49999999999999983)
  )

  private def specimen(framing: PanelFraming, sizePt: Double = 5.5) =
    plot(scores)
      .aes(_.brain, _.design)
      .scaleXContinuous()
      .scaleYContinuous()
      .size(sizePt)
      .geomPoint()
      .title("Paired participant scores")
      .axisTitles("Brain score", "Design score")
      .framing(framing)

  /** Panel clip and the ink rectangle of every disc drawn inside it. */
  private def panelInk(plan: RenderPlan): (DeviceClip, Vector[(Double, Double, Double, Double)]) =
    val scene = ok(plan.deviceScene)
    scene.elements
      .collectFirst {
        case DeviceElement.Group(name, Some(clip), _, children)
            if name.exists(_.value == "plot-panel") =>
          val ink = children.collect { case DeviceElement.Mark(d: DevicePrimitive.Disc) =>
            val e = d.radius + d.gp.stroke.fold(0.0)(_ => d.gp.lineWidth / 2.0)
            (d.centerX - e, d.centerY - e, d.centerX + e, d.centerY + e)
          }
          (clip, ink)
      }
      .getOrElse(fail("missing clipped plot panel"))

  private def centres(plan: RenderPlan): Vector[(Double, Double)] =
    panelInk(plan)._2.map((l, t, r, b) => ((l + r) / 2.0, (t + b) / 2.0))

  private def clearanceOf(plan: RenderPlan): Double =
    val (clip, ink) = panelInk(plan)
    ink.map { (l, t, r, b) =>
      Vector(l - clip.x, t - clip.y, clip.x + clip.width - r, clip.y + clip.height - b).min
    }.min

  test("the compact specimen clips under Data and fits with clearance under MarkInk") {
    val context = RenderContext.unsafe(width = 640, height = 220)
    val data = ok(specimen(PanelFraming.Data).renderPlan(context))
    val framed = ok(specimen(PanelFraming.markInk).renderPlan(context))
    assert(clearanceOf(data) < 0.0, "the defect reproduces: Data framing lets ink cross the clip")
    // 2 pt default clearance at 96 ppi, to solver tolerance.
    assertEqualsDouble(clearanceOf(framed), 2.0 * 96.0 / 72.0, 1e-6)
    assertEquals(panelInk(framed)._2.length, 3)
  }

  test("framing keeps observation order and moves nothing when the marks already fit") {
    val tall = RenderContext.unsafe(width = 640, height = 360)
    val data = ok(specimen(PanelFraming.Data, sizePt = 2.0).renderPlan(tall))
    val framed = ok(specimen(PanelFraming.markInk, sizePt = 2.0).renderPlan(tall))
    assert(clearanceOf(data) > 2.0 * 96.0 / 72.0)
    assertEquals(framed.scene, data.scene)

    val compact = ok(specimen(PanelFraming.markInk).renderPlan(RenderContext.unsafe(640, 220)))
    val points = centres(compact)
    assertEquals(points.sortBy(_._1), points)
    // Device y grows downward, so a larger design score sits higher.
    assertEquals(points.sortBy(-_._2), points)
  }

  test("framing holds at native, compact, HiDPI, and export resolutions") {
    Vector(
      RenderContext.unsafe(width = 640, height = 220),
      RenderContext.unsafe(width = 320, height = 160),
      RenderContext.unsafe(width = 1280, height = 440, pixelsPerInch = 192.0),
      RenderContext.unsafe(width = 2400, height = 825, pixelsPerInch = 300.0)
    ).foreach { context =>
      val plan = ok(specimen(PanelFraming.markInk).renderPlan(context))
      val expected = 2.0 * context.pixelsPerInch / 72.0
      assertEqualsDouble(clearanceOf(plan), expected, 1e-6, clue(context))
    }
  }

  test("a clearance can be chosen, and must be finite and nonnegative") {
    val context = RenderContext.unsafe(width = 640, height = 220)
    val tight = ok(specimen(ok(PanelFraming.MarkInk(0.0))).renderPlan(context))
    assertEqualsDouble(clearanceOf(tight), 0.0, 1e-6)
    assert(PanelFraming.MarkInk(-1.0).isLeft)
    assert(PanelFraming.MarkInk(Double.NaN).isLeft)
  }

  test("a zoomed axis keeps its window while the other axis frames the ink") {
    val context = RenderContext.unsafe(width = 640, height = 220)
    val zoomed = ok(
      specimen(PanelFraming.markInk)
        .coordZoom(x = Some(Interval.unsafe(-1.0, 1.0)))
        .resolve(context)
    )
    val layout = zoomed.layout.getOrElse(fail("missing layout"))
    val unzoomed = ok(
      specimen(PanelFraming.Data).coordZoom(x = Some(Interval.unsafe(-1.0, 1.0))).resolve(context)
    )
    assertEquals(layout.xScale, unzoomed.layout.get.xScale)
    assert(layout.yScale.width > unzoomed.layout.get.yScale.width)
  }

  test("flipped and transformed axes frame in the space they draw in") {
    val context = RenderContext.unsafe(width = 640, height = 220)
    val flipped = ok(
      specimen(PanelFraming.markInk).coord(Coord.Flipped()).renderPlan(context)
    )
    assertEqualsDouble(clearanceOf(flipped), 2.0 * 96.0 / 72.0, 1e-6)

    final case class Dose(dose: Double, response: Double)
    val doses = Vector(Dose(1.0, 1.0), Dose(10.0, 2.0), Dose(1000.0, 3.0))
    val logged = ok(
      plot(doses)
        .aes(_.dose, _.response)
        .scaleXContinuous(transform = Transform.log10)
        .scaleYContinuous()
        .size(6.0)
        .geomPoint()
        .framing(PanelFraming.markInk)
        .renderPlan(context)
    )
    assertEqualsDouble(clearanceOf(logged), 2.0 * 96.0 / 72.0, 1e-6)
  }

  test("faceted panels frame their ink, and shared scales keep one range") {
    final case class Trial(group: String, x: Double, y: Double)
    val trials = Vector(
      Trial("a", 0.0, 0.0),
      Trial("a", 1.0, 1.0),
      Trial("b", 0.5, 0.25),
      Trial("b", 0.75, 0.5),
      Trial("c", 0.1, 0.9),
      Trial("c", 0.2, 0.8)
    )
    val context = RenderContext.unsafe(width = 640, height = 240)

    /** Every clipped group that draws discs, with its minimum ink clearance. */
    def clearances(plan: RenderPlan): Vector[Double] =
      def walk(element: DeviceElement): Vector[Double] =
        element match
          case DeviceElement.Group(_, Some(clip), _, children) =>
            val discs = children.collect { case DeviceElement.Mark(d: DevicePrimitive.Disc) => d }
            val here = discs.map { d =>
              val e = d.radius + d.gp.stroke.fold(0.0)(_ => d.gp.lineWidth / 2.0)
              Vector(
                d.centerX - e - clip.x,
                d.centerY - e - clip.y,
                clip.x + clip.width - d.centerX - e,
                clip.y + clip.height - d.centerY - e
              ).min
            }
            (if here.isEmpty then Vector.empty else Vector(here.min)) ++ children.flatMap(walk)
          case DeviceElement.Group(_, None, _, children) => children.flatMap(walk)
          case DeviceElement.Annotated(_, children)      => children.flatMap(walk)
          case _                                         => Vector.empty
      ok(plan.deviceScene).elements.flatMap(walk)
    def facetted(scales: FacetScales, framing: PanelFraming) =
      plot(trials)
        .aes(_.x, _.y)
        .scaleXContinuous()
        .scaleYContinuous()
        .size(6.0)
        .geomPoint()
        .facetWrap(_.group, columns = 3, scales = scales)
        .framing(framing)
    val expected = 2.0 * 96.0 / 72.0
    Vector(FacetScales.Shared, FacetScales.FreeX, FacetScales.FreeY, FacetScales.Free).foreach {
      scales =>
        assert(clearances(ok(facetted(scales, PanelFraming.Data).renderPlan(context))).min < 0.0)
        val framed = ok(facetted(scales, PanelFraming.markInk).renderPlan(context))
        val perPanel = clearances(framed)
        assertEquals(perPanel.length, 3, clue(scales))
        perPanel.foreach(c => assert(c >= expected - 1e-6, clue((scales, perPanel))))
        val layouts = ok(facetted(scales, PanelFraming.markInk).resolve(context)).facetPanels
          .map(_.layout)
        if !scales.xIsFree then assertEquals(layouts.map(_.xScale).distinct.length, 1, clue(scales))
        if !scales.yIsFree then assertEquals(layouts.map(_.yScale).distinct.length, 1, clue(scales))
    }
  }

  test("a fixed aspect stays exact while the ink is framed") {
    val context = RenderContext.unsafe(width = 640, height = 220)
    val program = specimen(PanelFraming.markInk).coordFixed(ratio = 1.0)
    val plan = ok(program.renderPlan(context))
    assertEqualsDouble(clearanceOf(plan), 2.0 * 96.0 / 72.0, 1e-6)
    // Ratio 1: one data unit spans as many pixels across as up, so the panel's pixel aspect equals
    // the ranges' aspect. That holds only if the returned panel was solved at the returned ranges.
    val layout = ok(program.resolve(context)).layout.getOrElse(fail("missing layout"))
    val (clip, _) = panelInk(plan)
    assertEqualsDouble(
      clip.height / clip.width,
      layout.yScale.width / layout.xScale.width,
      1e-9
    )
  }

  test("a free facet axis frames alone even when its base range equals a neighbour's") {
    final case class Mark(group: String, x: Double, y: Double, size: Double)
    // Both panels train x to [0, 10]; only panel "a" has a large mark at the edge.
    val marks = Vector(
      Mark("a", 0.0, 0.0, 2.0),
      Mark("a", 10.0, 1.0, 12.0),
      Mark("b", 0.0, 0.0, 2.0),
      Mark("b", 10.0, 1.0, 2.0)
    )
    val resolved = ok(
      plot(marks)
        .aes(_.x, _.y)
        .scaleXContinuous()
        .scaleYContinuous()
        .size(_.size)
        .geomPoint()
        .facetWrap(_.group, columns = 2, scales = FacetScales.FreeX)
        .framing(PanelFraming.markInk)
        .resolve(RenderContext.unsafe(width = 640, height = 240))
    )
    val xs = resolved.facetPanels.map(panel => panel.cell -> panel.layout.xScale)
    assertEquals(xs.length, 2)
    val (a, b) = (xs(0)._2, xs(1)._2)
    assert(a.width > b.width, clue(xs))
    // The shared y axis still pools both panels.
    assertEquals(resolved.facetPanels.map(_.layout.yScale).distinct.length, 1)
  }

  test("rich and lean lowering frame to the same ranges") {
    val context = RenderContext.unsafe(width = 640, height = 220)
    def layout(options: PlotCompilerOptions) =
      ok(
        specimen(PanelFraming.markInk)
          .compilerOptions(options.copy(framing = PanelFraming.markInk))
          .resolve(context)
      ).layout.getOrElse(fail("missing layout"))
    val base = PlotCompilerOptions(guides = GuidePolicy.Derived())
    val rich = layout(base)
    val lean = layout(base.copy(provenance = ProvenancePolicy.None))
    assertEquals(lean.xScale, rich.xScale)
    assertEquals(lean.yScale, rich.yScale)
  }

  test("the axis solver returns the narrowest fitting span and refuses an impossible fit") {
    import MarkInkFraming.{AxisMark, frameAxis}
    val base = Interval.unsafe(-0.05, 1.05)
    // Two marks at the data extremes with 10 px of ink on a 100 px axis.
    val marks = Vector(AxisMark(0.0, 0.0, 10.0, 10.0, 100.0), AxisMark(1.0, 0.0, 10.0, 10.0, 100.0))
    val framed = frameAxis(base, marks)
    // Exact answer: 10 px each side leaves 80 px for the unit data span.
    assertEqualsDouble(framed.lower, -0.125, 1e-12)
    assertEqualsDouble(framed.upper, 1.125, 1e-12)
    // Already fitting on a 1000 px axis: unchanged.
    assertEquals(
      frameAxis(
        base,
        Vector(AxisMark(0.0, 0.0, 10.0, 10.0, 1000.0), AxisMark(1.0, 0.0, 10.0, 10.0, 1000.0))
      ),
      base
    )
    // Ink wider than a 15 px axis cannot fit at any span.
    assertEquals(
      frameAxis(
        base,
        Vector(AxisMark(0.0, 0.0, 10.0, 10.0, 15.0), AxisMark(1.0, 0.0, 10.0, 10.0, 15.0))
      ),
      base
    )
    // A mark offset by absolute pixels moves its constraint with it.
    val offset = frameAxis(base, Vector(AxisMark(1.0, 5.0, 0.0, 10.0, 100.0)))
    assertEqualsDouble((1.0 - offset.lower) / offset.width * 100.0 + 5.0 + 10.0, 100.0, 1e-9)
    // Marks from a 100 px and a 200 px panel sharing one range pool into one solve.
    val pooled = frameAxis(
      base,
      Vector(AxisMark(0.0, 0.0, 10.0, 0.0, 100.0), AxisMark(1.0, 0.0, 0.0, 30.0, 200.0))
    )
    assert(pooled.lower <= 0.0 - 0.1 * pooled.width + 1e-12)
    assert(pooled.upper >= 1.0 + 0.15 * pooled.width - 1e-12)
    assertEqualsDouble(pooled.width, 1.0 / 0.75, 1e-12)
  }
