package consumer

import intaglio.*
import intaglio.browser.*
import intaglio.interaction.*
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*

/** An application written against the published API only: two keyed scatters of the same
  * observations in different row orders, linked over one key space, with tooltips, selection,
  * navigation and disposal reachable from `window.consumer` for the smoke check.
  */
object Consumer:
  final case class Obs(id: String, x: Double, y: Double)

  private def orThrow[A](value: Either[IntaglioError, A]): A =
    value.fold(error => throw new IllegalStateException(error.message), identity)

  private val rows = Vector.tabulate(30)(i => Obs(s"o$i", 1.0 + i * 3.0, math.sin(i / 4.0)))
  private val context = RenderContext.unsafe(420, 300)
  private val space = orThrow(KeySpace("obs", KeyCodec.text))

  private def view(slot: String, data: Vector[Obs]): SvgWidgetView[String] =
    val plan = orThrow(
      InteractionCompiler.compile(
        orThrow(
          plot(data)
            .aes(_.x, _.y)
            .size(5)
            // The default hollow glyph: the smoke check points at its unpainted centre.
            .geomPoint()
            .title(s"Plot $slot")
            .build
        ).plot,
        space,
        orThrow(DataRevision("r1")),
        SemanticId.unsafe(slot),
        orThrow(PlanRevision("r1")),
        PlotCompilerOptions(renderContext = Some(context), guides = GuidePolicy.Derived())
      )(_.id)
    )
    orThrow(SvgWidgetView.compile(plan, context, slot, Some(s"Plot $slot")))

  def main(args: Array[String]): Unit =
    val document = g.document
    val behavior = InteractionBehavior.describingEntities[String](id => s"observation $id")
    val renderer = if g.window.location.search.asInstanceOf[String].contains("canvas") then
      WidgetRenderer.Canvas
    else WidgetRenderer.Svg
    val options = WidgetOptions(renderer = renderer)
    val a = orThrow(
      SvgWidget.mount(document.getElementById("a"), view("a", rows), behavior, options = options)
    )
    val b = orThrow(
      SvgWidget.mount(document.getElementById("b"), view("b", rows.reverse), behavior, options = options)
    )
    val events = js.Array[String]()
    a.subscribe(record => events.push(s"a:${record.event.getClass.getSimpleName}"))
    b.subscribe(record => events.push(s"b:${record.event.getClass.getSimpleName}"))
    val link = orThrow(WidgetLink.connect(space, Vector(a, b)))
    def selected(w: SvgWidget[String]): js.Array[String] =
      w.state.toOption.fold(js.Array[String]())(_.selection.entities.map(_.value).toVector.sorted.toJSArray)
    g.window.consumer = js.Dynamic.literal(
      events = events,
      selectedA = () => selected(a),
      selectedB = () => selected(b),
      zoomA = () => a.navigate(PanelWindow(Some((0.2, 0.5)), None)).fold(_.message, _ => "ok"),
      windowA = () => a.currentWindow.x.fold[js.Any](null)((lo, hi) => js.Array(lo, hi)),
      dispose = () => {
        link.dispose()
        a.dispose()
        b.dispose()
        js.Array(a.domListenerCount, b.domListenerCount)
      },
      ready = true
    )
