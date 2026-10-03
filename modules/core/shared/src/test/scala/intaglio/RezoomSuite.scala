package intaglio

import intaglio.interaction.*

/** Data-window navigation re-windows a compiled interactive plot without recompiling it: no
  * statistic runs again, and the result is the scene a fresh compile at the same window draws.
  */
class RezoomSuite extends munit.FunSuite:
  private def ok[A](result: Either[IntaglioError, A]): A =
    result.fold(error => fail(error.message), identity)

  final case class Obs(id: String, x: Double, y: Double, day: CalendarDate)

  private val rows = Vector.tabulate(40) { i =>
    Obs(
      s"o$i",
      1.0 + i * 2.5,
      math.sin(i / 4.0),
      ok(CalendarDate(2026, 1, 1)).addDaysUnsafe(i * 3L)
    )
  }
  private val context = RenderContext.unsafe(480, 320)
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
  private val space = ok(KeySpace("obs", KeyCodec.text))

  /** The identity statistic, counting how often it runs. */
  private final class CountingStat extends Stat[Obs]:
    var calls = 0
    val label = "counting-identity"
    val contract = Stat.Identity.contract
    def compute[Input <: Obs](
        batch: StatBatch[Input],
        context: StatContext
    ): Either[StatError, StatResult[Input]] =
      calls += 1
      Stat.Identity.compute(batch, context)

  private def plotWith(stat: Stat[Obs], x: AesSpec[Obs] => Either[GraphicsError, AesSpec[Obs]]) =
    val mapping = ok(x(AesSpec.empty[Obs].withPosition(_.x, _.y)))
    ok(
      Plot(rows).addLayer(
        ok(Layer.fromMapping(Geom.Point, mapping, inheritMapping = false, stat = stat))
      )
    )

  private val linear: AesSpec[Obs] => Either[GraphicsError, AesSpec[Obs]] = spec =>
    ContinuousScaleSpec("x", Palette.numeric).flatMap(s =>
      spec.bindScale(ScaleBinding(Aesthetic.X, (o: Obs) => o.x, s))
    )
  private val logX: AesSpec[Obs] => Either[GraphicsError, AesSpec[Obs]] = spec =>
    ContinuousScaleSpec("x", Palette.numeric, Transform.log10)
      .flatMap(s => spec.bindScale(ScaleBinding(Aesthetic.X, (o: Obs) => o.x, s)))
  private val dateX: AesSpec[Obs] => Either[GraphicsError, AesSpec[Obs]] = spec =>
    DateScaleSpec("day").flatMap(s =>
      spec.bindScale(ScaleBinding(Aesthetic.X, (o: Obs) => o.day, s))
    )

  private def compile(plot: Plot[Obs]): InteractionPlan[String] =
    ok(
      InteractionCompiler.compile(
        plot,
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe("p"),
        ok(PlanRevision("r")),
        options
      )(_.id)
    )

  test("re-windowing runs no statistic, mapping or scale training") {
    val stat = CountingStat()
    val plan = compile(plotWith(stat, linear))
    assertEquals(stat.calls, 1)
    val windows = Vector((10.0, 50.0), (20.0, 30.0), (1.0, 98.0))
    val (_, readings) = PhaseClock.profile {
      windows.foreach { (lo, hi) =>
        ok(InteractionCompiler.rezoom(plan, Some(ok(CoordinateWindow.numeric(lo, hi))), None))
      }
    }
    assertEquals(stat.calls, 1, "the statistic did not run again")
    Vector(PhaseClock.Phase.Mapping, PhaseClock.Phase.Stat, PhaseClock.Phase.ScaleTraining)
      .foreach { phase =>
        assertEquals(readings.get(phase).fold(0L)(_.calls), 0L, phase.toString)
      }
  }

  private def ticks(plan: InteractionPlan[String]): Vector[Vector[(Double, String)]] =
    plan.trained.guides.map(_.spec).collect { case axis: GuideSpec.Axis =>
      axis.ticks.getOrElse(Vector.empty).map(t => (t.value, t.label))
    }

  /** A re-windowed plan shows the panel ranges and axis ticks a fresh compile at the same window
    * shows. (The fresh compile solves its own layout; navigation keeps the original one.)
    */
  private def oracle(
      axis: AesSpec[Obs] => Either[GraphicsError, AesSpec[Obs]],
      window: CoordinateWindow
  ): Unit =
    val plot = plotWith(Stat.Identity, axis)
    val zoomed = ok(InteractionCompiler.rezoom(compile(plot), Some(window), None))
    val fresh = compile(plot.withCoord(ok(Coord.zoomWindows(x = Some(window)))))
    assertEquals(zoomed.trained.layout.map(_.xScale), fresh.trained.layout.map(_.xScale))
    assertEquals(zoomed.trained.layout.map(_.yScale), fresh.trained.layout.map(_.yScale))
    assertEquals(ticks(zoomed), ticks(fresh))

  test("the panel does not move as the window changes") {
    val plan = compile(plotWith(Stat.Identity, linear))
    Vector((10.0, 50.0), (20.0, 21.0), (1.0, 98.0)).foreach { (lo, hi) =>
      val zoomed =
        ok(InteractionCompiler.rezoom(plan, Some(ok(CoordinateWindow.numeric(lo, hi))), None))
      assertEquals(zoomed.trained.layout.map(_.frame), plan.trained.layout.map(_.frame))
    }
  }

  test("a numeric window shows the ranges and ticks a fresh compile at that window shows") {
    oracle(linear, ok(CoordinateWindow.numeric(10, 50)))
  }

  test(
    "a window on a log axis is raw data, mapped through the transform, as a fresh compile does"
  ) {
    oracle(logX, ok(CoordinateWindow.numeric(5, 50)))
  }

  test("a date window keeps the date axis kind") {
    oracle(
      dateX,
      ok(CoordinateWindow.date(ok(CalendarDate(2026, 1, 20)), ok(CalendarDate(2026, 3, 1))))
    )
  }

  test("marks, targets and identity carry over; an empty window restores the compiled view") {
    val plan = compile(plotWith(Stat.Identity, linear))
    val zoomed =
      ok(InteractionCompiler.rezoom(plan, Some(ok(CoordinateWindow.numeric(10, 50))), None))
    assert(zoomed.groups eq plan.groups)
    assertEquals(zoomed.revision, plan.revision)
    assertEquals(zoomed.trained.layers.map(_.grobs), plan.trained.layers.map(_.grobs))
    assertNotEquals(zoomed.scene, plan.scene)
    assertEquals(ok(InteractionCompiler.rezoom(zoomed, None, None)).scene, plan.scene)
  }

  test(
    "a window of the wrong kind, a faceted plot, flipped coordinates and a context-free plan are refused"
  ) {
    val plan = compile(plotWith(Stat.Identity, linear))
    val dateWindow =
      ok(CoordinateWindow.date(ok(CalendarDate(2026, 1, 1)), ok(CalendarDate(2026, 2, 1))))
    assert(InteractionCompiler.rezoom(plan, Some(dateWindow), None).isLeft)
    val flipped = compile(plotWith(Stat.Identity, linear).withCoord(Coord.Flipped()))
    assert(
      InteractionCompiler.rezoom(flipped, Some(ok(CoordinateWindow.numeric(1, 2))), None).isLeft
    )
    val faceted = compile(
      (plotWith(Stat.Identity, linear).withFacet(
        ok(FacetSpec.wrap[Obs](o => if o.x < 50 then "a" else "b"))
      ))
    )
    assert(
      InteractionCompiler.rezoom(faceted, Some(ok(CoordinateWindow.numeric(1, 2))), None).isLeft
    )
    val unplaced = ok(
      InteractionCompiler.compile(
        plotWith(Stat.Identity, linear),
        space,
        ok(DataRevision("d")),
        SemanticId.unsafe("p"),
        ok(PlanRevision("r"))
      )(_.id)
    )
    assert(
      InteractionCompiler.rezoom(unplaced, Some(ok(CoordinateWindow.numeric(1, 2))), None).isLeft
    )
  }
