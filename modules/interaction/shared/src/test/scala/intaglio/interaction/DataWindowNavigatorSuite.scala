package intaglio.interaction

import intaglio.*

/** Navigation arithmetic checked against the panel frame's own data-to-device mapping and against
  * the compiler: rectangles give the data window they cover, zoom keeps the pivot's data fixed,
  * windows stay inside the trained extent and keep their axis kind, and region selection matches an
  * independent geometric oracle.
  */
class DataWindowNavigatorSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: String, x: Double, y: Double, day: CalendarDate)

  private val rows = Vector.tabulate(30) { i =>
    Obs(
      s"o$i",
      2.0 + i * 3.0,
      math.cos(i / 3.0),
      ok(CalendarDate(2026, 1, 1)).addDaysUnsafe(i * 2L)
    )
  }
  private val context = RenderContext.unsafe(480, 320)
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
  private val space = ok(KeySpace("obs", KeyCodec.text))

  private def compile(builder: PlotBuilder[Obs, ?]): InteractionPlan[String] =
    ok(
      InteractionCompiler.compile(
        ok(builder.build).plot,
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe("p"),
        ok(PlanRevision("r")),
        options
      )(_.id)
    )

  private val linear = compile(
    plot(rows).aes(_.x, _.y).scaleXContinuous().scaleYContinuous().geomPoint()
  )
  private val logged = compile(
    plot(rows)
      .aes(_.x, _.y)
      .scaleXContinuous(transform = Transform.log10)
      .scaleYContinuous()
      .geomPoint()
  )

  private def panel(plan: InteractionPlan[String]): ResolvedViewportFrame =
    ok(DeviceScene.fromScene(plan.scene, context))
      .frame(PlotRegion.Panel)
      .fold(e => fail(e.message), identity)

  private def device(plan: InteractionPlan[String], x: Double, y: Double): DevicePoint =
    panel(plan).nativeToDevice(DevicePoint(x, y)).fold(e => fail(e.message), identity)

  private def numeric(window: Option[CoordinateWindow]): (Double, Double) =
    window match
      case Some(CoordinateWindow.Numeric(range)) => (range.lower, range.upper)
      case other                                 => fail(s"expected a numeric window, got $other")

  test("a rubber band over data x 20..60 is the window 20..60, on linear and log axes") {
    Vector(linear, logged).foreach { plan =>
      val nav = ok(DataWindowNavigator.of(plan, context))
      val window = nav.rectangle(panel(plan).frame, device(plan, 20, -0.5), device(plan, 60, 0.5))
      val (x, _) = ok(nav.windows(window))
      val (lo, hi) = numeric(x)
      assertEqualsDouble(lo, 20, 1e-6)
      assertEqualsDouble(hi, 60, 1e-6)
    }
  }

  test("zooming keeps the data under the pivot where it was, after the plan is re-windowed") {
    Vector(linear, logged).foreach { plan =>
      val nav = ok(DataWindowNavigator.of(plan, context))
      val pivot = device(plan, 35, 0.2)
      val (x, y) = ok(nav.windows(nav.zoom(panel(plan).frame, pivot, 0.5)))
      val zoomed = ok(InteractionCompiler.rezoom(plan, x, y))
      val after = panel(zoomed).deviceToNative(pivot).fold(e => fail(e.message), identity)
      assertEqualsDouble(after.x, 35, 1e-6)
      assertEqualsDouble(after.y, 0.2, 1e-6)
    }
  }

  test("windows stay inside the trained extent; zooming out past it is the compiled view") {
    val nav = ok(DataWindowNavigator.of(linear, context))
    val frame = panel(linear).frame
    val centre = DevicePoint(frame.x + frame.width / 2, frame.y + frame.height / 2)
    assertEquals(nav.zoom(frame, centre, 2.0), PanelWindow.full)
    val (x, y) = ok(nav.windows(nav.zoom(frame, centre, 0.25)))
    val zoomed = ok(InteractionCompiler.rezoom(linear, x, y))
    // A huge drag to the left reaches the right edge of the data and stops there.
    val panned = nav.pan(panel(zoomed).frame, -100000, 0)
    val (px, _) = ok(nav.windows(panned))
    assertEqualsDouble(numeric(px)._2, rows.map(_.x).max, 1e-6)
    assertEquals(nav.pan(frame, 50, 50), PanelWindow.full, "panning the full view changes nothing")
  }

  test("a date axis yields a date window") {
    val dated = compile(
      plot(rows)
        .aes(_.x, _.y)
        .scaleXDate(_.day)
        .encode(Aesthetic.Y, _.y, ok(ContinuousScaleSpec("y", Palette.numeric)))
        .geomPoint()
    )
    val nav = ok(DataWindowNavigator.of(dated, context))
    val frame = panel(dated).frame
    val window = nav.rectangle(
      frame,
      DevicePoint(frame.x + frame.width * 0.3, frame.y + 5),
      DevicePoint(frame.x + frame.width * 0.6, frame.y + frame.height - 5)
    )
    ok(nav.windows(window))._1 match
      case Some(CoordinateWindow.Date(_)) => ()
      case other                          => fail(s"expected a date window, got $other")
    assert(
      ok(InteractionCompiler.rezoom(dated, ok(nav.windows(window))._1, None)).scene != dated.scene
    )
  }

  test("region selection adds and subtracts exactly the marks a rectangle covers") {
    val picking = ok(Picking.compile(linear, context))
    val host = HostInput(
      picking,
      picking.prepareNavigation(),
      ok(PickViewport.fit(480, 320, 0, 0, 480, 320)),
      InteractionBehavior.default[String]
    )
    val domain = ok(InteractionDomain(Vector(linear), linear.revision))
    var state = ok(InteractionState.initial(domain))
    var sequence = 0L
    def apply(actions: Vector[HostAction[String]]) =
      actions.foreach { a =>
        sequence += 1
        state = ok(
          InteractionState.reduce(
            state,
            InputStamp(domain.revision, SemanticId.unsafe("h"), sequence, a.cause),
            a.action
          )
        ).state
      }
    def area(x0: Double, x1: Double) =
      val a = device(linear, x0, 1.2)
      val b = device(linear, x1, -1.2)
      ok(
        PickArea.rectangle(
          math.min(a.x, b.x),
          math.min(a.y, b.y),
          math.max(a.x, b.x),
          math.max(a.y, b.y)
        )
      )
    // Independent oracle: rows whose data x lies inside the band (edges avoid data at 2 + 3i).
    def expected(x0: Double, x1: Double) = rows.filter(r => r.x > x0 && r.x < x1).map(_.id).toSet
    apply(host.region(state, area(10.5, 40.5), AreaRule.CenterInside, SelectionOperation.Replace))
    assertEquals(state.selection.entities.map(_.value), expected(10.5, 40.5))
    apply(host.region(state, area(60.5, 80.5), AreaRule.CenterInside, SelectionOperation.Add))
    assertEquals(
      state.selection.entities.map(_.value),
      expected(10.5, 40.5) ++ expected(60.5, 80.5)
    )
    apply(host.region(state, area(20.5, 70.5), AreaRule.CenterInside, SelectionOperation.Subtract))
    assertEquals(
      state.selection.entities.map(_.value),
      (expected(10.5, 40.5) ++ expected(60.5, 80.5)) -- expected(20.5, 70.5)
    )
  }

  test("an application window is checked, shifted inside the extent, and full means compiled") {
    val nav = ok(DataWindowNavigator.of(linear, context))
    assert(nav.normalize(PanelWindow(Some((0.6, 0.2)), None)).isLeft, "reversed")
    assert(nav.normalize(PanelWindow(Some((0.1, Double.NaN)), None)).isLeft, "not finite")
    assertEquals(ok(nav.normalize(PanelWindow(Some((-0.2, 0.3)), None))).x, Some((0.0, 0.5)))
    assertEquals(
      ok(nav.normalize(PanelWindow(Some((0.0, 1.0)), Some((-1.0, 2.0))))),
      PanelWindow.full
    )
    val raw = compile(plot(rows).aes(_.x, _.y).geomPoint())
    val rawNav = ok(DataWindowNavigator.of(raw, context))
    val extent = panel(raw).frame.xScale
    assertEquals(
      ok(rawNav.normalize(PanelWindow(Some((extent.lower - 5, extent.lower + 5)), None))).x,
      Some((extent.lower, extent.lower + 10))
    )
  }

  test("zooming in on a date axis stops at a window the axis can still show") {
    val dated = compile(
      plot(rows)
        .aes(_.x, _.y)
        .scaleXDate(_.day)
        .encode(Aesthetic.Y, _.y, ok(ContinuousScaleSpec("y", Palette.numeric)))
        .geomPoint()
    )
    val nav = ok(DataWindowNavigator.of(dated, context))
    var plan = dated
    var shown = Vector.empty[CoordinateWindow]
    (1 to 40).foreach { _ =>
      val frame = panel(plan).frame
      val centre = DevicePoint(frame.x + frame.width / 2, frame.y + frame.height / 2)
      val window = nav.zoom(frame, centre, 0.5)
      val (x, _) = ok(nav.windows(window))
      x.foreach(w => shown :+= w)
      plan = ok(InteractionCompiler.rezoom(dated, x, None))
    }
    val last = shown.last match
      case CoordinateWindow.Date(range) => range
      case other                        => fail(s"expected a date window, got $other")
    assert(last.lower != last.upper, s"zoom stopped before a one-day window: $last")
    val day = 1.0 / 58.0 // the domain spans 58 days
    assert(nav.normalize(PanelWindow(Some((0.5, 0.5 + day / 10)), None)).isLeft, "one value")
    assertEquals(shown.takeRight(5).distinct.size, 1, "further zoom-in keeps the last window")
  }
