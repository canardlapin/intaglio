package intaglio.browserfixture

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** The navigation page behind tools/check-navigation-browser.cjs: a linear scatter compiled with a
  * counting statistic, a log10 scatter and a date scatter, each with region selection and
  * data-window navigation.
  */
object NavigationFixture:
  final case class Obs(id: String, x: Double, y: Double, day: CalendarDate)

  private def orThrow[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  private val rows = Vector.tabulate(40) { i =>
    Obs(
      s"o${i + 1}",
      2.0 + i * 2.5,
      math.sin(i / 5.0) + (i % 4) * 0.1,
      orThrow(CalendarDate(2026, 1, 1)).addDaysUnsafe(i * 3L)
    )
  }
  private val context = RenderContext.unsafe(480, 320)
  private val options =
    PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
  private val space = orThrow(KeySpace("obs", KeyCodec.text))

  /** The identity statistic, counting how often it runs. */
  private var statCalls = 0
  private object CountingStat extends Stat[Obs]:
    val label = "counting-identity"
    val contract = Stat.Identity.contract
    def compute[Input <: Obs](
        batch: StatBatch[Input],
        context: StatContext
    ): Either[StatError, StatResult[Input]] =
      statCalls += 1
      Stat.Identity.compute(batch, context)

  private def view(slot: String): SvgWidgetView[String] =
    val (plotValue, title) = slot match
      case "linear" =>
        val mapping = orThrow(
          AesSpec
            .empty[Obs]
            .withPosition(_.x, _.y)
            .bindScale(
              ScaleBinding(
                Aesthetic.X,
                (o: Obs) => o.x,
                orThrow(ContinuousScaleSpec("x", Palette.numeric))
              )
            )
        )
        val layer = orThrow(
          Layer.fromMapping(Geom.Point, mapping, inheritMapping = false, stat = CountingStat)
        )
        (orThrow(Plot(rows).addLayer(layer)).withTitle("Linear (counted statistic)"), "Linear")
      case "log" =>
        (
          orThrow(
            plot(rows)
              .aes(_.x, _.y)
              .scaleXContinuous(transform = Transform.log10)
              .geomPoint()
              .title("Log10 x")
              .build
          ).plot,
          "Log"
        )
      case _ =>
        (
          orThrow(
            plot(rows)
              .aes(_.x, _.y)
              .scaleXDate(_.day)
              .encode(Aesthetic.Y, _.y, orThrow(ContinuousScaleSpec("y", Palette.numeric)))
              .geomPoint()
              .title("Date x")
              .build
          ).plot,
          "Date"
        )
    val plan = orThrow(
      InteractionCompiler.compile(
        plotValue,
        space,
        orThrow(DataRevision(slot)),
        SemanticId.unsafe(slot),
        orThrow(PlanRevision(slot)),
        options
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, context, slot, Some(title)))

  def run(): Unit =
    val document = g.document
    val slots = Vector("linear", "log", "date")
    val behavior = InteractionBehavior
      .describingEntities[String](id => s"observation $id")
      .withInverseEmphasis(true)
    val views = slots.map(s => s -> view(s)).toMap
    val widgets = slots.map { slot =>
      slot -> orThrow(
        SvgWidget.mount(
          document.getElementById(s"nav-$slot"),
          views(slot),
          behavior,
          label = s"$slot plot",
          options = WidgetOptions(renderer =
            if g.window.location.search.asInstanceOf[String].contains("canvas") then
              WidgetRenderer.Canvas
            else WidgetRenderer.Svg
          )
        )
      )
    }.toMap
    val events = js.Dictionary(slots.map(s => s -> js.Array[String]())*)
    slots.foreach { slot =>
      widgets(slot).subscribe(record =>
        events(slot).push(s"${record.event.getClass.getSimpleName}:${record.stamp.cause}")
      )
    }

    def box(slot: String) =
      document
        .querySelector(s"[data-intaglio-widget=$slot] .intaglio-base")
        .getBoundingClientRect()

    /** Client coordinates of every mark's bounds centre, by entity (the point region selection
      * tests). Not the navigation anchor: a hollow glyph's anchor sits on its ring, not its centre.
      */
    def marks(slot: String): js.Array[js.Array[js.Any]] =
      // The current window's marks: re-derive anchors from the current view through the widget.
      val current = widgets(slot)
      val anchors = currentView(slot).navigation.targets
      val b = box(slot)
      val scale = b.width.asInstanceOf[Double] / context.width
      anchors.map { geometry =>
        js.Array[js.Any](
          geometry.target.entity.fold("-")(_.value),
          b.left.asInstanceOf[Double] + (geometry.left + geometry.right) / 2 * scale,
          b.top.asInstanceOf[Double] + (geometry.top + geometry.bottom) / 2 * scale
        )
      }.toJSArray

    def currentView(slot: String): SvgWidgetView[String] =
      // Rebuild the view the widget shows from its window, as the widget does.
      val nav = orThrow(DataWindowNavigator.of(views(slot).singlePlan.get, context))
      val (x, y) = orThrow(nav.windows(widgets(slot).currentWindow))
      val plan =
        if widgets(slot).currentWindow.isFull then views(slot).singlePlan.get
        else orThrow(InteractionCompiler.rezoom(views(slot).singlePlan.get, x, y))
      orThrow(SvgWidgetView.compile(plan, context, s"$slot-probe", None))

    /** The data under a client point, through the current panel frame's inverse mapping. */
    def dataAt(slot: String, clientX: Double, clientY: Double): js.Array[Double] =
      val b = box(slot)
      val scale = b.width.asInstanceOf[Double] / context.width
      val device = DevicePoint(
        (clientX - b.left.asInstanceOf[Double]) / scale,
        (clientY - b.top.asInstanceOf[Double]) / scale
      )
      val frame = orThrow(DeviceScene.fromScene(currentView(slot).scene, context))
        .frame(PlotRegion.Panel)
      frame.flatMap(_.deviceToNative(device)).fold(_ => js.Array[Double](), p => js.Array(p.x, p.y))

    /** The plot panel's client box `[left, top, right, bottom]`. */
    def panel(slot: String): js.Array[Double] =
      val b = box(slot)
      val scale = b.width.asInstanceOf[Double] / context.width
      currentView(slot).panelFrame.fold(js.Array[Double]()) { f =>
        val (l, t) = (b.left.asInstanceOf[Double], b.top.asInstanceOf[Double])
        js.Array(
          l + f.x * scale,
          t + f.y * scale,
          l + (f.x + f.width) * scale,
          t + (f.y + f.height) * scale
        )
      }

    def selected(slot: String): js.Array[String] =
      widgets(slot).state.toOption
        .fold(js.Array[String]())(_.selection.entities.map(_.value).toVector.sorted.toJSArray)

    def window(slot: String): js.Any =
      val w = widgets(slot).currentWindow
      if w.isFull then null
      else
        js.Array(
          w.x.map(_._1).getOrElse(Double.NaN),
          w.x.map(_._2).getOrElse(Double.NaN),
          w.y.map(_._1).getOrElse(Double.NaN),
          w.y.map(_._2).getOrElse(Double.NaN)
        )

    g.window.intaglioNav = js.Dynamic.literal(
      events = events,
      marks = (slot: String) => marks(slot),
      dataAt = (slot: String, x: Double, y: Double) => dataAt(slot, x, y),
      selected = (slot: String) => selected(slot),
      window = (slot: String) => window(slot),
      panel = (slot: String) => panel(slot),
      navigate = (slot: String, lo: Double, hi: Double) =>
        widgets(slot).navigate(PanelWindow(Some((lo, hi)), None)).fold(_.message, _ => "ok"),
      statCalls = () => statCalls,
      magnify =
        (slot: String, factor: Double) => widgets(slot).magnify(factor).fold(_.message, _ => "ok"),
      ready = true
    )
