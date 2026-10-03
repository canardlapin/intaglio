package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** The page behind tools/check-performance-browser.cjs: one keyed scatter of `n` deterministic
  * points (a 10,000-mark SVG and a 100,000-point Canvas workload) and an aggregate histogram whose
  * bins keep their exact members. Every phase is timed here with `performance.now()`; the check
  * script times what crosses an animation frame and reads the heap through the DevTools protocol.
  *
  * The data deliberately overlaps: every 25th row repeats the previous row's position, so coincident
  * marks with distinct keys are part of every query.
  */
object PerformanceFixture:
  final case class Row(id: String, x: Double, y: Double, group: String)

  private def orThrow[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  private val space = orThrow(KeySpace("row", KeyCodec.text))
  private val context = RenderContext.unsafe(640, 420)

  /** SplitMix64 rows; identical on every run and platform. */
  def rows(n: Int): Vector[Row] =
    var state = 0x9e3779b97f4a7c15L
    def next(): Double =
      state += 0x9e3779b97f4a7c15L
      var z = state
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
      z = z ^ (z >>> 31)
      (z >>> 11).toDouble / (1L << 53).toDouble
    val out = Vector.newBuilder[Row]
    var previous: Option[Row] = None
    var i = 0
    while i < n do
      val group = if i % 3 == 0 then "B" else "A"
      val row = previous match
        case Some(p) if i % 25 == 0 => Row(s"r$i", p.x, p.y, group)
        case _                      =>
          val u = next()
          val v = next()
          Row(s"r$i", 1000.0 * u, 0.5 + 0.35 * (v - 0.5) + 0.15 * math.sin(6.0 * u), group)
      out += row
      previous = Some(row)
      i += 1
    out.result()

  private def now(): Double = g.performance.now().asInstanceOf[Double]
  private def timed[A](body: => A): (A, Double) =
    val start = now()
    val value = body
    (value, now() - start)

  private def scatterPlan(
      data: Vector[Row],
      revision: String,
      pointSize: Double
  ): InteractionPlan[String] =
    val options = PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    orThrow(
      InteractionCompiler.compile(
        orThrow(
          plot(data)
            .aes(_.x, _.y)
            .scaleColorDiscrete(_.group, levels = Vector("A", "B"), name = "group")
            .size(pointSize)
            .geomPoint()
            .title("Performance scatter")
            .build
        ).plot,
        space,
        orThrow(DataRevision(revision)),
        SemanticId.unsafe("scatter"),
        orThrow(PlanRevision(revision)),
        options
      )(_.id)
    )

  private def histogramPlan(
      data: Vector[Row],
      retention: MembershipRetention
  ): InteractionPlan[String] =
    val options = PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
    orThrow(
      InteractionCompiler.compile(
        orThrow(
          plot(data)
            .aes(_.x)
            .geomHistogram(bins = HistogramBins.breaksUnsafe(Vector.tabulate(41)(_ * 25.0)))
            .title("Performance histogram")
            .build
        ).plot,
        space,
        orThrow(DataRevision("h")),
        SemanticId.unsafe("histogram"),
        orThrow(PlanRevision("h")),
        options,
        retention
      )(_.id)
    )

  // The single workload this page holds. Release drops every reference so the heap check can see
  // what a disposed widget leaves behind.
  private var data: Vector[Row] = Vector.empty
  private var view: Option[SvgWidgetView[String]] = None
  private var widget: Option[SvgWidget[String]] = None
  private var renderer: WidgetRenderer = WidgetRenderer.Svg
  private var pointSize = 3.0
  private var revisions = 0

  private val behavior: InteractionBehavior[String] =
    InteractionBehavior.default[String].withInverseEmphasis(true)

  private val histogramBehavior: InteractionBehavior[String] =
    InteractionBehavior
      .default[String]
      .withAggregateSelection(_ => AggregateSelection.Members)
      .withAggregateEmphasis(orThrow(EmphasisRule.fraction(0.5)))

  private def host = g.document.getElementById("perf")

  /** Build the scatter for `n` rows, timing every phase separately. */
  def prepare(n: Int, canvas: Boolean): js.Dynamic =
    renderer = if canvas then WidgetRenderer.Canvas else WidgetRenderer.Svg
    pointSize = if n > 20000 then 1.5 else 3.0
    val (rowsBuilt, rowsMs) = timed(rows(n))
    data = rowsBuilt
    val (plan, planMs) = timed(scatterPlan(data, "r0", pointSize))
    val (device, lowerMs) = timed(orThrow(DeviceScene.fromScene(plan.scene, context)))
    val (picking, pickingMs) =
      timed(orThrow(Picking.fromResolved(device, plan.groups, context, PickPolicy.default)))
    val (navigation, navigationMs) = timed(picking.prepareNavigation())
    val (compiled, viewMs) = timed(orThrow(SvgWidgetView.compile(plan, context, "perf")))
    view = Some(compiled)
    js.Dynamic.literal(
      marks = compiled.navigation.targets.size,
      pickTargets = picking.targetCount,
      navigationTargets = navigation.targets.size,
      rowsMs = rowsMs,
      planMs = planMs,
      lowerMs = lowerMs,
      pickingMs = pickingMs,
      navigationMs = navigationMs,
      viewMs = viewMs,
      markupChars = compiled.markup.length
    )

  /** Build only the rows, for the membership workload. */
  def load(n: Int, canvas: Boolean): Double =
    renderer = if canvas then WidgetRenderer.Canvas else WidgetRenderer.Svg
    val (built, ms) = timed(rows(n))
    data = built
    ms

  def mount(): js.Dynamic =
    val current = view.getOrElse(throw new IllegalStateException("prepare first"))
    val (mounted, ms) = timed(
      orThrow(
        SvgWidget.mount(
          host,
          current,
          behavior,
          label = "performance plot",
          options = WidgetOptions(renderer = renderer)
        )
      )
    )
    widget = Some(mounted)
    js.Dynamic.literal(ms = ms, nodes = g.document.getElementsByTagName("*").length)

  private def mounted = widget.getOrElse(throw new IllegalStateException("mount first"))
  private def shown = view.getOrElse(throw new IllegalStateException("prepare first"))

  /** Client coordinates of target `index`'s anchor, in reading order. */
  def anchor(index: Int): js.Array[Double] =
    val geometry = shown.navigation.targets(index % shown.navigation.targets.size)
    val box = g.document.querySelector("#perf .intaglio-base").getBoundingClientRect()
    val scale = box.width.asInstanceOf[Double] / shown.width
    js.Array(
      box.left.asInstanceOf[Double] + geometry.anchor.x * scale,
      box.top.asInstanceOf[Double] + geometry.anchor.y * scale
    )

  /** `count` nearest-target queries spread over the plot, through the public picking plan. */
  def pickBurst(count: Int, tolerance: Double): js.Dynamic =
    val picking = shown.picking
    var found = 0
    val (_, ms) = timed {
      var i = 0
      while i < count do
        val x = (i * 7919 % 1000) / 1000.0 * shown.width
        val y = (i * 104729 % 1000) / 1000.0 * shown.height
        if orThrow(picking.nearest(DevicePoint(x, y), tolerance)).nonEmpty then found += 1
        i += 1
    }
    js.Dynamic.literal(ms = ms, perQueryMs = ms / count, found = found)

  /** Select the first `count` rows' entities programmatically. */
  def select(count: Int): Double =
    val keys = data.take(count).map(row => orThrow(space.entity(row.id))).toSet
    val (_, ms) = timed(orThrow(mounted.setSelection(Selection(keys))))
    ms

  def selectedCount: Int = mounted.state.toOption.fold(-1)(_.selection.entities.size)

  /** Zoom to the central `fraction` of each axis, or back to the full window. */
  def navigate(fraction: Double): Double =
    val window =
      if fraction >= 1 then PanelWindow.full
      else
        val frame = shown.panelFrame.get
        def middle(interval: Interval): (Double, Double) =
          val centre = (interval.lower + interval.upper) / 2
          val half = (interval.upper - interval.lower) * fraction / 2
          (centre - half, centre + half)
        PanelWindow(Some(middle(frame.xScale)), Some(middle(frame.yScale)))
    val (_, ms) = timed(orThrow(mounted.navigate(window)))
    ms

  /** Repaint `count` targets through application styles. */
  def restyle(count: Int): Double =
    val styles = shown.navigation.targets
      .take(count)
      .map(_.target.id -> WidgetTargetStyle(fill = Some(Rgba.unsafe(204, 26, 26, 1.0))))
      .toMap
    val (_, ms) = timed(orThrow(mounted.setTargetStyles(styles)))
    ms

  /** Replace the view: the same revision recompiled (a restyle or resize of the same data), or a
    * new revision with every 100th row removed. Reports the time to compile the next view and to
    * show it, and whether surviving selected entities stay selected.
    */
  def update(newData: Boolean): js.Dynamic =
    val before = mounted.state.toOption.map(_.selection.entities.map(_.value)).getOrElse(Set.empty)
    val next =
      if newData then
        revisions += 1
        data.zipWithIndex.collect { case (row, i) if i % 100 != 99 => row }
      else data
    val revision = if newData then s"r$revisions" else s"r${revisions}"
    val (compiled, compileMs) = timed {
      val plan = scatterPlan(next, revision, pointSize)
      orThrow(SvgWidgetView.compile(plan, context, "perf"))
    }
    val (_, updateMs) = timed(orThrow(mounted.update(compiled)))
    data = next
    view = Some(compiled)
    val after = mounted.state.toOption.map(_.selection.entities.map(_.value)).getOrElse(Set.empty)
    val surviving = next.map(_.id).toSet
    js.Dynamic.literal(
      compileMs = compileMs,
      updateMs = updateMs,
      selectedBefore = before.size,
      selectedAfter = after.size,
      preserved = after == before.intersect(surviving)
    )

  /** Dispose the widget; the page keeps no reference afterwards. */
  def dispose(): js.Dynamic =
    val listeners = widget.fold(0) { w =>
      w.dispose()
      w.domListenerCount
    }
    widget = None
    js.Dynamic.literal(listeners = listeners, roots = host.childElementCount)

  /** Drop every reference to the workload so the check can measure retained heap. */
  def release(): Unit =
    widget.foreach(_.dispose())
    widget = None
    view = None
    data = Vector.empty

  /** Mount and dispose a fresh widget over the prepared view `times` times, keeping only weak
    * references to each widget; the check collects garbage and counts survivors.
    */
  def cycle(times: Int): Double =
    val current = shown
    val (_, ms) = timed {
      (0 until times).foreach { _ =>
        val w = orThrow(
          SvgWidget.mount(host, current, behavior, options = WidgetOptions(renderer = renderer))
        )
        weak.push(js.Dynamic.newInstance(g.WeakRef)(w.asInstanceOf[js.Any]))
        w.dispose()
      }
    }
    ms / times

  private val weak = js.Array[js.Dynamic]()

  /** How many cycled widgets are still reachable. Meaningful only after a full collection. */
  def survivors(): Int = weak.count(ref => !js.isUndefined(ref.deref()))

  /** Aggregate membership cost: compile a 40-bin histogram over the prepared rows under `retention`,
    * mount it, then select `selected` observations so every bin's coverage is measured at redraw.
    */
  def membership(retention: String, selected: Int): js.Dynamic =
    val mode = MembershipRetention.valueOf(retention)
    val (plan, compileMs) = timed(histogramPlan(data, mode))
    val (compiled, viewMs) = timed(orThrow(SvgWidgetView.compile(plan, context, "perf")))
    view = Some(compiled)
    val (w, mountMs) = timed(
      orThrow(
        SvgWidget.mount(
          host,
          compiled,
          // Member selection needs exact (or deferred) members; counts alone select the bin.
          if mode == MembershipRetention.ExactKeys then histogramBehavior
          else InteractionBehavior.default[String],
          label = "membership plot",
          options = WidgetOptions(renderer = renderer)
        )
      )
    )
    widget = Some(w)
    val keys = data.take(selected).map(row => orThrow(space.entity(row.id))).toSet
    val (_, selectMs) = timed(orThrow(w.setSelection(Selection(keys))))
    js.Dynamic.literal(
      bins = compiled.navigation.targets.size,
      compileMs = compileMs,
      viewMs = viewMs,
      mountMs = mountMs,
      selectMs = selectMs
    )

  def coveredRings: Int = g.document.querySelectorAll("#perf .intaglio-ring-covered").length.asInstanceOf[Int]

  def run(): Unit =
    g.window.intaglioPerf = js.Dynamic.literal(
      prepare = (n: Int, canvas: Boolean) => prepare(n, canvas),
      load = (n: Int, canvas: Boolean) => load(n, canvas),
      mount = () => mount(),
      anchor = (index: Int) => anchor(index),
      pickBurst = (count: Int, tolerance: Double) => pickBurst(count, tolerance),
      select = (count: Int) => select(count),
      selectedCount = () => selectedCount,
      navigate = (fraction: Double) => navigate(fraction),
      restyle = (count: Int) => restyle(count),
      update = (newData: Boolean) => update(newData),
      dispose = () => dispose(),
      release = () => release(),
      cycle = (times: Int) => cycle(times),
      survivors = () => survivors(),
      membership = (retention: String, selected: Int) => membership(retention, selected),
      coveredRings = () => coveredRings,
      ready = true
    )
